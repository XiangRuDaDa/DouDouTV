package com.tabbit.myvidhub.data.tvbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TVBox 客户端。
 *
 * 支持两类「源」：
 * 1) 苹果CMS / MacCMS 风格 JSON 接口（type 1）：
 *      ?ac=videolist&pg=1     → 列表
 *      ?ac=videolist&wd=关键词 → 搜索
 *      ?ac=detail&ids=vodId   → 详情（含播放列表）
 * 2) TVBox 配置 JSON（形如 {"sites":[...]}）：
 *      · 自动取出 type=1 的 CMS 接口，依次聚合首页列表
 *      · 同时取出 type=3 的 Spider 爬虫站点，通过 [SpiderLoader] 动态加载并反射调用
 *
 * type=3 是 TVBox 现代配置的主力格式，爬虫逻辑封装在 spider JAR 里，
 * 运行时 DexClassLoader 加载 + 反射调用 Spider 基类的 5 个标准方法。
 */
class TvBoxClient(
    endpoint: String,
    context: Context? = null
) {

    /** 用户可能只填了 192.168.1.2:8080 或 example.com/api.php/provide/vod/，这里自动补协议 */
    private val base: String = endpoint.trim()
        .let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it" }
        .let { toPunycode(it) }

    private val origin: String = base.toHttpUrlOrNull()?.let { url ->
        val defaultPort = if (url.scheme == "https") 443 else 80
        buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != defaultPort) append(':').append(url.port)
        }
    }.orEmpty()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

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
     * 站点信息（线路）。type 1 = CMS 接口，type 3 = Spider 爬虫。
     * 由 [availableSites] 从 TVBox 配置解析后暴露给 UI 做线路切换。
     */
    data class TvBoxSite(
        val key: String,
        val name: String,
        val type: Int,
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

    private data class CmsSite(val key: String, val name: String, val api: String)
    private data class SpiderSite(val key: String, val name: String, val api: String, val ext: Any?)

    companion object {
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

        /** 播放 URL 过滤：只保留 http(s) 直链 */
        internal fun AbsolutePlayUrl(raw: String): String? {
            val url = AbsoluteUrl(raw)
            return if (url.startsWith("http://") || url.startsWith("https://")) url else null
        }
    }

    // ---------- HTTP ----------

    private fun requestJson(baseUrl: String, params: Map<String, String>): JSONObject {
        val punyBase = toPunycode(baseUrl)
        val url = punyBase.toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw IllegalStateException("接口地址无效：$baseUrl（需以 http:// 或 https:// 开头）")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/3.12.0")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("接口返回 HTTP ${response.code}：$baseUrl")
                }
                if (body.isBlank()) throw IllegalStateException("接口返回内容为空：$baseUrl")
                return parseConfigJson(body) ?: throw IllegalStateException(
                    "接口返回的不是 JSON：$baseUrl（请确认填的是 CMS 接口或 TVBox 配置地址）"
                )
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("无法连接接口：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 兼容 {"list":[]} 与 {"data":{"list":[]}} 两种包装 */
    private fun responseList(json: JSONObject): JSONArray? =
        json.optJSONArray("list") ?: json.optJSONObject("data")?.optJSONArray("list")

    // ---------- 列表 / 搜索 / 详情 ----------

    /**
     * 首页列表。
     * · 普通 CMS 接口：直接取第一页
     * · TVBox 配置：
     *   - type=1 CMS 站点：聚合各自第一页
     *   - type=3 Spider 站点：通过 SpiderLoader 取 homeContent
     */
    suspend fun homeContent(limit: Int = 100): List<Vod> = withContext(Dispatchers.IO) {
        // 先不带任何参数拉一次 —— 如果这是 TVBox 配置 JSON，响应里会有 sites/spider 字段。
        // 如果这是 CMS 接口，响应里会有 list 字段（或 ac=videolist 的数据）。
        val raw = runCatching { requestRaw(base) }.getOrNull()
        val configRoot = raw?.takeIf { it.has("sites") }
        val cmsList = raw?.let { responseList(it) }

        if (configRoot != null) {
            // ===== TVBox 配置 JSON 路径 =====
            val cmsSites = parseCmsSites(configRoot)
            val spSites = parseSpiderSites(configRoot)

            if (cmsSites.isEmpty() && spSites.isEmpty()) return@withContext emptyList()

            configSpiderUrl = configRoot.optString("spider")

            val merged = LinkedHashMap<String, Vod>()
            // 先拉 type 1 CMS 站点
            for (site in cmsSites.take(6)) {
                val json = runCatching {
                    requestJson(site.api, mapOf("ac" to "videolist", "pg" to "1"))
                }.getOrNull() ?: continue
                val list = responseList(json) ?: continue
                parseList(list, site.key, site.name).forEach {
                    merged["${site.key}:${it.vodId}"] = it
                }
                if (merged.size >= limit) break
            }

            // 再拉 type 3 spider 站点
            if (merged.size < limit && spSites.isNotEmpty() && spiderLoader != null && configSpiderUrl.isNotBlank()) {
                loadSpiderHome(spSites, merged, limit)
            }
            spiderSites = spSites
            return@withContext merged.values.take(limit).toList()
        }

        // ===== CMS 接口路径 =====
        if (cmsList != null) {
            return@withContext parseList(cmsList, "", "").take(limit)
        }

        // 兜底：加 ac=videolist 查询参数再试一次
        val retry = runCatching { requestJson(base, mapOf("ac" to "videolist", "pg" to "1")) }.getOrNull()
        retry?.let { responseList(it)?.let { list -> return@withContext parseList(list, "", "").take(limit) } }

        // 再兜底：用 ac=list 取第一个分类
        val firstCategory = runCatching { categories() }.getOrDefault(emptyList()).firstOrNull()
            ?: return@withContext emptyList()
        runCatching { requestJson(base, mapOf("ac" to "videolist", "t" to firstCategory.first, "pg" to "1")) }
            .getOrNull()?.let { responseList(it)?.let { list -> return@withContext parseList(list, "", "").take(limit) } }
        emptyList()
    }

    /**
     * 当前配置的站点（线路）列表。
     * · TVBox 配置 JSON：返回 type=1 CMS + type=3 Spider 全部站点
     * · 普通 CMS 接口：返回单条「默认线路」
     * 结果缓存，重复调用不会重复请求。
     */
    suspend fun availableSites(): List<TvBoxSite> = withContext(Dispatchers.IO) {
        cachedSites?.let { return@withContext it }
        val raw = runCatching { requestRaw(base) }.getOrNull()
        if (raw == null || raw.length() == 0) return@withContext emptyList()
        val sites = if (raw.has("sites")) {
            parseCmsSites(raw).map { TvBoxSite(it.key, it.name, 1, it.api) } +
                parseSpiderSites(raw).map { TvBoxSite(it.key, it.name, 3, it.api, it.ext) }
        } else {
            // 普通 CMS 接口：整个源就是一条线路
            listOf(TvBoxSite("", "默认线路", 1, base))
        }
        cachedSites = sites
        sites
    }

    /**
     * 只拉某个站点（线路）的首页列表。
     * 用户切换线路时按需调用，避免每次都聚合全部站点。
     * 返回影片列表 + 该线路 homeContent 的分类（class 数组），供 UI 按动态分区展示。
     */
    suspend fun siteHome(site: TvBoxSite, limit: Int = 100): SiteHomeResult = withContext(Dispatchers.IO) {
        if (site.type == 3) {
            val loader = spiderLoader ?: return@withContext SiteHomeResult(emptyList())
            if (configSpiderUrl.isBlank()) {
                configSpiderUrl = runCatching { requestRaw(base) }.getOrNull()?.optString("spider").orEmpty()
            }
            if (configSpiderUrl.isBlank()) return@withContext SiteHomeResult(emptyList())
            val merged = LinkedHashMap<String, Vod>()
            val categories = try {
                loadSpiderHome(listOf(SpiderSite(site.key, site.name, site.api, site.ext)), merged, limit)
            } catch (e: Exception) {
                // 不再吞异常：把壳初始化/爬虫调用的真实原因透出到 UI（否则切线路静默失败、内容不变）
                throw IllegalStateException("站点「${site.name}」爬取失败：${e.message ?: e.javaClass.simpleName}")
            }
            return@withContext SiteHomeResult(merged.values.take(limit).toList(), categories)
        }
        // type 1 CMS 站点
        val json = runCatching {
            requestJson(site.api.ifBlank { base }, mapOf("ac" to "videolist", "pg" to "1"))
        }.getOrNull() ?: return@withContext SiteHomeResult(emptyList())
        SiteHomeResult(parseList(responseList(json), site.key, site.name).take(limit))
    }

    /**
     * 站点分类内容（对应 TVBox 点分类后调 categoryContent 的分页）。
     * type 3 → spider.categoryContent(tid, pg, filter, extend)；
     * type 1 → CMS 接口 ac=videolist&t=typeId。
     */
    suspend fun categoryContent(site: TvBoxSite, typeId: String, page: Int = 1): List<Vod> =
        withContext(Dispatchers.IO) {
            if (site.type == 3) {
                val loader = spiderLoader ?: return@withContext emptyList()
                if (configSpiderUrl.isBlank()) {
                    configSpiderUrl = runCatching { requestRaw(base) }.getOrNull()?.optString("spider").orEmpty()
                }
                if (configSpiderUrl.isBlank()) return@withContext emptyList()
                val loaderKey = runCatching { loader.ensureLoaded(configSpiderUrl) }.getOrNull()
                    ?: return@withContext emptyList()
                val proxy = loader.getSpider(loaderKey, site.api, site.ext) ?: return@withContext emptyList()
                val json = proxy.categoryContent(typeId, page.toString(), filter = true, extend = null)
                    ?: return@withContext emptyList()
                return@withContext CatVodResultParser.parseVodList(json, site.key, site.name)
            }
            // type 1 CMS 站点
            val json = requestJson(
                site.api.ifBlank { base },
                mapOf("ac" to "videolist", "t" to typeId, "pg" to page.toString())
            )
            parseList(responseList(json), site.key, site.name)
        }

    /**
     * 远程搜索：调 Spider Guard 的 searchContent 接口，直接在源站搜索。
     * 跟本地 DB 搜索互补——本地搜已入库的，远程搜 Guard 全网。
     *
     * @param site 目标站点（type 3 Spider 才支持）
     * @param keyword 关键词（支持中文片名/演员/题材，也支持拼音首字母但 Guard 内部不一定认）
     * @param quick 是否快速搜索（CatVod 协议语义，true 时 Guard 可能只返回前 10 条）
     */
    suspend fun search(site: TvBoxSite, keyword: String, quick: Boolean = false): List<Vod> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isBlank()) return@withContext emptyList()
            // type 1 CMS 站点：直接 ac=videolist&wd= 搜索，不走 spider，不触发 Go 代理初始化
            if (site.type == 1) {
                val json = runCatching {
                    requestJson(
                        site.api.ifBlank { base },
                        mapOf("ac" to "videolist", "wd" to kw)
                    )
                }.getOrNull() ?: return@withContext emptyList()
                return@withContext parseList(responseList(json), site.key, site.name)
            }
            // type 3 Guard 站点：只有 spider 已加载过（loaders 缓存命中）才尝试，
            // 避免为了一次搜索触发完整的 Go 代理初始化（"正在初始化本地代理"弹窗）
            if (site.type == 3) {
                val loader = spiderLoader ?: return@withContext emptyList()
                if (configSpiderUrl.isBlank()) {
                    configSpiderUrl = runCatching { requestRaw(base) }.getOrNull()?.optString("spider").orEmpty()
                }
                if (configSpiderUrl.isBlank()) return@withContext emptyList()
                // 关键：只查缓存，不调 ensureLoaded（那会触发 Go 代理初始化）
                val loaderKey = loader.cachedLoaderKeyOrNull(configSpiderUrl)
                    ?: return@withContext emptyList()
                val proxy = loader.getSpider(loaderKey, site.api, site.ext)
                    ?: return@withContext emptyList()
                val json = runCatching { proxy.searchContent(kw, quick) }.getOrNull()
                    ?: return@withContext emptyList()
                return@withContext CatVodResultParser.parseVodList(json, site.key, site.name)
            }
            emptyList()
        }

    /** 不带参数的原始请求，用来判断 URL 类型（CMS 还是 TVBox 配置） */
    private fun requestRaw(rawUrl: String): JSONObject {
        val punyUrl = toPunycode(rawUrl)
        val request = Request.Builder()
            .url(punyUrl)
            .header("User-Agent", "okhttp/3.12.0")
            .get()
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                parseConfigJson(body) ?: JSONObject()
            }
        }.getOrNull() ?: JSONObject()
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

    suspend fun category(typeId: String, page: Int = 1): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(base, mapOf("ac" to "videolist", "t" to typeId, "pg" to page.toString()))
        parseList(responseList(json), "", "")
    }

    suspend fun search(keyword: String): List<Vod> = withContext(Dispatchers.IO) {
        val json = requestJson(base, mapOf("ac" to "videolist", "wd" to keyword))
        parseList(responseList(json), "", "")
    }

    /**
     * 取详情：拿回播放地址列表（这是实际能播的关键）。
     * @param siteKey 来自 TVBox 配置时按站点定位接口；空表示直接使用填写的接口
     */
    suspend fun detail(vodId: String, siteKey: String = ""): Vod? = withContext(Dispatchers.IO) {
        // 先尝试直接 CMS 接口（用户填的就是 CMS API 地址）
        if (siteKey.isBlank()) {
            fetchCmsDetail(base, vodId, "", "")?.let { return@withContext it }
        }

        val root = runCatching { requestJson(base, emptyMap()) }.getOrNull()
        val cmsSites = root?.let { parseCmsSites(it) } ?: emptyList()
        val spSites = root?.let { parseSpiderSites(it) } ?: spiderSites

        // 优先按 siteKey 精确匹配
        if (siteKey.isNotBlank()) {
            cmsSites.firstOrNull { it.key == siteKey }?.let { site ->
                fetchCmsDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
            }
            // Spider 路径
            if (spSites.isNotEmpty() && root != null) {
                configSpiderUrl = root.optString("spider")
            }
            spSites.firstOrNull { it.key == siteKey }?.let { site ->
                val result = fetchSpiderDetail(site, vodId)
                if (result != null) return@withContext result
            }
        }

        // 兜底：所有 CMS 站点逐个尝试
        for (site in cmsSites) {
            fetchCmsDetail(site.api, vodId, site.key, site.name)?.let { return@withContext it }
        }

        // 再兜底：所有 Spider 站点
        if (spSites.isNotEmpty()) {
            if (configSpiderUrl.isBlank() && root != null) configSpiderUrl = root.optString("spider")
            if (configSpiderUrl.isBlank() && spSites.any { true /* any */ }) {
                // spider URL 可能从之前的 homeContent 已经拿到了
            }
            for (site in spSites) {
                fetchSpiderDetail(site, vodId)?.let { return@withContext it }
            }
        }

        // 最后兜底：纯 CMS 接口
        if (siteKey.isBlank()) {
            fetchCmsDetail(base, vodId, "", "")?.let { return@withContext it }
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
        val root = runCatching { requestRaw(base) }.getOrNull()
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

    suspend fun categories(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val json = requestJson(base, mapOf("ac" to "list"))
        val classes = json.optJSONArray("class") ?: return@withContext emptyList()
        (0 until classes.length()).mapNotNull { i ->
            val o = classes.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("type_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.optString("type_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }
    }

    // ---------- 内部 ----------

    private fun fetchCmsDetail(api: String, vodId: String, siteKey: String, siteName: String): Vod? {
        val json = runCatching {
            requestJson(api, mapOf("ac" to "detail", "ids" to vodId))
        }.getOrNull() ?: return null
        val list = responseList(json) ?: return null
        if (list.length() == 0) return null
        val obj = list.optJSONObject(0) ?: return null
        return parseVod(obj, withPlay = true).copy(siteKey = siteKey, siteName = siteName)
    }

    /** 配置 JSON 里的 type=1（CMS）站点 */
    private fun parseCmsSites(root: JSONObject): List<CmsSite> {
        val arr = root.optJSONArray("sites") ?: return emptyList()
        val out = ArrayList<CmsSite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "1") continue
            val api = o.optString("api").trim()
            if (!api.startsWith("http", true)) continue
            val rawKey = o.optString("key").ifBlank { o.optString("name") }.ifBlank { "site$i" }
            val name = o.optString("name").ifBlank { rawKey }
            out += CmsSite(rawKey.replace(":", ""), name, api)
        }
        return out.distinctBy { it.key }
    }

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

    private fun parseList(arr: JSONArray?, siteKey: String, siteName: String): List<Vod> {
        if (arr == null) return emptyList()
        val out = ArrayList<Vod>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val vod = parseVod(o, withPlay = false)
            if (vod.vodId.isBlank()) continue
            out += vod.copy(siteKey = siteKey, siteName = siteName)
        }
        return out
    }

    private fun parseVod(o: JSONObject, withPlay: Boolean): Vod {
        val id = o.optString("vod_id")
        return Vod(
            vodId = id,
            name = o.optString("vod_name"),
            pic = absoluteUrl(o.optString("vod_pic")),
            remarks = o.optString("vod_remarks"),
            year = o.optString("vod_year").toIntOrNull() ?: 0,
            area = o.optString("vod_area"),
            typeName = o.optString("vod_class").ifBlank { o.optString("type_name") },
            content = HtmlText.strip(o.optString("vod_content")),
            score = o.optString("vod_score").toDoubleOrNull() ?: 0.0,
            playSources = if (withPlay) parsePlaySources(o) else emptyList()
        )
    }

    /**
     * vod_play_from = "线路1$$$线路2"
     * vod_play_url  = "第1集$url#第2集$url$$$第1集$url#..."
     * 分隔符：$$$ 分线路，# 分集，$ 分「集名 / 地址」
     */
    private fun parsePlaySources(o: JSONObject): List<PlaySource> {
        val from = o.optString("vod_play_from")
        val urlStr = o.optString("vod_play_url")
        if (urlStr.isBlank()) return emptyList()

        val fromNames = from.split("$$$").map { it.trim() }
        val groups = urlStr.split("$$$")

        return groups.mapIndexedNotNull group@{ gi, group ->
            val episodes = group.split('#').mapIndexedNotNull episode@{ idx, piece ->
                if (piece.isBlank()) return@episode null
                val sep = piece.indexOf('$')
                // 保留所有 URL（含云盘 scheme），不再用 absolutePlayUrl 过滤非 http 地址，
                // 否则网盘线路的分集会全部丢失。
                if (sep < 0) {
                    Episode("正片", absoluteUrl(piece.trim()))
                } else {
                    val name = piece.substring(0, sep).trim().ifBlank { "第${idx + 1}集" }
                    val url = absoluteUrl(piece.substring(sep + 1).trim())
                    Episode(name, url)
                }
            }
            if (episodes.isEmpty()) return@group null
            val name = fromNames.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路${gi + 1}"
            PlaySource(name, episodes)
        }
    }

    /** 兼容 //host/x、/path/x 这类相对地址 */
    private fun absoluteUrl(raw: String): String {
        val url = raw.trim()
        return when {
            url.isBlank() -> ""
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") && origin.isNotBlank() -> origin + url
            else -> url
        }
    }
}

/** 极简 HTML 清理，用于 CMS 返回的简介字段 */
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
