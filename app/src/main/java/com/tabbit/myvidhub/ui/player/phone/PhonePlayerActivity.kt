package com.tabbit.myvidhub.ui.player.phone

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.tabbit.myvidhub.R
import com.tabbit.myvidhub.data.MediaRepository
import com.tabbit.myvidhub.data.format.FormatProbe
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.components.usesSourceEpisodeName
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放页（VidHub 风格）。
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

    /** 拖动进度时的目标时间提示（只是提示，不是控制条） */
    private var seekPreview by mutableStateOf<String?>(null)
    private var seekPreviewJob: Job? = null

    /**
     * 缓冲提示（非空 = 正在「想播但没数据」），与 TV 端同一套文案。
     * 高码率原码资源在低带宽下会长时间不起播，画面看着就是「卡死」；
     * 把已缓冲秒数和这条流需要的码率摊出来，用户才能分辨是网速不够还是片子坏了。
     */
    private var bufferHint by mutableStateOf<String?>(null)
    private var bufferJob: Job? = null

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
    private var orientationView: TextView? = null
    private var prevEpisodeView: TextView? = null
    private var nextEpisodeView: TextView? = null
    private var episodesView: TextView? = null
    private var playPauseView: TextView? = null

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
            MyVidHubTheme {
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

        titleView?.text = titleState
        speedView?.text = formatSpeed(speedState)
        aspectView?.text = aspectLabel()
        orientationView?.text = orientationLabel()
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

        // 底部栏首位：播放暂停（快退/快进已移到左右滑动手势）
        playPauseView?.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            updatePlayPauseLabel()
        }

        // 遥控器：控制条显示后不要过快自动隐藏，方便选择上下集
        view.controllerShowTimeoutMs = 5_000
        updateEpisodeButtons()
    }

    /** 更新底部播放暂停按钮文字 */
    private fun updatePlayPauseLabel() {
        val p = player ?: return
        playPauseView?.text = if (p.isPlaying) "暂停" else "播放"
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
            val p = player ?: return@setOnTouchListener false
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
        headers: Map<String, String> = emptyMap()
    ) {
        if (item.uri.isBlank()) {
            toastState = "该条目没有可播放地址（可能需要在详情页重新加载）"
            return
        }

        // 云盘 scheme 地址需要先解析出真实视频 URL + 请求头，再创建播放器
        lifecycleScope.launch {
            val resolved = resolvePlayUrl(item)
                ?: return@launch // 失败时 toastState 已在 resolvePlayUrl 内设置

            Log.d("PhonePlayer", "startPlayback url=${resolved.url.take(120)}")
            Log.d("PhonePlayer", "startPlayback headers=${resolved.headers.map { "${it.key}=${it.value.take(30)}..." }}")

            // 必须先释放旧 player 再创建新的 —— SimpleCache 单例同一时刻只能被一个 player 引用
            player?.release()
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
                    Log.d("PhonePlayer", "player state=$state")
                    if (state == Player.STATE_ENDED) playNext()
                    watchBuffering()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d("PhonePlayer", "isPlaying=$isPlaying")
                    updatePlayPauseLabel()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    rememberFormat(tracks)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("PhonePlayer", "player error: ${error.errorCodeName} ${error.message}", error)
                    // 优先使用 OkHttp 层拦截时抛出的带中文提示的 IOException message
                    // （我们在 PlayerFactory Interceptor 里对 404/403/412 抛了明确中文消息）
                    val causeMsg = error.cause?.message
                    val translated = when {
                        causeMsg?.startsWith("HTTP 404") == true -> causeMsg
                        causeMsg?.startsWith("HTTP 403") == true ||
                        causeMsg?.startsWith("HTTP 412") == true -> causeMsg
                        error.errorCodeName.contains("BAD_HTTP") -> "视频源不可用，请检查来源或切换其他线路"
                        error.errorCodeName == "ERROR_CODE_IO_UNSPECIFIED" -> "网络连接异常，请检查网络设置"
                        error.errorCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" -> "视频格式不支持"
                        else -> "播放失败：${error.errorCodeName}"
                    }
                    toastState = translated
                }
            })

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
     * @return 解析结果（URL + headers）；解析失败返回 null，错误信息写入 [toastState]
     */
    private suspend fun resolvePlayUrl(item: MediaEntity): TvBoxClient.ResolvedPlayUrl? {
        return runCatching {
            MediaRepository(this@PhonePlayerActivity).resolveTvBoxPlayUrl(item)
        }.fold(
            onSuccess = { it },
            onFailure = {
                val msg = it.message ?: "云盘解析失败"
                Log.w("PhonePlayer", "resolvePlayUrl failed: $msg")
                toastState = msg
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
                .filter { it.uri.isNotBlank() }
                .sortedBy { it.episode }
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

    private fun playEpisode(episode: MediaEntity) {
        if (episode.uri.isBlank()) return
        saveProgress(force = true)

        current = episode
        titleState = displayTitle(episode)
        titleView?.text = titleState
        updateEpisodeButtons()

        // 云盘 scheme 地址需要先解析出真实视频 URL + 请求头再喂给播放器
        lifecycleScope.launch {
            val resolved = resolvePlayUrl(episode)
                ?: return@launch

            // 合并基础头（Emby 鉴权）与云盘解析出的头（Cookie 等）。
            // 对 Emby 的 http 直链：resolved.headers 为空 → mergedHeaders = baseHeaders（不变，不重建）。
            // 对云盘切源：resolved.headers 变化 → mergedHeaders 变化 → 重建播放器带新 Cookie。
            val mergedHeaders = baseHeaders + resolved.headers
            val needRebuild = mergedHeaders != currentPlayHeaders
            val p = if (needRebuild) {
                // 切换了播放源（Cookie 等 headers 变化），必须重建播放器
                player?.release()
                player = null
                currentPlayHeaders = mergedHeaders
                val np = PlayerFactory.createPhonePlayer(this@PhonePlayerActivity, mergedHeaders)
                player = np
                playerView?.player = np
                np.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) playNext()
                        watchBuffering()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseLabel()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        rememberFormat(tracks)
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val causeMsg = error.cause?.message
                        toastState = when {
                            causeMsg?.startsWith("HTTP 404") == true -> causeMsg
                            causeMsg?.startsWith("HTTP 403") == true ||
                            causeMsg?.startsWith("HTTP 412") == true -> causeMsg
                            error.errorCodeName.contains("BAD_HTTP") -> "视频源不可用，请检查来源或切换其他线路"
                            error.errorCodeName == "ERROR_CODE_IO_UNSPECIFIED" -> "网络连接异常，请检查网络设置"
                            else -> "播放失败：${error.errorCodeName}"
                        }
                    }
                })
                np
            } else {
                player ?: return@launch
            }

            p.setMediaItem(MediaItem.fromUri(resolved.url))
            p.prepare()
            if (episode.positionMs > 3_000L) p.seekTo(episode.positionMs)
            p.setPlaybackSpeed(speedState)
            p.playWhenReady = true
        }
    }

    private fun playNext() {
        val index = episodeIndex()
        val next = episodeList.getOrNull(index + 1) ?: return
        if (next.id == current?.id) return
        playEpisode(next)
    }

    private fun playPrevious() {
        val index = episodeIndex()
        val prev = episodeList.getOrNull(index - 1) ?: return
        if (prev.id == current?.id) return
        playEpisode(prev)
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

    private fun orientationLabel(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "小屏" else "全屏"

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
        orientationView?.text = orientationLabel()
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
     * 让「已缓冲 X 秒」是活的而不是进门时的一次性快照。
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
        bufferJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                val cur = player ?: break
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
        val bufferedSec = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L) / 1000
        val bps = videoBitrateBps(p)
        return if (bps > 0) {
            "缓冲中 · 已缓冲 ${bufferedSec}s · 这条流 ${"%.1f".format(bps / 1_000_000.0)} Mbps"
        } else {
            "缓冲中 · 已缓冲 ${bufferedSec}s"
        }
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
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 5_000L) return
        lastSavedAt = now

        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0L } ?: 0L

        lifecycleScope.launch {
            runCatching {
                val repo = MediaRepository(this@PhonePlayerActivity)
                repo.saveProgress(item.id, pos)
                if (dur > 0L) repo.fillDuration(item.id, dur)
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
    }

    override fun onDestroy() {
        saveProgress(force = true)
        playerView?.player = null
        player?.release()
        player = null
        // 退出播放即清掉磁盘缓存，避免旧视频断网仍可播放、长期占用存储空间
        PlayerFactory.clearCache(this)
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

        // 顶部标题仅在控制条里展示；这里只保留拖动进度预览与错误提示
        if (!seekPreview.isNullOrBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
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
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
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
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    buffering,
                    color = Color(0xFFFFC46B),
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
            .clip(RoundedCornerShape(8.dp))
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
                            .clip(RoundedCornerShape(8.dp))
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
                        Button(
                            onClick = { onChange(v, textSize) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) AppColors.Brand else AppColors.SurfaceHi,
                                contentColor = if (selected) Color.White else AppColors.TextSecondary
                            ),
                            modifier = Modifier.height(32.dp)
                        ) { Text("${(v * 100).toInt()}%") }
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
                        Button(
                            onClick = { onChange(bgAlpha, v) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) AppColors.Brand else AppColors.SurfaceHi,
                                contentColor = if (selected) Color.White else AppColors.TextSecondary
                            ),
                            modifier = Modifier.height(32.dp)
                        ) { Text("${v.toInt()}sp") }
                    }
                }
            }
        }
    )
}
