package com.tabbit.myvidhub.player

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.image.ImageRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 播放器工厂 —— 手机端与电视端各自一套参数。
 *
 * 「播一下就转圈」的真正原因不是带宽不够，而是**起播门槛太低**：
 * 旧配置 bufferForPlaybackMs = 1s，意思是只要缓冲到 1 秒数据就立刻开播。
 * 而一部 1080p 影片每秒要吃掉约 1~2MB，网络实际下载速率只要略低于码率，
 * 这 1 秒的余量会在几秒内被吃干，于是立刻 underrun → 转圈 → 再缓冲 → 再播。
 * 用户看到的就是「播一下就转圈」的循环。
 *
 * 因此这里的关键改动是**抬高起播与重缓冲门槛**，让播放器先攒够余量再出画面：
 *   1. bufferForPlaybackMs 1s → 5s：首帧稍晚一点，但换来连续播放；
 *   2. bufferForPlaybackAfterRebufferMs 2.5s → 5s：卡顿后必须重新攒够 5s 才恢复，
 *      避免「恢复 0.5 秒又卡」的抖动；
 *   3. minBufferMs 20s/30s → 30s/45s：网络抖动时靠本地缓冲兜住；
 *   4. maxBufferMs 与 targetBufferBytes 同步放大，防止缓冲到顶后主动停止预读。
 *
 * 磁盘缓存默认 LRU 自动淘汰，但如果用户长时间不用、缓存越积越多，
 * 可以通过设置页手动清空。关闭单个播放器时也会调用 clearCacheFor(context) 清一次。
 */
@UnstableApi
object PlayerFactory {

    /** 缓存目录名称 */
    private const val CACHE_DIR_NAME = "media_cache"

    /** 磁盘缓存期望上限：手机 256MB（之前 1GB 太激进，看个 20G 电影缓存膨胀到 20G 就是这个原因） */
    private const val CACHE_PREFERRED_PHONE = 256L * 1024 * 1024
    /** 磁盘缓存期望上限：电视盒子 512MB */
    private const val CACHE_PREFERRED_TV = 512L * 1024 * 1024
    /** 保底缓存下限：低于这个值连一集都存不下，缓存就失去意义了 */
    private const val CACHE_MIN_BYTES = 64L * 1024 * 1024

    /** 起播门槛：攒够 2 秒再出画面（5 秒太激进，夸克 CDN 限速时要等超久） */
    private const val BUFFER_FOR_PLAYBACK_MS = 2_000

    /** 卡顿后恢复门槛：3 秒，兼顾恢复速度与稳定性 */
    private const val BUFFER_AFTER_REBUFFER_MS = 3_000

    /** 开区间 Range 头（bytes=X-）：只有这种请求才需要补一个结尾，防止一路读到 EOF */
    private val OPEN_RANGE_REGEX = Regex("bytes=(\\d+)-$")

    @Volatile
    private var cache: SimpleCache? = null

    /** 计算磁盘缓存上限 */
    private fun cacheLimitFor(context: Context, isTv: Boolean): Long {
        val preferred = if (isTv) CACHE_PREFERRED_TV else CACHE_PREFERRED_PHONE
        val free = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(preferred * 4)
        return minOf(preferred, maxOf(free / 4, CACHE_MIN_BYTES))
    }

    private fun cacheInstance(context: Context, isTv: Boolean): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: run {
                SimpleCache(
                    File(context.cacheDir, CACHE_DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(cacheLimitFor(context, isTv)),
                    StandaloneDatabaseProvider(context)
                ).also { cache = it }
            }
        }

    /** 手机端：屏幕小、常切前后台，缓冲略收一点以保证内存占用可控 */
    fun createPhonePlayer(
        context: Context,
        /** Emby 反向代理 / 严格鉴权时需要额外 header（如 X-Emby-Authorization） */
        headers: Map<String, String> = emptyMap()
    ): ExoPlayer = build(context, isTv = false, headers = headers)

    /** 电视端：码率更高、网络更稳，缓冲池更大、超时更宽松 */
    fun createTvPlayer(
        context: Context,
        headers: Map<String, String> = emptyMap()
    ): ExoPlayer = build(context, isTv = true, headers = headers)

    private fun build(
        context: Context,
        isTv: Boolean,
        headers: Map<String, String> = emptyMap()
    ): ExoPlayer {
        // OkHttp 底层 —— DefaultHttpDataSource (HttpURLConnection) 在 MIUI/Xiaomi 上会静默丢弃
        // Cookie 等自定义 header，导致夸克/阿里云盘 CDN 返回 412 或空响应，播放器无限卡 BUFFERING。
        // OkHttpDataSource 直接透传所有 header，行为与 OkHttp 调用完全一致。
        val connectTimeout = if (isTv) 15_000 else 10_000
        val readTimeout = if (isTv) 60_000 else 45_000

        // Range 请求改写逻辑：
        // ExoPlayer 的进度式播放（MP4/MKV/AVI...）在 seek 后会发开区间 Range（"bytes=X-"），
        // 如果原样透传，CDN 会一直返回到文件 EOF（动辄几个 GB），OkHttp 连接被长时间占用，
        // 后续 seek 抢不到连接就卡住。所以只对这种「开区间 Range」限制结尾（8MB 一块）。
        //
        // 关键：请求里本来就没有 Range 头时绝对不能再加！
        // HLS/DASH 的媒体分片（.ts / .m4s）请求就是不带 Range 的，强行改写成
        // "bytes=0-32767" 会把几 MB 的分片截成 32KB，分片数据不完整 → 解析失败 / 丢帧，
        // 表现为播放器每 0.2~0.3 秒就「缓冲→就绪→播放→暂停」循环、进度条一顿一顿往前跳。
        val chunkSize = 8L * 1024 * 1024
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // 显式连接池：保持 5 个空闲连接 5 分钟，Range 请求可复用 keep-alive 连接
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val rangeHeader = original.header("Range")
                val openEnded = rangeHeader != null && OPEN_RANGE_REGEX.containsMatchIn(rangeHeader)
                val request = if (openEnded) {
                    // 有 start 无 end（"bytes=X-"）→ 8MB 固定块，避免读到 EOF 拖住连接
                    val start = OPEN_RANGE_REGEX.find(rangeHeader!!)!!.groupValues[1].toLong()
                    original.newBuilder().header("Range", "bytes=$start-${start + chunkSize - 1}").build()
                } else {
                    // 无 Range（HLS/DASH 分片、清单）或已带完整 end → 原样透传
                    original
                }
                Log.d("PlayerHttp", ">>> ${request.method} ${request.url}")
                val range = request.header("Range")
                if (range != null) Log.d("PlayerHttp", "    Range: $range")
                val response = chain.proceed(request)
                val ct = response.header("Content-Type", "")
                val ar = response.header("Accept-Ranges", "")
                val cr = response.header("Content-Range")
                val code = response.code
                val cl = response.body?.contentLength() ?: -1L
                Log.d("PlayerHttp", "<<< $code ${response.message} size=$cl contentType=$ct acceptRanges=$ar")
                if (!cr.isNullOrBlank()) Log.d("PlayerHttp", "    Content-Range: $cr")

                // ===== 404 资源失效拦截：提前抛出带明确消息的 IOException =====
                // Media3 的 ERROR_CODE_IO_BAD_HTTP_STATUS 只给错误码，用户看不懂。
                // 在 OkHttp 层拦截 404 → 抛带中文提示的 IOException → ExoPlayer 把 message
                // 放进 PlaybackException → onPlayerError 能拿到 message 翻译。
                if (code == 404) {
                    val host = request.url.host
                    Log.e("PlayerHttp", "404 Not Found — 资源已失效 host=$host url=${request.url}")
                    response.close()
                    throw java.io.IOException(
                        "HTTP 404: 该资源已失效（$host），可能是云盘分享链接过期或内容被移除。"
                    )
                }
                // 403/412 = Cookie 失效或签名过期（云盘常见）
                if (code == 403 || code == 412) {
                    val host = request.url.host
                    Log.e("PlayerHttp", "$code ${response.message} — 认证失效 host=$host")
                    response.close()
                    throw java.io.IOException(
                        "HTTP $code: 访问被拒绝（$host），云盘 Cookie 或登录状态可能已过期。"
                    )
                }

                response
            })
            .build()

        val httpFactory = OkHttpDataSource.Factory(client)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        Log.d("PlayerFactory", "build OkHttpDataSource headers=${headers.keys} connect=${connectTimeout}ms read=${readTimeout}ms")

        // ===== 永远禁用 CacheDataSource =====
        // 原因：OkHttp Interceptor 会拦截 ExoPlayer 的 Range 请求并改写（首次探测、固定块等），
        // 这与 CacheDataSource 内部的缓存片段 bookkeeping 逻辑不兼容，
        // 导致 open() 阶段直接抛 READ_POSITION_OUT_OF_RANGE。
        // 此 bug 影响所有来源（Emby 本地直链、夸克/百度云盘 CDN），
        // 因此直接使用裸 OkHttpDataSource，跳过 CacheDataSource。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isTv) 45_000 else 30_000,
                if (isTv) 300_000 else 240_000,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(if (isTv) 384 * 1024 * 1024 else 256 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60_000, true)
            .build()

        val errorPolicy = DefaultLoadErrorHandlingPolicy(8)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)

        // 自定义 RenderersFactory：过滤掉 ImageRenderer。
        // 原因：Range 拦截器会改写 "bytes=X-" 开区间请求，MKV/MP4 内嵌的 poster 图片
        // attachment 轨道数据可能不完整，ImageRenderer 拿到残缺数据 → BitmapFactory 解码失败
        // → ParserException → 播放器崩掉。作为视频播放器我们不需要渲染内嵌图片，直接移除。
        val defaultFactory = DefaultRenderersFactory(context)
        val renderersFactory = RenderersFactory { handler, videoRendererEventListener, audioRendererEventListener, textRendererOutput, metadataRendererOutput ->
            defaultFactory.createRenderers(
                handler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput
            ).filter { it !is ImageRenderer }.toTypedArray()
        }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** 读取缓存目录当前占用（字节数），设置页展示用 */
    fun cacheSizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) return 0L
        fun walk(f: File): Long {
            return if (f.isFile) f.length()
            else f.listFiles()?.sumOf { walk(it) } ?: 0L
        }
        return walk(dir)
    }

    /** 清理磁盘缓存。SimpleCache 必须 release 后才能删目录，否则会被锁。 */
    fun clearCache(context: Context) {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
        }
        runCatching {
            val dir = File(context.cacheDir, CACHE_DIR_NAME)
            if (dir.exists()) {
                // 先删 SimpleCache 自己的 db 文件，确保下次创建不冲突
                dir.listFiles()?.forEach { it.deleteRecursively() }
                dir.delete()
            }
        }
    }

    /** 释放当前 SimpleCache 实例（退出应用 / 播放器 Activity onDestroy 时调用） */
    fun releaseCache() {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
        }
    }

    /** 字节数格式化，展示友好 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.1f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            else -> "%.0f KB".format(bytes / kb)
        }
    }
}
