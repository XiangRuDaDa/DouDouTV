package com.tabbit.myvidhub.ui.player.tv

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.tabbit.myvidhub.data.MediaRepository
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 电视端播放器（与手机端完全独立的一套实现）。
 *
 * 交互遵循遥控器心智，不做触摸手势、不做比例切换：
 *   · OK 键：暂停 / 继续，并显示进度条
 *   · 下方向键：弹出功能面板（倍速 / 字幕 / 音轨 / 选集），3 秒无操作自动收起
 *   · 左右方向键：快退 / 快进 10 秒，带时间提示
 *   · 返回键：面板打开时先关面板，再按退出播放
 * 始终全屏，不提供「适应 / 铺满 / 缩放」切换。
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
    private val handler = Handler(Looper.getMainLooper())

    // ---------- 供 Compose 读取的界面状态 ----------
    private var overlayVisible by mutableStateOf(true)
    private var panelTab by mutableStateOf<TvPanelTab?>(null)
    private var toast by mutableStateOf<String?>(null)
    private var seekHint by mutableStateOf<String?>(null)
    private var isPlaying by mutableStateOf(false)
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var speed by mutableStateOf(1f)
    private var titleText by mutableStateOf("")
    private var trackOptions by mutableStateOf<List<TvTrack>>(emptyList())

    /** PlayerView 引用：用于设置字幕样式 */
    private var playerView: PlayerView? = null

    /** 字幕是否处于关闭态。自己记录而不是反查播放器，避免不同 Media3 小版本 API 差异 */
    private var textDisabled by mutableStateOf(false)

    /** 字幕样式：背景透明度 / 字号，与手机端共用同一套偏好 */
    private var subtitleBgAlpha by mutableStateOf(DEFAULT_SUBTITLE_BG_ALPHA)
    private var subtitleTextSize by mutableStateOf(DEFAULT_SUBTITLE_TEXT_SIZE)

    /** 交互计数：每次按键 / 选项切换都 +1，用于重置 3 秒自动隐藏 */
    private var tick by mutableStateOf(0)

    private val progressTicker = object : Runnable {
        override fun run() {
            player?.let { p ->
                positionMs = p.currentPosition.coerceAtLeast(0L)
                durationMs = p.duration.takeIf { it > 0L } ?: 0L
                isPlaying = p.isPlaying
                if (p.isPlaying) saveProgress(force = false)
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        repo = MediaRepository(applicationContext)
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, 0L)

        setContent {
            MyVidHubTheme {
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
            player?.release()
            baseHeaders = headers
            currentPlayHeaders = headers
            player = PlayerFactory.createTvPlayer(this@TvPlayerActivity, headers)

            current = item
            titleText = displayTitle(item)
            play(item, item.positionMs)
            refreshTracks()
            val list = runCatching { repo.episodes(item.seriesKey) }.getOrDefault(emptyList())
            episodes = list.filter { it.seriesKey == item.seriesKey }
                .sortedWith(compareBy({ it.season }, { it.episode }))
            refreshTracks()
        }
    }

    private fun play(item: MediaEntity, startMs: Long) {
        titleText = displayTitle(item)

        // 云盘 scheme 地址需要先解析出真实视频 URL + 请求头再喂给播放器
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val resolved = resolvePlayUrl(item)
                ?: return@launch

            // 合并基础头（Emby 鉴权）与云盘解析出的头（Cookie 等）。
            // 对 Emby 的 http 直链：resolved.headers 为空 → mergedHeaders = baseHeaders（不变，不重建）。
            // 对云盘切源：resolved.headers 变化 → mergedHeaders 变化 → 重建播放器带新 Cookie。
            val mergedHeaders = baseHeaders + resolved.headers
            val needRebuild = mergedHeaders != currentPlayHeaders
            val p = if (needRebuild) {
                player?.release()
                player = null
                currentPlayHeaders = mergedHeaders
                val np = PlayerFactory.createTvPlayer(this@TvPlayerActivity, mergedHeaders)
                player = np
                playerView?.player = np
                np
            } else {
                player ?: return@launch
            }

            p.setMediaItem(MediaItem.fromUri(resolved.url))
            p.prepare()
            if (startMs > 3_000L) p.seekTo(startMs)
            p.setPlaybackSpeed(speed)
            p.playWhenReady = true

            // ===== TV 端补上 Player.Listener（之前完全缺失！播放失败用户什么都看不到）=====
            // onPlayerError 把 Media3 errorCode 翻译成用户能懂的中文，
            // 优先用 OkHttp 层拦截时抛出的带明确提示的 IOException message（404/403/412）。
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val causeMsg = error.cause?.message
                    toast = when {
                        causeMsg?.startsWith("HTTP 404") == true -> causeMsg
                        causeMsg?.startsWith("HTTP 403") == true ||
                        causeMsg?.startsWith("HTTP 412") == true -> causeMsg
                        error.errorCodeName.contains("BAD_HTTP") -> "视频源不可用，请检查来源或切换其他线路"
                        error.errorCodeName == "ERROR_CODE_IO_UNSPECIFIED" -> "网络连接异常，请检查网络设置"
                        error.errorCodeName == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" -> "视频格式不支持"
                        else -> "播放失败：${error.errorCodeName}"
                    }
                }
            })
        }
    }

    /**
     * 解析播放地址：http(s) 直链原样返回，云盘 scheme 地址调用 spider 的 playerContent 解析。
     * @return 解析结果（URL + headers）；解析失败返回 null，错误信息写入 [toast]
     */
    private suspend fun resolvePlayUrl(item: MediaEntity): TvBoxClient.ResolvedPlayUrl? {
        val uri = item.uri
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return TvBoxClient.ResolvedPlayUrl(uri)
        }
        return runCatching {
            repo.resolveTvBoxPlayUrl(item)
        }.fold(
            onSuccess = { it },
            onFailure = {
                toast = it.message ?: "云盘解析失败"
                null
            }
        )
    }

    private fun switchEpisode(ep: MediaEntity) {
        current = ep
        play(ep, ep.positionMs)
        panelTab = null
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
    }

    /** 应用字幕样式（背景透明度 + 字号），与手机端一致 */
    private fun applySubtitleStyle() {
        val sv = playerView?.subtitleView ?: return
        val bg = android.graphics.Color.argb(
            (subtitleBgAlpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0
        )
        sv.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                android.graphics.Color.WHITE,
                bg,
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                bg,
                android.graphics.Color.TRANSPARENT,
                null
            )
        )
        sv.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleTextSize)
    }

    /** 上一集：当前集在列表中的前一个 */
    private fun prevEpisode() {
        val idx = episodes.indexOfFirst { it.id == current?.id }
        if (idx > 0) switchEpisode(episodes[idx - 1])
        else toast = "已经是第一集"
    }

    /** 下一集：当前集在列表中的后一个 */
    private fun nextEpisode() {
        val idx = episodes.indexOfFirst { it.id == current?.id }
        if (idx >= 0 && idx < episodes.size - 1) switchEpisode(episodes[idx + 1])
        else toast = "已经是最后一集"
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 面板打开时，方向键交给 Compose 焦点系统在选项间移动
        if (panelTab != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                panelTab = null
                tick++
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                panelTab = null
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // 面板关闭时直接在 Activity 层处理，不依赖 Compose 焦点
        // —— 否则首次按键可能被 PlayerView 吃掉，需要按两次才能唤起面板
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                panelTab = TvPanelTab.SPEED
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
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress(force = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        saveProgress(force = true)
        player?.release()
        player = null
        // 退出播放即清掉磁盘缓存，避免旧视频断网仍可播放、长期占用存储空间
        PlayerFactory.clearCache(this)
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
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { block() }
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

        // 进度层 3 秒自动隐藏（面板不自动隐藏，由返回键 / 停止操作时关闭）
        LaunchedEffect(tick, panelTab, isPlaying) {
            if (panelTab == null && overlayVisible && isPlaying && seekHint == null) {
                delay(3_000L)
                overlayVisible = false
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
                    }
                },
                update = { it.player = this@TvPlayerActivity.player }
            )

            // ---------- 进度层 ----------
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvProgressBar(
                    title = titleText,
                    position = positionMs,
                    duration = durationMs,
                    playing = isPlaying
                )
            }

            // ---------- 功能面板（下键唤出） ----------
            AnimatedVisibility(
                visible = panelTab != null,
                enter = slideInVertically { it / 3 } + fadeIn(),
                exit = slideOutVertically { it / 3 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TvFunctionPanel(
                    tab = panelTab ?: TvPanelTab.SPEED,
                    speed = speed,
                    tracks = trackOptions,
                    episodes = episodes,
                    currentId = current?.id ?: 0L,
                    subtitleBgAlpha = subtitleBgAlpha,
                    subtitleTextSize = subtitleTextSize,
                    hasEpisodes = episodes.isNotEmpty(),
                    onTabChange = { panelTab = it; tick++ },
                    onSpeed = { cycleSpeed(it); panelTab = null },
                    onTrack = { selectTrack(it); panelTab = null },
                    onEpisode = { switchEpisode(it) },
                    onPrevEpisode = { prevEpisode() },
                    onNextEpisode = { nextEpisode() },
                    onSubtitleStyle = { bg, size ->
                        subtitleBgAlpha = bg
                        subtitleTextSize = size
                        applySubtitleStyle()
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
                        .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }

            toast?.let {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val DEFAULT_SUBTITLE_BG_ALPHA = 0.0f
        private const val DEFAULT_SUBTITLE_TEXT_SIZE = 22f

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
    playing: Boolean
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
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.26f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.Brand)
            )
        }

        Text(
            if (playing) "播放中 · OK 暂停 · ↓ 功能" else "已暂停 · OK 继续 · ↓ 功能",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ---------- 功能面板 ----------

@Composable
private fun TvFunctionPanel(
    tab: TvPanelTab,
    speed: Float,
    tracks: List<TvTrack>,
    episodes: List<MediaEntity>,
    currentId: Long,
    subtitleBgAlpha: Float,
    subtitleTextSize: Float,
    hasEpisodes: Boolean,
    onTabChange: (TvPanelTab) -> Unit,
    onSpeed: (Float) -> Unit,
    onTrack: (TvTrack) -> Unit,
    onEpisode: (MediaEntity) -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onSubtitleStyle: (bgAlpha: Float, textSize: Float) -> Unit
) {
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(tab) { runCatching { tabFocus.requestFocus() } }

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
                TvChip(text = "上一集", selected = false, onClick = onPrevEpisode)
                TvChip(text = "下一集", selected = false, onClick = onNextEpisode)
            }
            TvPanelTab.entries.forEach { t ->
                val selected = t == tab
                TvChip(
                    text = t.label,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(tabFocus) else Modifier,
                    onClick = { onTabChange(t) }
                )
            }
        }

        // 二级选项（Column，字幕样式需要两行）
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
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
                        episodes.take(12).forEach { ep ->
                            TvChip(
                                text = if (ep.episode > 0)
                                    "S${ep.season}E${ep.episode}" else ep.title,
                                selected = ep.id == currentId,
                                onClick = { onEpisode(ep) }
                            )
                        }
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
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
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
            maxLines = 1
        )
    }
}

// ---------- 工具 ----------

private fun displayTitle(item: MediaEntity): String = when {
    item.kind == com.tabbit.myvidhub.data.model.MediaKind.EPISODE && item.episode > 0 ->
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
