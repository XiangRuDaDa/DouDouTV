package com.tabbit.myvidhub.data.tvbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
        /** vod_actor 原文：逗号/顿号分隔的姓名，协议不含演员头像 */
        val actors: String = "",
        /** vod_director 原文，同上 */
        val directors: String = "",
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
        val ext: Any? = null,
        /** 配置 searchable：1 参与搜索（缺省），0/2 不参与 */
        val searchable: Boolean = true,
        /** 配置 quickSearch：1 支持快速搜索（缺省），0 不支持 */
        val quickSearch: Boolean = true
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

    private data class SpiderSite(
        val key: String,
        val name: String,
        val api: String,
        val ext: Any?,
        val searchable: Boolean = true,
        val quickSearch: Boolean = true
    )

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

        /**
         * 配置地址 → spider 包地址（进程级）。
         * 播放页每次都是 new TvBoxClient，实例里没有配置信息；有了这张表，
         * 冷启动播放直链时不用先下载整份配置就能判断 spider 是否已加载。
         */
        private val spiderUrlByBase = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** 直链解析超时：超过就回退原地址，不让慢 spider 拖住本来就能播的直链 */
        private const val DIRECT_RESOLVE_TIMEOUT_MS = 8_000L

        /** 首页 homeContent 为空时的一次短暂重试间隔：壳 Init 已完成，不需要 2s/4s/8s 三轮等待 */
        private const val HOME_RETRY_DELAY_MS = 1_500L

        /** 直链解析的独立协程作用域：超时后可以不等待阻塞中的反射调用直接返回 */
        private val directCallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            spiderUrlByBase.remove(key)
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

        rememberSpiderUrl(configRoot.optString("spider"))
        if (configSpiderUrl.isBlank()) {
            throw IllegalStateException("TVBox 配置缺少 spider 字段，无法加载爬虫")
        }
        spiderSites = spSites
        if (spiderLoader == null) return@withContext emptyList()

        val merged = LinkedHashMap<String, Vod>()
        loadSpiderHome(spSites, merged)
        merged.values.take(limit).toList()
    }

    /**
     * 只把 spider 包准备到能调用（JAR 落盘 → 壳 Init → 起本地代理），不请求任何站点数据。
     *
     * 加密壳的 Init 是纯本地计算（实测电视上要二十多秒），首页/分类/详情都得先过这一步。
     * 放在刷新链路里等 = 用户白等一轮，所以给启动预热用：跑完后 SpiderLoader 的进程级缓存命中，
     * 后续 homeContent / detail 直接复用，不会再触发一次壳初始化。
     */
    suspend fun prepareSpider(): Boolean = withContext(Dispatchers.IO) {
        val loader = spiderLoader ?: return@withContext false
        if (configSpiderUrl.isBlank()) {
            val root = baseConfig() ?: return@withContext false
            rememberSpiderUrl(root.optString("spider"))
        }
        if (configSpiderUrl.isBlank()) return@withContext false
        runCatching { loader.ensureLoaded(configSpiderUrl) }.isSuccess
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
        val sites = parseSpiderSites(raw).map {
            TvBoxSite(it.key, it.name, it.api, it.ext, it.searchable, it.quickSearch)
        }
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
            rememberSpiderUrl(baseConfig()?.optString("spider").orEmpty())
        }
        if (configSpiderUrl.isBlank()) {
            throw IllegalStateException("TVBox 配置缺少 spider 字段，无法加载爬虫")
        }
        val merged = LinkedHashMap<String, Vod>()
        val categories = try {
            loadSpiderHome(
                listOf(SpiderSite(site.key, site.name, site.api, site.ext, site.searchable, site.quickSearch)),
                merged
            )
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
                rememberSpiderUrl(baseConfig()?.optString("spider").orEmpty())
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
                rememberSpiderUrl(baseConfig()?.optString("spider").orEmpty())
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
        merged: LinkedHashMap<String, Vod>
    ): List<CatVodResultParser.ClassItem> = withContext(Dispatchers.IO) {
        val loader = spiderLoader ?: throw IllegalStateException("缺少 Android Context，无法加载爬虫")
        val loaderKey = try {
            loader.ensureLoaded(configSpiderUrl)
        } catch (e: Exception) {
            throw IllegalStateException("加载 spider 包失败：${e.message ?: e.javaClass.simpleName}")
        }

        // 先串行取实例：首次会触发壳内 Guard 初始化，并发创建实例没有收益，还容易压垮 JSVM
        val reasons = ArrayList<String>()
        val proxies = ArrayList<Pair<SpiderSite, SpiderProxy>>()
        for (site in sites.take(4)) {
            val proxy = loader.getSpider(loaderKey, site.api, site.ext) ?: run {
                reasons += "${site.name}: ${loader.lastError}"
                null
            } ?: continue
            proxies += site to proxy
        }
        if (proxies.isEmpty()) {
            throw IllegalStateException(
                if (reasons.isEmpty()) "该配置的爬虫站点没有返回任何影片"
                else "爬虫站点均无数据：${reasons.take(3).joinToString("；")}"
            )
        }

        // 站点之间并发请求：原来是串行 + 每站最多 2s/4s/8s 递增重试，
        // 4 个站点最坏要等近一分钟；并发后总耗时约等于最慢的那个站点。
        // 单个站点异常只影响它自己，不取消其他站点的请求。
        val results = coroutineScope {
            proxies.map { (site, proxy) ->
                async {
                    runCatching { fetchSiteHome(site, proxy) }.getOrElse { e ->
                        Log.w("SpiderProxy", "${site.name} homeContent 异常：${e.message}")
                        SiteHomeFetch(site, null)
                    }
                }
            }.awaitAll()
        }

        val categories = ArrayList<CatVodResultParser.ClassItem>()
        for (result in results) {
            val json = result.json
            if (json.isNullOrEmpty()) {
                reasons += "${result.site.name}: homeContent 返回空"
                continue
            }
            // 分类（class）：即使 list 为空、只有分类，站点也「有数据」，UI 可按分类分区浏览
            val siteCategories = CatVodResultParser.parseClasses(json)
            categories += siteCategories
            val list = CatVodResultParser.parseVodList(json, result.site.key, result.site.name)
            if (list.isEmpty() && siteCategories.isEmpty()) reasons += "${result.site.name}: homeContent 无影片"
            list.forEach { merged["${result.site.key}:${it.vodId}"] = it }
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

    private data class SiteHomeFetch(val site: SpiderSite, val json: String?)

    /**
     * 单个站点的 homeContent（为空时重试一次）。
     * 壳 Init/LocalServer 已在 [loadSpiderHome] 里就绪，这里只留 1.5s 缓冲，
     * 不再用 2s/4s/8s 三轮递增重试 —— 那是首页刷新慢的主要来源。
     */
    private suspend fun fetchSiteHome(site: SpiderSite, proxy: SpiderProxy): SiteHomeFetch {
        var json = proxy.homeContent(filter = true)
        if (json.isNullOrEmpty()) {
            delay(HOME_RETRY_DELAY_MS)
            json = proxy.homeContent(filter = true)
        }
        return SiteHomeFetch(site, json)
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
            rememberSpiderUrl(root.optString("spider"))
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
        val vod = CatVodResultParser.parseDetail(json, site.key, site.name) ?: return null

        // 注意：不再在这里对所有非 http 地址统一调 playerContent 解析。
        // 网盘线路一部剧动辄几十上百集、多条云盘，逐个 playerContent 会让详情加载卡死。
        // 云盘 scheme 地址（quark://、aliyundrive:// 等）原样保留，播放时再按需解析。
        return vod
    }

    /** playerContent 解析结果：视频直链 + 所需 HTTP 请求头 */
    data class ResolvedPlayUrl(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** playerContent 返回的 parse 标记：0 = 可直接播放，1 = 需要网页解析 */
        val parse: Int = 0,
        /** 需要网页解析（parse/jx = 1，或返回地址本身是网页）：当前版本不支持 */
        val needsWebParse: Boolean = false
    )

    /**
     * 解析播放地址（对应 CatVod 的 playerContent）。
     *
     * 与 FongMi 等成熟实现一致：**直链与网盘地址统一先问 spider**。
     * spider 能补上 Cookie/Referer 等请求头，也能识别出需要网页解析的线路。
     *
     * 为不影响现有可播链路，额外做三点保护：
     *  1. 直链 + 本进程从未加载过 spider（如「继续观看」冷启动）→ 直接返回原地址，
     *     不为一个本来就能播的链接去下载/解密 JAR、起本地代理；
     *  2. 直链解析超时（[DIRECT_RESOLVE_TIMEOUT_MS]）、异常或返回空 → 回退原地址；
     *  3. 网盘 scheme（quark:// 等）必须解析成功，失败时保留原始错误（原地址播不了）。
     *
     * @param siteKey    站点 key（用于定位 spider 站点）
     * @param playSource 播放源名称（即 vod_play_from，如「夸克网盘」），作为 playerContent 的 flag
     * @param rawUrl     线路原始地址（云盘 scheme 或 http(s) 直链），作为 playerContent 的 id
     * @return 解析结果（URL + headers + parse 标记）；云盘解析失败或需要网页解析时抛异常
     */
    suspend fun resolvePlayUrl(
        siteKey: String,
        playSource: String,
        rawUrl: String
    ): ResolvedPlayUrl = withContext(Dispatchers.IO) {
        val isDirect = rawUrl.startsWith("http://") || rawUrl.startsWith("https://")

        // 冷启动保护：spider 从未加载过时，直链不需要（也不值得）走解析
        if (isDirect) {
            val loadedKey = knownSpiderUrl().takeIf { it.isNotBlank() }
                ?.let { spiderLoader?.cachedLoaderKeyOrNull(it) }
            if (spiderLoader == null || loadedKey == null) {
                return@withContext ResolvedPlayUrl(rawUrl)
            }
        }

        val resolved = if (isDirect) {
            runCatching { doResolvePlayUrl(siteKey, playSource, rawUrl, direct = true) }
                .getOrElse {
                    Log.w("SpiderProxy", "playerContent($playSource) 直链解析失败，回退原地址：${it.message}")
                    return@withContext ResolvedPlayUrl(rawUrl)
                }
        } else {
            doResolvePlayUrl(siteKey, playSource, rawUrl, direct = false)
        }

        when {
            // 直链场景：只有原地址本身就是网页时才拒绝，避免误伤被 spider 误标 parse=1 的可播直链
            resolved.needsWebParse && isDirect && !isWebPageUrl(rawUrl) -> {
                Log.w("SpiderProxy", "playerContent($playSource) 标记需要网页解析，但原地址像直链，回退原地址")
                ResolvedPlayUrl(rawUrl)
            }
            resolved.needsWebParse -> throw IllegalStateException(
                "该线路需要网页解析（spider 标记 parse/jx=1 或地址是网页），当前版本不支持，请切换其他线路"
            )
            else -> resolved
        }
    }

    /** 真正调用 playerContent：取站点 → 加载 spider → 解析返回 JSON */
    private suspend fun doResolvePlayUrl(
        siteKey: String,
        playSource: String,
        rawUrl: String,
        direct: Boolean
    ): ResolvedPlayUrl {
        val root = baseConfig()
        val spSites = root?.let { parseSpiderSites(it) } ?: spiderSites
        if (configSpiderUrl.isBlank()) {
            rememberSpiderUrl(root?.optString("spider").orEmpty())
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

        // CatVod 约定 vipFlags 传配置根级 flags（如 youku/qq 等 VIP 平台名），
        // spider 普遍按 vipFlags.contains(flag) 决定走不走 VIP 解析。
        // 配置没写 flags 就必须传空列表：把「播放源名」当 vipFlags 会让 spider 误判成 VIP 线路。
        val flags = configFlags(root)

        val json = (if (direct) playerContentWithTimeout(proxy, playSource, rawUrl, flags)
        else proxy.playerContent(playSource, rawUrl, flags))
            ?: throw IllegalStateException("playerContent 返回空")

        logPlayerContentResult(playSource, flags, json)
        val resolved = parsePlayerContentResult(json)
        // 分两行打，避免 logcat 单行截断看不到 URL。
        // 用 W 级：这台电视 ROM 吞掉 app 的 D 级日志，播放链路取证只能靠 W 才抓得到。
        Log.w("SpiderProxy", "playerContent($playSource) → url=${resolved.url}")
        Log.w(
            "SpiderProxy",
            "playerContent($playSource) → headers=${resolved.headers.keys} parse=${resolved.parse}"
        )
        return resolved
    }

    /**
     * 直链场景的 playerContent 带超时调用。
     *
     * SpiderProxy.playerContent 内部是阻塞反射调用（自身最多等 30s），协程超时打断不了它，
     * 所以放到独立作用域里 await：到点就放弃等待、回退原地址，慢 spider 不会拖住本来就能播的直链。
     */
    private suspend fun playerContentWithTimeout(
        proxy: SpiderProxy,
        flag: String,
        id: String,
        flags: List<String>
    ): String? {
        val call = directCallScope.async { proxy.playerContent(flag, id, flags) }
        val json = withTimeoutOrNull(DIRECT_RESOLVE_TIMEOUT_MS) { call.await() }
        if (json == null) {
            call.cancel()
            Log.w("SpiderProxy", "playerContent($flag) 直链解析超过 ${DIRECT_RESOLVE_TIMEOUT_MS}ms，回退原地址")
        }
        return json
    }

    /** 记录配置里的 spider 地址（同时写进程级表，供其他 TvBoxClient 实例快速判断 spider 是否已加载） */
    private fun rememberSpiderUrl(raw: String) {
        if (raw.isBlank()) return
        configSpiderUrl = raw
        spiderUrlByBase[base] = raw
    }

    /** 当前配置的 spider 地址：本实例没读过配置时回落到进程级缓存，可能为空 */
    private fun knownSpiderUrl(): String = configSpiderUrl.ifBlank { spiderUrlByBase[base].orEmpty() }

    /**
     * 配置根级 flags（playerContent 的 vipFlags）。
     * 兼容数组写法 ["youku","qq"] 与逗号分隔字符串写法 "youku,qq"。
     */
    private fun configFlags(root: JSONObject?): List<String> {
        return when (val raw = root?.opt("flags")) {
            is JSONArray -> (0 until raw.length())
                .map { raw.optString(it).trim() }
                .filter { it.isNotBlank() }
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    /** 粗判网页地址：去掉 query/fragment 后以网页后缀结尾（真实视频直链不会这样） */
    private fun isWebPageUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".shtml")
    }

    /**
     * 记录 playerContent 原始返回的「形状」，用于定位「返回了 msg 却没有 url」这类失败。
     * 只打字段名、parse/jx、url 的 host 与长度、msg 摘要；header 里是 Cookie/api_key，绝不打印。
     */
    private fun logPlayerContentResult(flag: String, flags: List<String>, json: String) {
        val brief = runCatching {
            val obj = JSONObject(json)
            val keys = obj.keys().asSequence().joinToString(",")
            val msg = (obj.optString("msg").takeIf { it.isNotBlank() }
                ?: obj.optString("message")).take(300)
            val url = obj.optString("url")
            val host = if (url.startsWith("http://") || url.startsWith("https://")) {
                runCatching { android.net.Uri.parse(url).host }.getOrNull()
            } else null
            "keys=[$keys] parse=${obj.optInt("parse", 0)} jx=${obj.optInt("jx", 0)} " +
                "urlLen=${url.length} urlHost=$host msg=$msg"
        }.getOrElse { "非 JSON，长度=${json.length} 前120字=${json.take(120)}" }
        Log.w("SpiderProxy", "playerContent($flag) ← flags=$flags $brief")
    }

    /**
     * 解析 playerContent 返回的 JSON，提取真实视频 URL、请求头与解析标记。
     *
     * 标准格式：{"parse":0,"url":"https://...","header":"{\"Cookie\":\"...\"}"}
     * parse=1 或 jx=1 表示 url 只是网页/待解析地址，需要再走网页解析（parse API / jx API）
     * 才能拿到视频直链；本 App 未集成解析服务，由调用方给出明确提示。
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
        val parse = obj.optInt("parse", 0)
        val jx = obj.optInt("jx", 0)
        val rawUrl = obj.optString("url").takeIf { it.isNotBlank() }
        if (rawUrl == null) {
            val msg = obj.optString("msg").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: "playerContent 未返回视频地址"
            throw IllegalStateException(msg)
        }
        val headers = parseHeaders(obj.opt("header"))
        val url = unwrapLocalProxyUrl(rawUrl)
        val needsWebParse = parse == 1 || jx == 1 || isWebPageUrl(url)
        return ResolvedPlayUrl(url, headers, parse, needsWebParse)
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
            if (cdnUrl.isNullOrBlank()) {
                // 本地代理形态但没带 url 参数：整条视频流都会经 127.0.0.1 中转，
                // 单连接吞吐受代理本身限制 —— 网盘播放慢的第一嫌疑，必须留痕。
                Log.w("SpiderProxy", "本地代理地址无 url 参数，播放器将直连 127.0.0.1：$url")
                url
            } else {
                Log.w(
                    "SpiderProxy",
                    "已从本地代理地址解出真实直链：${uri.host}:${uri.port}${uri.path} → " +
                        android.net.Uri.parse(cdnUrl).host
                )
                cdnUrl
            }
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
            out += SpiderSite(
                key = rawKey.replace(":", ""),
                name = name,
                api = api,
                ext = ext,
                searchable = optFlag(o, "searchable"),
                quickSearch = optFlag(o, "quickSearch")
            )
        }
        return out.distinctBy { it.key }
    }

    /**
     * 解析站点的布尔开关（searchable / quickSearch）。
     * 兼容 1/0、true/false、"1"/"0" 三种写法；缺省与 CatVod 一致按开启处理。
     */
    private fun optFlag(o: JSONObject, name: String): Boolean = when (val v = o.opt(name)) {
        null, JSONObject.NULL -> true
        is Boolean -> v
        is Number -> v.toInt() == 1
        is String -> v.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
        else -> true
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
