package com.tabbit.myvidhub.data.tvbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TVBox 客户端。
 *
 * 只处理 TVBox 配置 JSON（形如 {"sites":[...],"spider":"..."}）里的
 * type=3 Spider 爬虫站点：爬虫逻辑封装在 spider JAR 里，运行时由 [SpiderLoader]
 * DexClassLoader 加载 + 反射调用 Spider 基类的标准方法（homeContent /
 * categoryContent / searchContent / detailContent / playerContent）。
 */
class TvBoxClient(
    endpoint: String,
    context: Context? = null
) {

    /** 用户可能只填了 192.168.1.2:8080 或 example.com/api.php/provide/vod/，这里自动补协议 */
    private val base: String = normalizeEndpoint(endpoint)

    /**
     * 全局共享的 HTTP 客户端。
     * 之前每个 TvBoxClient 都 new 一个 OkHttpClient，而 Repository 每次操作（切线路、拉分类、
     * 开详情）都会 new TvBoxClient —— 等于每条请求都重新建连接池/线程池、重新 TCP+TLS 握手。
     * 改成进程级单例后，配置下载也能复用连接。
     */
    private val http: OkHttpClient = sharedHttp

    /** Spider 加载器（type 3 需要 Android Context；进程级单例，整个 App 只初始化一次） */
    private val spiderLoader: SpiderLoader? = context?.applicationContext?.let {
        SpiderLoader.init(it)
        SpiderLoader
    }

    /** 配置根级别的 spider URL（type 3 共用同一个 JAR） */
    private var configSpiderUrl: String = ""

    /** type 3 spider 站点列表 */
    private var spiderSites: List<SpiderSite> = emptyList()

    /** 解析并缓存好的站点（线路）列表，供 UI 展示线路切换 */
    private var cachedSites: List<TvBoxSite>? = null

    data class Vod(
        val vodId: String,
        val name: String,
        val pic: String,
        val remarks: String,
        val year: Int,
        val area: String,
        val typeName: String,
        val content: String,
        val score: Double,
        /** 来自 TVBox 配置时，标记所属站点，保证 remoteId 不冲突 */
        val siteKey: String = "",
        val siteName: String = "",
        /** 解析后的播放源列表：源名 → 剧集列表 */
        var playSources: List<PlaySource> = emptyList()
    )

    data class PlaySource(val name: String, val episodes: List<Episode>)
    data class Episode(val name: String, val url: String)

    /**
     * 站点信息（线路）。由 [availableSites] 从 TVBox 配置解析后暴露给 UI 做线路切换。
     */
    data class TvBoxSite(
        val key: String,
        val name: String,
        val api: String = "",
        val ext: Any? = null
    )

    /**
     * 站点首页结果。对应 TVBox spider homeContent 的标准结构：
     *   {"class":[{type_id,type_name}...], "filters":{...}, "list":[{vod_id,...}...]}
     * 注意：很多 4K / 网盘类线路首页 list 为空、只有 class —— 这时分类就是全部内容入口，
     * 不能把「list 空」当成站点失败。
     */
    data class SiteHomeResult(
        val vods: List<Vod>,
        val categories: List<CatVodResultParser.ClassItem> = emptyList()
    )

    private data class SpiderSite(val key: String, val name: String, val api: String, val ext: Any?)

    companion object {
        /** 默认 UA：部分采集站按 UA 区分线路/限速 */
        private const val DEFAULT_UA = "okhttp/3.12.0"

        /** 配置缓存有效期：10 分钟。配置只在用户新增/修改/删除来源时才变化 */
        private const val CONFIG_TTL_MS = 10 * 60 * 1000L

        /** 进程级共享 HTTP 客户端（连接池复用，避免每次操作重新握手） */
        private val sharedHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        private data class ConfigEntry(val json: JSONObject, val at: Long)
        private val configCache = java.util.concurrent.ConcurrentHashMap<String, ConfigEntry>()
        private val siteListCache = java.util.concurrent.ConcurrentHashMap<String, List<TvBoxSite>>()

        /** 地址归一化：补协议 + 中文域名转 punycode */
        internal fun normalizeEndpoint(raw: String): String =
            toPunycode(
                raw.trim().let {
                    if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it"
                }
            )

        /** 来源新增/修改/删除后清掉缓存，下次重新拉配置 */
        fun invalidateConfig(endpoint: String) {
            val key = normalizeEndpoint(endpoint)
            configCache.remove(key)
            siteListCache.remove(key)
        }

        /**
         * 整份配置 JSON（进程级缓存，带 TTL）。
         * 之前 homeContent / availableSites / detail / resolvePlayUrl 每处都重新下载一次整包配置，
         * 多仓配置动辄几百 KB~几 MB，在电视上一次解析就是几百毫秒起步。
         */
        private fun configOf(base: String): JSONObject? {
            configCache[base]?.let {
                if (System.currentTimeMillis() - it.at < CONFIG_TTL_MS) return it.json
            }
            val json = fetchRaw(base) ?: return null
            configCache[base] = ConfigEntry(json, System.currentTimeMillis())
            return json
        }

        private fun fetchRaw(url: String): JSONObject? = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA)
                .get()
                .build()
            sharedHttp.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) null else parseConfigJson(body)
            }
        }.getOrNull()

        /**
         * 中文域名（IDN）→ punycode。
         * 饭太硬等源常是中文域名（如 www.饭太硬.cc），OkHttp 无法解析中文 host，
         * 直接请求还会被服务器以 400 拒绝。这里把 host 段转成 xn-- 编码。
         */
        fun toPunycode(raw: String): String {
            val url = raw.trim()
            if (url.isBlank()) return url
            return try {
                val uri = java.net.URI(url)
                val host = uri.host ?: return url
                val ascii = java.net.IDN.toASCII(host)
                if (ascii == host) url
                else {
                    // 只替换 host 段：重新拼 scheme://asciiHost[:port]/path[?query]
                    val scheme = uri.scheme + "://"
                    val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
                    val rest = url.substringAfter(host).let { r ->
                        val slash = r.indexOf('/')
                        if (slash >= 0) r.substring(slash) else ""
                    }
                    scheme + ascii + port + rest
                }
            } catch (e: Exception) {
                url
            }
        }

        /**
         * 反爬伪装响应解析。
         * 饭太硬等源对 okhttp UA 返回 JPEG 图片，图片末尾（FFD9 之后）嵌入
         * Base64 编码的 TVBox 配置 JSON。此函数从图片字节里提取并解码。
         * 若 body 本身是合法 JSON 则直接解析。
         */
        fun parseConfigJson(body: String): JSONObject? {
            // 直接就是 JSON
            runCatching { return JSONObject(body) }.getOrNull()

            // 伪装：JPEG/BMP 图片末尾嵌入 Base64。Base64 解码后可能是明文 JSON
            // 或 gzip/zlib 压缩的 JSON（饭太硬当前是明文 base64）。
            val b64Candidate = extractBase64FromBinary(body)
            if (b64Candidate != null) {
                runCatching {
                    val bytes = android.util.Base64.decode(b64Candidate, android.util.Base64.DEFAULT)
                    // 先试明文
                    runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()?.let { return it }
                    // 再试 gzip
                    runCatching {
                        val gz = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()
                        JSONObject(String(gz, Charsets.UTF_8))
                    }.getOrNull()?.let { return it }
                    // 最后试 zlib
                    runCatching {
                        val inflater = java.util.zip.Inflater()
                        val out = java.io.ByteArrayOutputStream()
                        val inflaterStream = java.util.zip.InflaterInputStream(bytes.inputStream(), inflater)
                        inflaterStream.copyTo(out)
                        JSONObject(String(out.toByteArray(), Charsets.UTF_8))
                    }.getOrNull()?.let { return it }
                }
            }
            return null
        }

        /** 从二进制文本里提取最长的 Base64 片段（图片元数据里的杂字符会被过滤掉） */
        private fun extractBase64FromBinary(text: String): String? {
            // 用正则找连续 base64 字符段（长度 >= 64 才可能是真 payload）
            val matcher = Regex("[A-Za-z0-9+/]{32,}={0,2}").findAll(text)
            var best: String? = null
            for (m in matcher) {
                val candidate = m.value
                if (best == null || candidate.length > best.length) best = candidate
            }
            return best
        }

        /** CatVodResultParser 可调用的 URL 归一化（顶级函数，无 origin 上下文做相对 → 绝对转换） */
        internal fun AbsoluteUrl(raw: String): String {
            val url = raw.trim()
            return when {
                url.isBlank() -> ""
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("//") -> "https:$url"
                else -> url
            }
        }
    }

    // ---------- HTTP ----------

    /** 当前地址的整份配置（进程级缓存，10 分钟内只下载解析一次） */
    private fun baseConfig(): JSONObject? = configOf(base)

    /**
     * 首页列表：从 TVBox 配置的 spider 站点聚合第一页内容。
     */
    suspend fun homeContent(limit: Int = 100): List<Vod> = withContext(Dispatchers.IO) {
        val configRoot = baseConfig()?.takeIf { it.has("sites") } ?: return@withContext emptyList()
        val spSites = parseSpiderSites(configRoot)
        if (spSites.isEmpty()) return@withContext emptyList()

        configSpiderUrl = configRoot.optString("spider")
        if (configSpiderUrl.isBlank()) {
            throw IllegalStateException("TVBox 配置缺少 spider 字段，无法加载爬虫")
        }
        spiderSites = spSites
        if (spiderLoader == null) return@withContext emptyList()

        val merged = LinkedHashMap<String, Vod>()
        loadSpiderHome(spSites, merged, limit)
        merged.values.take(limit).toList()
    }

    /**
     * 当前配置的 spider 站点（线路）列表。
     * 结果缓存，重复调用不会重复请求。
     */
    suspend fun availableSites(): List<TvBoxSite> = withContext(Dispatchers.IO) {
        cachedSites?.let { return@withContext it }
        siteListCache[base]?.let { cachedSites = it; return@withContext it }
        val raw = baseConfig()
            ?: throw IllegalStateException("无法下载 TVBox 配置，请检查地址与网络")
        val sites = parseSpiderSites(raw).map { TvBoxSite(it.key, it.name, it.api, it.ext) }
        if (sites.isEmpty()) {
            throw IllegalStateException("配置里没有可用的 spider 站点（需要 spider 字段与 type=3 站点）")
        }
        cachedSites = sites
        siteListCache[base] = sites
        sites
    }

    /**
     * 只拉某个站点（线路）的首页列表。
     * 用户切换线路时按需调用，避免每次都聚合全部站点。
     * 返回影片列表 + 该线路 homeContent 的分类（class 数组），供 UI 按动态分区展示。
     */
    suspend fun siteHome(site: TvBoxSite, limit: Int = 100): SiteHomeResult = withContext(Dispatchers.IO) {
        if (configSpiderUrl.isBlank()) {
            configSpiderUrl = baseConfig()?.optString("spider").orEmpty()
        }
        if (configSpiderUrl.isBlank()) {
            throw IllegalStateException("TVBox 配置缺少 spider 字段，无法加载爬虫")
        }
        val merged = LinkedHashMap<String, Vod>()
        val categories = try {
            loadSpiderHome(listOf(SpiderSite(site.key, site.name, site.api, site.ext)), merged, limit)
        } catch (e: Exception) {
            // 不再吞异常：把壳初始化/爬虫调用的真实原因透出到 UI（否则切线路静默失败、内容不变）
            throw IllegalStateException("站点「${site.name}」爬取失败：${e.message ?: e.javaClass.simpleName}")
        }
        SiteHomeResult(merged.values.take(limit).toList(), categories)
    }

    /**
     * 站点分类内容（对应 TVBox 点分类后调 categoryContent 的分页）。
     */
    suspend fun categoryContent(site: TvBoxSite, typeId: String, page: Int = 1): List<Vod> =
        withContext(Dispatchers.IO) {
            val loader = spiderLoader ?: return@withContext emptyList()
            if (configSpiderUrl.isBlank()) {
                configSpiderUrl = baseConfig()?.optString("spider").orEmpty()
            }
            if (configSpiderUrl.isBlank()) return@withContext emptyList()
            val loaderKey = runCatching { loader.ensureLoaded(configSpiderUrl) }.getOrNull()
                ?: return@withContext emptyList()
            val proxy = loader.getSpider(loaderKey, site.api, site.ext) ?: return@withContext emptyList()
            val json = proxy.categoryContent(typeId, page.toString(), filter = true, extend = null)
                ?: return@withContext emptyList()
            CatVodResultParser.parseVodList(json, site.key, site.name)
        }

    /**
     * 远程搜索：调 Spider 的 searchContent 接口，直接在源站搜索。
     * 跟本地 DB 搜索互补——本地搜已入库的，远程搜配置里的 spider 站点。
     *
     * @param site 目标站点
     * @param keyword 关键词（支持中文片名/演员/题材，也支持拼音首字母但 Guard 内部不一定认）
     * @param quick 是否快速搜索（CatVod 协议语义，true 时 Guard 可能只返回前 10 条）
     */
    suspend fun search(site: TvBoxSite, keyword: String, quick: Boolean = false): List<Vod> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isBlank()) return@withContext emptyList()
            val loader = spiderLoader ?: return@withContext emptyList()
            if (configSpiderUrl.isBlank()) {
                configSpiderUrl = baseConfig()?.optString("spider").orEmpty()
            }
            if (configSpiderUrl.isBlank()) return@withContext emptyList()
            // 关键：只查缓存，不调 ensureLoaded（那会触发 Go 代理初始化）
            val loaderKey = loader.cachedLoaderKeyOrNull(configSpiderUrl)
                ?: return@withContext emptyList()
            val proxy = loader.getSpider(loaderKey, site.api, site.ext)
                ?: return@withContext emptyList()
            val json = runCatching { proxy.searchContent(kw, quick) }.getOrNull()
                ?: return@withContext emptyList()
            CatVodResultParser.parseVodList(json, site.key, site.name)
        }

    /** type 3 spider 站点首页聚合；返回各站点 homeContent 的 class（分类）数组 */
    private suspend fun loadSpiderHome(
        sites: List<SpiderSite>,
        merged: LinkedHashMap<String, Vod>,
        limit: Int
    ): List<CatVodResultParser.ClassItem> = withContext(Dispatchers.IO) {
        val loader = spiderLoader ?: throw IllegalStateException("缺少 Android Context，无法加载爬虫")
        val loaderKey = try {
            loader.ensureLoaded(configSpiderUrl)
        } catch (e: Exception) {
            throw IllegalStateException("加载 spider 包失败：${e.message ?: e.javaClass.simpleName}")
        }

        val reasons = ArrayList<String>()
        val categories = ArrayList<CatVodResultParser.ClassItem>()
        for (site in sites.take(4)) {
            if (merged.size >= limit) break
            val proxy = loader.getSpider(loaderKey, site.api, site.ext) ?: run {
                reasons += "${site.name}: ${loader.lastError}"
                null
            } ?: continue
            // 壳本地代理冷启动需要时间，首次 homeContent 失败/为空后按 2s/4s/8s 递增重试
            var json: String? = proxy.homeContent(filter = true)
            var retries = 0
            while (json.isNullOrEmpty() && retries < 3) {
                retries++
                delay(2000L * retries)
                json = proxy.homeContent(filter = true)
            }
            json ?: run {
                reasons += "${site.name}: homeContent 返回空"
                null
            } ?: continue
            // 分类（class）：即使 list 为空、只有分类，站点也「有数据」，UI 可按分类分区浏览
            categories += CatVodResultParser.parseClasses(json!!)
            val list = CatVodResultParser.parseVodList(json!!, site.key, site.name)
            if (list.isEmpty() && categories.isEmpty()) reasons += "${site.name}: homeContent 无影片"
            list.forEach { merged["${site.key}:${it.vodId}"] = it }
        }

        // 只有 list 和 class 都为空才判失败；有分类就足够支撑 UI 展示了
        if (merged.isEmpty() && categories.isEmpty()) {
            throw IllegalStateException(
                if (reasons.isEmpty()) "该配置的爬虫站点没有返回任何影片"
                else "爬虫站点均无数据：${reasons.take(3).joinToString("；")}"
            )
        }
        categories
    }

    /**
     * 取详情：拿回播放地址列表（这是实际能播的关键）。
     * @param siteKey 来自 TVBox 配置的站点 key；空表示在所有 spider 站点里逐个尝试
     */
    suspend fun detail(vodId: String, siteKey: String = ""): Vod? = withContext(Dispatchers.IO) {
        // 配置走缓存：之前这里每次都重新下载整份配置，开一个详情就多一次整包请求
        val root = baseConfig()
        val spSites = root?.let { parseSpiderSites(it) } ?: spiderSites
        if (spSites.isEmpty()) return@withContext null
        if (configSpiderUrl.isBlank() && root != null) {
            configSpiderUrl = root.optString("spider")
        }

        // 优先按 siteKey 精确匹配，其余站点作为兜底
        val ordered = if (siteKey.isNotBlank()) {
            listOfNotNull(spSites.firstOrNull { it.key == siteKey }) +
                spSites.filter { it.key != siteKey }
        } else {
            spSites
        }
        for (site in ordered) {
            fetchSpiderDetail(site, vodId)?.let { return@withContext it }
        }
        null
    }

    private suspend fun fetchSpiderDetail(site: SpiderSite, vodId: String): Vod? {
        val loader = spiderLoader ?: return null
        if (configSpiderUrl.isBlank()) return null
        val loaderKey = runCatching { loader.ensureLoaded(configSpiderUrl) }.getOrNull() ?: return null
        val proxy = loader.getSpider(loaderKey, site.api, site.ext) ?: return null
        val json = proxy.detailContent(listOf(vodId)) ?: return null
        Log.d("SpiderProxy", "detailContent($vodId) raw = $json")
        val vod = CatVodResultParser.parseDetail(json, site.key, site.name) ?: return null

        // 注意：不再在这里对所有非 http 地址统一调 playerContent 解析。
        // 网盘线路一部剧动辄几十上百集、多条云盘，逐个 playerContent 会让详情加载卡死。
        // 云盘 scheme 地址（quark://、aliyundrive:// 等）原样保留，播放时再按需解析。
        return vod
    }

    /** playerContent 解析结果：视频直链 + 所需 HTTP 请求头 */
    data class ResolvedPlayUrl(val url: String, val headers: Map<String, String> = emptyMap())

    /**
     * 解析云盘播放地址。
     *
     * 云盘 scheme 地址（quark://、aliyundrive://、uc:// 等）不是视频直链，
     * 必须调用 spider 的 playerContent(flag, id, vipFlags) 才能拿到真实可播放的 URL。
     * 返回结果同时包含 URL 和所需 HTTP 请求头（如 Cookie、Referer 等）。
     *
     * @param siteKey    站点 key（用于定位 spider 站点）
     * @param playSource 播放源名称（即 vod_play_from，如「夸克网盘」），作为 playerContent 的 flag
     * @param cloudUrl   云盘原始地址（作为 playerContent 的 id）
     * @param vipFlags   全部播放源名称列表（playerContent 的 vipFlags）
     * @return 解析结果（URL + headers）；解析失败抛异常
     */
    suspend fun resolvePlayUrl(
        siteKey: String,
        playSource: String,
        cloudUrl: String,
        vipFlags: List<String>
    ): ResolvedPlayUrl = withContext(Dispatchers.IO) {
        val root = baseConfig()
        val spSites = root?.let { parseSpiderSites(it) } ?: spiderSites
        if (configSpiderUrl.isBlank()) {
            configSpiderUrl = root?.optString("spider").orEmpty()
        }
        if (configSpiderUrl.isBlank()) throw IllegalStateException("spider 配置缺失")

        val site = if (siteKey.isNotBlank()) {
            spSites.firstOrNull { it.key == siteKey }
        } else {
            spSites.firstOrNull()
        } ?: throw IllegalStateException("未找到 spider 站点：$siteKey")

        val loader = spiderLoader ?: throw IllegalStateException("SpiderLoader 不可用")
        val loaderKey = runCatching { loader.ensureLoaded(configSpiderUrl) }.getOrNull()
            ?: throw IllegalStateException("spider JAR 加载失败")
        val proxy = loader.getSpider(loaderKey, site.api, site.ext)
            ?: throw IllegalStateException("spider 实例创建失败：${site.api}")

        val json = proxy.playerContent(playSource, cloudUrl, vipFlags)
            ?: throw IllegalStateException("playerContent 返回空")

        val resolved = parsePlayerContentResult(json)
        // 分两行打，避免 logcat 单行截断看不到 URL
        Log.d("SpiderProxy", "playerContent($playSource) → url=${resolved.url}")
        Log.d("SpiderProxy", "playerContent($playSource) → headers=${resolved.headers.keys}")
        resolved
    }

    /**
     * 解析 playerContent 返回的 JSON，提取真实视频 URL 及请求头。
     *
     * 标准格式：{"parse":0,"url":"https://...","header":"{\"Cookie\":\"...\"}"}
     * header 可能是 JSON 对象，也可能是 JSON 字符串（需二次解析）。
     * 错误格式：{"list":[],"msg":"[quark](auth) 还没有配置夸克 Cookie...","parse":0}
     *
     * 特殊处理：部分 spider 的 playerContent 返回的 url 指向本地 Go 代理
     * （形如 http://127.0.0.1:<port>/kaiser?url=<CDN_URL>），而 Go 代理的 /kaiser
     * 端点不支持 HTTP Range 请求，ExoPlayer 播放时会 EOF。
     * 此时从 url 参数提取真实 CDN 直链直接播放，CDN 原生支持 Range。
     *
     * @throws IllegalStateException 解析失败（含 spider 返回的错误信息）
     */
    private fun parsePlayerContentResult(json: String): ResolvedPlayUrl {
        val obj = JSONObject(json)
        val rawUrl = obj.optString("url").takeIf { it.isNotBlank() }
        if (rawUrl == null) {
            val msg = obj.optString("msg").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: "playerContent 未返回视频地址"
            throw IllegalStateException(msg)
        }
        val headers = parseHeaders(obj.opt("header"))
        val url = unwrapLocalProxyUrl(rawUrl)
        return ResolvedPlayUrl(url, headers)
    }

    /**
     * 若 url 是本地代理转发地址（http://127.0.0.1:port/xxx?url=<CDN_URL>），
     * 提取并返回真实 CDN 直链；否则原样返回。
     */
    private fun unwrapLocalProxyUrl(url: String): String {
        if (!url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:")) {
            return url
        }
        return runCatching {
            val uri = android.net.Uri.parse(url)
            val cdnUrl = uri.getQueryParameter("url")
            if (cdnUrl.isNullOrBlank()) url else cdnUrl
        }.getOrDefault(url)
    }

    /**
     * 解析 playerContent 返回的 header 字段。
     * header 可能是 JSONObject，也可能是 JSON 字符串（夸克等网盘通常返回字符串形式）。
     */
    private fun parseHeaders(headerAny: Any?): Map<String, String> {
        if (headerAny == null) return emptyMap()
        return runCatching {
            val headerObj = when (headerAny) {
                is JSONObject -> headerAny
                is String -> if (headerAny.isNotBlank()) JSONObject(headerAny) else return emptyMap()
                else -> return emptyMap()
            }
            val out = mutableMapOf<String, String>()
            val keys = headerObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = headerObj.optString(k)
            }
            out
        }.getOrDefault(emptyMap())
    }

    // ---------- 内部 ----------

    /** 配置 JSON 里的 type=3（Spider）站点 */
    private fun parseSpiderSites(root: JSONObject): List<SpiderSite> {
        val arr = root.optJSONArray("sites") ?: return emptyList()
        val out = ArrayList<SpiderSite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "3") continue
            val api = o.optString("api").trim()
            if (api.isBlank()) continue
            val rawKey = o.optString("key").ifBlank { o.optString("name") }.ifBlank { "spider$i" }
            val name = o.optString("name").ifBlank { rawKey }
            val ext = o.opt("ext")
            out += SpiderSite(rawKey.replace(":", ""), name, api, ext)
        }
        return out.distinctBy { it.key }
    }
}

/** 极简 HTML 清理，用于接口返回的简介字段 */
object HtmlText {
    private val tag = Regex("<[^>]+>")
    private val spaces = Regex("\\s{2,}")
    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\""
    )

    fun strip(input: String): String {
        var s = input.replace(tag, " ")
        entities.forEach { (k, v) -> s = s.replace(k, v) }
        return s.replace(spaces, " ").trim()
    }
}
