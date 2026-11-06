package com.chen.doudoutv.ui.player.tv

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.chen.doudoutv.data.MediaRepository
import com.chen.doudoutv.data.format.FormatProbe
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.tvbox.TvBoxClient
import com.chen.doudoutv.player.DownloadMeter
import com.chen.doudoutv.player.PlayerFactory
import com.chen.doudoutv.ui.components.episodeLabel
import com.chen.doudoutv.ui.components.usesSourceEpisodeName
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.DouDouTvTheme
import com.chen.doudoutv.ui.theme.Radius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 电视端播放器（与手机端完全独立的一套实现）。
 *
 * 交互遵循遥控器心智，不做触摸手势、不做比例切换：
 *   · OK 键：暂停 / 继续，并显示进度条
 *   · 下方向键：弹出功能工具栏（只有一级按钮，不展开二级菜单）；
 *     在某一个按钮上按确定才展开它的二级选项（倍速 / 字幕 / 音轨 / 字幕样式 / 选集）
 *   · 左右方向键：快退 / 快进 10 秒，带时间提示
 *   · 返回键：按「二级菜单 → 工具栏 → 时间轴」逐层收起，都收起了才退出播放
 *   · 工具栏 10 秒无按键自动收起（进度层仍是 3 秒）
 * 始终全屏，不提供「适应 / 铺满 / 缩放」切换。
 *
 * 按键一律在 [dispatchKeyEvent] 里拦，不用 onKeyDown：
 * onKeyDown 是「View 树没人要」才轮到 Activity 的兜底回调，进度条出现后 Compose 焦点
 * 还在页面上，方向键会被焦点搜索吃掉一次，表现就是「第一次能呼出、之后要按两次」。
 */
@UnstableApi
class TvPlayerActivity : ComponentActivity() {

    private lateinit var repo: MediaRepository
    private var player: ExoPlayer? = null
    /** 基础请求头（如 Emby 的 X-Emby-Authorization），同一次播放会话内不变 */
    private var baseHeaders: Map<String, String> = emptyMap()
    /** 当前播放器实际使用的 HTTP 请求头（baseHeaders + 云盘解析出的 headers），切换源时需重建播放器 */
    private var currentPlayHeaders: Map<String, String> = emptyMap()
    private var mediaId = 0L
    private var current: MediaEntity? = null
    private var episodes: List<MediaEntity> = emptyList()

    private val lastSavedAt = AtomicLong(0L)

    /**
     * 已经处理过「播完」的分集行 id。
     * 连播只认第一次 END：换集时旧播放器可能把 END 补投到新集上，
     * 没这道闸就会一集接一集地自己往下播。每次 play() 复位。
     */
    private var endedEpisodeId = 0L

    /** 本集是否已经为下一集预热过直链：一集最多一次，失败也不重试，不去反复敲源站 */
    private var prefetched = false

    /**
     * 装载代数。每调一次 [play] 加一，解析回来后只对号入座。
     * 连点两下「下一集」时会有两个解析在飞，晚到的那个不加闸就会把前一集的地址
     * 喂给正在加载新一集的播放器。
     */
    private var playGeneration = 0

    private val handler = Handler(Looper.getMainLooper())

    // ---------- 供 Compose 读取的界面状态 ----------
    private var overlayVisible by mutableStateOf(true)

    /**
     * 进度层这一层是不是被返回键收掉的。
     *
     * 收掉之后本轮缓冲不再自动呼出（缓冲结束、或用户自己呼出时复位），否则返回键会一直
     * 在收同一层：收掉 ≤500ms 后 ticker 又打开，而缓冲期 isPlaying=false，3 秒自动隐藏不生效。
     */
    private var overlayDismissedByBack = false

    /** 功能工具栏（一级按钮行）是否显示 —— 下键唤出，返回键收起 */
    private var toolbarOpen by mutableStateOf(false)

    /** 工具栏里当前展开的二级菜单；null = 只显示一级按钮，等用户按确定再展开 */
    private var panelTab by mutableStateOf<TvPanelTab?>(null)

    /**
     * 选集面板的窗口起点。
     * 面板那一行是不滚动的 Row，整表铺进去会把胶囊挤出屏幕（电视上还收不回来），
     * 所以一屏只显示以当前集为中心的一段，两端各挂一颗翻页胶囊。
     */
    private var epWindowStart by mutableStateOf(0)
    private var toast by mutableStateOf<String?>(null)
    private var seekHint by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)

    /**
     * 缓冲提示（非空 = 正在「想播但没数据」）。
     * 高码率原码资源在低带宽下会长时间不起播，画面看着就是「卡死」；
     * 把此刻的实际下载速率和这条流需要的码率摊出来，用户才能分辨是网速不够还是片子坏了。
     */
    private var bufferHint by mutableStateOf<String?>(null)

    /**
     * 实测下载速率（字节/秒）；-1 = 还没攒满第一个 1 秒窗口。
     *
     * 取「累计已收字节」的 1 秒差分，而不是直接把 Media3 的带宽估算值摆上去：
     * 后者单个样本最长可跨 2 秒、还会跨请求平滑，屏幕上看着像数字半天不动；更麻烦的是我们这条
     * 手工搭的数据源链压根没把 meter 挂成 TransferListener，那个回调一次也不会来，数字永远是 0。
     * 累计值由 [DownloadMeter] 在 OkHttp 真正读字节时提供，差分窗口固定 1 秒，所以这个数字一秒跳一次。
     */
    private var downloadBps by mutableStateOf(-1L)

    /**
     * 装载中（点击播放 → 首帧出画）。
     *
     * 云盘 / TVBox 线路要先跑 spider 解析出真实地址（实测 2~5 秒），这段时间播放器还停在
     * IDLE，PlayerView 什么都不画，屏幕上就是纯黑 —— 用户会以为点击没生效。
     * 所以从进入播放器第一帧就自己画转圈，而不是等线路解析完、播放器进入 BUFFERING 才有反馈。
     */
    private var loading by mutableStateOf(true)

    /** 上一个网速窗口的起点（累计字节 + 时刻）；speedWindowAt=0 表示还没建立基线 */
    private var speedWindowBytes = 0L
    private var speedWindowAt = 0L
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var speed by mutableStateOf(1f)
    private var titleText by mutableStateOf("")
    private var trackOptions by mutableStateOf<List<TvTrack>>(emptyList())

    /** PlayerView 引用：用于设置字幕样式 */
    private var playerView: PlayerView? = null

    /** 装载中进度圈：平台 ProgressBar，与 PlayerView 自带的缓冲转圈同款 */
    private var loadingView: ProgressBar? = null

    /**
     * 装载遮罩：一条黑幕，盖在 PlayerView 之上、进度圈之下。
     *
     * 存在的理由只有一个 —— 切集后到新一集首帧上屏之间要花十几秒（解析直链 + 首帧缓冲），
     * 这段时间里 SurfaceView 仍然挂着**上一集的最后一帧**，屏幕上是「第 5 集的标题 + 第 4 集的画面」，
     * 看着就像卡死。`Player.stop()` 在不同电视上并不保证清掉最后一帧，所以由我们自己盖。
     */
    private var shutterView: View? = null

    /** 字幕是否处于关闭态。自己记录而不是反查播放器，避免不同 Media3 小版本 API 差异 */
    private var textDisabled by mutableStateOf(false)

    /** 字幕样式：背景透明度 / 字号，与手机端共用同一份偏好文件（player_prefs） */
    private var subtitleBgAlpha by mutableStateOf(DEFAULT_SUBTITLE_BG_ALPHA)
    private var subtitleTextSize by mutableStateOf(DEFAULT_SUBTITLE_TEXT_SIZE)

    private var prefs: android.content.SharedPreferences? = null

    /**
     * 装载 / 预热用的 scope，onDestroy 一并取消。
     * 原来每处各自 `CoroutineScope(...)`：退出播放后 spider 解析还在跑，
     * 完回来会对已 release 的播放器调方法、给已 detach 的 PlayerView 挂上新 ExoPlayer，
     * 那一路的解码器没人 release。
     * 落进度不在这里挂：见 [saveProgress]，它要能在 Activity 已经销毁后把最后一笔写完。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 交互计数：每次按键 / 选项切换都 +1，用于重置 3 秒自动隐藏 */
    private var tick by mutableStateOf(0)

    private val progressTicker = object : Runnable {
        override fun run() {
            player?.let { p ->
                // 网速和缓冲提示不受 loading 影响：装载那一程（解析完 → 首帧）恰恰是最需要看到
                // 「到底在下多少」的时候，把它一起挂进下面的门里等于加载全程只显示测速中。
                sampleDownloadSpeed()
                bufferHint = buildBufferHint(p)
                // 装载期间（进播放器 / 切集）暂停下面的写入：这时播放器还挂着上一集，
                // 继续跑就会把上一集的进度、时长写到新一集头上。
                if (!loading) {
                    positionMs = p.currentPosition.coerceAtLeast(0L)
                    durationMs = p.duration.takeIf { it > 0L } ?: 0L
                    isPlaying = p.isPlaying
                    // 缓冲时把进度层叫回来：不然后台在缓冲、屏幕上什么都看不到。工具栏开着时不抢它的位子。
                    // 但用户用返回键收掉过一次，本轮缓冲就不再叫（见 overlayDismissedByBack）——
                    // 收掉之后 ≤500ms 又自动打开、而缓冲期 isPlaying=false 那条 3 秒自动隐藏又不成立，
                    // 返回键就永远在收同一层，表现是「要连按两下才退出播放」。
                    if (bufferHint == null) {
                        overlayDismissedByBack = false
                    } else if (!overlayDismissedByBack && !toolbarOpen) {
                        overlayVisible = true
                    }
                    if (p.isPlaying) {
                        saveProgress(force = false)
                        prefetchNextEpisode()
                    }
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    /** 只在「已按下播放但数据还没到」时给出提示文本，其余情况返回 null */
    private fun buildBufferHint(p: Player): String? {
        if (p.playbackState != Player.STATE_BUFFERING) return null
        if (!p.playWhenReady) return null
        val speedText = if (downloadBps < 0L) "测速中" else fmtSpeed(downloadBps)
        val bps = videoBitrateBps(p)
        return if (bps > 0) {
            "缓冲中 · 下载 $speedText · 这条流 ${"%.1f".format(bps / 1_000_000.0)} Mbps"
        } else {
            "缓冲中 · 下载 $speedText"
        }
    }

    /** 下载速率文本：≥1MB/s 显示 MB，否则 KB（按 1024 折算，和文件管理器里的口径一致） */
    private fun fmtSpeed(bytesPerSec: Long): String =
        if (bytesPerSec >= 1024L * 1024L) {
            "%.1f MB/s".format(bytesPerSec / 1048576.0)
        } else {
            "%.0f KB/s".format(bytesPerSec / 1024.0)
        }

    /**
     * 每攒满 1 秒刷新一次实测网速，让缓冲提示里的数字一秒一跳。
     *
     * 挂在 500ms 的进度 ticker 上而不是单开一个定时器：ticker 已经在跑，多一个定时器
     * 就多一条要跟着播放器生命周期收的路。窗口长度按实际经过时间折算，不受 ticker 抖动影响。
     *
     * [DownloadMeter] 是进程级单调累计的，这里只取差分；基线在第一次采样时落，所以新播放器
     * 不会把进播放器之前预读留下的字节一次性算成网速。
     */
    private fun sampleDownloadSpeed() {
        val now = SystemClock.elapsedRealtime()
        val total = DownloadMeter.totalBytes
        if (speedWindowAt == 0L) {
            speedWindowAt = now
            speedWindowBytes = total
            return
        }
        val elapsed = now - speedWindowAt
        if (elapsed < 1_000L) return
        downloadBps = ((total - speedWindowBytes) * 1000L / elapsed).coerceAtLeast(0L)
        speedWindowAt = now
        speedWindowBytes = total
    }

    /**
     * 创建电视端播放器，同时把网速窗口清空。
     *
     * 两处创建点（首次进入 / 换云盘源重建）都要走这里：[DownloadMeter] 是进程级累计的，
     * 不重建基线的话第一秒会把上一段没读完的字节一起算进来，跳出一个离谱数字。
     */
    private fun newTvPlayer(headers: Map<String, String>): ExoPlayer =
        PlayerFactory.createTvPlayer(this, headers).also { p ->
            downloadBps = -1L
            speedWindowAt = 0L
            speedWindowBytes = 0L
            // 播放期读真实轨道（分辨率/HDR/杜比视界/音轨），回写到剧集壳。
            // 详情页那条文件名推测值可能不准，以这里读到的码流信息为准。
            p.addListener(object : Player.Listener {
                // 首帧真正上屏才算「加载好了」：READY 只代表数据够播，电视上从 READY 到
                // 出画还有几百毫秒，提前撤掉转圈会看到一段黑屏闪烁。
                override fun onRenderedFirstFrame() {
                    // 旧实例补投的首帧不该撤掉新集的黑幕
                    if (player === p) loading = false
                }

                override fun onTracksChanged(tracks: Tracks) {
                    // 轨道是解析首帧之后才齐的（进来时播放器还 IDLE / BUFFERING），
                    // 不在这里刷一次，字幕/音轨二级菜单就只剩「关闭字幕」一项。
                    refreshTracks()
                    val info = FormatProbe.fromTracks(tracks) ?: return
                    val item = current ?: return
                    lifecycleScope0 { repo.savePlaybackFormat(item, info) }
                }

                /**
                 * 出画后打一条画面几何证据：杜比视界片源「上下多出遮罩」时，
                 * 靠这行区分是片源自带黑边（宽银幕）、像素比异常，还是 PlayerView 的适配问题。
                 */
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val v = playerView
                    val surface = v?.videoSurfaceView
                    val dm = resources.displayMetrics
                    val format = (player?.currentTracks?.groups?.firstOrNull {
                        it.type == C.TRACK_TYPE_VIDEO
                    }?.mediaTrackGroup?.let { g -> if (g.length > 0) g.getFormat(0) else null })
                    Log.i(
                        "TvPlayer",
                        "videoSize=${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio} " +
                            "frame=${surface?.width}x${surface?.height} view=${v?.width}x${v?.height} " +
                            "screen=${dm.widthPixels}x${dm.heightPixels} density=${dm.density} " +
                            "videoFormat=${format?.width}x${format?.height} " +
                            "color=${format?.colorInfo?.colorSpace}/${format?.colorInfo?.colorTransfer} " +
                            "codecs=${format?.codecs}"
                    )
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // 换源重建播放器时，旧实例的回调可能还排在主线程队列里。
                    // 不认实例，旧播放器的 READY / ENDED 会盖到正在加载的新集上。
                    if (player !== p) return
                    // 兜底：纯音频流不触发首帧回调，不能让它一直转圈
                    if (state == Player.STATE_READY && loading) {
                        // 1.2 秒内用户可能已经切走了，那时黑幕是给新一集的，不能由这一集的兜底撤掉
                        val readyEpisodeId = current?.id ?: 0L
                        handler.postDelayed({
                            if (loading && current?.id == readyEpisodeId) loading = false
                        }, 1_200L)
                    }
                    if (state == Player.STATE_ENDED && endedEpisodeId != (current?.id ?: 0L)) {
                        endedEpisodeId = current?.id ?: 0L
                        autoNextEpisode()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // 出错就别再转圈了，否则错误提示会压在转圈上，看着像还在加载
                    loading = false
                    val causeMsg = error.cause?.message
                    // W 级 + 带上出错地址：这台电视 ROM 吞 D 级日志，播放失败时没有这行就只能靠猜
                    Log.w(
                        "TvPlayer",
                        "播放失败 ${error.errorCodeName} " +
                            "uri=${p.currentMediaItem?.localConfiguration?.uri.toString().take(160)} " +
                            "cause=${error.cause?.javaClass?.name} msg=$causeMsg"
                    )
                    if (isTailSourceError(p)) {
                        // 片尾的坏数据（网盘重传截断/补零）不当故障报：按看完交给连播
                        Log.w("TvPlayer", "片尾容错命中 pos=${p.currentPosition} dur=${p.duration}")
                        if (endedEpisodeId != (current?.id ?: 0L)) {
                            endedEpisodeId = current?.id ?: 0L
                            saveProgress(force = true)
                            autoNextEpisode()
                        }
                        return
                    }
                    toast = when {
                        causeMsg?.startsWith("HTTP 404") == true -> causeMsg
                        causeMsg?.startsWith("HTTP 403") == true ||
                        causeMsg?.startsWith("HTTP 412") == true -> causeMsg
                        error.errorCodeName.contains("BAD_HTTP") -> "视频源不可用，请检查来源或切换其他线路"
                        // 容器读到损坏数据（片尾补零、上传截断），不是网络问题
                        causeMsg?.contains("varint") == true || causeMsg?.contains("EOF found") == true ->
                            "视频文件不完整（读到损坏数据），可换一条线路试试"
                        error.errorCodeName == "ERROR_CODE_IO_UNSPECIFIED" -> "网络连接异常，请检查网络设置"
                        error.errorCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" -> "视频格式不支持"
                        else -> "播放失败：${error.errorCodeName}"
                    }
                }
            })
        }

    /**
     * 片尾容错：网盘重传/截断的片子，最后零点几秒常是补零的坏数据，
     * MatroskaExtractor 读到就抛 varint 异常 → Source error。
     * 这个位置本来就是「看完了」，所以按 ENDED 处理：落盘 + 连播，不弹网络错误。
     * 只认片尾 5 秒内，别的位置读到坏数据仍然照实报错。
     */
    private fun isTailSourceError(p: Player): Boolean {
        val dur = p.duration
        if (dur <= 0L) return false
        return maxOf(p.currentPosition, p.bufferedPosition) >= dur - TAIL_ERROR_TOLERANCE_MS
    }

    /** 当前视频轨的声明码率（bit/s）；容器没写或还拿不到轨信息时返回 0 */
    private fun videoBitrateBps(p: Player): Int {
        val group = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return 0
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
        val bitrate = runCatching { group.getTrackFormat(index).bitrate }.getOrNull()
        return if (bitrate != null && bitrate > 0) bitrate else 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        repo = MediaRepository(applicationContext)
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, 0L)

        // 字幕样式在 PlayerView 建出来时（AndroidView factory 里的 applySubtitleStyle）生效，
        // 所以要在 setContent 之前把上次的选择读回来
        val sp = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs = sp
        subtitleBgAlpha = sp.getFloat(KEY_SUBTITLE_BG_ALPHA, DEFAULT_SUBTITLE_BG_ALPHA)
        subtitleTextSize = sp.getFloat(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE)

        setContent {
            DouDouTvTheme {
                TvPlayerScreen()
            }
        }

        loadInitial()
    }

    // ---------- 装载 ----------

    private fun loadInitial() {
        lifecycleScope0 {
            val item = runCatching { repo.getMedia(mediaId) }.getOrNull()
            if (item == null) {
                loading = false
                toast = "找不到该媒体"
                return@lifecycleScope0
            }
            // 查 source token 构造 Emby header —— 第二个 Emby 源严格鉴权需要 X-Emby-Authorization
            val headers = buildMap {
                val source = runCatching { repo.getSource(item.sourceId) }.getOrNull()
                if (source?.token?.isNotBlank() == true) {
                    put("X-Emby-Authorization", "MediaBrowser Token=\"${source.token}\"")
                }
            }
            // 先释放旧 player（如果有）再创建新的 —— SimpleCache 单例只能有一个引用
            PlayerFactory.release(player)
            baseHeaders = headers
            currentPlayHeaders = headers
            player = newTvPlayer(headers)

            current = item
            titleText = displayTitle(item)
            play(item, item.positionMs)
            // 轨道不在这里刷：刚 setMediaItem 的播放器还没有 currentTracks，
            // 刷了也是空列表；真正的刷新由 onTracksChanged 回调负责
            val list = runCatching { repo.episodes(item.seriesKey) }.getOrDefault(emptyList())
            // 上下集只在同一个网盘 / 同一条线路里走：episodes(seriesKey) 是全量，
            // 多线路的剧直接按它取下一集会跳到别的网盘（别的网盘还常常解析失败）。
            // playSource 为空的源（Emby、单线路 TVBox）没有线路概念，仍按全量。
            val source = item.playSource
            episodes = list.filter {
                it.seriesKey == item.seriesKey && (source.isBlank() || it.playSource == source)
            }
                // 与手机端同一口径：没有地址的分集不进上下集列表，否则「下一集」会跳到空条目
                .filter { it.uri.isNotBlank() }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            refreshTracks()
        }
    }

    private fun play(item: MediaEntity, startMs: Long) {
        // 与手机端同一守卫：空地址喂给播放器只会得到一个看不懂的 IO 错误
        if (item.uri.isBlank()) {
            loading = false
            toast = "该条目没有可播放地址（可能需要在详情页重新加载）"
            return
        }
        titleText = displayTitle(item)
        // 装载遮罩与转圈立刻出现，不用等播放器回调
        loading = true
        // 进度层立刻归零：否则黑幕下面还挂着上一集的「40:12 / 45:00」
        positionMs = 0L
        durationMs = 0L
        isPlaying = false
        bufferHint = null
        // 这一集还没播完过，闸门打开（否则「从头开始」再播到末尾不会连播下一集）
        endedEpisodeId = 0L
        prefetched = false

        // 云盘 scheme 地址需要先解析出真实视频 URL + 请求头再喂给播放器
        val gen = ++playGeneration
        workScope.launch {
            val resolved = resolvePlayUrl(item, gen)
                ?: run {
                    // 被更新的一次切集取代时别撤黑幕，那层黑幕现在是给新一集用的
                    if (gen == playGeneration) loading = false
                    return@launch
                }
            // 解析在飞的这几秒里用户可能又按了一次下一集，旧地址不能喂给新集
            if (gen != playGeneration) return@launch

            // 合并基础头（Emby 鉴权）与云盘解析出的头（Cookie 等）。
            // 对 Emby 的 http 直链：resolved.headers 为空 → mergedHeaders = baseHeaders（不变，不重建）。
            // 对云盘切源：resolved.headers 变化 → mergedHeaders 变化 → 重建播放器带新 Cookie。
            val mergedHeaders = baseHeaders + resolved.headers
            val needRebuild = mergedHeaders != currentPlayHeaders
            val p = if (needRebuild) {
                PlayerFactory.release(player)
                player = null
                currentPlayHeaders = mergedHeaders
                val np = newTvPlayer(mergedHeaders)
                player = np
                playerView?.player = np
                np
            } else {
                player ?: run {
                    loading = false
                    return@launch
                }
            }

            p.setMediaItem(MediaItem.fromUri(resolved.url))
            p.prepare()
            if (startMs > 3_000L) p.seekTo(startMs)
            p.setPlaybackSpeed(speed)
            p.playWhenReady = true
        }
    }

    /**
     * 解析播放地址：云盘 scheme 与 http(s) 直链统一交给 [MediaRepository.resolveTvBoxPlayUrl]
     * （直链也会先问 spider，失败/超时自动回退原地址）；非 TVBox 来源原样返回。
     * @param gen 本次装载的代数，与被取代的旧装载对不上就不写 [toast]，免得新一集还在转圈却弹出上一集的错
     * @return 解析结果（URL + headers）；解析失败返回 null
     */
    private suspend fun resolvePlayUrl(item: MediaEntity, gen: Int): TvBoxClient.ResolvedPlayUrl? {
        return runCatching {
            repo.resolveTvBoxPlayUrl(item)
        }.fold(
            onSuccess = { it },
            onFailure = {
                // 解析失败只弹 toast 时，logcat 里看不到任何线索；
                // 电视 ROM 吞 D 级日志，取证只能靠 W。
                Log.w("TvPlayer", "云盘解析失败 mediaId=${item.id} playSource=${item.playSource}：${it.message}")
                if (gen == playGeneration) toast = it.message ?: "云盘解析失败"
                null
            }
        )
    }

    /**
     * 切集：先把上一集落盘并让播放器停下来，再去解析新一集。
     *
     * 顺序很关键：
     *   · `saveProgress` 必须在 `current = ep` 之前 —— 它按 `current` 写库，
     *     晚一步就会把上一集看到的时间写进新一集头上。
     *   · `pause` 必须在解析之前 —— 解析要花好几秒，不停机就还是上一集在播，
     *     屏幕上却是新一集的标题，看着像卡死；而且 ticker 会拿旧播放器的进度写新集。
     */
    private fun switchEpisode(ep: MediaEntity) {
        saveProgress(force = true)
        player?.pause()
        current = ep
        play(ep, ep.positionMs)
        // 进度层叫回来：黑幕上没有别的文字，这一条就是「切到第几集了」的凭据
        overlayVisible = true
        panelTab = null
        // 面板下次打开时要停在新一集上，不是停在老的那一段
        centerEpisodeWindow()
        tick++
    }

    // ---------- 进度保存 ----------

    /**
     * 用独立 scope 保存进度。
     * Activity 销毁时 lifecycleScope 会被取消，若仍挂在上面，退出前的最后一笔进度会丢。
     */
    private fun saveProgress(force: Boolean) {
        val item = current ?: return
        val p = player ?: return
        // 装载期间播放器还挂着上一集（或刚创建、位置为 0），这时落盘会把
        // 错误的时间写到 current（已经是新一集）头上，直接跳过。
        if (loading) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSavedAt.get() < 5_000L) return
        lastSavedAt.set(now)

        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.takeIf { it > 0L } ?: 0L

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                repo.saveProgress(item.id, pos)
                if (dur > 0L) repo.fillDuration(item.id, dur)
            }
        }
    }

    // ---------- 轨道 ----------

    private fun refreshTracks() {
        val p = player ?: return
        val audios = mutableListOf<TvTrack>()
        val texts = mutableListOf<TvTrack>()

        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> buildString {
                        append(format.label ?: format.language ?: "声道 ${i + 1}")
                        format.channelCount.takeIf { it > 0 }?.let { append(" · ${it}ch") }
                    }
                    else -> format.label ?: format.language ?: "字幕 ${i + 1}"
                }
                val track = TvTrack(label, group.type, group, i, group.isTrackSelected(i))
                if (group.type == C.TRACK_TYPE_AUDIO) audios += track else texts += track
            }
        }
        // 「关闭字幕」放在字幕列表第一位，后面依次跟所有字幕轨，
        // 用户可直接在同一行切换：关闭 → 字幕1 → 字幕2 …
        val textTracks = listOf(TvTrack("关闭字幕", C.TRACK_TYPE_TEXT, null, -1, textDisabled)) + texts
        trackOptions = audios + textTracks
        // 一条 W 级证据：分清「片源真没内嵌字幕」还是「有轨没列出来」。
        // 电视 ROM 吞 D 级日志，没这行就只能靠猜。
        Log.w("TvPlayer", "轨道刷新 音轨=${audios.size} 字幕轨=${texts.size} 关闭字幕=${textDisabled}")
    }

    /** 应用字幕样式（背景透明度 + 字号），与手机端一致 */
    private fun applySubtitleStyle() {
        val sv = playerView?.subtitleView ?: return
        val bg = android.graphics.Color.argb(
            (subtitleBgAlpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0
        )
        sv.setStyle(
            // 形参顺序是 (前景, 字底, 窗口底, 描边类型, 描边色, 字体) —— 不是「描边类型排第三」。
            // 排错时窗口底会收到 EDGE_TYPE_NONE(=0) 永远透明，描边类型反而收到背景色。
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                bg,
                bg,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                android.graphics.Color.TRANSPARENT,
                null
            )
        )
        sv.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    /** 当前播放分集在列表里的下标；-1 = 电影、或列表还没加载出来 */
    private fun currentEpisodeIndex(): Int = episodes.indexOfFirst { it.id == current?.id }

    /** 上一集：当前集在列表中的前一个 */
    private fun prevEpisode() {
        val idx = currentEpisodeIndex()
        if (idx > 0) switchEpisode(episodes[idx - 1])
        else toast = "已经是第一集"
    }

    /** 下一集：当前集在列表中的后一个 */
    private fun nextEpisode() {
        val idx = currentEpisodeIndex()
        if (idx >= 0 && idx < episodes.size - 1) switchEpisode(episodes[idx + 1])
        else toast = "已经是最后一集"
    }

    /**
     * 播完自动接下一集（对齐 TVBox 的 playbackEnded → nextEpisode）：
     * 只在当前这条网盘线路内往下走，末集不循环、只提示。
     */
    private fun autoNextEpisode() {
        val idx = currentEpisodeIndex()
        if (idx < 0 || idx >= episodes.size - 1) {
            if (idx >= 0) toast = "本剧已播完"
            return
        }
        // 落盘由 switchEpisode 统一负责（它必须在切走之前把这一集写完）
        switchEpisode(episodes[idx + 1])
    }

    /**
     * 临近结束时预热同一条线路的下一集：提前把「刷分享令牌 + 问 spider 要直链」这两跳付掉，
     * 实测约 8 秒，正是切集最慢的一段；真到切集时命中进程级缓存，直接起播。
     *
     * 为什么不在开播 15 秒就预热（TVBox 是这么做的）：直链带签名、有时效，
     * 一集 45 分钟，早解析那条到下一集早就过期了，白敲一次源站还占着缓存。
     */
    private fun prefetchNextEpisode() {
        if (prefetched) return
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0L || dur - p.currentPosition > PREFETCH_REMAINING_MS) return
        prefetched = true
        val idx = currentEpisodeIndex()
        if (idx < 0 || idx >= episodes.size - 1) return
        val next = episodes[idx + 1]
        workScope.launch(Dispatchers.IO) {
            runCatching { repo.resolveTvBoxPlayUrl(next) }
                .onFailure { Log.w("TvPlayer", "下一集预热失败 mediaId=${next.id}：${it.message}") }
        }
    }

    /** 把选集窗口挪到当前集居中；整表不足一窗时贴左 */
    private fun centerEpisodeWindow() {
        val idx = currentEpisodeIndex()
        val lastStart = (episodes.size - EPISODE_WINDOW_SIZE).coerceAtLeast(0)
        epWindowStart = if (idx < 0) 0
        else (idx - EPISODE_WINDOW_SIZE / 2).coerceIn(0, lastStart)
    }

    /**
     * 选集面板翻页，一步一整窗。
     * 两端到底时胶囊留在原地只提示，不把它从组合树里摘掉 —— 焦点正挂在它上面，
     * 节点一消失焦点就不知道去哪了。
     */
    private fun stepEpisodeWindow(delta: Int) {
        val lastStart = (episodes.size - EPISODE_WINDOW_SIZE).coerceAtLeast(0)
        val next = (epWindowStart + delta * EPISODE_WINDOW_SIZE).coerceIn(0, lastStart)
        if (next == epWindowStart) {
            toast = if (delta < 0) "前面没有更多集了" else "后面没有更多集了"
        }
        epWindowStart = next
    }

    private fun selectTrack(track: TvTrack) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (track.type == C.TRACK_TYPE_TEXT && track.trackIndex < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            textDisabled = true
        } else {
            // 关键：从 player.currentTracks 里找当前最新的 group（快照 group 可能已失效）
            val targetMediaGroup = track.group?.mediaTrackGroup ?: return
            val freshGroup = p.currentTracks.groups.firstOrNull { it.mediaTrackGroup === targetMediaGroup }
                ?: return
            builder.setTrackTypeDisabled(track.type, false)
            builder.setOverrideForType(TrackSelectionOverride(freshGroup.mediaTrackGroup, track.trackIndex))
            if (track.type == C.TRACK_TYPE_TEXT) textDisabled = false
        }
        p.trackSelectionParameters = builder.build()
        refreshTracks()
        tick++
    }

    private fun cycleSpeed(value: Float) {
        speed = value
        player?.setPlaybackSpeed(value)
        toast = "倍速 ${fmtSpeed(value)}"
        tick++
    }

    // ---------- 按键 ----------

    private fun seekBy(delta: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0L } ?: return
        val target = (p.currentPosition + delta).coerceIn(0L, dur)
        p.seekTo(target)
        seekHint = "${if (delta > 0) "快进" else "快退"} ${fmtTime(target)} / ${fmtTime(dur)}"
        tick++
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        isPlaying = p.isPlaying
        overlayVisible = true
        tick++
    }

    /**
     * 遥控器按键的唯一入口。
     *
     * 这里用 [dispatchKeyEvent] 而不是 onKeyDown：onKeyDown 只有在整条 View 链都不要这个键时
     * 才会被调用，进度条/工具栏一出现，Compose 的焦点搜索就可能吃掉下键，表现就是
     * 「第一次能呼出、之后要按两次」。dispatchKeyEvent 在最外层，先由我们决定归属。
     *
     * 工具栏打开时方向键 / 确定键交给 Compose 焦点系统（一级按钮行 + 二级选项都在里面），
     * 我们只处理返回键的分层收起。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (toolbarOpen) {
            val closed = when (event.keyCode) {
                // 返回键：二级菜单 → 工具栏 → 时间轴，一层一层收，不直接退出播放
                KeyEvent.KEYCODE_BACK ->
                    if (panelTab != null) {
                        panelTab = null
                        tick++
                        true
                    } else {
                        closeToolbar()
                    }
                KeyEvent.KEYCODE_MENU -> closeToolbar()
                else -> false
            }
            if (closed) return true
            // 方向键 / 确定交给 Compose 焦点系统在按钮和选项间移动；
            // 顺手重置自动收起计时，否则用户正横向挑选项，工具栏中途消失。
            tick++
            return super.dispatchKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK ->
                if (overlayVisible) {
                    // 时间轴还挂着：先把它收掉，别把用户退出播放
                    overlayVisible = false
                    overlayDismissedByBack = true
                    tick++
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 只呼出工具栏，二级菜单等用户在按钮上按确定再展开
                toolbarOpen = true
                panelTab = null
                overlayVisible = false
                tick++
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000L); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000L); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> { togglePlayPause(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000L); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000L); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** 收起工具栏（返回键 / MENU 键），返回 true 表示事件已消费 */
    private fun closeToolbar(): Boolean {
        toolbarOpen = false
        panelTab = null
        tick++
        return true
    }

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        // 先停掉在飞的装载/预热，再 release 播放器：反过来的话，
        // 解析回来那一步还会摸到已释放的实例和已 detach 的 PlayerView
        workScope.cancel()
        saveProgress(force = true)
        PlayerFactory.release(player)
        player = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        // 边到边渲染：让内容延伸到状态栏 / 导航栏 / 挖孔区，
        // 否则横屏时系统会在摄像头那一侧留下一条黑带（cutout letterbox）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 避免与 lifecycle-runtime-ktx 的 lifecycleScope 扩展名混淆，这里包一层
    private fun lifecycleScope0(block: suspend () -> Unit) {
        workScope.launch { block() }
    }

    // ---------- Compose 界面 ----------

    @OptIn(UnstableApi::class)
    @Composable
    private fun TvPlayerScreen() {
        val ctx = LocalContext.current
        val rootFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            rootFocus.requestFocus()
            handler.post(progressTicker)
        }

        // 进度层 3 秒自动隐藏（工具栏另有一条 10 秒计时，见下）
        LaunchedEffect(tick, toolbarOpen, panelTab, isPlaying) {
            if (!toolbarOpen && overlayVisible && isPlaying && seekHint == null) {
                delay(3_000L)
                overlayVisible = false
            }
        }

        // 工具栏 10 秒无操作自动收起（含二级菜单一起收）。
        // 比进度条长：这里要横向逛一圈按钮、再按确定进二级、再选一项。
        LaunchedEffect(tick, toolbarOpen) {
            if (toolbarOpen) {
                delay(TOOLBAR_AUTO_HIDE_MS)
                toolbarOpen = false
                panelTab = null
            }
        }

        // token 提示自动消失
        LaunchedEffect(toast) {
            if (toast != null) {
                delay(1_600L)
                toast = null
            }
        }
        LaunchedEffect(seekHint) {
            if (seekHint != null) {
                delay(1_200L)
                seekHint = null
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        // 不让 PlayerView 抢占焦点，避免首次下键被原生 View 吃掉
                        isFocusable = false
                        isFocusableInTouchMode = false
                        // 电视端固定 16:9 适应，不提供比例切换
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        player = this@TvPlayerActivity.player
                        this@TvPlayerActivity.playerView = this
                        this@TvPlayerActivity.applySubtitleStyle()

                        // 黑幕先加、转圈后加：FrameLayout 按加入顺序叠放，转圈才在黑幕之上。
                        // 转圈用平台 ProgressBar 且不加任何自定义样式，和 PlayerView 自带那个同款；
                        // 解析期和缓冲期都由它负责（自带那个被黑幕盖住，不会两个圈重影）。
                        val shutter = View(c).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                        addView(shutter)
                        this@TvPlayerActivity.shutterView = shutter

                        val bar = ProgressBar(c).apply {
                            isIndeterminate = true
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.CENTER
                            )
                        }
                        addView(bar)
                        this@TvPlayerActivity.loadingView = bar
                    }
                },
                update = { view ->
                    view.player = this@TvPlayerActivity.player
                    // 从按下到新一集首帧上屏，全程黑幕 + 转圈：解析期播放器是 IDLE、
                    // 缓冲期是 BUFFERING，两段连起来才不露出上一集的画面。
                    val show = if (loading) View.VISIBLE else View.GONE
                    loadingView?.visibility = show
                    shutterView?.visibility = show
                }
            )

            // ---------- 装载中（点击播放 → 首帧出画） ----------
            // 转圈控件挂在 PlayerView 里（与它自带的缓冲转圈是同一个 ProgressBar），
            // 这里只负责按 loading 状态切换可见性，见 AndroidView 的 update。

            // ---------- 进度层 ----------
            AnimatedVisibility(
                visible = overlayVisible && !toolbarOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvProgressBar(
                    title = titleText,
                    position = positionMs,
                    duration = durationMs,
                    playing = isPlaying,
                    buffering = bufferHint
                )
            }

            // ---------- 功能工具栏（下键唤出） ----------
            AnimatedVisibility(
                visible = toolbarOpen,
                enter = slideInVertically { it / 3 } + fadeIn(),
                exit = slideOutVertically { it / 3 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvFunctionPanel(
                    tab = panelTab,
                    speed = speed,
                    tracks = trackOptions,
                    episodes = episodes,
                    currentId = current?.id ?: 0L,
                    epWindowStart = epWindowStart,
                    subtitleBgAlpha = subtitleBgAlpha,
                    subtitleTextSize = subtitleTextSize,
                    hasEpisodes = episodes.isNotEmpty(),
                    // 一级按钮：按确定展开/收起它的二级选项（同一个按钮再按一次收起）
                    onTabChange = {
                        // 展开选集时先把窗口对到当前集，否则看到的还是上一次那一段
                        if (it == TvPanelTab.EPISODE) centerEpisodeWindow()
                        panelTab = if (panelTab == it) null else it
                        tick++
                    },
                    onSpeed = { cycleSpeed(it); panelTab = null },
                    onTrack = { selectTrack(it); panelTab = null },
                    onEpisode = { switchEpisode(it) },
                    onPrevEpisode = { prevEpisode() },
                    onNextEpisode = { nextEpisode() },
                    onEpisodeWindow = { stepEpisodeWindow(it) },
                    onSubtitleStyle = { bg, size ->
                        subtitleBgAlpha = bg
                        subtitleTextSize = size
                        applySubtitleStyle()
                        prefs?.edit()
                            ?.putFloat(KEY_SUBTITLE_BG_ALPHA, bg)
                            ?.putFloat(KEY_SUBTITLE_TEXT_SIZE, size)
                            ?.apply()
                        tick++
                    }
                )
            }

            // 快进/快退时间提示
            seekHint?.let {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 46.dp)
                        .background(Color.Black.copy(alpha = 0.74f), Radius.Md)
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }

            toast?.let {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.74f), Radius.Md)
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_SUBTITLE_BG_ALPHA = "subtitle_bg_alpha"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val DEFAULT_SUBTITLE_BG_ALPHA = 0.0f
        private const val DEFAULT_SUBTITLE_TEXT_SIZE = 22f

        /** 工具栏无操作自动收起：10 秒（要横向逛按钮 + 按确定进二级，比进度条的 3 秒宽松） */
        private const val TOOLBAR_AUTO_HIDE_MS = 10_000L

        fun intent(context: android.content.Context, mediaId: Long) =
            android.content.Intent(context, TvPlayerActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, mediaId)

        internal val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

// ---------- 面板数据 ----------

internal enum class TvPanelTab(val label: String) {
    EPISODE("选集"),
    SPEED("倍速"),
    SUBTITLE("字幕"),
    SUBTITLE_STYLE("字幕样式"),
    AUDIO("音轨")
}

internal data class TvTrack(
    val label: String,
    val type: Int,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val selected: Boolean
)

// ---------- 进度层 ----------

@Composable
private fun TvProgressBar(
    title: String,
    position: Long,
    duration: Long,
    playing: Boolean,
    buffering: String?
) {
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.82f)
                )
            )
            .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${fmtTime(position)} / ${fmtTime(duration)}",
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // 进度轨道：已播部分高亮
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(Radius.Pill)
                .background(Color.White.copy(alpha = 0.26f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(Radius.Pill)
                    .background(AppColors.Brand)
            )
        }

        Text(
            // 缓冲提示优先占这一行：它旁边的进度条停着不动，配上「下载 X MB/s」才知道是在等数据、
            // 还是网速追不上码率。字体用主题品牌色，和进度条已播部分同一支色。
            text = buffering ?: if (playing) "播放中 · OK 暂停 · ↓ 功能" else "已暂停 · OK 继续 · ↓ 功能",
            color = if (buffering != null) AppColors.Brand else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (buffering != null) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---------- 功能面板 ----------

@Composable
private fun TvFunctionPanel(
    tab: TvPanelTab?,
    speed: Float,
    tracks: List<TvTrack>,
    episodes: List<MediaEntity>,
    currentId: Long,
    epWindowStart: Int,
    subtitleBgAlpha: Float,
    subtitleTextSize: Float,
    hasEpisodes: Boolean,
    onTabChange: (TvPanelTab) -> Unit,
    onSpeed: (Float) -> Unit,
    onTrack: (TvTrack) -> Unit,
    onEpisode: (MediaEntity) -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onEpisodeWindow: (Int) -> Unit,
    onSubtitleStyle: (bgAlpha: Float, textSize: Float) -> Unit
) {
    // tab 为 null = 工具栏只显示一级按钮；焦点要落在按钮行的第一个上，
    // 展开某个功能后焦点留在该按钮，按 ↓ 才进二级选项。
    val tabFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val firstTab = TvPanelTab.entries.first()
    LaunchedEffect(tab) {
        runCatching { (if (tab == null) rowFocus else tabFocus).requestFocus() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(alpha = 0.88f),
                    1f to Color.Black.copy(alpha = 0.96f)
                )
            )
            .padding(start = 56.dp, end = 56.dp, top = 46.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 第一行：上一集 / 下一集 + 一级标签（倍速、字幕…），统一在同一行
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasEpisodes) {
                TvChip(
                    text = "上一集",
                    selected = false,
                    modifier = if (tab == null) Modifier.focusRequester(rowFocus) else Modifier,
                    onClick = onPrevEpisode
                )
                TvChip(text = "下一集", selected = false, onClick = onNextEpisode)
            }
            TvPanelTab.entries.forEach { t ->
                val selected = t == tab
                val focusModifier = when {
                    selected -> Modifier.focusRequester(tabFocus)
                    tab == null && !hasEpisodes && t == firstTab -> Modifier.focusRequester(rowFocus)
                    else -> Modifier
                }
                TvChip(
                    text = t.label,
                    selected = selected,
                    modifier = focusModifier,
                    onClick = { onTabChange(t) }
                )
            }
        }

        // 二级选项：只有确定展开了才画出来（未展开时给一行提示）
        // 二级选项（Column，字幕样式需要两行）：tab 为 null 时只留一行提示，
        // 等用户在一级按钮上按确定才展开对应内容。
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                null -> {
                    // 未展开二级时这里什么都不画：不占位、不写提示文字
                }

                TvPanelTab.SPEED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvPlayerActivity.SPEEDS.forEach { v ->
                        TvChip(
                            text = fmtSpeed(v),
                            selected = kotlin.math.abs(v - speed) < 0.01f,
                            onClick = { onSpeed(v) }
                        )
                    }
                }

                TvPanelTab.SUBTITLE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val texts = tracks.filter { it.type == C.TRACK_TYPE_TEXT }
                    if (texts.isEmpty()) {
                        TvHint("该视频没有内嵌字幕")
                    } else {
                        texts.forEach { tr ->
                            TvChip(
                                text = tr.label,
                                selected = tr.selected,
                                onClick = { onTrack(tr) }
                            )
                        }
                    }
                }

                TvPanelTab.SUBTITLE_STYLE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("字号", color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelMedium)
                        listOf(18f, 20f, 22f, 26f, 30f, 36f).forEach { s ->
                            TvChip(
                                text = "${s.toInt()}号",
                                selected = kotlin.math.abs(s - subtitleTextSize) < 0.01f,
                                onClick = { onSubtitleStyle(subtitleBgAlpha, s) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("背景", color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelMedium)
                        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { a ->
                            TvChip(
                                text = "${(a * 100).toInt()}%",
                                selected = kotlin.math.abs(a - subtitleBgAlpha) < 0.01f,
                                onClick = { onSubtitleStyle(a, subtitleTextSize) }
                            )
                        }
                    }
                }

                TvPanelTab.AUDIO -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val audios = tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audios.isEmpty()) {
                        TvHint("该视频只有一条音轨")
                    } else {
                        audios.forEach { tr ->
                            TvChip(
                                text = tr.label,
                                selected = tr.selected,
                                onClick = { onTrack(tr) }
                            )
                        }
                    }
                }

                TvPanelTab.EPISODE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (episodes.isEmpty()) {
                        TvHint("当前不是剧集，没有可选分集")
                    } else {
                        val lastStart = (episodes.size - EPISODE_WINDOW_SIZE).coerceAtLeast(0)
                        val start = epWindowStart.coerceIn(0, lastStart)
                        // 翻页胶囊放在两端，一屏永远只有 EPISODE_WINDOW_SIZE 颗集名胶囊：
                        // 这一行不滚动，多铺的胶囊会直接掉到屏幕外，遥控器按不到也看不见。
                        TvChip(text = "更早", selected = false, onClick = { onEpisodeWindow(-1) })
                        episodes.drop(start).take(EPISODE_WINDOW_SIZE).forEach { ep ->
                            TvChip(
                                text = ep.episodeLabel(),
                                selected = ep.id == currentId,
                                // 等宽分摊：胶囊文字是网盘原文件名，长短差几十倍，
                                // 用 weight 才不会出现一颗把同行其余挤出屏幕的情况
                                modifier = Modifier.weight(1f),
                                onClick = { onEpisode(ep) }
                            )
                        }
                        TvChip(text = "更新", selected = false, onClick = { onEpisodeWindow(1) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHint(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.62f),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun TvChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier
            .clip(Radius.Md)
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, Radius.Md)
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.key.nativeKeyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> Color.White.copy(alpha = 0.88f)
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------- 工具 ----------

/**
 * 选集面板一屏的集数（奇数，当前集正好落中间）。
 * 电视一屏要同时装下这颗行 + 两端翻页胶囊，再多的胶囊就得到屏幕外了。
 */
private const val EPISODE_WINDOW_SIZE = 7

/** 距结尾多少毫秒开始预热下一集：解析本身要 8 秒左右，留足余量又不至于提前太久失效 */
private const val PREFETCH_REMAINING_MS = 90_000L

/** 片尾容错窗口：离结尾不到 5 秒的 Source error 按「看完」处理 */
private const val TAIL_ERROR_TOLERANCE_MS = 5_000L

private fun displayTitle(item: MediaEntity): String = when {
    item.kind == com.chen.doudoutv.data.model.MediaKind.EPISODE && item.usesSourceEpisodeName ->
        item.title
    item.kind == com.chen.doudoutv.data.model.MediaKind.EPISODE && item.episode > 0 ->
        "${item.title}  S${item.season}E${item.episode}"
    else -> item.title
}

private fun fmtSpeed(v: Float): String {
    val body = if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v"
    return "${body}x"
}

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
