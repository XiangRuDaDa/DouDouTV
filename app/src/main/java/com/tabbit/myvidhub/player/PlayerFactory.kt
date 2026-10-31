package com.tabbit.myvidhub.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.FileTypes
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.image.ImageRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.PsExtractor
import androidx.media3.extractor.ts.TsExtractor
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.File
import java.io.IOException
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

    /** 加载失败的重试次数（与之前 DefaultLoadErrorHandlingPolicy(8) 保持一致） */
    private const val MIN_LOAD_RETRY_COUNT = 8

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
        // 后续 seek 抢不到连接就卡住。所以只对这种「开区间 Range」限制结尾。
        //
        // 关键：请求里本来就没有 Range 头时绝对不能再加！
        // HLS/DASH 的媒体分片（.ts / .m4s）请求就是不带 Range 的，强行改写成
        // "bytes=0-32767" 会把几 MB 的分片截成 32KB，分片数据不完整 → 解析失败 / 丢帧，
        // 表现为播放器每 0.2~0.3 秒就「缓冲→就绪→播放→暂停」循环、进度条一顿一顿往前跳。
        //
        // 块大小 32MB：每块结束都要重开一次请求（Media3 会报一次人为 EOF），块太小会让
        // 这部分开销占比过高（8MB 时实测每 4 秒就空一下）。32MB 在 2MB/s 下约 16 秒一块。
        val chunkSize = 32L * 1024 * 1024
        // 每个播放器一份「防和谐」归一化器：记录哪些直链带伪装前缀、真实容器是什么。
        // 按完整 URL 记录：同一次播放里的签名参数不变，映射生命周期刚好覆盖一次播放。
        val normalizer = PlaybackNormalizer()
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // 显式连接池：保持 5 个空闲连接 5 分钟，Range 请求可复用 keep-alive 连接
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val urlKey = original.url.toString()

                // 已知带伪装头：请求坐标整体右移前缀字节数
                normalizer.planOf(urlKey)?.takeIf { it.shifted }?.let { plan ->
                    val shifted = normalizer.shiftRange(original, plan.prefixBytes, chunkSize)
                    return@Interceptor chain.proceed(shifted)
                }

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
                var response = chain.proceed(request)
                val code = response.code
                val cl = response.body?.contentLength() ?: -1L

                // ===== 伪装头识别 + 整文件限速规避 =====
                // 两件事共用同一次头部探测：
                //   1. 网盘伪装：视频前面塞一张图片（8 字节魔术字或整张 PNG/JPEG），
                //      必须找到真实容器起点后才能解析；
                //   2. 无 Range 的整文件 200：CDN 会把它限速在 ~100KB/s（实测夸克 99KB/s），
                //      4K 片源永远填不满缓冲，表现为「一直缓冲中、速度像 0」。改走 Range 通道即满速。
                // 伪装长度不固定，所以按窗口逐级扫描真实容器头，命中即停。
                val fromStart = rangeHeader == null || rangeHeader.startsWith("bytes=0-")
                val bigBody = cl <= 0 || cl > 8L * 1024 * 1024
                if (fromStart && bigBody) {
                    val head = runCatching { response.peekBody(16).bytes() }.getOrNull()
                    var plan: PlaybackNormalizer.Plan? = null
                    if (head != null && normalizer.looksLikeImage(head)) {
                        for (limit in normalizer.scanWindowLimits) {
                            val window = runCatching { response.peekBody(limit).bytes() }
                                .getOrNull() ?: break
                            plan = normalizer.detect(window, allowPrefix = true)
                            // 命中真实容器头，或已经读到流末尾（伪装图之外没有容器头）
                            if (plan != null || window.size < limit) break
                        }
                        plan?.let { normalizer.remember(urlKey, it) }
                    } else if (head != null) {
                        // 正常文件头：只认第 0 字节，避免把 MP4 的 free/wide 前置 box 当成伪装前缀
                        plan = normalizer.detect(head, allowPrefix = false)
                    }

                    // 无 Range 的整文件 200 → 改用 Range 通道；伪装头顺带把坐标平移到真实容器起点。
                    // 只对已知 >8MB 的响应生效，HLS 分片/小文件完全不受影响。
                    // 重发带的区间要按容器分：
                    //   开区间（bytes=X-）虽然能通过 Content-Range 报出真实总长，但实测夸克对这种
                    //   响应同样限速 ~99KB/s，4K MKV 光首包就要转十几秒；
                    //   MKV 的头部自描述、不依赖总长，所以首包也直接切带结尾的块，走满速通道。
                    //   MP4 的 moov 常在尾部，必须靠开区间拿到真实总长才能算进度条和 seek，
                    //   容器未识别时也保守走开区间。
                    val needRetry = rangeHeader == null && code == 200 && cl > 8L * 1024 * 1024
                    if (needRetry) {
                        response.close()
                        val prefix = plan?.prefixBytes ?: 0L
                        val retryChunk =
                            if (plan?.container == PlaybackNormalizer.Container.MATROSKA) chunkSize else null
                        val retry = normalizer.shiftRange(original, prefix, retryChunk)
                        // 只留这一条决策日志：切换通道是一次性的，分片级流水账已删
                        Log.w(
                            "PlayerHttp",
                            "整文件 200（${cl / 1024 / 1024}MB，${plan?.evidence ?: "容器未识别"}）" +
                                "改走 Range 通道：${retry.header("Range")}"
                        )
                        return@Interceptor chain.proceed(retry)
                    }
                }
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

        val errorPolicy = ChunkEofRetryPolicy()
        val mediaSourceFactory =
            DefaultMediaSourceFactory(httpFactory, ContainerExtractorsFactory(normalizer))
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
            .also { attachDiagnostics(it) }
    }

    /**
     * 播放链路探针。
     *
     * 「转圈不出画面」有两种完全不同的成因：网卡在收数据，或数据到了但解不出轨道。
     * 系统 ACodec / MediaCodec 日志是 D 级，这台电视 ROM 直接吞掉，所以必须在
     * app 侧用 W 级把「读了多少字节 → 解出什么轨道 → 建没建解码器」串起来。
     * 只打状态跃迁，不打每帧，正常播放时日志量很小。
     */
    @UnstableApi
    private fun attachDiagnostics(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                // 这个回调在 LoadErrorHandlingPolicy 重试之前触发，是唯一能看到
                // 「被重试吞掉的 IO 错误」的地方
                Log.w(
                    "PlayerDiag",
                    "load失败 实收=${loadEventInfo.bytesLoaded}B wasCanceled=$wasCanceled " +
                        "${error.javaClass.simpleName}: ${error.message}"
                )
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                Log.w("PlayerDiag", "视频解码器就绪 $decoderName (${initializationDurationMs}ms)")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                Log.w("PlayerDiag", "音频解码器就绪 $decoderName (${initializationDurationMs}ms)")
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                Log.w("PlayerDiag", "视频解码器失败 ${videoCodecError.javaClass.simpleName}: ${videoCodecError.message}")
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                Log.w("PlayerDiag", "音频解码器失败 ${audioCodecError.javaClass.simpleName}: ${audioCodecError.message}")
            }
        })
    }

    /**
     * 分块 Range 读到块尾时，ExtractorInput 会抛一记 EOFException —— 那不是网络故障，
     * 只是「这一块读完了」；但默认策略会退避 1 秒再续下一块（实测 8MB 块 + 1 秒空窗，
     * 吞吐直接少 20%）。所以 EOF 立即续读，真正的 IO 错误仍走默认退避，
     * 避免网络真的断掉时疯狂重试打爆服务器。
     */
    private class ChunkEofRetryPolicy :
        DefaultLoadErrorHandlingPolicy(MIN_LOAD_RETRY_COUNT) {

        override fun getRetryDelayMsFor(
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long {
            if (loadErrorInfo.exception is EOFException) return 0L
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /**
     * 容器识别兜底。
     *
     * 夸克 CDN 对同一部剧的 MKV 会给出两种 Content-Type：video/x-matroska 或
     * application/octet-stream。前者 Media3 只产出一个 MatroskaExtractor，直接采用、
     * 不做嗅探；后者 Media3 认不出容器，会退回「十几个 extractor 逐个嗅探」，实测
     * 读到 32KB 就全部失败 → 播放器转圈后直接 ENDED，没画面也没错误提示。
     *
     * 所以 MIME 不可判定容器时，按「探测到的容器 > 文件名后缀」排序候选，交给 Media3
     * sniff 最终定夺。文件名只能当提示、不能当事实：实测 E06.mkv 的真实容器是 MP4。
     * Emby 等 MIME 可靠的来源完全走原路径，不受影响。
     */
    private class ContainerExtractorsFactory(
        private val normalizer: PlaybackNormalizer
    ) : ExtractorsFactory {

        private val fallback = DefaultExtractorsFactory()

        override fun createExtractors(): Array<Extractor> = fallback.createExtractors()

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>
        ): Array<Extractor> {
            val contentType = contentTypeOf(responseHeaders)
            val mimeType = FileTypes.inferFileTypeFromMimeType(contentType)
            // extractor 在 MediaPeriod 构造时就创建，此时首包可能还没探测完，
            // 所以探测结果只是「锦上添花」，缺失时退回 MIME / 文件名。
            val detected = normalizer.planOf(uri.toString())?.container
                ?.takeIf { it != PlaybackNormalizer.Container.UNKNOWN }
            if (mimeType != FileTypes.UNKNOWN &&
                (detected == null || detected.fileType == mimeType)
            ) {
                return fallback.createExtractors(uri, responseHeaders)
            }
            // 夸克直链的路径里只有 hash，真实文件名在 filename 查询参数上
            val filename = uri.getQueryParameter("filename")
            val named = if (!filename.isNullOrBlank()) {
                Uri.Builder().scheme("file").appendPath(filename).build()
            } else {
                uri
            }
            val type = detected?.fileType ?: when (mimeType) {
                FileTypes.UNKNOWN -> FileTypes.inferFileTypeFromUri(named)
                else -> mimeType
            }
            // extractor 是有状态的：init() 不重置、release() 是空实现，同一实例绝不能
            // 跨剧集复用，否则新周期的 endTracks() 永远发不出来 → 播放器无限转圈。
            // 所以每次都向默认工厂要一批全新实例。
            //
            // 名字匹配的排在前面、其余保留，让 Media3 用 sniff 决定真正匹配的那个
            // —— 与 MIME 能判定时默认工厂返回「首选 + 全部」的行为一致。
            val all = fallback.createExtractors(named, emptyMap())
            val (preferred, rest) = all.partition { matches(type, it) }
            Log.w(
                "PlayerFactory",
                "MIME=${contentType ?: "无"} 探测容器=${detected?.label ?: "无"} " +
                    "filename=${filename ?: "无"} " +
                    "按${if (detected != null) "探测" else "名"}优先=" +
                    preferred.joinToString { it.javaClass.simpleName }.ifEmpty { "无" } +
                    " 共${all.size}个候选（嗅探定夺）"
            )
            return (preferred + rest).toTypedArray()
        }

        private fun contentTypeOf(headers: Map<String, List<String>>): String? =
            headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()

        private fun matches(type: Int, extractor: Extractor): Boolean = when (type) {
            FileTypes.MATROSKA -> extractor is MatroskaExtractor
            FileTypes.MP4 -> extractor is Mp4Extractor || extractor is FragmentedMp4Extractor
            FileTypes.TS -> extractor is TsExtractor
            FileTypes.PS -> extractor is PsExtractor
            else -> false
        }
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
