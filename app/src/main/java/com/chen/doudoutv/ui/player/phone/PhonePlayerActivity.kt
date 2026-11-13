package com.chen.doudoutv.ui.player.phone

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.chen.doudoutv.R
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.MediaRepository
import com.chen.doudoutv.data.format.FormatProbe
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind
import com.chen.doudoutv.data.tvbox.TvBoxClient
import com.chen.doudoutv.player.PlayerFactory
import com.chen.doudoutv.ui.components.AppButton
import com.chen.doudoutv.ui.components.AppButtonVariant
import com.chen.doudoutv.player.DownloadMeter
import com.chen.doudoutv.ui.components.usesSourceEpisodeName
import com.chen.doudoutv.ui.detail.fmtIntroMs
import com.chen.doudoutv.ui.player.SkipJumpController
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.DouDouTvTheme
import com.chen.doudoutv.ui.theme.Radius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放页（触摸 + 遥控器双形态）。
 *
 * 控制条不再由 Compose 浮层绘制，而是通过 [PlayerView] 的 controller_layout_id
 * 直接挂在播放器本体里（[R.layout.doudou_player_controller]）：
 * 播放/暂停、快退快进、seek bar、倍速、字幕、画面比例、上下集、选集
 * 全部是同一个控制条内的按钮，随控制条一起显隐，手机触摸与遥控器都能用。
 *
 * 手势：左右滑动快进/快退（带目标时间预览）；遥控器左右键在控制条隐藏时快进/快退。
 *
 * 支持：倍速、音轨/字幕轨道选择、字幕背景透明度与字号、画面比例、
 * 横竖屏切换、同剧集自动连播、选集、继续观看进度保存。
 */
@UnstableApi
class PhonePlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    /** 基础请求头（如 Emby 的 X-Emby-Authorization），同一次播放会话内不变 */
    private var baseHeaders: Map<String, String> = emptyMap()
    /** 当前播放器实际使用的 HTTP 请求头（baseHeaders + 云盘解析出的 headers），切换源时需重建播放器 */
    private var currentPlayHeaders: Map<String, String> = emptyMap()
    private var current: MediaEntity? = null
    private var lastSavedAt = 0L

    private lateinit var prefs: SharedPreferences

    private var titleState by mutableStateOf("")
    private var toastState by mutableStateOf<String?>(null)

    /**
     * 装载态：进入播放器到首帧上屏、以及每次切集到新一集首帧之间为 true。
     *
     * 不加这层遮罩时，解析直链那几秒 SurfaceView 还挂着**上一集的最后一帧**，
     * 标题却已经换成新一集，看着就像卡死；`Player.stop()` 又不清得掉那一帧，只能自己盖。
     * 只改这个字段没用，要走 [setLoading] —— 黑幕和转圈是播放器里的原生 View。
     */
    private var loading = true

    /** 装载黑幕：挂在 PlayerView 的画面框里，只盖画面，不盖控制条 */
    private var shutterView: View? = null

    /** 装载转圈：平台 ProgressBar，与 PlayerView 缓冲时那个是同款控件 */
    private var loadingBar: View? = null

    /** 解析期由点击强行叫出的控制条：首帧上屏后撤掉；用户自己开着的控制条不受影响 */
    private var controllerShownWhileLoading = false

    /**
     * 装载代数。每开始一次装载加一，解析回来后只对号入座：
     * 连点两下「下一集」会有两个解析在飞，晚到的那个不加闸就会把前一集的地址
     * 喂给正在加载新一集的播放器。
     */
    private var playGeneration = 0

    /**
     * 已经为哪一集触发过连播。同 TV 端口径：一集只允许连播一次，
     * 否则旧播放器迟投的 ENDED 会在这集已切走后再接着跳一集。
     */
    private var endedEpisodeId = 0L

    /**
     * 已做过「死链重试」的分集 id：播放中收到 404/403/412（缓存的签名直链已失效，
     * 典型场景是夸克重新分享/令牌过期）时，丢弃解析缓存强制重新解析并重播一次。
     * 同一集只重试一次，再失败就照旧弹提示；切集时复位。
     */
    private var deadStatusRetriedId = 0L

    /** 拖动进度时的目标时间提示（只是提示，不是控制条） */
    private var seekPreview by mutableStateOf<String?>(null)
    private var seekPreviewJob: Job? = null

    /** 片尾预热下一集的任务，与它对应的那一分集行 id（一集只预热一次） */
    private var prefetchJob: Job? = null
    private var prefetchArmedFor = 0L

    /**
     * 缓冲提示（非空 = 正在「想播但没数据」），与 TV 端同一套文案。
     * 高码率原码资源在低带宽下会长时间不起播，画面看着就是「卡死」；
     * 把已缓冲秒数和这条流需要的码率摊出来，用户才能分辨是网速不够还是片子坏了。
     */
    private var bufferHint by mutableStateOf<String?>(null)
    private var bufferJob: Job? = null

    /** 缓冲下载速度（字节/秒），-1 表示还没算出来 */
    private var downloadBps = -1L

    /** 网速采样窗口起点（SystemClock） */
    private var speedWindowAt = 0L

    /** 网速采样窗口起点累计字节 */
    private var speedWindowBytes = 0L

    private var speedState by mutableStateOf(1f)

    /** 0=适应 1=铺满 2=缩放 */
    private var resizeModeState by mutableStateOf(0)

    private var subtitleBgAlpha by mutableStateOf(DEFAULT_SUBTITLE_BG_ALPHA)
    private var subtitleTextSize by mutableStateOf(DEFAULT_SUBTITLE_TEXT_SIZE)

    private var showAudioDialog by mutableStateOf(false)
    private var showSubtitleDialog by mutableStateOf(false)
    private var showSubtitleStyleDialog by mutableStateOf(false)
    private var showEpisodeDialog by mutableStateOf(false)

    /** 当前剧集的全部分集，用于选集/上下集 */
    private var episodeList by mutableStateOf<List<MediaEntity>>(emptyList())

    private var titleView: TextView? = null
    private var speedView: TextView? = null
    private var aspectView: TextView? = null
    private var orientationView: ImageView? = null
    private var prevEpisodeView: TextView? = null
    private var nextEpisodeView: TextView? = null
    private var episodesView: TextView? = null
    private var playPauseView: ImageView? = null

    /** 功能栏「设片头」按钮（剧集 + 全局开关开启才显示） */
    private var introView: TextView? = null

    /** 剧集壳行手动配置的片头时长（毫秒），0=未配置 */
    private var introSkipMs = 0L

    /** 本集 playerContent 返回的协议 skips 区间 */
    private var introSkips: List<TvBoxClient.VodSkip> = emptyList()

    /** 片头配置已为哪个 seriesKey 装载：同剧切集复用，不重复查库 */
    private var introLoadedKey: String? = null

    /** 跳过片头判定器：每集消费一次，切集自动复位 */
    private val skipController = SkipJumpController()

    /** 500ms 轮询：检查是否该跳片头 */
    private var skipJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 手机端默认进入横屏全屏，不用等用户点按钮
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enterImmersive()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        subtitleBgAlpha = prefs.getFloat(KEY_SUBTITLE_BG_ALPHA, DEFAULT_SUBTITLE_BG_ALPHA)
        subtitleTextSize = prefs.getFloat(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE)

        val mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, 0L)
        if (mediaId == 0L) {
            finish()
            return
        }

        setContent {
            DouDouTvTheme {
                // toast 显示 2.5 秒后自动消失，不再常驻画面
                LaunchedEffect(toastState) {
                    if (toastState != null) {
                        delay(2_500L)
                        toastState = null
                    }
                }
                PlayerScreen(
                    toast = toastState,
                    seekPreview = seekPreview,
                    buffering = bufferHint,
                    onViewCreated = { view, _ ->
                        playerView = view
                        player?.let { view.player = it }
                        bindController(view)
                        setupTouchSeek(view)
                        applySubtitleStyle()
                    },
                    updateResizeMode = { view ->
                        view.setResizeMode(
                            when (resizeModeState) {
                                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        )
                    }
                )

                if (showAudioDialog) {
                    AudioDialog(
                        tracks = loadAudioTracks(),
                        onDismiss = { showAudioDialog = false },
                        onSelect = { selectAudio(it); showAudioDialog = false }
                    )
                }

                if (showSubtitleDialog) {
                    SubtitleDialog(
                        tracks = loadSubtitleTracks(),
                        styleLabel = subtitleStyleLabel(),
                        onDismiss = { showSubtitleDialog = false },
                        onSelect = { selectSubtitle(it); showSubtitleDialog = false },
                        onStyle = {
                            showSubtitleDialog = false
                            showSubtitleStyleDialog = true
                        }
                    )
                }

                if (showSubtitleStyleDialog) {
                    SubtitleStyleDialog(
                        bgAlpha = subtitleBgAlpha,
                        textSize = subtitleTextSize,
                        onChange = { bg, size ->
                            subtitleBgAlpha = bg
                            subtitleTextSize = size
                            applySubtitleStyle()
                            prefs.edit()
                                .putFloat(KEY_SUBTITLE_BG_ALPHA, bg)
                                .putFloat(KEY_SUBTITLE_TEXT_SIZE, size)
                                .apply()
                        },
                        onDismiss = { showSubtitleStyleDialog = false }
                    )
                }

                if (showEpisodeDialog) {
                    EpisodeDialog(
                        episodes = episodeList,
                        current = current,
                        onDismiss = { showEpisodeDialog = false },
                        onSelect = {
                            showEpisodeDialog = false
                            playEpisode(it)
                        }
                    )
                }
            }
        }

        lifecycleScope.launch {
            val repo = MediaRepository(this@PhonePlayerActivity)
            val item = repo.getMedia(mediaId)
            if (item == null) {
                toastState = "条目不存在"
                return@launch
            }
            // 提前从 Room 查 source token 构造 header —— 第二个 Emby 源严格鉴权需要 X-Emby-Authorization
            val headers = buildMap {
                val source = runCatching { repo.getSource(item.sourceId) }.getOrNull()
                if (source?.token?.isNotBlank() == true) {
                    put("X-Emby-Authorization", "MediaBrowser Token=\"${source.token}\"")
                }
            }
            current = item
            titleState = displayTitle(item)
            startPlayback(item, headers = headers)
            loadEpisodes(item)
        }
    }

    /**
     * 绑定播放器内置控制条上的自定义按钮。
     * 播放/暂停、快退快进、seek bar 由 Media3 自己处理；
     * 这里只处理倍速、字幕、比例、上下集、选集这些额外功能。
     */
    private fun bindController(view: PlayerView) {
        titleView = view.findViewById(R.id.doudou_title)
        speedView = view.findViewById(R.id.doudou_speed)
        aspectView = view.findViewById(R.id.doudou_aspect)
        orientationView = view.findViewById(R.id.doudou_orientation)
        prevEpisodeView = view.findViewById(R.id.doudou_prev_episode)
        nextEpisodeView = view.findViewById(R.id.doudou_next_episode)
        episodesView = view.findViewById(R.id.doudou_episodes)
        playPauseView = view.findViewById(R.id.doudou_play_pause)
        introView = view.findViewById(R.id.doudou_intro_skip)

        titleView?.text = titleState
        speedView?.text = formatSpeed(speedState)
        aspectView?.text = aspectLabel()
        orientationView?.setImageResource(orientationIcon())
        updatePlayPauseLabel()

        view.findViewById<View>(R.id.doudou_back).setOnClickListener { finish() }
        speedView?.setOnClickListener { cycleSpeed() }
        aspectView?.setOnClickListener { cycleAspect() }
        orientationView?.setOnClickListener { toggleOrientation() }
        view.findViewById<View>(R.id.doudou_audio).setOnClickListener {
            showAudioDialog = true
        }
        view.findViewById<View>(R.id.doudou_subtitle).setOnClickListener {
            showSubtitleDialog = true
        }
        prevEpisodeView?.setOnClickListener { playPrevious() }
        nextEpisodeView?.setOnClickListener { playNext() }
        episodesView?.setOnClickListener {
            if (episodeList.isNotEmpty()) showEpisodeDialog = true
        }
        introView?.setOnClickListener { onIntroClick() }

        // 底部栏首位：播放暂停（快退/快进已移到左右滑动手势）
        playPauseView?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            updatePlayPauseLabel()
        }

        // 遥控器：控制条显示后不要过快自动隐藏，方便选择上下集
        view.controllerShowTimeoutMs = 5_000
        updateEpisodeButtons()
        updateIntroButton()
        startSkipTicker()

        buildLoadingViews(view)
    }

    /**
     * 装载用的黑幕与转圈。
     *
     * 黑幕挂进 PlayerView 的**画面框**（AspectRatioFrameLayout）而不是根节点：
     * 根节点上的兄弟是按顺序叠放的，挂根节点会连控制条一起盖住，装载中点屏幕就没反应了。
     * 转圈用平台 ProgressBar，和 PlayerView 缓冲时自己弹出来的那个是同款控件；
     * 装载期把自带的缓冲圈关掉，免得两个圈同时出现在同一个位置。
     */
    private fun buildLoadingViews(view: PlayerView) {
        val contentFrame =
            view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: view
        shutterView = View(view.context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentFrame.addView(shutterView)

        loadingBar = ProgressBar(view.context).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        view.addView(loadingBar)
        applyLoadingViews()
    }

    private fun setLoading(on: Boolean) {
        loading = on
        applyLoadingViews()
    }

    private fun applyLoadingViews() {
        val visibility = if (loading) View.VISIBLE else View.GONE
        shutterView?.visibility = visibility
        loadingBar?.visibility = visibility
        playerView?.setShowBuffering(
            if (loading) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_WHEN_PLAYING
        )
        if (!loading && controllerShownWhileLoading) {
            // 解析期叫出的控制条不带自动隐藏（那会儿播放器还没建起来），出画了就该收回去
            controllerShownWhileLoading = false
            playerView?.hideController()
        }
    }

    /** 更新底部播放暂停按钮图标 */
    private fun updatePlayPauseLabel() {
        val p = player ?: return
        playPauseView?.setImageResource(
            if (p.isPlaying) R.drawable.doudou_ic_pause else R.drawable.doudou_ic_play
        )
    }

    /**
     * 手势分发：
     *   · 横向滑动 → 快进/快退（滑过整屏 ≈ 1.5 倍时长）
     *   · 左边缘上下滑 → 音量
     *   · 右边缘上下滑 → 亮度
     *   · 双击 → 播放/暂停（不弹出控制条）
     *   · 单击 → 交给 PlayerView 弹控制条
     */
    private fun setupTouchSeek(view: PlayerView) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val doubleTapMs = ViewConfiguration.getDoubleTapTimeout()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 边缘调节区：左右各占屏宽 12%，中间区域不触发音量/亮度，
        // 避免下拉系统任务栏时误触。
        val edgeZone = 0.12f

        // 手势类型：用 Int 常量而不是 enum（enum 不能定义在函数内部）
        val G_NONE = 0; val G_SEEK = 1; val G_VOLUME = 2; val G_BRIGHTNESS = 3
        var gesture = G_NONE
        var downX = 0f
        var downY = 0f
        var startPos = 0L
        var targetPos = 0L
        var startVolume = 0
        var startBrightness = 0.5f
        var lastTapMs = 0L

        view.setOnTouchListener { v, event ->
            val p = player
            if (p == null) {
                // 解析直链的这几秒里播放器还是 null，而 PlayerView 自己的单击分支带了
                // player == null 判空，于是点屏幕完全没反应、只剩转圈。这里手动把控制条叫出来，
                // 至少让用户看到进度条和时间（此时是 00:00，按钮点下去是 no-op，不会崩）。
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    playerView?.showController()
                    controllerShownWhileLoading = true
                }
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    startPos = p.currentPosition
                    targetPos = startPos
                    gesture = G_NONE

                    // 起始音量 / 亮度快照，滑动时以它为基准增量
                    startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBrightness = window.attributes.screenBrightness.takeIf { it > 0f } ?: 0.5f

                    // 双击暂停：两次 DOWN 间隔 < doubleTapTimeout → 切换播放
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs < doubleTapMs) {
                        if (p.isPlaying) p.pause() else p.play()
                        updatePlayPauseLabel()
                        // 消费掉这次 DOWN，并隐藏控制条，避免双击时弹出进度条/功能栏
                        playerView?.hideController()
                        lastTapMs = 0L
                        true
                    } else {
                        lastTapMs = now
                        false
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (gesture == G_NONE) {
                        val adx = abs(dx)
                        val ady = abs(dy)
                        if (adx <= slop && ady <= slop) return@setOnTouchListener false
                        val w = v.width.coerceAtLeast(1)
                        gesture = when {
                            adx > ady * 1.4f -> G_SEEK
                            downX < w * edgeZone -> G_VOLUME
                            downX > w * (1f - edgeZone) -> G_BRIGHTNESS
                            else -> G_NONE  // 中间区域竖向滑动不处理，避免误触
                        }
                    }
                    when (gesture) {
                        G_SEEK -> {
                            val duration = p.duration
                            if (duration <= 0) return@setOnTouchListener false
                            val span = duration * 1.5f * (dx / v.width.coerceAtLeast(1))
                            targetPos = (startPos + span.toLong()).coerceIn(0L, duration)
                            showSeekPreview("${fmtTime(targetPos)} / ${fmtTime(duration)}")
                            true
                        }
                        G_VOLUME -> {
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / v.height.coerceAtLeast(1)) * max * 1.5f
                            val vol = (startVolume + delta).toInt().coerceIn(0, max)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                            showSeekPreview("音量 ${vol * 100 / max.coerceAtLeast(1)}%")
                            true
                        }
                        G_BRIGHTNESS -> {
                            val delta = (-dy / v.height.coerceAtLeast(1))
                            val b = (startBrightness + delta).coerceIn(0.01f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = b }
                            showSeekPreview("亮度 ${(b * 100).toInt()}%")
                            true
                        }
                        G_NONE -> false
                        else -> false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (gesture) {
                        G_SEEK -> {
                            p.seekTo(targetPos)
                            showSeekPreview("${fmtTime(targetPos)} / ${fmtTime(p.duration)}")
                            true
                        }
                        G_VOLUME, G_BRIGHTNESS -> true
                        // 没有识别到手势（单击）：返回 false，让 PlayerView 弹控制条
                        G_NONE -> false
                        else -> false
                    }.also { gesture = G_NONE }
                }

                else -> false
            }
        }
    }

    private fun showSeekPreview(text: String) {
        seekPreview = text
        seekPreviewJob?.cancel()
        seekPreviewJob = lifecycleScope.launch {
            delay(900)
            seekPreview = null
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, duration)
        p.seekTo(target)
        showSeekPreview("${fmtTime(target)} / ${fmtTime(duration)}")
    }

    private fun displayTitle(item: MediaEntity): String =
        if (item.usesSourceEpisodeName) {
            item.title
        } else if (item.kind == MediaKind.EPISODE && item.episode > 0) {
            "%s  S%02dE%02d".format(item.title, item.season, item.episode)
        } else {
            item.title
        }

    private fun startPlayback(
        item: MediaEntity,
        startPosition: Long = item.positionMs,
        headers: Map<String, String> = emptyMap(),
        forceRefresh: Boolean = false
    ) {
        if (item.uri.isBlank()) {
            toastState = "该条目没有可播放地址（可能需要在详情页重新加载）"
            return
        }

        // 云盘 scheme 地址需要先解析出真实视频 URL + 请求头，再创建播放器
        setLoading(true)
        // 这一集还没播完过，闸门打开（否则「从头开始」再播到末尾不会连播下一集）
        endedEpisodeId = 0L
        val gen = ++playGeneration
        lifecycleScope.launch {
            val resolved = resolvePlayUrl(item, gen, forceRefresh)
            if (resolved == null) {
                // 失败时 toastState 已在 resolvePlayUrl 内设置；黑幕必须撤，否则错误提示压在转圈下
                if (gen == playGeneration) setLoading(false)
                return@launch
            }
            // 解析在飞的这几秒里用户可能又切了一次，旧地址不能喂给新集
            if (gen != playGeneration) return@launch

            // 片头配置：手动 > 协议 skips；旧集的协议区间先清掉再挂新集的
            introSkips = emptyList()
            loadIntroConfig(item.seriesKey)
            introSkips = resolved.skips
            updateIntroButton()

            // 必须先释放旧 player 再创建新的 —— SimpleCache 单例同一时刻只能被一个 player 引用
            PlayerFactory.release(player)
            player = null

            // baseHeaders 是本次播放会话的固定头（如 Emby 鉴权），后续切集时复用
            baseHeaders = headers
            // 合并调用方传入的 headers（如 Emby 鉴权）与云盘解析出的 headers（如 Cookie）
            val mergedHeaders = headers + resolved.headers
            currentPlayHeaders = mergedHeaders

            val p = PlayerFactory.createPhonePlayer(this@PhonePlayerActivity, mergedHeaders)
            player = p
            playerView?.player = p
            playerView?.controllerAutoShow = false
            playerView?.hideController()

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // 换源重建播放器时，旧实例的回调可能还排在主线程队列里。
                    // 不认实例，旧播放器的 READY / ENDED 会盖到正在加载的新集上。
                    if (player !== p) {
                        Log.w("PlayTrace", "startPlayback 忽略旧实例 state=$state")
                        return
                    }
                    traceState("startPlayback", p, state)
                    if (state == Player.STATE_ENDED && endedEpisodeId != (current?.id ?: 0L)) {
                        endedEpisodeId = current?.id ?: 0L
                        playNext()
                    }
                    if (state == Player.STATE_READY) {
                        armNextPrefetch()
                        clearLoadingWhenFirstFrameLate(p)
                    }
                    watchBuffering()
                }

                // 首帧真正上屏才撤黑幕：READY 只代表数据够播，从 READY 到出画还有几百毫秒
                override fun onRenderedFirstFrame() {
                    if (player === p) setLoading(false)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseLabel()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    rememberFormat(tracks)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    setLoading(false)
                    Log.e("PhonePlayer", "player error: ${error.errorCodeName} ${error.message}", error)
                    if (isTailSourceError(p)) {
                        // 片尾的坏数据不当故障报：按看完落盘，让连播接手
                        Log.w("PlayTrace", "片尾容错命中 pos=${p.currentPosition} dur=${p.duration}")
                        if (endedEpisodeId != (current?.id ?: 0L)) {
                            endedEpisodeId = current?.id ?: 0L
                            saveProgress(force = true)
                            playNext()
                        }
                        return
                    }
                    if (handleDeadStatusRetry(p, error)) return
                    toastState = translatePlayerError(error)
                }
            })

            PlayerFactory.traceUrl = resolved.url
            p.setMediaItem(MediaItem.fromUri(resolved.url))
            p.prepare()
            if (startPosition > 3_000L) p.seekTo(startPosition)
            p.setPlaybackSpeed(speedState)
            p.playWhenReady = true
        }
    }

    /**
     * 解析播放地址：云盘 scheme 与 http(s) 直链统一交给 [MediaRepository.resolveTvBoxPlayUrl]
     * （直链也会先问 spider，失败/超时自动回退原地址）；非 TVBox 来源原样返回。
     * @param gen 本次装载的代数，与被取代的旧装载对不上就不写 [toastState]
     * @param forceRefresh true 表示死链重试：丢弃解析缓存并强制刷新云盘分享令牌
     * @return 解析结果（URL + headers）；解析失败返回 null
     */
    private suspend fun resolvePlayUrl(
        item: MediaEntity,
        gen: Int,
        forceRefresh: Boolean = false
    ): TvBoxClient.ResolvedPlayUrl? {
        return runCatching {
            MediaRepository(this@PhonePlayerActivity).resolveTvBoxPlayUrl(item, forceRefresh)
        }.fold(
            onSuccess = { it },
            onFailure = {
                val msg = it.message ?: "云盘解析失败"
                Log.w("PhonePlayer", "resolvePlayUrl failed: $msg")
                if (gen == playGeneration) toastState = msg
                null
            }
        )
    }

    /**
     * 播放期读真实轨道（分辨率/HDR/杜比视界/音轨），回写画质到剧集壳。
     * 详情页那条文件名推测值可能不准，以这里读到的码流信息为准；仓储侧判重，不会重复写。
     */
    private fun rememberFormat(tracks: Tracks) {
        val info = FormatProbe.fromTracks(tracks) ?: return
        val item = current ?: return
        lifecycleScope.launch {
            MediaRepository(this@PhonePlayerActivity).savePlaybackFormat(item, info)
        }
    }

    private fun loadEpisodes(item: MediaEntity) {
        if (item.kind != MediaKind.EPISODE || item.seriesKey.isBlank()) {
            episodeList = emptyList()
            updateEpisodeButtons()
            return
        }
        lifecycleScope.launch {
            val list = runCatching {
                MediaRepository(this@PhonePlayerActivity).episodes(item.seriesKey)
            }.getOrDefault(emptyList())
                // 与电视端同一口径：上下集/选集只在同一个网盘、同一条线路里走，
                // 否则「下一集」会跳到别的网盘（那一集常常还解析失败）。
                .filter { item.playSource.isBlank() || it.playSource == item.playSource }
                .filter { it.uri.isNotBlank() }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            episodeList = list
            updateEpisodeButtons()
        }
    }

    private fun updateEpisodeButtons() {
        val visible = current?.kind == MediaKind.EPISODE && episodeList.size > 1
        val visibility = if (visible) View.VISIBLE else View.GONE
        prevEpisodeView?.visibility = visibility
        nextEpisodeView?.visibility = visibility
        episodesView?.visibility = visibility

        val index = episodeIndex()
        prevEpisodeView?.isEnabled = index > 0
        prevEpisodeView?.alpha = if (index > 0) 1f else 0.4f
        nextEpisodeView?.isEnabled = index >= 0 && index < episodeList.size - 1
        nextEpisodeView?.alpha = if (index >= 0 && index < episodeList.size - 1) 1f else 0.4f
    }

    private fun episodeIndex(): Int {
        val cur = current ?: return -1
        return episodeList.indexOfFirst { it.id == cur.id }
    }

    /** 读剧集壳行的手动片头配置；同剧切集命中缓存直接复用 */
    private suspend fun loadIntroConfig(seriesKey: String) {
        if (seriesKey.isBlank()) {
            introSkipMs = 0L
            introLoadedKey = null
            return
        }
        if (introLoadedKey == seriesKey) return
        introSkipMs = runCatching {
            MediaRepository(this).shellOf(seriesKey)?.introSkipMs ?: 0L
        }.getOrDefault(0L)
        introLoadedKey = seriesKey
    }

    /** 「设片头」按钮：全局开关 + 剧集才显示；文案随配置变化 */
    private fun updateIntroButton() {
        val cur = current
        val isSeries = cur != null && cur.kind == MediaKind.EPISODE && cur.seriesKey.isNotBlank()
        introView?.visibility =
            if (Prefs.skipIntroEnabled && isSeries) View.VISIBLE else View.GONE
        introView?.text = if (introSkipMs > 0L) "片头 ${fmtIntroMs(introSkipMs)}" else "设片头"
    }

    /**
     * 「设片头」点击：
     * · 未配置 → 把当前播放位置写为片头终点（>10 分钟拒绝）；
     * · 已配置 → 弹小面板：改为当前位置 / 清除 / 取消。
     */
    private fun onIntroClick() {
        val p = player ?: return
        val cur = current ?: return
        if (cur.kind != MediaKind.EPISODE || cur.seriesKey.isBlank()) return
        val pos = p.currentPosition
        if (introSkipMs > 0L) {
            android.app.AlertDialog.Builder(this)
                .setTitle("跳过片头")
                .setMessage("当前已设置 ${fmtIntroMs(introSkipMs)}（全剧生效）")
                .setPositiveButton(
                    if (pos > 0L) "改为当前位置 ${fmtIntroMs(pos)}" else "改为当前位置"
                ) { _, _ -> if (pos > 0L) saveIntroSkip(cur.seriesKey, pos) }
                .setNeutralButton("清除") { _, _ -> saveIntroSkip(cur.seriesKey, 0L) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (pos > 600_000L) {
            toastState = "片头位置超过 10 分钟，请回退到片头附近再设置"
            return
        }
        if (pos < 1_000L) {
            toastState = "等画面播到片头结尾时再按"
            return
        }
        saveIntroSkip(cur.seriesKey, pos)
    }

    /** 写片头时长到剧集壳行（0=清除），成功后刷新按钮文案 */
    private fun saveIntroSkip(seriesKey: String, ms: Long) {
        lifecycleScope.launch {
            val ok = runCatching {
                MediaRepository(this@PhonePlayerActivity).setIntroSkip(seriesKey, ms)
            }.getOrDefault(false)
            if (!ok) {
                toastState = "未找到该剧的剧集信息，设置失败"
                return@launch
            }
            introSkipMs = ms
            introLoadedKey = seriesKey
            updateIntroButton()
            toastState = if (ms > 0L) "已设置跳过片头 ${fmtIntroMs(ms)}（全剧生效）"
            else "已清除片头设置"
        }
    }

    /**
     * 片头跳过轮询：每 500ms 拿一次位置问 [SkipJumpController]。
     * 播放器还没建起来 / 电影 / 全局开关关闭时都静默跳过，不产生任何副作用。
     */
    private fun startSkipTicker() {
        if (skipJob?.isActive == true) return
        skipJob = lifecycleScope.launch {
            while (true) {
                delay(500L)
                val p = player ?: continue
                val cur = current ?: continue
                val jump = skipController.nextJump(
                    enabled = Prefs.skipIntroEnabled,
                    introMs = introSkipMs,
                    skips = introSkips,
                    episodeId = cur.id,
                    position = p.currentPosition,
                    duration = p.duration,
                    isPlaying = p.isPlaying
                )
                if (jump != null) {
                    p.seekTo(jump)
                    toastState = "已跳过片头"
                }
            }
        }
    }

    private fun playEpisode(episode: MediaEntity) {
        if (episode.uri.isBlank()) return
        // 先落盘：saveProgress 按 current 写库，必须赶在切到新一集之前
        saveProgress(force = true)
        // 上一集就此收尾：回传一次服务端，之后不再管它
        reportToEmby()
        // 停机：不停的话解析直链那几秒上一集还在放，标题却已经换成新一集
        player?.pause()

        current = episode
        titleState = displayTitle(episode)
        titleView?.text = titleState
        updateEpisodeButtons()
        // 上一集的协议 skips 区间作废，等这一集解析完再挂新的
        introSkips = emptyList()

        setLoading(true)
        bufferHint = null
        // 上一集排的片尾预热作废：它按旧集的时长排，切集后时间点已经不对
        prefetchJob?.cancel()
        prefetchArmedFor = 0L
        // 新一集允许它自己播完一次连播（旧集的 ENDED 闸门见 endedEpisodeId 注释）
        endedEpisodeId = 0L
        // 新一集的死链重试闸门重新打开
        deadStatusRetriedId = 0L

        // 云盘 scheme 地址需要先解析出真实视频 URL + 请求头再喂给播放器
        val gen = ++playGeneration
        lifecycleScope.launch {
            val resolved = resolvePlayUrl(episode, gen)
            if (resolved == null) {
                if (gen == playGeneration) setLoading(false)
                return@launch
            }
            // 解析在飞的这几秒里用户可能又按了一次下一集，旧地址不能喂给新集
            if (gen != playGeneration) return@launch

            // 片头配置：手动 > 协议 skips（同剧切集走缓存，不重复查库）
            loadIntroConfig(episode.seriesKey)
            introSkips = resolved.skips
            updateIntroButton()

            // 合并基础头（Emby 鉴权）与云盘解析出的头（Cookie 等）。
            // 对 Emby 的 http 直链：resolved.headers 为空 → mergedHeaders = baseHeaders（不变，不重建）。
            // 对云盘切源：resolved.headers 变化 → mergedHeaders 变化 → 重建播放器带新 Cookie。
            val mergedHeaders = baseHeaders + resolved.headers
            val needRebuild = mergedHeaders != currentPlayHeaders
            val p = if (needRebuild) {
                // 切换了播放源（Cookie 等 headers 变化），必须重建播放器
                PlayerFactory.release(player)
                player = null
                currentPlayHeaders = mergedHeaders
                val np = PlayerFactory.createPhonePlayer(this@PhonePlayerActivity, mergedHeaders)
                player = np
                playerView?.player = np
                np.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        // 同第一个 listener：只认当前这台播放器，旧实例的迟到回调不能盖到新集上
                        if (player !== np) {
                            Log.w("PlayTrace", "playEpisode 忽略旧实例 state=$state")
                            return
                        }
                        traceState("playEpisode(np)", np, state)
                        if (state == Player.STATE_ENDED && endedEpisodeId != (current?.id ?: 0L)) {
                            endedEpisodeId = current?.id ?: 0L
                            playNext()
                        }
                        if (state == Player.STATE_READY) {
                            armNextPrefetch()
                            clearLoadingWhenFirstFrameLate(np)
                        }
                        watchBuffering()
                    }

                    // 首帧真正上屏才撤黑幕
                    override fun onRenderedFirstFrame() {
                        if (player === np) setLoading(false)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseLabel()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        rememberFormat(tracks)
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        setLoading(false)
                        Log.e("PhonePlayer", "player error: ${error.errorCodeName} ${error.message}", error)
                        if (isTailSourceError(np)) {
                            // 同第一个 listener：片尾的坏数据按看完处理，交给连播
                            Log.w("PlayTrace", "片尾容错命中 pos=${np.currentPosition} dur=${np.duration}")
                            if (endedEpisodeId != (current?.id ?: 0L)) {
                                endedEpisodeId = current?.id ?: 0L
                                saveProgress(force = true)
                                playNext()
                            }
                            return
                        }
                        if (handleDeadStatusRetry(np, error)) return
                        toastState = translatePlayerError(error)
                    }
                })
                np
            } else {
                player ?: run { setLoading(false); return@launch }
            }

            PlayerFactory.traceUrl = resolved.url
            p.setMediaItem(MediaItem.fromUri(resolved.url))
            p.prepare()
            Log.w("PlayTrace", "playEpisode 装载 id=${episode.id} rebuild=$needRebuild 续播位置=${episode.positionMs}")
            if (episode.positionMs > 3_000L) p.seekTo(episode.positionMs)
            p.setPlaybackSpeed(speedState)
            p.playWhenReady = true
        }
    }

    private fun playNext() {
        val index = episodeIndex()
        val next = episodeList.getOrNull(index + 1)
        Log.w("PlayTrace", "playNext 索引=$index/${episodeList.size} 下一集=${next?.let { "${it.id}/${displayTitle(it)}" } ?: "无"}")
        if (next == null) return
        if (next.id == current?.id) return
        playEpisode(next)
    }

    private fun playPrevious() {
        val index = episodeIndex()
        val prev = episodeList.getOrNull(index - 1) ?: return
        if (prev.id == current?.id) return
        playEpisode(prev)
    }

    /**
     * 纯音频流和不回首帧事件的片子等不到 [Player.Listener.onRenderedFirstFrame]，
     * READY 之后兜一把，否则黑幕和转圈永远撤不掉。
     *
     * 1.2 秒内用户可能又切走了，那时黑幕是给新一集的，不能由这一集的兜底撤掉。
     */
    private fun clearLoadingWhenFirstFrameLate(p: Player) {
        if (!loading || player !== p) return
        val readyEpisodeId = current?.id ?: 0L
        lifecycleScope.launch {
            delay(1_200L)
            if (loading && current?.id == readyEpisodeId) setLoading(false)
        }
    }

    /**
     * 片尾前把同一条线路的下一集先解析好（刷分享令牌 + 问 spider 要直链，实测约 8 秒），
     * 真到切集时命中 [MediaRepository] 的进程级直链缓存，直接起播。
     *
     * 一集只排一次：STATE_READY 在每次缓冲恢复后都会再来，重复排程就是反复敲源站。
     * 不排在开播时 —— 直链带签名有有效期，45 分钟那一集看完早就凉了。
     */
    private fun armNextPrefetch() {
        val epId = current?.id ?: return
        if (prefetchArmedFor == epId) return
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0L) return
        prefetchArmedFor = epId
        val remaining = dur - p.currentPosition - PREFETCH_REMAINING_MS
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch {
            delay(remaining.coerceAtLeast(0L))
            val next = episodeList.getOrNull(episodeIndex() + 1) ?: return@launch
            runCatching {
                MediaRepository(this@PhonePlayerActivity).resolveTvBoxPlayUrl(next)
            }.onFailure {
                Log.w("PhonePlayer", "下一集预热失败 mediaId=${next.id}：${it.message}")
            }
        }
    }

    /**
     * 【临时取证】「播到结尾不连播 + 报网络错误」的定位日志：
     * 状态机每次都记，配合 PlayerFactory 里的 PlayTrace（HTTP 出口）能还原
     * 片尾到底走没走 ENDED、连播闸门拦在哪一步。拿到结论后两处一起删。
     */
    private fun traceState(where: String, p: Player, state: Int) {
        Log.w(
            "PlayTrace",
            "$where state=" + when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?$state"
            } + " pos=${p.currentPosition} dur=${p.duration} buf=${p.bufferedPosition}" +
                " endedGate=$endedEpisodeId cur=${current?.id} idx=${episodeIndex()}/${episodeList.size}"
        )
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

    /**
     * 死链（404/403/412）处理：缓存/入库的签名直链已失效（典型场景是夸克重新分享、
     * 令牌过期，整剧旧链接一起凉）。丢弃解析缓存、强制刷新分享令牌重新解析，
     * 从当前位置自动重播这一集一次；同一集只重试一次（切集复位），再失败照旧弹提示。
     *
     * @return true 表示已发起重试，调用方不要再弹错误提示
     */
    private fun handleDeadStatusRetry(
        p: Player,
        error: androidx.media3.common.PlaybackException
    ): Boolean {
        val causeMsg = error.cause?.message
        val deadStatus = causeMsg?.startsWith("HTTP 404") == true ||
            causeMsg?.startsWith("HTTP 403") == true ||
            causeMsg?.startsWith("HTTP 412") == true
        val ep = current
        if (!deadStatus || ep == null || !ep.seriesKey.startsWith("tvbox:") ||
            deadStatusRetriedId == ep.id
        ) return false
        deadStatusRetriedId = ep.id
        Log.w("PhonePlayer", "死链重试：强制重新解析 mediaId=${ep.id} cause=$causeMsg")
        startPlayback(ep, maxOf(p.currentPosition, ep.positionMs), baseHeaders, forceRefresh = true)
        return true
    }

    /** 错误翻译：优先用 OkHttp 层抛出的中文提示，其次说实话，最后才兜底到网络 */
    private fun translatePlayerError(error: androidx.media3.common.PlaybackException): String {
        val causeMsg = error.cause?.message
        return when {
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

    private fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOfFirst { abs(it - speedState) < 0.01f } + 1) % SPEEDS.size]
        speedState = next
        player?.setPlaybackSpeed(next)
        speedView?.text = formatSpeed(next)
    }

    private fun cycleAspect() {
        resizeModeState = (resizeModeState + 1) % 3
        playerView?.setResizeMode(
            when (resizeModeState) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        )
        aspectView?.text = aspectLabel()
    }

    private fun aspectLabel(): String = when (resizeModeState) {
        1 -> "铺满"
        2 -> "缩放"
        else -> "适应"
    }

    private fun orientationIcon(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            R.drawable.doudou_ic_fullscreen_exit
        else R.drawable.doudou_ic_fullscreen

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    /** 字幕外观：背景不透明度 + 字号 */
    private fun applySubtitleStyle() {
        val sv = playerView?.subtitleView ?: return
        val bg = android.graphics.Color.argb(
            (subtitleBgAlpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0
        )
        sv.setStyle(
            // 形参顺序是 (前景, 字底, 窗口底, 描边类型, 描边色, 字体) —— 与 TV 端同一处笔误，一起修。
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

    private fun toggleOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientationView?.setImageResource(orientationIcon())
        // 转屏后窗口尺寸 / inset 变化，必须重新应用沉浸式，
        // 否则可能残留旧的 cutout 留边，画面看起来偏了。
        enterImmersive()
    }

    // ---------- 轨道选择（音轨 / 字幕 独立处理，避免互相覆盖导致报错） ----------
    //
    // 根因：原来的实现把音轨和字幕混在同一个 loadTracks 列表里，
    // selectTrack 时用 builder.setOverrideForType 同时改 audio 和 text 的参数，
    // 但 TrackOption 里存的 group 是 loadTracks 当时的快照，播放器在 seek / 网络抖动后
    // currentTracks.groups 会变，旧 group.mediaTrackGroup 传给 TrackSelectionOverride
    // 会被 Media3 判为"不在当前轨道列表"，直接抛 PlaybackException → 视频报错停止。
    //
    // 修复：1. 音轨 / 字幕 分开加载、分开选择；
    //      2. 每次 select 时重新从 player.currentTracks.groups 找当前最新的 group 引用。

    private fun loadAudioTracks(): List<TrackOption> {
        val p = player ?: return emptyList()
        val out = mutableListOf<TrackOption>()
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.sampleMimeType == null) continue
                val label = buildString {
                    append(format.label ?: format.language ?: "音轨")
                    format.channelCount.takeIf { it > 0 }?.let { append(" · ${it}声道") }
                    format.bitrate.takeIf { it > 0 }?.let { append(" · ${it / 1000}kbps") }
                }
                out += TrackOption(label, C.TRACK_TYPE_AUDIO, group, i, group.isTrackSelected(i))
            }
        }
        return out
    }

    private fun loadSubtitleTracks(): List<TrackOption> {
        val p = player ?: return emptyList()
        val disabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val out = mutableListOf<TrackOption>()
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language ?: "字幕"
                out += TrackOption(label, C.TRACK_TYPE_TEXT, group, i,
                    !disabled && group.isTrackSelected(i))
            }
        }
        val firstGroup = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        out += TrackOption("关闭字幕", C.TRACK_TYPE_TEXT,
            firstGroup ?: return out, -1, disabled)
        return out
    }

    private fun selectAudio(option: TrackOption) {
        val p = player ?: return
        // 关键：每次从当前 currentTracks 里找最新的 group，不用 Dialog 里快照的旧 group
        val targetMediaGroup = option.group.mediaTrackGroup
        val freshGroup = p.currentTracks.groups.firstOrNull { it.mediaTrackGroup === targetMediaGroup }
            ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        builder.setOverrideForType(
            TrackSelectionOverride(freshGroup.mediaTrackGroup, option.trackIndex)
        )
        p.trackSelectionParameters = builder.build()
    }

    private fun selectSubtitle(option: TrackOption) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option.trackIndex < 0) {
            // Media3 单参数 API：setTrackTypeDisabled 后 Media3 自动忽略该 type 的所有 override，
            // 因此不需要额外调用 clearOverrideForType（这个方法在当前版本也不存在）
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val targetMediaGroup = option.group.mediaTrackGroup
            val freshGroup = p.currentTracks.groups.firstOrNull { it.mediaTrackGroup === targetMediaGroup }
                ?: return
            builder.setOverrideForType(
                TrackSelectionOverride(freshGroup.mediaTrackGroup, option.trackIndex)
            )
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun subtitleStyleLabel(): String {
        val bg = (subtitleBgAlpha * 100).toInt()
        return "背景 ${bg}% · ${subtitleTextSize.toInt()}sp"
    }

    /**
     * 缓冲状态下开始/停止 500ms 轮询。
     * 手机端没有 TV 端那样的常驻进度 ticker（控制条自己刷），所以缓冲期间临时起一个循环，
     * 让「下载速度」是活的而不是进门时的一次性快照。
     */
    private fun watchBuffering() {
        val p = player ?: return
        val buffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady
        if (!buffering) {
            bufferJob?.cancel()
            bufferJob = null
            bufferHint = null
            return
        }
        bufferHint = buildBufferHint(p)
        if (bufferJob?.isActive == true) return
        // 新一轮缓冲重新起算网速窗口，避免把上一轮的字节算进来
        speedWindowAt = 0L
        downloadBps = -1L
        bufferJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                val cur = player ?: break
                // 与 TV 端同款：每攒满 1 秒刷新一次实测下载速度
                sampleDownloadSpeed()
                val hint = buildBufferHint(cur)
                bufferHint = hint
                if (hint == null) break
            }
            bufferJob = null
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

    /**
     * 每攒满 1 秒刷新一次实测网速（与 TV 端同款窗口算法）。
     * 只读 [DownloadMeter] 的累计值，不干扰播放器；取不到增量时保留上一次的值，
     * 避免 500ms 轮询把数字刷成 0 造成视觉抖动。
     */
    private fun sampleDownloadSpeed() {
        val now = SystemClock.elapsedRealtime()
        val bytes = DownloadMeter.totalBytes
        if (speedWindowAt == 0L) {
            speedWindowAt = now
            speedWindowBytes = bytes
            return
        }
        val elapsed = now - speedWindowAt
        if (elapsed < 1_000L) return
        val delta = bytes - speedWindowBytes
        if (delta > 0L) {
            downloadBps = delta * 1000L / elapsed
            speedWindowAt = now
            speedWindowBytes = bytes
        }
    }

    private fun fmtSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000L -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
        else -> "%.0f KB/s".format(bytesPerSec / 1024.0)
    }

    /** 当前视频轨的声明码率（bit/s）；容器没写或还拿不到轨信息时返回 0 */
    private fun videoBitrateBps(p: Player): Int {
        val group = p.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return 0
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
        val bitrate = runCatching { group.getTrackFormat(index).bitrate }.getOrNull()
        return if (bitrate != null && bitrate > 0) bitrate else 0
    }

    private fun saveProgress(force: Boolean = false) {
        val item = current ?: return
        val p = player ?: return
        // 装载期间播放器还挂着上一集（或刚创建、位置为 0），这时落盘会把错误的时间
        // 写到 current（已经是新一集）头上。切集前已经强制落过盘，跳过不丢进度。
        if (loading) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 5_000L) return
        lastSavedAt = now

        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0L } ?: 0L

        // 落盘不挂在 Activity 生命周期上：onDestroy 里 lifecycleScope 已被取消，
        // 挂在它上面的协程会在「切到 IO 真正写库」之前就结束，退出前的最后一笔进度就丢了。
        // 与 TV 端口径一致，用独立 scope；context 用 applicationContext，
        // 因为这个协程可能活得比 Activity 久。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val repo = MediaRepository(applicationContext)
                repo.saveProgress(item.id, pos)
                if (dur > 0L) repo.fillDuration(item.id, dur)
            }
        }
    }

    /**
     * 离开这条播放时把进度回传服务端（Emby 源）。
     *
     * 只在「离开」这一刻发一次：切集、退出播放器。播放期间不回传 ——
     * 服务端只需要知道「最后看到哪」，中途心跳对续播没有任何用处。
     */
    private fun reportToEmby() {
        val item = current ?: return
        val p = player ?: return
        // 装载期间播放器还挂着上一集（或位置还没起来），这时报出去的是错的
        if (loading) return
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (pos <= 0L) return
        val dur = p.duration.takeIf { it > 0L } ?: 0L
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                MediaRepository(applicationContext).reportPlaybackToEmby(item.id, pos, dur)
            }
        }
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

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
        // 离开播放器这一刻回传一次服务端；onDestroy 之前一定先走 onPause，不重复报
        reportToEmby()
    }

    override fun onDestroy() {
        saveProgress(force = true)
        playerView?.player = null
        PlayerFactory.release(player)
        player = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val controllerVisible = playerView?.isControllerFullyVisible == true
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(30_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-10_000L)
                return true
            }
            // 控制条隐藏时，遥控器左右键直接快退/快进；显示时交给控制条做焦点移动
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!controllerVisible) { seekBy(-10_000L); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controllerVisible) { seekBy(30_000L); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_SUBTITLE_BG_ALPHA = "subtitle_bg_alpha"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val DEFAULT_SUBTITLE_BG_ALPHA = 0.35f
        private const val DEFAULT_SUBTITLE_TEXT_SIZE = 18f

        private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** 距结尾多少毫秒预热下一集：解析本身要 8 秒左右，留足余量又不至于提前太久失效 */
        private const val PREFETCH_REMAINING_MS = 90_000L

        /** 片尾容错窗口：离结尾不到 5 秒的 Source error 按「看完」处理 */
        private const val TAIL_ERROR_TOLERANCE_MS = 5_000L

        fun intent(context: Context, mediaId: Long): Intent =
            Intent(context, PhonePlayerActivity::class.java).putExtra(EXTRA_MEDIA_ID, mediaId)
    }
}

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private data class TrackOption(
    val label: String,
    val type: Int,
    /** 当前快照 group（select 时只用来取出 mediaTrackGroup 做引用比对） */
    val group: Tracks.Group,
    val trackIndex: Int,
    val selected: Boolean
)

@UnstableApi
@Composable
private fun PlayerScreen(
    toast: String?,
    seekPreview: String?,
    buffering: String?,
    onViewCreated: (PlayerView, Context) -> Unit,
    updateResizeMode: (PlayerView) -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.view_doudou_player, null) as PlayerView
                onViewCreated(view, ctx)
                view
            },
            update = { view ->
                updateResizeMode(view)
            }
        )

        // ---------- 装载中的黑幕与转圈 ----------
        // 不是 Compose 浮层，而是挂在 PlayerView 里的原生 View（见 buildLoadingViews）：
        // 黑幕只盖画面，控制条照常能弹出来，转圈和播放器缓冲时那个是同款控件。

        // 顶部标题仅在控制条里展示；这里只保留拖动进度预览与错误提示
        if (!seekPreview.isNullOrBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.72f), Radius.Md)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    seekPreview,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (!toast.isNullOrBlank()) {
            // toast 显示在视频上部，外层 2.5 秒后自动清除
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.72f), Radius.Md)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(toast, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 缓冲提示：控制条上那个转圈只说「还在等」，这里补上等了多久、这条流要多大带宽
        if (!buffering.isNullOrBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 120.dp)
                    .background(Color.Black.copy(alpha = 0.72f), Radius.Md)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    buffering,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AudioDialog(
    tracks: List<TrackOption>,
    onDismiss: () -> Unit,
    onSelect: (TrackOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        containerColor = AppColors.Surface,
        title = { Text("音轨", color = AppColors.TextPrimary) },
        text = {
            if (tracks.isEmpty()) {
                Text("未检测到多音轨", color = AppColors.TextSecondary)
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(tracks) { TrackRow(it) { onSelect(it) } }
                }
            }
        }
    )
}

@Composable
private fun SubtitleDialog(
    tracks: List<TrackOption>,
    styleLabel: String,
    onDismiss: () -> Unit,
    onSelect: (TrackOption) -> Unit,
    onStyle: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onStyle) { Text("外观 · $styleLabel") } },
        containerColor = AppColors.Surface,
        title = { Text("字幕", color = AppColors.TextPrimary) },
        text = {
            if (tracks.isEmpty()) {
                Text("未检测到可选字幕", color = AppColors.TextSecondary)
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(tracks) { TrackRow(it) { onSelect(it) } }
                }
            }
        }
    )
}

@Composable
private fun TrackRow(option: TrackOption, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.Md)
            .background(if (option.selected) AppColors.SurfaceHi else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            option.label,
            color = if (option.selected) AppColors.Brand else AppColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (option.selected) Text("✓", color = AppColors.Brand,
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun EpisodeDialog(
    episodes: List<MediaEntity>,
    current: MediaEntity?,
    onDismiss: () -> Unit,
    onSelect: (MediaEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = AppColors.Surface,
        title = { Text("选集（共 ${episodes.size} 集）", color = AppColors.TextPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(episodes, key = { it.id }) { ep ->
                    val selected = current?.id == ep.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(Radius.Md)
                            .background(if (selected) AppColors.SurfaceHi else Color.Transparent)
                            .clickable { onSelect(ep) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            if (ep.episode > 0) "E%02d".format(ep.episode) else "•",
                            color = if (selected) AppColors.Brand else AppColors.TextTertiary,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            ep.title,
                            color = if (selected) AppColors.Brand else AppColors.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text("播放中", color = AppColors.Brand,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun SubtitleStyleDialog(
    bgAlpha: Float,
    textSize: Float,
    onChange: (Float, Float) -> Unit,
    onDismiss: () -> Unit
) {
    val bgValues = listOf(0f, 0.15f, 0.35f, 0.55f, 0.8f, 1f)
    val sizeValues = listOf(12f, 14f, 16f, 18f, 20f, 22f, 26f, 32f)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        containerColor = AppColors.Surface,
        title = { Text("字幕样式", color = AppColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "背景不透明度  ${(bgAlpha * 100).toInt()}%",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    bgValues.forEach { v ->
                        val selected = kotlin.math.abs(v - bgAlpha) < 0.05f
                        AppButton(
                            text = "${(v * 100).toInt()}%",
                            onClick = { onChange(v, textSize) },
                            variant = if (selected) AppButtonVariant.Primary else AppButtonVariant.Neutral,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            textStyle = MaterialTheme.typography.labelMedium,
                            minHeight = 0.dp,
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
                Text(
                    "字号  ${textSize.toInt()} sp",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizeValues.forEach { v ->
                        val selected = kotlin.math.abs(v - textSize) < 1f
                        AppButton(
                            text = "${v.toInt()}sp",
                            onClick = { onChange(bgAlpha, v) },
                            variant = if (selected) AppButtonVariant.Primary else AppButtonVariant.Neutral,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            textStyle = MaterialTheme.typography.labelMedium,
                            minHeight = 0.dp,
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
            }
        }
    )
}
