package com.tabbit.myvidhub.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import com.tabbit.myvidhub.core.VideoCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 网盘直链的取数层，管三件事：流坐标、磁盘缓存、让路。
 *
 * ── 为什么需要「流坐标」 ──
 * 防和谐的网盘会在视频前面塞一张诱饵图（几十字节到几百 KB），播放器看到的第 0 字节
 * 其实是网盘文件里的第 prefix 字节。以前这个平移放在 OkHttp 拦截器里（改 Range 头），
 * 缓存的区间却是按 DataSpec 坐标记的，两套坐标一叠，CacheDataSource.open() 直接抛
 * READ_POSITION_OUT_OF_RANGE —— 这就是这个仓库以前彻底禁用缓存的原因。
 * 现在平移收口在 [StreamSpaceDataSource]：上层（含 Media3 的缓存记账）只用流坐标，
 * 文件坐标只在这一个类里出现，外面看不到第二套。
 *
 * ── 分块 ──
 * 夸克这类 CDN 只有「带结尾的定长 Range 块」跑得满，而且按连接累计限速，
 * 所以每块仍然是 32MB 闭区间 + 每块换新连接（Connection: close 由拦截器加）。
 * 块边界对上层不可见：本类内部续开下一个闭区间，表现成一条连续流。
 * 这点是硬要求 —— 只要提前返回一次 END_OF_INPUT，CacheDataSource 就把它当成
 * 「资源到此为止」，把缓存元数据的总长记成半截，之后整集都被截断。
 *
 * ── 缓存挂载点 ──
 * [VideoCacheDataSource] 是唯一决定「这条直链走不走缓存」的地方，判据只有 URL 登记没有：
 * Emby 直链、m3u8 分片、容量档关闭时都不会被登记，于是拿到的就是裸 OkHttpDataSource，
 * 与今天逐字节一致。
 *
 * ══════════════════════════════════════════════════════════════════
 *
 * [StreamSpaceProbe]：探测一条直链的流坐标原点 —— 伪装前缀长度 + 网盘文件总长。
 *
 * 用一次 16KB 的闭区间请求同时拿到两样东西（Content-Range 里有总长），
 * 头部像诱饵图时再逐级放大窗口找真实容器起点 —— 和以前拦截器里的探测同一套判据。
 */
/** C.LENGTH_UNSET 在 Java 侧是 int，而 DataSpec.length / 缓存记的总长是 long，Kotlin 比不了；值一致 */
private const val UNSET_LENGTH = -1L

@UnstableApi
internal class StreamSpaceProbe(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val normalizer: PlaybackNormalizer,
) {

    /** 探测不出流坐标（服务器不认 Range / 伪装图后面找不到容器）时抛这个，调用方据此退回纯网络路径 */
    class Unsupported(message: String) : IOException(message)

    fun cachedPlan(url: String): PlaybackNormalizer.Plan? =
        normalizer.planOf(url)?.takeIf { it.totalBytes != UNSET_LENGTH }

    @Throws(IOException::class)
    fun ensure(url: String): PlaybackNormalizer.Plan {
        cachedPlan(url)?.let { return it }
        return probe(url).also { normalizer.remember(url, it) }
    }

    private fun probe(url: String): PlaybackNormalizer.Plan {
        val first = fetch(url, HEAD_WINDOW)
        val plan = if (normalizer.looksLikeImage(first.head)) {
            normalizer.detect(first.head, allowPrefix = true) ?: run {
                var found: PlaybackNormalizer.Plan? = null
                for (limit in longArrayOf(64L * 1024, 256L * 1024)) {
                    found = normalizer.detect(fetch(url, limit).head, allowPrefix = true)
                    if (found != null) break
                }
                found ?: throw Unsupported("伪装图之后 256KB 内找不到容器，这条直链不缓存")
            }
        } else {
            // 正常文件只认第 0 字节，避免把 MP4 的 free/wide 前置 box 当成伪装前缀
            normalizer.detect(first.head, allowPrefix = false)
                ?: PlaybackNormalizer.Plan(0L, PlaybackNormalizer.Container.UNKNOWN, "文件头即容器")
        }
        val total = first.totalBytes
        Log.w(
            "VideoCache",
            "流坐标 前缀=${plan.prefixBytes}B 容器=${plan.container.label} " +
                "文件=${total / 1048576}MB 流=${(total - plan.prefixBytes) / 1048576}MB ${plan.evidence}"
        )
        return plan.copy(totalBytes = total)
    }

    /** 取文件开头 size 字节，同时拿到文件总长 */
    private fun fetch(url: String, size: Long): Head {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${size - 1}")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val call = runCatching { client.newCall(builder.build()) }
            .getOrElse { throw Unsupported("探测请求建不出来：${it.message}") }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw Unsupported("探测失败：${e.message}")
        }
        response.use {
            val code = it.code
            // 带了 Range 却不回 206 = 服务器不认区间，按块取数这条路走不通
            if (code != 206) throw Unsupported("服务器不支持 Range（code=$code），这条直链不缓存")
            val total = contentRangeTotal(it.header("Content-Range"))
            if (total <= 0L) throw Unsupported("Content-Range 里拿不到文件总长")
            val head = runCatching { it.peekBody(size).bytes() }.getOrDefault(ByteArray(0))
            return Head(head, total)
        }
    }

    /** Content-Range: bytes 0-16383/858993459 */
    private fun contentRangeTotal(value: String?): Long =
        RANGE_TOTAL_REGEX.find(value.orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    private class Head(val head: ByteArray, val totalBytes: Long)

    private companion object {
        const val HEAD_WINDOW = 16L * 1024
        val RANGE_TOTAL_REGEX = Regex("bytes\\s+\\d+-\\d+/(\\d+)")
    }
}

/**
 * 流坐标读取层：把播放器的 DataSpec（流坐标）翻译成网盘的闭区间 Range（文件坐标），
 * 并在块边界内部续请求，对外表现成一条连续流。
 */
@UnstableApi
internal class StreamSpaceDataSource(
    private val httpFactory: DataSource.Factory,
    private val probe: StreamSpaceProbe,
    private val chunkBytes: Long,
) : DataSource {

    private val listeners = ArrayList<TransferListener>()
    private var http: DataSource? = null
    private var requestUri: Uri? = null
    private var streamLength = UNSET_LENGTH
    private var prefix = 0L

    /** 下一条将交付给上层的字节的流坐标 */
    private var nextPos = 0L

    /** 本次 open 还要交付多少流字节；归零就按约定返回 END_OF_INPUT */
    private var requestRemaining = 0L
    private var chunkDelivered = 0L
    private var chunkOpenAt = 0L
    private var emptyChunks = 0

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val plan = probe.ensure(dataSpec.uri.toString())
        prefix = plan.prefixBytes
        streamLength = plan.totalBytes - prefix
        requestUri = dataSpec.uri
        nextPos = dataSpec.position.coerceAtMost(streamLength)
        val available = (streamLength - nextPos).coerceAtLeast(0L)
        requestRemaining =
            if (dataSpec.length == UNSET_LENGTH) available else minOf(dataSpec.length, available)
        // 关键：这里必须回「本次请求的剩余流长度」，不能回一个分块大小 ——
        // CacheDataSource 会拿这个数当整条资源写进缓存元数据，回小了整集就只剩这一块。
        if (requestRemaining == 0L) return 0L
        openChunk(nextPos)
        return requestRemaining
    }

    private fun openChunk(streamPos: Long) {
        closeHttp()
        val size = minOf(chunkBytes, streamLength - streamPos).coerceAtLeast(1L)
        val source = httpFactory.createDataSource()
        listeners.forEach { source.addTransferListener(it) }
        http = source
        chunkDelivered = 0L
        chunkOpenAt = SystemClock.elapsedRealtime()
        source.open(
            DataSpec.Builder()
                .setUri(checkNotNull(requestUri))
                .setPosition(streamPos + prefix)
                .setLength(size)
                .build()
        )
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (requestRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val source = http ?: return C.RESULT_END_OF_INPUT
        val n = source.read(buffer, offset, minOf(length.toLong(), requestRemaining).toInt())
        if (n == C.RESULT_END_OF_INPUT) {
            logChunk()
            if (nextPos >= streamLength) return C.RESULT_END_OF_INPUT   // 真正的流末尾
            if (chunkDelivered == 0L) {
                // 开口成功但一个字节没给，再来三次还是这样就报故障，别在这空转
                if (++emptyChunks >= 3) throw IOException("连续 $emptyChunks 个分块拿不到字节")
            } else {
                emptyChunks = 0
            }
            openChunk(nextPos)
            return read(buffer, offset, length)
        }
        nextPos += n
        chunkDelivered += n
        requestRemaining -= n
        return n
    }

    private fun logChunk() {
        val cost = SystemClock.elapsedRealtime() - chunkOpenAt
        if (chunkDelivered == 0L || cost <= 0L) return
        Log.w(
            "PlayerHttp",
            "块账 pos=$nextPos 交付=${chunkDelivered / 1048576}MB 用时=${cost}ms " +
                "速率=${chunkDelivered / 1024 * 1000 / cost}KB/s"
        )
    }

    override fun getUri(): Uri? = requestUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        http?.getResponseHeaders() ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        closeHttp()
        requestUri = null
        requestRemaining = 0L
        nextPos = 0L
        streamLength = UNSET_LENGTH
    }

    private fun closeHttp() {
        val source = http ?: return
        http = null
        // 残包关掉失败不外抛：那是我们主动放弃的字节，不该盖掉真正的读错误
        runCatching { source.close() }
    }

    class Factory(
        private val httpFactory: DataSource.Factory,
        private val probe: StreamSpaceProbe,
        private val chunkBytes: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            StreamSpaceDataSource(httpFactory, probe, chunkBytes)
    }
}

/**
 * 一条播放会话的缓存挂载点：SimpleCache 与 CacheDataSource 工厂都在这里收口。
 *
 * [bundleOrNull] 会打开缓存目录和一张 SQLite 索引表，只许在 IO / 播放器加载线程调用；
 * 主线程（设置页、看完删）只用 [com.tabbit.myvidhub.core.VideoCache] 上那几个不新建的方法。
 */
@UnstableApi
internal class CloudCachePipeline(
    client: OkHttpClient,
    headers: Map<String, String>,
    normalizer: PlaybackNormalizer,
    private val httpFactory: DataSource.Factory,
    private val chunkBytes: Long,
) {

    val probe = StreamSpaceProbe(client, headers, normalizer)

    private val lock = Any()
    private var built: Bundle? = null

    /** 容量档关闭或 SimpleCache 建不起来时返回 null，调用方退回纯网络 */
    fun bundleOrNull(): Bundle? {
        val cache = runCatching { VideoCache.cacheOrNull() }.getOrNull() ?: return null
        synchronized(lock) {
            built?.takeIf { it.cache === cache }?.let { return it }
            return Bundle(
                cache,
                newFactory(cache, "播放"),
                newFactory(cache, "预读"),
            ).also { built = it }
        }
    }

    private fun newFactory(cache: Cache, role: String): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                StreamSpaceDataSource.Factory(httpFactory, probe, chunkBytes)
            )
            // key 已经在 VideoCacheDataSource 里查过一遍，这里只是把它交给 Media3
            .setCacheKeyFactory { dataSpec ->
                VideoCache.keyFor(dataSpec.uri.toString()) ?: dataSpec.uri.toString()
            }
            // 缓存这一层出任何问题都只让缓存失效，不许把正在播的那一集拖下水
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    if (cachedBytesRead > 0) {
                        Log.w(
                            "VideoCache",
                            "[$role] 命中缓存 +${cachedBytesRead / 1048576}MB，累计占用 ${cacheSizeBytes / 1048576}MB"
                        )
                    }
                }

                override fun onCacheIgnored(reason: Int) {
                    // 2 = 缓存出错后本次绕开缓存；预读器和播放器抢同一段时不算异常
                    Log.w("VideoCache", "[$role] 本次绕开缓存 reason=$reason")
                }
            })

    class Bundle(
        val cache: Cache,
        private val playbackFactory: CacheDataSource.Factory,
        private val downloadFactory: CacheDataSource.Factory,
    ) {

        /** 播放器用：非阻塞。区域锁被预读器占着时直接绕开缓存读网络，绝不让播放等锁 */
        fun newPlaybackSource(): DataSource = playbackFactory.createDataSource()

        /** 预读器用：阻塞等锁，让路靠线程中断 */
        fun newDownloadSource(): DataSource = downloadFactory.createDataSourceForDownloading()
    }
}

/**
 * 播放器实际拿到的 DataSource —— 「这条直链走不走磁盘缓存」只在 [open] 判一次。
 *
 * 判据是 URL 有没有被登记过（[com.tabbit.myvidhub.core.VideoCache.keyFor]）：
 * Emby 直链、m3u8 分片、容量档关闭时都拿不到 key，于是每次 open 新建一个裸
 * OkHttpDataSource，Range 改写仍由拦截器负责 —— 与今天逐字节一致。
 */
@UnstableApi
internal class VideoCacheDataSource(
    private val pipeline: CloudCachePipeline,
    private val httpFactory: DataSource.Factory,
    private val onStreamMiss: (url: String, streamPos: Long) -> Unit,
) : DataSource {

    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val delegate = cacheSourceFor(dataSpec) ?: httpFactory.createDataSource()
        listeners.forEach { delegate.addTransferListener(it) }
        active = delegate
        return delegate.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    private fun cacheSourceFor(dataSpec: DataSpec): DataSource? {
        val url = dataSpec.uri.toString()
        val key = VideoCache.keyFor(url) ?: return null
        val bundle = pipeline.bundleOrNull() ?: return null
        val plan = runCatching { pipeline.probe.ensure(url) }.getOrElse {
            Log.w("VideoCache", "流坐标探测失败，这条直链退回纯网络：${it.message}")
            VideoCache.exclude(url)
            return null
        }
        dropIfStale(bundle.cache, key, plan)
        // 播放器要的是缓存里没有的字节 —— 预读器从它前面开始攒
        onStreamMiss(url, dataSpec.position)
        return bundle.newPlaybackSource()
    }

    private fun dropIfStale(cache: Cache, key: String, plan: PlaybackNormalizer.Plan) {
        val cachedLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (cachedLength == UNSET_LENGTH || cachedLength == plan.totalBytes - plan.prefixBytes) {
            return
        }
        // 同一集换了文件（重传 / 换清晰度），或者上次的前缀探错了 —— 错位的半截缓存比没缓存更糟
        Log.w(
            "VideoCache",
            "缓存记的总长=$cachedLength 与本集流长=${plan.totalBytes - plan.prefixBytes} 不符，整条作废"
        )
        runCatching { cache.removeResource(key) }
    }

    override fun getUri(): Uri? = active?.getUri()

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.getResponseHeaders() ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        val source = active ?: return
        active = null
        source.close()
    }

    class Factory(
        private val pipeline: CloudCachePipeline,
        private val httpFactory: DataSource.Factory,
        private val onStreamMiss: (String, Long) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            VideoCacheDataSource(pipeline, httpFactory, onStreamMiss)
    }
}
