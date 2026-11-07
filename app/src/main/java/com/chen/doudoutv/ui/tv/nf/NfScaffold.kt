package com.chen.doudoutv.ui.tv.nf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.TvText
import com.chen.doudoutv.ui.theme.themeBackdrop
import com.chen.doudoutv.ui.tv.tvMoveFocus

/** 顶栏收色带的高度：导航条本身透明，文字要有一层由页面底色淡出的衬底才读得清 */
private val NfNavScrimHeight = 116.dp

/**
 * 常驻顶栏的外壳状态。
 *
 * 顶栏以前跟着页面一起放在 AnimatedContent 里，切 tab 时整条导航条被销毁重建：
 * 用户看到的就是「顶栏也跟着闪一下 / 滑一下」，焦点还得靠 NfTabSwitch 标记重新抢回来。
 * 现在导航条提到转场之外常驻一份，只有内容区参与动画。
 *
 * 四页的装配参数（↓ 落点、可见性、淡出曲线、是否流内）按 tab 各存一份：
 * 转场期间新旧两页同时在组合里，各写各的键，顶栏只读当前路由那一页的。
 */
class NfShellState {
    val chrome = NfChromeState()

    /** 导航条实测高度：流内装配的页面用它给内容让位。初值取常态量得的高度，首帧不跳 */
    var navHeight by mutableStateOf(56.dp)

    /**
     * 每个 tab 一份顶栏装配参数。
     *
     * 用普通 map 而不是状态 map：这份 map 只负责「找到当前 tab 那份参数」，
     * 真正会变的字段在 [NfNavConfig] 里各自是状态。页面写、顶栏读，两边都不该因为
     * map 的内容换了就整页重组 —— 原来每重组一次就 new 一个 NfNavConfig 塞进去，
     * 顶栏被拖进重组，tab 的下划线弹簧和缩放从头再跑一遍，切页时顶栏就抖一下。
     */
    private val configs = mutableMapOf<NfTab, NfNavConfig>()

    /** 槽位新建时自增：顶栏靠它发现「这一页刚把参数交上来」 */
    var configEpoch by mutableStateOf(0)
        private set

    /** 页面侧：拿到（或建出）这一 tab 的参数槽位 */
    internal fun configSlot(tab: NfTab): NfNavConfig {
        configs[tab]?.let { return it }
        val slot = NfNavConfig()
        configs[tab] = slot
        configEpoch++
        return slot
    }

    /** 顶栏侧：当前这一 tab 的参数槽位（页面还没交上来就是 null） */
    internal fun configOf(tab: NfTab): NfNavConfig? {
        // 读一次 epoch：槽位新建的那一刻它自增，这里读到新值才会被叫醒重画
        val epoch = configEpoch
        return if (epoch == 0) null else configs[tab]
    }
}

/**
 * 一页交给常驻顶栏的装配参数。
 *
 * 这些参数页面每次重组都会重新交一遍。只有真正变了的槽位才写回：
 * 写状态就是叫醒读它的顶栏，顶栏一重组，tab 的下划线弹簧和缩放就从头再跑一遍。
 */
internal class NfNavConfig {
    /** 导航条按 ↓ 的落点 */
    var below: FocusRequester? by mutableStateOf(null)
        private set

    /** 顶栏（含其后面的收色带）是否出现在组合里 */
    var visible: Boolean by mutableStateOf(true)
        private set

    /** 顶栏是不是流内装配（内容区要给它让出高度） */
    var inFlow: Boolean by mutableStateOf(false)
        private set

    /** 顶栏有没有透明度曲线：有 / 没有之间切换时顶栏要重组一次换掉这层 graphicsLayer */
    var hasAlpha: Boolean by mutableStateOf(false)
        private set

    /** 顶栏不透明度来源：连续量，只能在 draw phase 读，所以是普通字段而不是状态 */
    var alpha: (() -> Float)? = null
        private set

    /** 焦点落到顶栏时的补偿动作（把内容滚回顶部） */
    var onFocus: (() -> Unit)? = null
        private set

    fun update(
        below: FocusRequester?,
        visible: Boolean,
        alpha: (() -> Float)?,
        onFocus: (() -> Unit)?,
        inFlow: Boolean
    ) {
        if (this.below !== below) this.below = below
        if (this.visible != visible) this.visible = visible
        if (this.inFlow != inFlow) this.inFlow = inFlow
        if (this.hasAlpha != (alpha != null)) this.hasAlpha = alpha != null
        // 这两个每次重组都是新对象，但都在 draw / 事件回调里现读现用，
        // 不需要为了换一个 lambda 把顶栏叫醒
        this.alpha = alpha
        this.onFocus = onFocus
    }
}

val LocalNfShell = staticCompositionLocalOf<NfShellState?> { null }

@Composable
fun rememberNfShell(): NfShellState = remember { NfShellState() }

/**
 * 一套页面的公共外壳状态：导航条上的焦点锚点 + 抽屉开关。
 *
 * 抽屉开关放在这里而不是 NfScaffold 内部，是因为页面内容也要用它：
 * TVBox 没选线路时，分类页空态要能直接打开抽屉，而不是让用户先退回导航条。
 */
class NfChromeState {
    /** 作用域胶囊（来源 › 线路） */
    val scopeFocus = FocusRequester()

    private val firstTabFocus = FocusRequester()
    private val browseTabFocus = FocusRequester()
    private val mineTabFocus = FocusRequester()
    private val searchTabFocus = FocusRequester()

    /**
     * 本页在导航条上的那颗 tab —— 内容层按 ↑ 的回交对象。
     * 每页只多这一层，到顶不再往上逃逸（规范 A 层级表）。
     */
    fun tabOf(tab: NfTab): FocusRequester = when (tab) {
        NfTab.Home -> firstTabFocus
        NfTab.Browse -> browseTabFocus
        NfTab.Mine -> mineTabFocus
        NfTab.Search -> searchTabFocus
    }

    var openScopeDrawer by mutableStateOf(false)

    /**
     * 焦点此刻是不是落在导航条（含其子节点）上。
     * 首页「滚过轮播就撤顶栏」，但顶栏被撤时若焦点正在它上面会直接把焦点弄丢，
     * 所以顶栏可见条件是「没滚过轮播 或 焦点就在顶栏上」。
     */
    var navFocused by mutableStateOf(false)
}

@Composable
fun rememberNfChrome(): NfChromeState {
    val own = remember { NfChromeState() }
    // 常驻外壳在时四页共用同一份锚点：顶栏只有一份，tab 的 FocusRequester 也就只有一份，
    // 切页时焦点停在原来那颗标签上，不需要重新抢
    return LocalNfShell.current?.chrome ?: own
}

/** 当前生效来源 + 线路名（Emby 没有线路概念，右半段为空） */
private class NfScope(
    val sources: List<SourceEntity>,
    val sourceName: String,
    val siteName: String?
)

@Composable
private fun rememberNfScope(vm: LibraryViewModel): NfScope {
    val sources by vm.enabledSources.collectAsStateWithLifecycle()
    val selectedSource = sources.firstOrNull { it.id == vm.homeSourceId } ?: sources.firstOrNull()
    val namedSites = selectedSource?.id?.let { vm.homeSitesBySource[it] }.orEmpty()
        .filter { it.key.isNotBlank() }
    val siteName = if (selectedSource?.kind == SourceKind.TVBOX) {
        namedSites.firstOrNull { it.key == vm.homeSiteKey }
            ?.name?.substringBeforeLast("（")?.trim()?.takeIf { it.isNotBlank() }
    } else null
    // 冷启动时没人选过来源，线路列表一直是空的 —— 主动为当前生效来源拉一次（内部有缓存）
    LaunchedEffect(selectedSource?.id) {
        selectedSource?.id?.let { vm.ensureHomeSites(it) }
    }
    return NfScope(
        sources = sources,
        sourceName = selectedSource?.name?.takeIf { it.isNotBlank() } ?: "未选择来源",
        siteName = siteName
    )
}

/** 作用域抽屉：来源 › 线路 双列选择 */
@Composable
private fun NfScopeDrawerHost(
    vm: LibraryViewModel,
    scope: NfScope,
    chrome: NfChromeState,
    onManageSources: () -> Unit
) {
    if (!chrome.openScopeDrawer) return
    NfScopeDrawer(
        sources = scope.sources,
        selectedSourceId = vm.effectiveHomeSourceId(),
        selectedSiteKey = vm.homeSiteKey,
        sitesOf = { id -> vm.homeSitesBySource[id].orEmpty() },
        loadingSites = vm.homeSitesLoading,
        onEnsureSites = { vm.ensureHomeSites(it) },
        onPickSource = { vm.selectHomeSource(it) },
        onPickSite = { key, site -> vm.selectHomeSite(key, site) },
        onManage = {
            chrome.openScopeDrawer = false
            onManageSources()
        },
        onDismiss = { chrome.openScopeDrawer = false }
    )
}

/**
 * 常驻顶栏：画在切页转场之外，切 tab 时它一动不动。
 *
 * 装配参数读当前路由那一页交上来的 [NfNavConfig]；转场期间旧页写的是它自己那个 tab 的键，
 * 不会盖掉新页的。首页的「滚过轮播就淡出」也照旧生效，只是淡出的是一条常驻的顶栏。
 */
@Composable
fun NfShellNav(
    vm: LibraryViewModel,
    shell: NfShellState,
    current: NfTab,
    onTab: (NfTab) -> Unit,
    onSettings: () -> Unit,
    onManageSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberNfScope(vm)
    val chrome = shell.chrome
    val config = shell.configOf(current)
    val density = LocalDensity.current
    val visible = config?.visible ?: true

    LaunchedEffect(visible) {
        if (!visible) chrome.navFocused = false
    }

    Box(modifier.fillMaxWidth()) {
        if (visible) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // 透明度在 draw phase 现读现用：曲线每帧都变，进组合读会把整条顶栏拖进重组
                    .then(
                        if (config?.hasAlpha == true)
                            Modifier.graphicsLayer { this.alpha = config?.alpha?.invoke() ?: 1f }
                        else Modifier
                    )
            ) {
                // 收色带只在悬浮装配（首页）时有意义：流内那三页顶栏下面就是内容，
                // 压一层渐变反而把分类条压暗
                if (config?.inFlow != true) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(NfNavScrimHeight)
                            .background(
                                Brush.verticalGradient(
                                    0f to AppColors.Bg.copy(alpha = 0.78f),
                                    0.52f to AppColors.Bg.copy(alpha = 0.30f),
                                    1f to Color.Transparent
                                )
                            )
                    )
                }
                NfNavBar(
                    current = current,
                    sourceName = scope.sourceName,
                    siteName = scope.siteName,
                    scanning = vm.scanning,
                    onTab = onTab,
                    onRefresh = { vm.refreshHome() },
                    onScope = { chrome.openScopeDrawer = true },
                    onSettings = onSettings,
                    belowFocusRequester = config?.below,
                    tabFocus = chrome::tabOf,
                    scopeFocus = chrome.scopeFocus,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .onSizeChanged { shell.navHeight = with(density) { it.height.toDp() } }
                        .onFocusEvent {
                            chrome.navFocused = it.hasFocus
                            // 焦点来到顶栏 = 用户按了 ↑，此刻顶栏多半已经淡掉一半：
                            // 先把内容滚回顶部再谈可见性，否则就是「选中框在空气上」
                            if (it.hasFocus) config?.onFocus?.invoke()
                        }
                )
            }
        }
        NfScopeDrawerHost(vm, scope, chrome, onManageSources)
    }
}

/**
 * Netflix 基准那一套 TV 界面的公共外壳：主题背景 + 导航条 + 作用域抽屉。
 *
 * 顶栏可见性沿用经典版口径：透明悬浮不占内容高度、随滚动连续淡出（[navAlpha]，
 * 在 draw phase 算，不进重组）、淡到位就从组合里摘掉（[navVisible]）—— 1.6.8 的焦点
 * 搜索不看透明度，留着一个看不见的顶栏等于留着一排会被方向键选中的按钮。
 *
 * 外层挂着 [LocalNfShell] 时（TV 外壳常驻顶栏），这里只把装配参数交上去、只渲染内容：
 * 背景、导航条、抽屉都由转场之外的那一份画，切页动画因此只作用在内容区。
 */
@Composable
fun NfScaffold(
    vm: LibraryViewModel,
    current: NfTab,
    chrome: NfChromeState,
    /**
     * 导航条按 ↓ 的落点。四页都给显式落点：这台设备上默认几何搜索向下会静默失败
     * （经典版同样复现），落点必须逐条交棒（规范 R1）。
     */
    belowFocusRequester: FocusRequester? = null,
    /** 顶栏（含其后面的收色带）是否出现在组合里 */
    navVisible: Boolean = true,
    /**
     * 导航条走「流内」而不是悬浮：作为内容的上一层排在顶上。
     * 悬浮导航条 ⇄ Lazy 内容区这条跨焦点子树的显式抢焦不可靠（requestFocus 不抛异常、
     * 焦点却不动），流内装配让导航条与内容同在一棵 Column 里；这三页顶栏本来也常驻，视觉不变。
     */
    navInFlow: Boolean = false,
    /**
     * 顶栏不透明度，按滚动距离连续淡出。必须在 draw phase 读滚动状态：
     * 在组合里读连续量会让每次滚动把整页拖进重组。
     */
    navAlpha: (() -> Float)? = null,
    /** 焦点落到顶栏时的视觉补偿：把内容滚回顶部，避免高亮落在看不见的按钮上 */
    onNavFocus: (() -> Unit)? = null,
    onTab: (NfTab) -> Unit,
    onSettings: () -> Unit,
    onManageSources: () -> Unit,
    content: @Composable () -> Unit
) {
    val shell = LocalNfShell.current
    if (shell != null) {
        SideEffect {
            shell.configSlot(current).update(
                below = belowFocusRequester,
                visible = navVisible,
                alpha = navAlpha,
                onFocus = onNavFocus,
                inFlow = navInFlow
            )
        }
        LaunchedEffect(navVisible) {
            if (!navVisible) chrome.navFocused = false
        }
        // 底色画在这一页里面，而不是画在转场之外：转场时新旧两页各自不透明，
        // 滑动是「一层擦掉一层」；底色画在外面时新页自己透明，
        // 新页的海报压着旧页的海报糊成一片，看上去就是转场卡顿。
        Box(Modifier.fillMaxSize().themeBackdrop()) {
            if (navInFlow) {
                Column(Modifier.fillMaxSize()) {
                    // 顶栏常驻在转场之外，这里只给它让出高度；4dp 是它和内容之间那条窄缝
                    Spacer(Modifier.height(shell.navHeight + 4.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                }
            } else {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
        return
    }

    // 顶栏被移出组合时它不再会上报「焦点离开」，这条记录得自己清 ——
    // 留着为 true 顶栏就再也收不起来了
    LaunchedEffect(navVisible) {
        if (!navVisible) chrome.navFocused = false
    }
    val scope = rememberNfScope(vm)

    // 导航条在两种装配方式下共用同一份构造：悬浮（首页）与流内（分类/我的/搜索）
    val navBar: @Composable (Modifier) -> Unit = { barModifier ->
        NfNavBar(
            current = current,
            sourceName = scope.sourceName,
            siteName = scope.siteName,
            scanning = vm.scanning,
            onTab = onTab,
            onRefresh = { vm.refreshHome() },
            onScope = { chrome.openScopeDrawer = true },
            onSettings = onSettings,
            belowFocusRequester = belowFocusRequester,
            tabFocus = chrome::tabOf,
            scopeFocus = chrome.scopeFocus,
            modifier = barModifier.onFocusEvent {
                chrome.navFocused = it.hasFocus
                if (it.hasFocus) onNavFocus?.invoke()
            }
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
    ) {
        if (navInFlow) {
            Column(Modifier.fillMaxSize()) {
                // 流内装配时导航条下面只留一条窄缝：它自己只有 top padding，
                // 紧贴着排就会和第一行内容（搜索页的输入框）糊成一块；
                // 12dp 时和分类条、网格上边距叠起来纵向太空，收到 4dp
                navBar(Modifier.padding(bottom = 4.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        } else {
            content()

            if (navVisible) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(
                            if (navAlpha != null) Modifier.graphicsLayer { this.alpha = navAlpha() }
                            else Modifier
                        )
                ) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(NfNavScrimHeight)
                            .background(
                                Brush.verticalGradient(
                                    0f to AppColors.Bg.copy(alpha = 0.78f),
                                    0.52f to AppColors.Bg.copy(alpha = 0.30f),
                                    1f to Color.Transparent
                                )
                            )
                    )
                    navBar(Modifier.align(Alignment.TopCenter))
                }
            }
        }

        NfScopeDrawerHost(vm, scope, chrome, onManageSources)
    }
}

/** 页面内的空态：一句话 + 一句更弱的补充说明，可选一颗操作按钮 */
@Composable
fun NfInlineEmpty(
    text: String,
    hint: String? = null,
    actionLabel: String? = null,
    /** 动作按钮左侧的真图标（Material Icons），不用 Unicode 字符冒充 */
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = TvText.Section,
            style = MaterialTheme.typography.headlineSmall
        )
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (actionLabel != null && onAction != null) {
            NfActionButton(
                text = actionLabel,
                icon = actionIcon,
                onClick = onAction,
                modifier = Modifier.padding(top = 20.dp).then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
                )
            )
        }
    }
}

/** 页面内「这一层的返回键」：把按键事件接到 onBack，onBack 返回 true 就吃掉这次返回 */
fun Modifier.nfBackKey(onBack: () -> Boolean): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && event.key == Key.Back && onBack()
}

/**
 * 返回键的层级交棒。
 *
 * 用户要的层级是：内容（卡片）→ 分类栏 → tab 栏 → 首页。
 * 每一层按返回先把焦点交回上一层，只有「已经在最外层」才轮到退页 / 切 tab，
 * 而不是不管焦点站在哪都一下退回首页。
 *
 * 时序沿用方向键交棒那一套（等一帧再跳、跳不动 180ms 补一次）：刚从别的页切回来那一帧
 * 锚点可能还没挂载，直接 requestFocus 会静默失败。
 */
@Composable
fun rememberNfLayerJump(): NfLayerJump {
    val scope = rememberCoroutineScope()
    return NfLayerJump { targets ->
        scope.launch {
            withFrameNanos { }
            val array = targets.toTypedArray()
            if (!tvMoveFocus(*array)) {
                delay(180)
                tvMoveFocus(*array)
            }
        }
        true
    }
}

/** 返回键交回上一层这个动作本身；调用时按顺序给落点，第一个挂得上的就是落点 */
class NfLayerJump internal constructor(private val launchJump: (List<FocusRequester>) -> Boolean) {
    operator fun invoke(vararg targets: FocusRequester): Boolean = launchJump(targets.toList())
}

/** 页面里的一颗普通操作（空态重试、切换来源确认等），沿用导航条按钮的层级语言 */
@Composable
fun NfActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 左侧真图标；为空时只出文案 */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val contentColor = if (focused) Color.Black else TvText.Title
    Box(
        modifier
            .clip(Radius.Md)
            .background(if (focused) Color.White else AppColors.Brand.copy(alpha = 0.24f))
            .then(
                if (focused) Modifier.border(2.dp, Color.White, Radius.Md)
                else Modifier.border(1.dp, AppColors.Brand, Radius.Md)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 26.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text,
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
            }
        } else {
            Text(
                text,
                color = contentColor,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
    }
}
