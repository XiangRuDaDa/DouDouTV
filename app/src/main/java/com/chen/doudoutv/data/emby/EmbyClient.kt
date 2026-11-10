package com.chen.doudoutv.data.emby

import android.net.Uri
import com.chen.doudoutv.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * Emby 客户端。
 *
 * 只用公开的 REST API，覆盖三件事：
 * 1) 用户名密码换取 AccessToken（Authorization / X-Emby-Authorization 头）
 * 2) 拉取媒体库里的电影与剧集（含海报/背景图/简介/评分）
 * 3) 生成可直接交给 ExoPlayer 的播放直链
 *
 * 几个容易踩坑的点，这里统一处理：
 * · 地址未写协议时自动补 http://，避免 toHttpUrlOrNull() 返回 null 造成"静默无数据"
 * · Emby 图片接口默认需要鉴权，海报/背景图 URL 必须带 api_key，否则海报全空
 * · 自建 Emby 常用自签名 HTTPS 证书，正式校验失败时降级重试一次
 * · 网络错误抛出带中文说明的异常，由上层展示，而不是返回空列表让用户一头雾水
 */
class EmbyClient(
    serverUrl: String,
    private val token: String = "",
    private val userId: String = ""
) {

    private val base: String = serverUrl.trim()
        .let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it" }
        .trimEnd('/')

    data class Auth(val userId: String, val token: String, val userName: String)

    /** 刮削详情：导演/演员/工作室/分级/标语 + 媒体流信息 */
    data class ItemDetail(
        val tagline: String,
        val officialRating: String,
        val studios: List<String>,
        val directors: List<Person>,
        val actors: List<Person>,
        val videoResolution: String = "",
        val hdrType: String = "",
        val videoCodec: String = "",
        /** 文件大小（字节），0 表示未知 */
        val fileSizeBytes: Long = 0L
    )

    data class Person(val name: String, val role: String, val imageUrl: String)

    /**
     * 条目上的用户状态：续播点 / 已看 / 收藏 / 最后播放时间。
     *
     * 媒体库接口的响应里本来就带 UserData，不用额外请求 —— 之前没解析，
     * 于是「在网页端看过、收藏过」的记录一点都读不回来。
     */
    data class ItemUserData(
        /** 续播点（1 tick = 100 毫秒） */
        val positionTicks: Long = 0L,
        /** 是否已看完 */
        val played: Boolean = false,
        /** 是否收藏 */
        val isFavorite: Boolean = false,
        /** 最后播放时间（毫秒），0 表示服务端没给 */
        val lastPlayedMs: Long = 0L
    )

    data class Item(
        val id: String,
        val name: String,
        val isSeries: Boolean,
        val year: Int,
        val overview: String,
        val posterUrl: String,
        val backdropUrl: String,
        val logoUrl: String = "",
        val rating: Double,
        val genres: String,
        val runTimeMs: Long,
        val indexNumber: Int,
        val parentIndexNumber: Int,
        val seriesId: String,
        /** 分集所属的剧名（服务端给的 SeriesName）：本地没有剧集壳时拿它当标题 */
        val seriesName: String = "",
        /** 该条目的用户状态 */
        val userData: ItemUserData = ItemUserData()
    )

    /** 媒体库（CollectionFolder）：动态分类按库分区用。collectionType 如 movies / tvshows / playlists */
    data class Library(val id: String, val name: String, val collectionType: String)

    private val secureHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** 仅在证书校验失败时使用：自建 Emby 的自签名证书 */
    private val insecureHttp: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun authHeader(): String =
        "MediaBrowser Client=\"DouDouTV\", Device=\"Android\", DeviceId=\"${Prefs.embyDeviceId}\", " +
            "Version=\"1.0.0\", Token=\"$token\""

    private fun execute(request: Request): Response =
        try {
            secureHttp.newCall(request).execute()
        } catch (e: SSLException) {
            // 自签名证书：正式校验失败后降级重试一次
            insecureHttp.newCall(request).execute()
        }

    private fun getJson(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = (base + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (token.isNotBlank()) addQueryParameter("api_key", token)
        }?.build() ?: throw IllegalStateException("Emby 地址无效：$base")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("X-Emby-Authorization", authHeader())
            .header("Accept", "application/json")
            .get()
            .build()

        return readJson(request)
    }

    private fun readJson(request: Request): JSONObject {
        try {
            execute(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val hint = when (response.code) {
                        401, 403 -> "（登录已失效，请重新登录）"
                        404 -> "（地址或接口不存在，请确认只填服务器根地址）"
                        else -> ""
                    }
                    throw IllegalStateException("Emby 返回 HTTP ${response.code}$hint")
                }
                if (body.isBlank()) throw IllegalStateException("Emby 返回内容为空")
                return runCatching { JSONObject(body) }.getOrElse {
                    throw IllegalStateException("Emby 返回的不是 JSON，请检查服务器地址")
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("无法连接 Emby：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 账号密码登录换取 token；失败直接抛出可展示的错误 */
    suspend fun authenticate(userName: String, password: String): Auth = withContext(Dispatchers.IO) {
        val url = "$base/Users/AuthenticateByName".toHttpUrlOrNull()
            ?: throw IllegalStateException("Emby 地址无效：$base")

        val payload = JSONObject().apply {
            put("Username", userName)
            put("Pw", password)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("X-Emby-Authorization", authHeader())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val json = readJson(request)
        val accessToken = json.optString("AccessToken")
        if (accessToken.isBlank()) {
            throw IllegalStateException("登录失败：Emby 未返回 AccessToken，请检查账号密码")
        }
        val user = json.optJSONObject("User")
        Auth(
            userId = user?.optString("Id").orEmpty(),
            token = accessToken,
            userName = user?.optString("Name").orEmpty()
        )
    }

    /**
     * 全量拉取媒体库（电影 + 剧集壳）：分页翻到 TotalRecordCount 见底。
     *
     * 之前单次请求硬上限 600 条，库里几千条也只进最近入库的 600，分类页/题材标签全部失真。
     * 现在按 [pageSize] 顺序翻页、页间节流 [PAGE_THROTTLE_MS]，不并发轰服务器；
     * [onPage] 每拉完一页回调（累计条数, 服务端总条数），供上层显示同步进度；总数未知时给 0。
     * 仅首次同步（本地为空）走这里。
     */
    suspend fun fetchLibrary(
        pageSize: Int = PAGE_SIZE,
        onPage: ((Int, Int) -> Unit)? = null
    ): List<Item> = fetchItemsPaged(emptyMap(), pageSize, onPage)

    /**
     * 增量拉取：只返回 [sinceMs] 之后有新增/修改的条目（Emby 的 MinDateLastSaved 过滤）。
     *
     * 首次全量之后，每次启动/刷新走这里，避免反复整库重拉触发服务端限流。
     * 天然盲区：服务器上被删除的条目增量拉不到，本地会残留（点播时才发现失效），按口径接受。
     */
    suspend fun fetchUpdated(
        sinceMs: Long,
        pageSize: Int = PAGE_SIZE,
        onPage: ((Int, Int) -> Unit)? = null
    ): List<Item> = fetchItemsPaged(mapOf("MinDateLastSaved" to isoUtc(sinceMs)), pageSize, onPage)

    /**
     * 媒体库清单：/Users/{uid}/Views 返回一组 CollectionFolder（电影库/剧集库/NSFW 等）。
     * 动态分类按这份清单分区，而不是 /Genres 题材表（NSFW 是库不是题材，题材表里永远没有它）。
     * 与 Yamby/openemby 等客户端同一接口。失败返回空，上层回退整库同步。
     */
    suspend fun fetchViews(): List<Library> = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext emptyList()
        val json = runCatching { getJson("/Users/$uid/Views") }.getOrNull() ?: return@withContext emptyList()
        val arr = json.optJSONArray("Items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("Type") != "CollectionFolder") return@mapNotNull null
            val id = o.optString("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Library(id, o.optString("Name"), o.optString("CollectionType"))
        }
    }

    /**
     * 逐库拉取某媒体库下的电影/剧集壳：ParentId={库id} 分页。
     * 整库递归查询不返回 ParentId，无法事后打库归属，故按库逐个拉、拉时即知归属。
     * [sinceMs] 非 null 时叠加 MinDateLastSaved 做该库的增量。
     */
    suspend fun fetchLibraryItems(
        parentId: String,
        sinceMs: Long? = null,
        pageSize: Int = PAGE_SIZE,
        onPage: ((Int, Int) -> Unit)? = null
    ): List<Item> {
        val extra = if (sinceMs != null) mapOf("MinDateLastSaved" to isoUtc(sinceMs)) else emptyMap()
        return fetchItemsPaged(extra, pageSize, onPage, parentId)
    }

    /**
     * 电影/剧集壳列表的分页公共逻辑：全量与增量共用，差别只在 [extra] 过滤参数。
     * 判停三重保险：服务端 TotalRecordCount、本页返回数 < 页大小、安全上限 [MAX_LIBRARY_ITEMS]。
     */
    private suspend fun fetchItemsPaged(
        extra: Map<String, String>,
        pageSize: Int,
        onPage: ((Int, Int) -> Unit)?,
        parentId: String? = null
    ): List<Item> = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: throw IllegalStateException("无法获取 Emby 用户信息，请重新登录")
        val out = ArrayList<Item>()
        var startIndex = 0
        var total = Int.MAX_VALUE
        while (startIndex < total) {
            val params = mutableMapOf(
                "IncludeItemTypes" to "Movie,Series",
                "Recursive" to "true",
                "Fields" to "Overview,Genres,CommunityRating,RunTimeTicks,ProductionYear,PrimaryImageAspectRatio",
                "ImageTypeLimit" to "1",
                "EnableImageTypes" to "Primary,Backdrop",
                "SortBy" to "DateCreated",
                "SortOrder" to "Descending",
                "Limit" to pageSize.toString(),
                "StartIndex" to startIndex.toString()
            )
            if (parentId != null) params["ParentId"] = parentId
            params.putAll(extra)
            val json = getJson("/Users/$uid/Items", params)
            val serverTotal = json.optInt("TotalRecordCount", -1)
            if (serverTotal >= 0) total = serverTotal
            val page = parseItems(json.optJSONArray("Items"))
            if (page.isEmpty()) break
            out += page
            startIndex += page.size
            onPage?.invoke(out.size, if (total == Int.MAX_VALUE) 0 else total)
            if (page.size < pageSize) break
            if (out.size >= MAX_LIBRARY_ITEMS) break
            if (startIndex < total) delay(PAGE_THROTTLE_MS)
        }
        out
    }

    /**
     * 按用户状态筛条目：IsPlayed（已看完）/ IsResumable（看过没看完）/ IsFavorite（收藏）。
     *
     * 用来把「在网页端看过、收藏过」的记录读回本地。媒体库接口只给元数据，
     * 不专门问这一句，服务端那边的观看历史和收藏就永远进不来。
     *
     * 服务端不认这个 Filter 时会忽略它、退回普通列表，调用方要按返回条目的
     * UserData 自己筛掉无关条目（这里都带 Limit，不会一次拉回整库）。
     */
    suspend fun fetchUserItems(
        filter: String,
        itemTypes: String,
        sortBy: String,
        limit: Int = 60
    ): List<Item> = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext emptyList()
        val json = runCatching {
            getJson("/Users/$uid/Items", mapOf(
                "Filters" to filter,
                "IncludeItemTypes" to itemTypes,
                "Recursive" to "true",
                "Fields" to "Overview,Genres,CommunityRating,RunTimeTicks,ProductionYear",
                "EnableImageTypes" to "Primary,Backdrop",
                "ImageTypeLimit" to "1",
                "SortBy" to sortBy,
                "SortOrder" to "Descending",
                "Limit" to limit.toString()
            ))
        }.getOrNull() ?: return@withContext emptyList()
        parseItems(json.optJSONArray("Items"))
    }

    /** 单个条目（含 UserData）：本地缺剧集壳时用它补一个壳行 */
    suspend fun fetchItemById(itemId: String): Item? = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext null
        val json = runCatching {
            getJson(
                "/Users/$uid/Items/$itemId",
                mapOf("Fields" to "Overview,Genres,CommunityRating,RunTimeTicks,ProductionYear")
            )
        }.getOrNull() ?: return@withContext null
        parseItem(json)
    }

    /**
     * 全库题材清单：直接问服务端 /Genres，而不是从已入库条目里按频次统计。
     * 后者样本有限又取前 N，冷门题材永远挤不进、换库也是那几个高频词；服务端清单跟库内容相关且完整。
     */
    suspend fun fetchGenres(): List<String> = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext emptyList()
        val json = runCatching {
            getJson("/Genres", mapOf("UserId" to uid, "SortBy" to "SortName", "Limit" to "500"))
        }.getOrNull() ?: return@withContext emptyList()
        val arr = json.optJSONArray("Items") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull {
            arr.optJSONObject(it)?.optString("Name")?.takeIf { s -> s.isNotBlank() }
        }
    }

    /** 增量过滤要 ISO8601 UTC 时间；用 SimpleDateFormat 兼容 minSdk 21（java.time 要 26） */
    private fun isoUtc(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }

    /** 剧集的季与分集（单部失败不影响整库同步） */
    suspend fun fetchEpisodes(seriesId: String): List<Item> = withContext(Dispatchers.IO) {
        val json = runCatching {
            getJson(
                "/Shows/$seriesId/Episodes",
                mapOf(
                    "Fields" to "Overview,RunTimeTicks,ProductionYear",
                    "EnableImageTypes" to "Primary",
                    "SortBy" to "ParentIndexNumber,IndexNumber"
                )
            )
        }.getOrNull() ?: return@withContext emptyList()

        parseItems(json.optJSONArray("Items"))
    }

    /** 搜索（TV 端全局搜索用） */
    suspend fun search(keyword: String, limit: Int = 40): List<Item> = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext emptyList()
        val json = runCatching {
            getJson(
                "/Users/$uid/Items",
                mapOf(
                    "SearchTerm" to keyword,
                    "IncludeItemTypes" to "Movie,Series",
                    "Recursive" to "true",
                    "Fields" to "Overview,Genres,CommunityRating,ProductionYear",
                    "Limit" to limit.toString()
                )
            )
        }.getOrNull() ?: return@withContext emptyList()

        parseItems(json.optJSONArray("Items"))
    }

    /** 刮削信息：标语/分级/工作室/导演/演员 + MediaSources 媒体流信息 */
    suspend fun fetchItemDetail(itemId: String): ItemDetail? = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext null
        val json = runCatching {
            getJson("/Users/$uid/Items/$itemId", mapOf(
                "Fields" to "People,Studios,Taglines,OfficialRating,MediaSources,MediaStreams"
            ))
        }.getOrNull() ?: return@withContext null

        val tagline = json.optJSONArray("Taglines")?.optString(0).orEmpty()
        val officialRating = json.optString("OfficialRating")
        val studios = json.optJSONArray("Studios")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("Name") }
        }.orEmpty()

        val directors = mutableListOf<Person>()
        val actors = mutableListOf<Person>()
        json.optJSONArray("People")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val name = p.optString("Name")
                if (name.isBlank()) continue
                val tag = p.optString("PrimaryImageTag")
                val image = if (tag.isNotBlank()) {
                    val auth = if (token.isNotBlank()) "&api_key=$token" else ""
                    "$base/Persons/${Uri.encode(name)}/Images/Primary?tag=$tag&maxHeight=300&quality=90$auth"
                } else ""
                val person = Person(name, p.optString("Role"), image)
                when (p.optString("Type")) {
                    "Director" -> directors += person
                    "Actor" -> actors += person
                }
            }
        }

        // ---- MediaSources / MediaStreams 解析 ----
        var videoResolution = ""
        var hdrType = ""
        var videoCodec = ""
        // 文件大小：顶层 JSON 有 Size 字段（Emby 所有 item 都返回）
        val fileSizeBytes = json.optLong("Size", 0L)

        // Emby API 结构兼容：老版本顶层 MediaStreams，新版本 MediaSources[].MediaStreams[]
        val streams = (json.optJSONArray("MediaSources")?.optJSONObject(0)?.optJSONArray("MediaStreams")
            ?: json.optJSONArray("MediaStreams"))

        if (streams != null) {
            runCatching {
                for (i in 0 until streams.length()) {
                    val stream = streams.optJSONObject(i) ?: continue
                    if (stream.optString("Type") != "Video") continue
                    val height = stream.optInt("Height", 0)
                    val codec = stream.optString("Codec", "")
                    val videoRange = stream.optString("VideoRange", "")
                    val colorTransfer = stream.optString("ColorTransfer", "")
                    val hdrMode = stream.optString("HdrMode", "")
                    val title = stream.optString("Title", "")

                    // 分辨率 → 标签
                    videoResolution = when {
                        height >= 2160 -> "4K"
                        height >= 1080 -> "1080p"
                        height >= 720 -> "720p"
                        height > 0 -> "${height}p"
                        else -> videoResolution
                    }
                    // Codec → 短名
                    videoCodec = when (codec.uppercase()) {
                        "H264", "AVC" -> "H.264"
                        "HEVC", "H265" -> "HEVC"
                        "AV1" -> "AV1"
                        "VP9" -> "VP9"
                        "VP8" -> "VP8"
                        "H263" -> "H.263"
                        else -> codec.ifBlank { videoCodec }
                    }
                    // HDR：优先 Title（Emby 有时把 Dolby Vision 放这里），再看 HdrMode / VideoRange / ColorTransfer
                    hdrType = when {
                        title.contains("Dolby Vision", ignoreCase = true) -> "Dolby Vision"
                        hdrMode.contains("DolbyVision", ignoreCase = true) -> "Dolby Vision"
                        title.contains("HDR10+", ignoreCase = true) -> "HDR10+"
                        title.contains("HDR10", ignoreCase = true) -> "HDR10"
                        hdrMode.contains("HDR10", ignoreCase = true) -> "HDR10"
                        colorTransfer.contains("SMPTE2084", ignoreCase = true) -> "HDR"
                        colorTransfer.contains("ARIB", ignoreCase = true) -> "HLG"
                        videoRange.equals("HDR", ignoreCase = true) -> "HDR"
                        videoRange.equals("DOVI", ignoreCase = true) -> "Dolby Vision"
                        else -> hdrType
                    }
                    // 只取主视频流
                    break
                }
            }
        }

        ItemDetail(
            tagline, officialRating, studios,
            directors.take(3), actors.take(12),
            videoResolution, hdrType, videoCodec, fileSizeBytes
        )
    }

    /**
     * POST /Items/{itemId}/PlaybackInfo —— Emby HLS 播放前置步骤。
     * 返回 PlaySessionId + MediaSourceId，用于构造 master.m3u8 URL。
     * 反代服务器上 direct stream GET 被拦时，HLS 通常仍可用。
     */
    suspend fun fetchPlaybackInfo(itemId: String): PlaybackInfo? = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext null
        val jsonBody = JSONObject().apply {
            put("DeviceId", deviceId())
            put("UserId", uid)
            put("AutoOpenLiveStream", false)
            put("IsPlayback", true)
            put("DirectPlayProtocols", JSONArray().apply { put("Hls") })
        }
        val url = base + "/Items/$itemId/PlaybackInfo?api_key=$token"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("X-Emby-Authorization", authHeader())
            .header("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            execute(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) return@use null
                val json = JSONObject(body)
                val psid = json.optString("PlaySessionId")
                val msArr = json.optJSONArray("MediaSources")
                val msid = msArr?.optJSONObject(0)?.optString("Id").orEmpty()
                if (psid.isBlank() || msid.isBlank()) null
                else PlaybackInfo(psid, msid)
            }
        }.getOrNull()
    }

    data class PlaybackInfo(val playSessionId: String, val mediaSourceId: String)

    // ---------------- 回传：观看进度 / 已看 / 收藏 ----------------

    /**
     * 写回「上次看到哪」（POST /Users/{uid}/Items/{id}/UserData）。
     *
     * 只写位置、不动「已看」标记：重看一遍还没看完的片子，
     * 不该把之前看过的状态抹掉 —— 「已看」只由看完和清除记录两个动作决定。
     */
    suspend fun updatePlaybackPosition(itemId: String, positionTicks: Long) = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext
        val payload = JSONObject().apply { put("PlaybackPositionTicks", positionTicks) }
        sendJson("/Users/$uid/Items/$itemId/UserData", payload.toString())
    }

    /** 标记已看（POST /Users/{uid}/PlayedItems/{id}） */
    suspend fun markPlayed(itemId: String, positionTicks: Long = 0L, datePlayed: String? = null) =
        withContext(Dispatchers.IO) {
            val uid = currentUserId() ?: return@withContext
            val params = mutableMapOf("api_key" to token)
            if (positionTicks > 0) params["PlaybackPositionTicks"] = positionTicks.toString()
            datePlayed?.let { params["DatePlayed"] = it }
            sendEmpty("/Users/$uid/PlayedItems/$itemId", params, "POST")
        }

    /** 取消已看（DELETE /Users/{uid}/PlayedItems/{id}）：本地清除观看记录时同步过去 */
    suspend fun unmarkPlayed(itemId: String) = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext
        sendEmpty("/Users/$uid/PlayedItems/$itemId", mapOf("api_key" to token), "DELETE")
    }

    /** 收藏 / 取消收藏（POST/DELETE /Users/{uid}/FavoriteItems/{id}） */
    suspend fun setFavorite(itemId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        val uid = currentUserId() ?: return@withContext
        sendEmpty(
            "/Users/$uid/FavoriteItems/$itemId",
            mapOf("api_key" to token),
            if (favorite) "POST" else "DELETE"
        )
    }

    /** POST 一个 JSON 体：回传类接口都是 204/200 空体，失败只抛异常不解析 */
    private suspend fun sendJson(path: String, payload: String) {
        val url = (base + path).toHttpUrlOrNull()
            ?: throw IllegalStateException("Emby 地址无效：$base")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("X-Emby-Authorization", authHeader())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Emby 回传失败：HTTP ${response.code}")
            }
        }
    }

    /** 不带体的 POST / DELETE（收藏、已看标记都是这种） */
    private suspend fun sendEmpty(path: String, params: Map<String, String>, method: String) {
        val url = (base + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw IllegalStateException("Emby 地址无效：$base")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("X-Emby-Authorization", authHeader())
            .header("Content-Type", "application/json")
            .apply {
                if (method == "DELETE") delete() else post("".toRequestBody("application/json".toMediaType()))
            }
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Emby 回传失败：HTTP ${response.code}")
            }
        }
    }

    private fun deviceId(): String = Prefs.embyDeviceId

    private var cachedUserId: String? = null

    private fun currentUserId(): String? {
        userId.takeIf { it.isNotBlank() }?.let { return it }
        cachedUserId?.let { return it }
        val json = runCatching { getJson("/Users/Me") }.getOrNull() ?: return null
        val id = json.optString("Id").takeIf { it.isNotBlank() } ?: return null
        cachedUserId = id
        return id
    }

    private fun parseItems(arr: JSONArray?): List<Item> {
        if (arr == null) return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            out += parseItem(arr.optJSONObject(i)) ?: continue
        }
        return out
    }

    /** 单个条目 → Item。UserData 一并解析：续播点 / 已看 / 收藏都在这里 */
    private fun parseItem(o: JSONObject?): Item? {
        if (o == null) return null
        val id = o.optString("Id")
        if (id.isBlank()) return null
        val type = o.optString("Type")
        val imageTags = o.optJSONObject("ImageTags")
        val backdropTags = o.optJSONObject("BackdropImageTags")

        val primaryTag = imageTags?.optString("Primary").orEmpty()
        val logoTag = imageTags?.optString("Logo").orEmpty()
        val backdropTag = backdropTags?.let {
            if (it.length() > 0) it.optString(it.keys().next()) else ""
        }.orEmpty()

        val ud = o.optJSONObject("UserData")
        val userData = ItemUserData(
            positionTicks = ud?.optLong("PlaybackPositionTicks", 0L) ?: 0L,
            played = ud?.optBoolean("Played", false) ?: false,
            isFavorite = ud?.optBoolean("IsFavorite", false) ?: false,
            lastPlayedMs = parseEmbyTime(ud?.optString("LastPlayedDate").orEmpty())
        )

        return Item(
            id = id,
            name = o.optString("Name"),
            isSeries = type == "Series",
            year = o.optInt("ProductionYear"),
            overview = o.optString("Overview"),
            posterUrl = if (primaryTag.isNotBlank())
                imageUrl("/Items/$id/Images/Primary", primaryTag, "maxHeight=600&quality=90") else "",
            backdropUrl = if (backdropTag.isNotBlank())
                imageUrl("/Items/$id/Images/Backdrop", backdropTag, "maxWidth=1280&quality=90") else "",
            // 透明 PNG，maxHeight 给足以保证电视端大屏下不糊
            logoUrl = if (logoTag.isNotBlank())
                imageUrl("/Items/$id/Images/Logo", logoTag, "maxHeight=400&quality=90") else "",
            rating = o.optDouble("CommunityRating", 0.0),
            genres = o.optJSONArray("Genres")?.let { g ->
                (0 until g.length()).mapNotNull { g.optString(it).takeIf { s -> s.isNotBlank() } }
                    .joinToString(" / ")
            }.orEmpty(),
            runTimeMs = (o.optLong("RunTimeTicks") / 10_000L),
            indexNumber = o.optInt("IndexNumber"),
            parentIndexNumber = o.optInt("ParentIndexNumber"),
            seriesId = o.optString("SeriesId"),
            seriesName = o.optString("SeriesName"),
            userData = userData
        )
    }

    /**
     * Emby 的时间戳是 ISO8601（"2024-05-01T12:34:56.1234567Z"），只取到秒。
     * 服务端给的是 UTC，解析不出来就当没有（0）。
     */
    private fun parseEmbyTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        val head = raw.substring(0, minOf(19, raw.length))
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return runCatching { sdf.parse(head)?.time ?: 0L }.getOrDefault(0L)
    }

    /** Emby 图片接口默认要鉴权，必须带 api_key，否则海报是空白的 */
    private fun imageUrl(path: String, tag: String, size: String): String {
        val auth = if (token.isNotBlank()) "&api_key=$token" else ""
        return "$base$path?tag=$tag&$size$auth"
    }

    companion object {
        /** 全量/增量分页的单页条数：太大单响应慢、太小请求多，500 是折中（用户拍板） */
        private const val PAGE_SIZE = 500

        /** 翻页之间的节流（毫秒）：顺序拉、不并发，避免短时间轰服务器触发限流 */
        private const val PAGE_THROTTLE_MS = 250L

        /** 单次同步的安全上限：服务端 TotalRecordCount 异常时也不至于无限翻页拉爆内存 */
        private const val MAX_LIBRARY_ITEMS = 100_000

        /**
         * Emby 播放直链（direct stream）。
         * static=true 表示不转码直接原码流传输 —— 对局域网与性能最好的选择。
         * 但部分反代服务器会拦 GET body 只放行 HEAD，此时需要用 HLS。
         */
        fun playbackUrl(serverUrl: String, itemId: String, token: String, static: Boolean = true): String {
            val base = serverUrl.trim().trimEnd('/')
            return "$base/Videos/$itemId/stream?static=$static&api_key=$token"
        }

        /**
         * Emby HLS 播放地址 —— 先 POST PlaybackInfo 拿到 PlaySessionId + MediaSourceId，
         * 再请求 master.m3u8。相比 direct stream：
         *   · 对反代更友好（HLS manifest 和分片通常走不同后端路径）
         *   · 自动支持多码率自适应
         *   · 服务器开启转码 / 重封装时也能正常工作
         */
        fun playbackUrlHls(
            serverUrl: String,
            itemId: String,
            token: String,
            playSessionId: String,
            mediaSourceId: String,
            deviceId: String = Prefs.embyDeviceId
        ): String {
            val base = serverUrl.trim().trimEnd('/')
            return "$base/Videos/$itemId/master.m3u8?api_key=$token" +
                "&PlaySessionId=$playSessionId" +
                "&MediaSourceId=$mediaSourceId" +
                "&DeviceId=$deviceId"
        }

        /** 字幕轨道（外挂 srt/ass） */
        fun subtitleUrl(serverUrl: String, itemId: String, index: Int, token: String): String {
            val base = serverUrl.trim().trimEnd('/')
            return "$base/Videos/$itemId/Subtitles/$index/Stream.srt?api_key=$token"
        }

        fun isValidServer(url: String): Boolean {
            val u = Uri.parse(url.trim())
            return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
        }
    }
}
