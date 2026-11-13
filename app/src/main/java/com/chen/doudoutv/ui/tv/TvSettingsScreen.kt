package com.chen.doudoutv.ui.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.core.AppCache
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.core.VideoCache
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.data.scrape.ScrapeEngine
import com.chen.doudoutv.data.scrape.ScrapeStrategy
import com.chen.doudoutv.player.PlayerFactory
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.components.TmdbDisclaimer
import com.chen.doudoutv.ui.settings.APP_VERSION_LABEL
import com.chen.doudoutv.ui.settings.SettingChoice
import com.chen.doudoutv.ui.settings.forceTvMode
import com.chen.doudoutv.ui.settings.label
import com.chen.doudoutv.ui.settings.setForceTvMode
import com.chen.doudoutv.ui.tv.nf.LocalNfShell
import com.chen.doudoutv.ui.tv.nf.NfScaffold
import com.chen.doudoutv.ui.tv.nf.NfTab
import com.chen.doudoutv.ui.tv.nf.nfBackKey
import com.chen.doudoutv.ui.tv.nf.rememberNfChrome
import com.chen.doudoutv.ui.tv.nf.rememberNfLayerJump
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.AppPalette
import com.chen.doudoutv.ui.theme.Edge
import com.chen.doudoutv.ui.theme.ThemePalettes
import com.chen.doudoutv.ui.theme.TvText
import com.chen.doudoutv.ui.theme.themeBackdrop
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电视端设置页 —— 侧栏 master-detail 的十英尺版本。
 *
 * 左栏是五组入口（状态 / 界面 / 缓存 / 刮削 / 关于），右栏是当前组的详情。
 * 沿左栏上下移动即切换右栏预览；OK / → 进右栏操作，← / Back 回左栏。
 * 「数据来源」本就是一句声明，已并入「刮削」底部（开启 TMDB 时显示）。
 *
 * 控件全部换成十英尺形态：可聚焦区域至少 40dp 高、焦点态白边 + 放大，
 * 行内只留「标题 + 当前值」，档位收进紧凑横排弹窗（[TvChoiceDialog]，选中即生效不关窗）。
 *
 * 焦点路由（规范 R1-R8）：
 *  · 左栏五颗入口用显式 [tvFocusRoute] 上下相连，两端 Cancel 收口；
 *  · 左 ↔ 右是跨功能区，交棒一律延后一帧再 requestFocus（[enterPane] / [exitPane]）；
 *  · 右栏用 Column + verticalScroll 让所有行一开始就全部组合，组内上下走默认几何搜索（R8）；
 *  · 信息组（状态 / 关于）右栏没有可聚焦行，整块容器聚焦，上下键直接滚动；
 *  · 入口抢焦与「回到本页重抢」走 [tvClaimFocusOnEnter]（R7）。
 */
@Composable
fun TvSettingsScreen(
    vm: LibraryViewModel,
    onTab: (NfTab) -> Unit = {},
    onSettings: () -> Unit = {},
    onSources: () -> Unit = {}
) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val libraryCount by vm.libraryCount.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Netflix 布局（LocalNfShell 非空）：设置页像分类 / 搜索一样嵌进常驻顶栏，
    // 内容用 NfScaffold(navInFlow) 给顶栏让位；经典版（null）保持原来的全屏独立页。
    val shell = LocalNfShell.current
    val embedded = shell != null
    val chrome = rememberNfChrome()
    val jumpTo = rememberNfLayerJump()
    // 左栏第一颗按 ↑ 交回顶栏齿轮键（设置页那颗伪 tab）；经典版没有顶栏，到头收口。
    val railTopAnchor = if (embedded) chrome.tabOf(NfTab.Settings) else FocusRequester.Cancel

    var forceTv by remember { mutableStateOf(ctx.forceTvMode()) }
    // 缩略图档位单独留一份状态：Prefs 背后是 SharedPreferences，读它不会触发重组，
    // 只写不读状态的话选中圆点永远停在按下前那一格（手机端设置页同样是这么处理的）
    var posterSize by remember { mutableStateOf(Prefs.posterSize) }
    // 焦点移动效果同理
    var cardMotion by remember { mutableStateOf(Prefs.tvCardMotion) }
    var tmdbOn by remember { mutableStateOf(Prefs.tmdbEnabled) }
    var tmdbToken by remember { mutableStateOf(Prefs.tmdbReadToken) }
    var engineTick by remember { mutableStateOf(0) }
    // 只在记忆化里做纯计算（读凭据推导），写全局状态放到副作用里
    val activeEngine = remember(engineTick) { ScrapeStrategy.resolve() }
    LaunchedEffect(engineTick) { ScrapeStrategy.refresh() }

    // 条数查的是 scraped_meta 表，不能在组合期同步读库；下面的 resumeTick effects 里填
    var cacheCount by remember { mutableStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    var imageCache by remember { mutableStateOf(0L) }
    // 视频缓存的档位与占用：同上，写 Prefs 不会触发重组，选中态得自己留一份
    var videoTier by remember { mutableStateOf(Prefs.videoCacheTier) }
    var videoCache by remember { mutableStateOf(0L) }
    var skipIntro by remember { mutableStateOf(Prefs.skipIntroEnabled) }

    // 档位收进弹窗：记住开的是哪一组，以及是哪一行叫出来的（关掉后焦点要还给那一行）
    var choice by remember { mutableStateOf<Pair<SettingChoice, FocusRequester>?>(null) }
    val posterFocus = remember { FocusRequester() }
    val motionFocus = remember { FocusRequester() }
    val themeFocus = remember { FocusRequester() }
    val skipIntroFocus = remember { FocusRequester() }

    // 侧栏 master-detail：selected 是当前预览/展开的分组；railFocus 是左栏五颗入口；
    // paneFirstFocus 是右栏第一颗可聚焦元件（信息组没有可聚焦行，落在可滚动的容器上）。
    var selected by remember { mutableIntStateOf(0) }
    val railFocus = remember { List(5) { FocusRequester() } }
    val paneFirstFocus = remember { FocusRequester() }
    val paneScroll = rememberScrollState()
    val resumeTick = tvResumeTick()
    // 进页 / 回到本页都把焦点交回左栏第一颗（状态），右栏跟着预览（R7）。
    tvClaimFocusOnEnter(
        resumeTick,
        railFocus[0],
        scrollToTop = { paneScroll.scrollTo(0) }
    )
    // 图片缓存的体积只在进页读一次会停在旧值上：回到这一页（resume tick）再读一遍。
    // 读体积是磁盘遍历（缓存里可能几万个条目），放 IO 线程，不在主线程上按文件数卡帧。
    LaunchedEffect(resumeTick) {
        imageCache = withContext(Dispatchers.IO) { AppCache.sizeBytes(ctx) }
        videoCache = withContext(Dispatchers.IO) { VideoCache.usedBytes() }
        cacheCount = vm.scrapeCacheSize()
    }

    // 侧栏 ↔ 详情的交棒：跨功能区跳焦必须延后一帧（同步跳会被当成本帧输入吞掉），
    // 新按键到来先作废上一趟还没落地的交棒，避免焦点跳两格或回弹（参考 nfHandover）。
    val handover = remember { mutableStateOf<Job?>(null) }
    val enterPane: () -> Unit = {
        handover.value?.cancel()
        handover.value = scope.launch { withFrameNanos { }; tvMoveFocus(paneFirstFocus) }
    }
    val exitPane: () -> Unit = {
        val target = railFocus[selected]
        handover.value?.cancel()
        handover.value = scope.launch { withFrameNanos { }; tvMoveFocus(target) }
    }

    val titles = listOf("状态", "界面", "缓存", "刮削", "关于")
    val icons = listOf(
        Icons.Outlined.Insights, Icons.Outlined.Tune, Icons.Outlined.Storage,
        Icons.Outlined.AutoAwesome, Icons.Outlined.Info
    )
    val summaries = listOf(
        "${sources.size} 来源 · $libraryCount 条",
        "${AppPalette.current.label} · ${posterSize.label()} · ${cardMotion.label()}",
        if (videoCache > 0) "${videoTier.label} · 已用 ${PlayerFactory.formatBytes(videoCache)}" else videoTier.label,
        if (tmdbOn) "TMDB 已开启" else "TMDB 已关闭",
        APP_VERSION_LABEL
    )
    val descs = listOf(
        "媒体库与来源的当前规模。",
        "布局、焦点动效与主题色，改动即时生效，档位按 OK 展开选择。",
        "看过的内容在本机留一份：往回拖进度、重看同一集不再重复下载，看完的自动删。",
        "自动补全海报、评分、简介与演职员。",
        "版本、功能与实现说明。"
    )

    // 光晕画在不滚动的外层：挂在 verticalScroll 节点上会跟内容一起滚走，滚到底就只剩纯黑。
    // 背景与顶栏让位由外壳负责：嵌入时 NfScaffold 画 themeBackdrop 并在顶部留出导航条高度，
    // 经典版（无外壳）自己包一层 themeBackdrop，保持原来的全屏独立页。
    val settingsBody: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 电视（≥700dp）走侧栏 master-detail；手机强制 TV 是窄屏（~384dp）：左栏写死 300dp
            // 会把右栏挤成一条缝，切组又靠遥控器焦点（触摸不驱动焦点），所以窄屏换纯触摸布局——
            // 顶部横向分组 tab（手指点选直接切组）+ 下方单列触摸滚动。行 / 弹窗 / 存储键两套完全共用。
            val wide = maxWidth >= 700.dp
            Column(Modifier.fillMaxSize()) {
                if (!wide) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)
                    ) {
                        Text(
                            "设置",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TvText.Title,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            titles.forEachIndexed { i, title ->
                                TvSettingsTab(title = title, active = selected == i, onClick = { selected = i })
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Edge.Hairline))
                }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (wide) {
            // ---------- 左栏：分组入口（master） ----------
            Column(
                Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(Color(0xFF141414), Color(0xFF101010))))
                    .padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 20.dp)
                    // 返回键交回顶栏齿轮键只挂在左栏这一列：它和右栏是兄弟节点，
                    // 焦点在右栏时这条预览键链根本不经过左栏，右栏自己的返回（退出到左栏）先吃掉。
                    // 挂到外层 Row 会因为 onPreviewKeyEvent 自顶向下预览，抢在右栏之前把返回吃掉。
                    .then(if (embedded) Modifier.nfBackKey { jumpTo(chrome.tabOf(NfTab.Settings)) } else Modifier)
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    titles.forEachIndexed { i, title ->
                        TvSettingsRailItem(
                            title = title,
                            summary = summaries[i],
                            icon = icons[i],
                            selected = selected == i,
                            focusRequester = railFocus[i],
                            up = if (i > 0) railFocus[i - 1] else railTopAnchor,
                            down = if (i < titles.lastIndex) railFocus[i + 1] else FocusRequester.Cancel,
                            onFocused = { selected = i },
                            onEnter = enterPane
                        )
                    }
                }
                Text(
                    "$APP_VERSION_LABEL · Media3 内核",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvText.Muted,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // 分隔线
            Box(Modifier.width(1.dp).fillMaxHeight().background(Edge.Hairline))
                    }

            // ---------- 右栏：当前分组详情（detail） ----------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 仅电视劫持方向/返回键做焦点导航；手机触摸必须走系统返回，
                    // 否则 KEYCODE_BACK 被这里吞掉只把焦点弹回左栏，页面永远退不出。
                    .then(
                        if (wide) Modifier.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key.nativeKeyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK -> { exitPane(); true }
                                // 信息组（状态 / 关于）右栏没有可聚焦行，整块容器聚焦，上下键直接滚动
                                KeyEvent.KEYCODE_DPAD_UP ->
                                    if (selected == 0 || selected == 4) {
                                        scope.launch { paneScroll.animateScrollTo(paneScroll.value - 220) }; true
                                    } else false
                                KeyEvent.KEYCODE_DPAD_DOWN ->
                                    if (selected == 0 || selected == 4) {
                                        scope.launch { paneScroll.animateScrollTo(paneScroll.value + 220) }; true
                                    } else false
                                else -> false
                            }
                        } else Modifier
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(paneScroll)
                        .then(
                            if (wide && (selected == 0 || selected == 4))
                                Modifier.focusRequester(paneFirstFocus).focusable()
                            else Modifier
                        )
                        .padding(horizontal = if (wide) 40.dp else 20.dp, vertical = if (wide) 28.dp else 16.dp)
                ) {
                    Text(
                        titles[selected],
                        style = MaterialTheme.typography.displaySmall,
                        color = TvText.Title,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(descs[selected], style = MaterialTheme.typography.bodyMedium, color = TvText.Meta)
                    Spacer(Modifier.height(20.dp))
                    Column(
                        Modifier.widthIn(max = 720.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        when (selected) {
                            0 -> {
                                TvStatRow("来源数量", "${sources.size}")
                                TvStatRow("媒体条目", "$libraryCount")
                                TvStatRow(
                                    "Emby 已登录",
                                    "${sources.count { it.kind == SourceKind.EMBY && it.token.isNotBlank() }}"
                                )
                            }

                            1 -> {
                                // 强制 TV 模式只在窄屏（手机强制 TV）露出：真 TV 上这颗开关没意义，
                                // 但手机一旦开了强制 TV，整个 App 走 TV UI、手机设置页进不去，
                                // 必须在这里留一个切回手机模式的出口，否则只能清数据才能退出。
                                if (!wide) {
                                    TvSettingRow(
                                        title = "强制 TV 模式",
                                        hint = "手机上开启可模拟 TV 界面布局，重启后生效",
                                        focusRequester = paneFirstFocus,
                                        onClick = {
                                            forceTv = !forceTv
                                            ctx.setForceTvMode(forceTv)
                                        },
                                        value = if (forceTv) "已开启" else "已关闭",
                                        accent = forceTv
                                    )
                                }
                                // 本组第一颗可聚焦行要接住 enterPane 的 paneFirstFocus：
                                // 窄屏是上面的强制 TV 行；宽屏隐藏它后，第一颗变成缩略图大小。
                                val posterAnchor = if (wide) paneFirstFocus else posterFocus
                                // 档位收进弹窗：右栏只留「当前值」，OK 键才展开对应的那一组
                                TvSettingRow(
                                    title = "缩略图大小",
                                    hint = null,
                                    focusRequester = posterAnchor,
                                    value = posterSize.label(),
                                    onClick = { choice = SettingChoice.PosterSize to posterAnchor }
                                )
                                TvSettingRow(
                                    title = "移动效果",
                                    hint = "焦点卡怎么动：只有图片放大，或整卡上抬",
                                    focusRequester = motionFocus,
                                    value = cardMotion.label(),
                                    onClick = { choice = SettingChoice.CardMotion to motionFocus }
                                )
                                TvSettingRow(
                                    title = "主题色",
                                    hint = null,
                                    focusRequester = themeFocus,
                                    value = AppPalette.current.label,
                                    onClick = { choice = SettingChoice.Theme to themeFocus }
                                )
                                TvSettingRow(
                                    title = "自动跳过片头",
                                    hint = "开启后剧集详情页与播放器显示「跳过片头」按钮；关闭则不显示、也不再自动跳过",
                                    focusRequester = skipIntroFocus,
                                    value = if (skipIntro) "已开启" else "已关闭",
                                    accent = skipIntro,
                                    onClick = {
                                        skipIntro = !skipIntro
                                        Prefs.skipIntroEnabled = skipIntro
                                    }
                                )
                            }

                            2 -> {
                                TvSettingRow(
                                    title = "视频缓存容量上限",
                                    hint = null,
                                    focusRequester = paneFirstFocus,
                                    value = videoTier.label,
                                    onClick = { choice = SettingChoice.VideoTier to paneFirstFocus }
                                )
                                TvSettingAction(
                                    title = "视频缓存占用：${PlayerFactory.formatBytes(videoCache)}",
                                    hint = "看过的内容在本机留一份：往回拖进度、重看同一集不再重复下载；看完的自动删",
                                    actionLabel = "清空",
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                VideoCache.clearAll()
                                                videoCache = VideoCache.usedBytes()
                                            }
                                        }
                                    }
                                )
                                TvSettingAction(
                                    title = "图片缓存占用：${PlayerFactory.formatBytes(imageCache)}",
                                    hint = "封面与海报的本地副本，清除后浏览时重新下载",
                                    actionLabel = "清除",
                                    onClick = {
                                        // 清缓存与读体积都是磁盘操作，都不该压在按钮点击里跑主线程
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AppCache.clear(ctx)
                                                imageCache = AppCache.sizeBytes(ctx)
                                            }
                                        }
                                    }
                                )
                            }

                            3 -> {
                                TvSettingRow(
                                    title = "使用 TMDB 刮削",
                                    hint = "开启后自动补全海报、评分、简介与演职员",
                                    focusRequester = paneFirstFocus,
                                    onClick = {
                                        tmdbOn = !tmdbOn
                                        Prefs.tmdbEnabled = tmdbOn
                                        engineTick++
                                    },
                                    value = if (tmdbOn) "已开启" else "已关闭",
                                    accent = tmdbOn
                                )

                                if (tmdbOn) {
                                    TvSettingField(
                                        label = "Read Access Token（v4）",
                                        value = tmdbToken,
                                        placeholder = "粘贴以 eyJ 开头的令牌",
                                        onValueChange = {
                                            tmdbToken = it
                                            Prefs.tmdbReadToken = it
                                            // 填了令牌就自动打开总开关，避免「密钥填了但刮削没生效」的困惑
                                            if (it.isNotBlank() && !tmdbOn) {
                                                tmdbOn = true
                                                Prefs.tmdbEnabled = true
                                            }
                                            engineTick++
                                        }
                                    )
                                    Text(
                                        "TMDB 账号 → Settings → API → Read Access Token，整串复制。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TvText.Meta
                                    )

                                    val tmdbActive = activeEngine == ScrapeEngine.TMDB
                                    Text(
                                        when {
                                            tmdbActive -> "当前方案：TMDB 刮削"
                                            Prefs.tmdbReadToken.isBlank() -> "当前方案：本地元数据（尚未填写令牌）"
                                            else -> "当前方案：本地元数据"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (tmdbActive) AppColors.Brand else TvText.Body
                                    )
                                }

                                // 清除刮削缓存不依赖 tmdbOn 状态：关掉开关后旧条目还在，用户照样可能想清掉。
                                TvSettingAction(
                                    title = "刮削缓存",
                                    hint = "已记住 $cacheCount 部影片，同名同年不再联网重刮",
                                    actionLabel = if (clearing) "清除中…" else "清除缓存",
                                    onClick = {
                                        if (clearing) return@TvSettingAction
                                        clearing = true
                                        scope.launch {
                                            vm.clearScrapeCache()
                                            cacheCount = vm.scrapeCacheSize()
                                            clearing = false
                                        }
                                    }
                                )

                                // 数据来源并入刮削：它本就是一句声明，开启 TMDB 时挂在底部。
                                if (tmdbOn) {
                                    Spacer(Modifier.height(4.dp))
                                    TmdbDisclaimer()
                                }
                            }

                            else -> {
                                Text(
                                    APP_VERSION_LABEL,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TvText.Title,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TvAboutBlock(
                                    "功能介绍",
                                    "海报墙与沉浸式首页，继续观看断点续播、单集播完自动连播。\n" +
                                        "Emby 直连原码流、TVBox 多线路聚合，网盘直链播放与本机磁盘缓存。\n" +
                                        "可选 TMDB 刮削补全海报与简介；手机与电视同一安装包，扫码即可维护数据源。"
                                )
                                TvAboutBlock(
                                    "个人开发免责",
                                    "本应用为个人业余项目，按「现状」提供，不作任何明示或暗示的担保。\n" +
                                        "不保证持续更新，也不保证在所有设备上完全兼容。\n" +
                                        "媒体库、观看进度与收藏均存储在本机，卸载应用即清除，请自行备份重要数据。"
                                )
                                TvAboutBlock(
                                    "内容版权",
                                    "播放器为自研实现，本应用不内置、不存储、也不提供任何影视资源。\n" +
                                        "仅用于播放你自有或已获授权的内容，相关版权与合法性由内容来源及使用者负责。"
                                )
                                TvAboutBlock(
                                    "开源与致谢",
                                    "代码为本项目原创，可自由使用、修改与分发。\n" +
                                        "TVBox 侧仅参考 CatVod / TVBox 公开配置规范与 FongMi/TV 的行为约定，未复制其源码。\n" +
                                        "感谢 Media3 / ExoPlayer、Jetpack Compose、Room、Coil、OkHttp、jsoup 等开源项目（Apache-2.0 / MIT）。"
                                )
                            }
                        }
                    }
                }
            }
        }
            }

        // ---------- 档位弹窗：一次只开一个 ----------
        val opened = choice
        if (opened != null) {
            val (which, trigger) = opened
            val dismiss: () -> Unit = {
                choice = null
                // 电视：弹窗是独立窗口，关掉后这一页焦点是空的，还给叫出它的那一行，
                // 接着按方向键就在原位。手机触摸不驱动焦点，归还反而在行上留一道白边，故仅电视归还。
                if (wide) {
                    scope.launch {
                        withFrameNanos { }
                        runCatching { trigger.requestFocus() }
                    }
                }
            }
            when (which) {
                SettingChoice.PosterSize -> TvChoiceDialog(
                    title = "缩略图大小",
                    options = Prefs.PosterSize.values().toList(),
                    label = { it.label() },
                    selected = posterSize,
                    dot = null,
                    hint = null,
                    onPick = { Prefs.posterSize = it; posterSize = it },
                    onDismiss = dismiss
                )

                SettingChoice.CardMotion -> TvChoiceDialog(
                    title = "移动效果",
                    options = Prefs.CardMotion.values().toList(),
                    label = { it.label() },
                    selected = cardMotion,
                    dot = null,
                    hint = "放大：只有图片放大 5%，片名一动不动。上抬：整卡不放大，往上抬 12dp。" +
                        "两档都留在行内 14dp 余量里，超出会被容器裁掉一条边。",
                    onPick = { Prefs.tvCardMotion = it; cardMotion = it },
                    onDismiss = dismiss
                )

                SettingChoice.Theme -> TvChoiceDialog(
                    title = "主题色",
                    options = ThemePalettes,
                    label = { it.label },
                    selected = AppPalette.current,
                    dot = { it.brand },
                    hint = "只换强调色与背景氛围，文字层级和中性底色保持不变。",
                    onPick = { AppPalette.select(it.id) },
                    onDismiss = dismiss
                )

                SettingChoice.VideoTier -> TvChoiceDialog(
                    title = "视频缓存容量上限",
                    options = Prefs.VideoCacheTier.values().toList(),
                    label = { it.label },
                    selected = videoTier,
                    dot = null,
                    hint = "只给看过的网盘内容留一份，看完的自动删，超出上限先清最久没看的。",
                    onPick = { t ->
                        val previous = Prefs.videoCacheTier
                        if (previous != t) {
                            Prefs.videoCacheTier = t
                            videoTier = t
                            // 降档（含关闭）要清空目录，占用数字跟着重读；都在 IO 线程做
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    VideoCache.applyTier(t.maxBytes, previous.maxBytes)
                                    videoCache = VideoCache.usedBytes()
                                }
                            }
                        }
                    },
                    onDismiss = dismiss
                )
            }
        }
        }
    }
    if (embedded) {
        NfScaffold(
            vm = vm,
            current = NfTab.Settings,
            chrome = chrome,
            navInFlow = true,
            belowFocusRequester = railFocus[0],
            onTab = onTab,
            onSettings = onSettings,
            onManageSources = onSources
        ) { settingsBody() }
    } else {
        Box(Modifier.fillMaxSize().themeBackdrop()) { settingsBody() }
    }
}

/**
 * 电视端的档位弹窗：档位铺成紧凑的横向网格，一屏看完，OK 键选中即生效（不关窗）。
 *
 * 与首页来源/线路选择器同一形态（Compose Dialog + tvMoveFocus 抢焦，规范 R1/R5）：
 * 独立窗口天然把焦点关在弹窗里，不会逃逸到下面的设置行，Back 由窗口自己处理。
 * 默认焦点落在当前档位，不是第一格。色板带色点（dot）一行 7 颗，其余档位一行 3 颗。
 */
@Composable
private fun <T> TvChoiceDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    selected: T,
    dot: ((T) -> Color)?,
    hint: String?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    val focusIndex = options.indexOfFirst { it == selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        // 等一帧：Dialog 内容要先组合完成，requester 才挂得上
        withFrameNanos { }
        tvMoveFocus(firstFocus)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 手机窄屏：弹窗占满可用宽（左右各留 20dp），档位每行颗数收小保证手指点得中；
            // 电视：取半屏宽（520–860dp）。色板档一行 4 颗（窄屏 3 颗）——
            // 名字里带拉丁文（NF红 / Plex 橙）比纯中文宽，一行 7 颗会把名字截断。
            val compact = maxWidth < 700.dp
            val dialogWidth = if (compact) maxWidth - 40.dp else (maxWidth * 0.5f).coerceIn(520.dp, 860.dp)
            val perRow = if (dot != null) (if (compact) 3 else 4) else (if (compact) 2 else 3)
            Column(
                Modifier
                    .width(dialogWidth)
                    .clip(Radius.Lg)
                    .background(AppColors.SurfaceHi)
                    .border(1.dp, Edge.HairlineStrong, Radius.Lg)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TvText.Title,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (compact) "点选即生效 · 返回关闭" else "OK 选中 · Back 关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvText.Muted
                    )
                }
                var index = 0
                options.chunked(perRow).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { option ->
                            val i = index++
                            TvChoiceOption(
                                text = label(option),
                                selected = option == selected,
                                dot = dot?.invoke(option),
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (i == focusIndex) Modifier.focusRequester(firstFocus) else Modifier),
                                onClick = { onPick(option) }
                            )
                        }
                        // 末行不足补等宽空位，保证每颗档位一样宽
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = TvText.Meta)
                }
            }
        }
    }
}

/** 弹窗里的一颗档位：焦点态白边 + 放大，选中态品牌描边 + 打勾；色板档带色点 */
@Composable
private fun TvChoiceOption(
    text: String,
    selected: Boolean,
    dot: Color?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier
            .height(if (dot != null) 96.dp else 60.dp)
            .clip(Radius.Md)
            .background(
                when {
                    focused -> AppColors.SurfaceRaise
                    selected -> AppColors.Brand.copy(alpha = 0.22f)
                    else -> AppColors.Bg.copy(alpha = 0.5f)
                }
            )
            .tvFocusRing(focused)
            .then(
                when {
                    focused -> Modifier
                    selected -> Modifier.border(1.5.dp, AppColors.Brand, Radius.Md)
                    else -> Modifier.border(1.dp, Edge.Hairline, Radius.Md)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(dot)
                    .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (focused || selected) TvText.Title else TvText.Body,
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused || selected) TvText.Title else TvText.Body,
                    fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.Check, null,
                        tint = if (focused) TvText.Title else AppColors.Brand,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 左栏的一颗分组入口（master）。
 *
 * 焦点态：白边 + 轻微放大 + 抬 zIndex + 抬亮填充，与 TV 端其它可聚焦元件一致；
 * 选中态（焦点在右栏时）：抬亮填充 + 品牌色左侧条，不画白边 —— 白边只表示「焦点在这」。
 * 上下走显式路由（[tvFocusRoute]，两端 Cancel 收口），OK / → 进右栏（[onEnter]）。
 * 拿到焦点即把 selected 切到本组，右栏跟着预览（master-detail）。
 */
@Composable
private fun TvSettingsRailItem(
    title: String,
    summary: String,
    icon: ImageVector,
    selected: Boolean,
    focusRequester: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    onFocused: () -> Unit,
    onEnter: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val brand = AppColors.Brand
    val active = focused || selected
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRoute(up = up, down = down)
            .focusRequester(focusRequester)
            .clip(Radius.Md)
            .background(if (active) AppColors.SurfaceHi else Color.Transparent)
            .tvFocusRing(focused)
            .drawWithContent {
                if (selected && !focused) {
                    drawRect(brand, topLeft = Offset.Zero, size = Size(4.dp.toPx(), size.height))
                }
                drawContent()
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onEnter)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    onEnter(); true
                } else false
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (active) AppColors.BrandSoft else TvText.Meta,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) TvText.Title else TvText.Body,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
            )
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) AppColors.BrandSoft else TvText.Muted,
                maxLines = 1
            )
        }
        if (focused) {
            Icon(Icons.Outlined.ChevronRight, null, tint = TvText.Title, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * 窄屏（手机强制 TV）顶部的分组 tab：纯触摸点选，onClick 直接把 selected 切到本组。
 *
 * 不复用左栏入口那颗——左栏靠遥控器焦点切组（onFocused），触摸不驱动焦点，点了不切组。
 * 这里选中态用品牌描边 + 抬亮填充表达，不画焦点白边（手机没有焦点常态）。
 */
@Composable
private fun TvSettingsTab(title: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(Radius.Pill)
            .background(if (active) AppColors.Brand.copy(alpha = 0.22f) else AppColors.SurfaceHi)
            .then(
                if (active) Modifier.border(1.5.dp, AppColors.Brand, Radius.Pill)
                else Modifier.border(1.dp, Edge.Hairline, Radius.Pill)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) TvText.Title else TvText.Body,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 关于页的一段：抬亮小面板 + 品牌浅色小标题 + 正文，段间靠内容列的 spacedBy 拉开 */
@Composable
private fun TvAboutBlock(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.Md)
            .background(AppColors.Surface)
            .border(1.dp, Edge.Hairline, Radius.Md)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.BrandSoft,
            fontWeight = FontWeight.SemiBold
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TvText.Body)
    }
}

/** 纯信息行：不可聚焦，遥控器上下移动时会被自然跳过 */
@Composable
private fun TvStatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceHi, Radius.Md)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TvText.Body,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TvText.Title)
    }
}

/**
 * 可聚焦的设置行：整行是焦点单位，右侧回显当前值，OK 键直接切换。
 *
 * 焦点态用「白边 + 轻微放大 + 抬 zIndex」，与 TvPillButton / TvPosterCard 一致：
 * 电视上涟漪和阴影看不见，只有描边和尺寸变化能在三米外读出来。
 */
@Composable
private fun TvSettingRow(
    title: String,
    hint: String?,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    accent: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.Md)
            .background(if (focused) AppColors.SurfaceHi else AppColors.Bg.copy(alpha = 0.35f))
            .tvFocusRing(focused)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onClick != null,
                onClick = { onClick?.invoke() }
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TvText.Title)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvText.Meta
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = if (accent) AppColors.Brand else TvText.Body
            )
        }
        trailing?.invoke(this)
    }
}

/** 带操作按钮的行：标题在左、主操作在右，焦点落在按钮上 */
@Composable
private fun TvSettingAction(
    title: String,
    hint: String?,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TvText.Title
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvText.Meta,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        TvPillButton(text = actionLabel, primary = false, size = TvButtonSize.Md, onClick = onClick)
    }
}

/**
 * 令牌输入行。
 *
 * 电视上没有物理键盘可用时，聚焦输入框会拉起系统软键盘（与来源页的接口地址输入同一条路径）；
 * 60dp 高的框是为遥控器光标准备的，矮了看不清插入点。
 */
@Composable
private fun TvSettingField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = TvText.Title)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = TvText.Meta) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvText.Title),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            shape = Radius.Md,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = AppColors.Bg,
                focusedContainerColor = AppColors.Bg,
                unfocusedBorderColor = AppColors.SurfaceHi,
                focusedBorderColor = AppColors.Brand,
                cursorColor = AppColors.Brand,
                focusedTextColor = TvText.Title,
                unfocusedTextColor = TvText.Title
            )
        )
    }
}
