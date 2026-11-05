package com.tabbit.myvidhub.ui.tv.nf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.ReturnSpot
import com.tabbit.myvidhub.ui.StandTarget
import com.tabbit.myvidhub.ui.cardStand
import com.tabbit.myvidhub.ui.components.PosterArt
import com.tabbit.myvidhub.ui.components.PosterFallback
import com.tabbit.myvidhub.ui.components.continueTitle
import com.tabbit.myvidhub.ui.components.episodeLabel
import com.tabbit.myvidhub.ui.gridState
import com.tabbit.myvidhub.ui.tv.TvPagePadding
import com.tabbit.myvidhub.ui.tv.tvGridColumns
import com.tabbit.myvidhub.ui.tv.tvMoveFocus
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.Edge
import com.tabbit.myvidhub.ui.theme.Motion
import com.tabbit.myvidhub.ui.theme.Radius
import com.tabbit.myvidhub.ui.theme.TvText

/**
 * Netflix 基准布局（TV 第二套界面）的共用件，与 `ui/tv` 经典版完全并行，由 `Prefs.tvLayoutStyle` 选用。
 *
 * 焦点机制直接复用经典版那套纯机制 helper（tvMoveFocus / tvClaimFocusOnEnter），不写第二份。
 * 相对旧件新增两件事：
 *   · 焦点「translateY 抬起」：放大之外再上移，配合描边做出离地感；
 *   · 列表「逐格入场 stagger」：只在每行首次拿到数据那一段时间里播，滚动中不重播
 *     （海报淡入在电视上会读成「整屏刷新了一下」，这里绕开同一个坑）。
 */

/** 焦点抬起量。必须小于所在行的纵向 contentPadding（[NfRowVerticalPadding]），否则抬出容器边界被裁掉 */
private val CardLift = 8.dp
private val ButtonLift = 4.dp

/** 入场动画时长与逐格间隔 */
private const val EnterMillis = 220
private const val EnterSlotMillis = 45L
private val EnterRise = 14.dp

/** 卡与卡的间距 */
val NfCardGap = 16.dp

/** 抬起动画所需的行内纵向余量 */
val NfRowVerticalPadding = 14.dp

/** 一级导航的四个落点 */
enum class NfTab { Home, Browse, Mine, Search }

/**
 * 上/下落点走按键拦截 + [tvMoveFocus]，不用 focusProperties：
 * 这套界面的落点大多挂在 Lazy 容器里，容器滚过一屏后落点就不再组合，
 * focusProperties 会把那一向直接判成「无路可走」，按键被吃掉且毫无反馈（规范 R8）。
 *
 * 交棒必须延后一帧再跳，不能在按键回调里同步 requestFocus —— 同步跳会被当成本帧输入的一部分吞掉。
 * 时序见 [nfHandover]。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.nfFocusRoute(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
): Modifier {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // 上一趟交棒的「180ms 补跳」还挂着就来了新按键：旧的必须先作废。
    // 否则补跳会叠在新按键上，焦点跳两格或回弹，快速连按时读作「卡断掉帧」。
    val pendingHandover = remember { mutableStateOf<Job?>(null) }
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val isUp = e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        val isDown = e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        if (!isUp && !isDown) return@onPreviewKeyEvent false
        val requested = if (isUp) up else down
        val direction = if (isUp) FocusDirection.Up else FocusDirection.Down
        pendingHandover.value?.cancel()
        if (requested === FocusRequester.Cancel) return@onPreviewKeyEvent true // 这一向到头，别让焦点飞出屏
        if (requested == null) {
            // 这一向不设显式落点：等一帧自己调默认几何搜索，别把键原样交回去
            pendingHandover.value = scope.launch {
                withFrameNanos { }
                runCatching { focusManager.moveFocus(direction) }
            }
            return@onPreviewKeyEvent true
        }
        pendingHandover.value = scope.launch { nfHandover(requested, focusManager, direction) }
        true
    }
}

/** [nfFocusRoute] 的交棒时序：等一帧 → 跳 → 180ms 补跳 → 仍不动就交回默认几何搜索 */
@OptIn(ExperimentalComposeUiApi::class)
private suspend fun nfHandover(
    target: FocusRequester,
    focusManager: FocusManager,
    direction: FocusDirection,
) {
    withFrameNanos { }
    if (tvMoveFocus(target)) return
    delay(180)
    if (!tvMoveFocus(target)) runCatching { focusManager.moveFocus(direction) }
}

/**
 * 整块区域的 ↓ 交给 [onDown] 安排：Hero 这类「里面只有一颗按钮、下面还有一屏内容」的元件，
 * 落点在视口外时默认几何搜索找不到，必须由外层「先滚动再把焦点交出去」。
 * [enabled] 为 false 表示下面没有可聚焦内容，这次不接管，按键原样交回默认处理。
 */
@Composable
fun Modifier.nfKeyDown(
    enabled: Boolean = true,
    onDown: suspend () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown ||
            e.key.nativeKeyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
            !enabled
        ) {
            return@onPreviewKeyEvent false
        }
        scope.launch { onDown() }
        true
    }
}

/**
 * 这一行的「首次填充」stagger 开关：数据第一次到达时为 true，播完永久 false。
 * Lazy 容器滚动时会不断新建 item 的组合，开关若一直开着，每滚进一张卡就播一次淡入。
 *
 * [key] 给定时（按页面/分类记）播过的内容集再回来不再播：进详情会把整棵子树销毁重建，
 * 「返回」不该被读成一次刷新。
 */
@Composable
fun nfFirstFillStagger(itemCount: Int, key: String? = null): Boolean {
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(key != null && NfIntroLog.contains(key)) }
    LaunchedEffect(itemCount) {
        if (started || itemCount <= 0) return@LaunchedEffect
        started = true
        if (key != null) NfIntroLog.add(key)
        playing = true
        delay(itemCount.coerceAtMost(10) * EnterSlotMillis + EnterMillis + 120L)
        playing = false
    }
    return playing
}

/** 进程级的「已入场」名单：页面重建后 remember 全新一份，靠它分清「第一次出数据」和「又渲染了一遍同样的数据」 */
private object NfIntroLog {
    private val played = HashSet<String>()
    fun contains(key: String): Boolean = synchronized(played) { played.contains(key) }
    fun add(key: String): Unit = synchronized(played) { played.add(key) }
}

/**
 * 入场进度 0→1；[stagger] 为 false 时直接给 1（不播）。
 * 序号超过 12 的按 12 封顶，免得排在长列表后面的格子等半秒才亮。
 */
@Composable
fun nfEntrance(index: Int, stagger: Boolean): Float {
    var shown by remember(stagger) { mutableStateOf(!stagger) }
    LaunchedEffect(stagger, index) {
        if (!stagger) {
            shown = true
        } else if (!shown) {
            delay(index.coerceAtMost(12) * EnterSlotMillis)
            shown = true
        }
    }
    return animateFloatAsState(if (shown) 1f else 0f, tween(EnterMillis, easing = Motion.Ease), label = "nfEnter").value
}

/** 导航条的一颗 tab：选中态加粗+淡底，焦点态品牌色底 + 白边 + 轻微抬起（与旧按钮同一套层级语言） */
@Composable
private fun NfNavTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val liftPx = with(density) { ButtonLift.toPx() }
    val scale = animateFloatAsState(if (focused) 1.05f else 1f, tween(Motion.FAST, easing = Motion.Ease), label = "tab")
    val lift = animateFloatAsState(if (focused) liftPx else 0f, tween(Motion.FAST, easing = Motion.Ease), label = "tabLift")
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationY = -lift.value
            }
            .clip(Radius.Md)
            .background(
                when {
                    focused -> AppColors.Brand.copy(alpha = 0.55f)
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, Radius.Md) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected || focused) TvText.Title else TvText.Body,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 作用域胶囊：`来源 › 线路`，点它开双列抽屉。
 * Emby 来源没有线路概念，只显示左半段。
 */
@Composable
private fun NfScopeChip(
    sourceName: String,
    siteName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (focused) 1.05f else 1f, tween(Motion.FAST, easing = Motion.Ease), label = "scope")
    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(Radius.Md)
            .background(if (focused) AppColors.Brand.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f))
            .then(
                if (focused) Modifier.border(2.dp, Color.White, Radius.Md)
                else Modifier.border(1.dp, Edge.HairlineStrong, Radius.Md)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            sourceName,
            color = TvText.Title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp)
        )
        if (!siteName.isNullOrBlank()) {
            Text("›", color = TvText.Muted, style = MaterialTheme.typography.titleMedium)
            Text(
                siteName,
                color = TvText.Section,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
    }
}

/**
 * 导航条：左 = 首页/分类/我的/搜索，右 = 刷新 / 作用域胶囊 / 设置。
 * 背景完全透明 —— 不压暗、不加渐变，页面主题底色直接透出来。
 *
 * 焦点：↑ = Cancel（其上无物，禁止向别的 tab 逃逸）；↓ = [belowFocusRequester]。
 * [tabFocus] 每颗 tab 各一个 requester：内容层按 ↑ 交回的是本页自己那颗 tab（规范 R1）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NfNavBar(
    current: NfTab,
    sourceName: String,
    siteName: String?,
    scanning: Boolean,
    onTab: (NfTab) -> Unit,
    onRefresh: () -> Unit,
    onScope: () -> Unit,
    onSettings: () -> Unit,
    belowFocusRequester: FocusRequester?,
    tabFocus: (NfTab) -> FocusRequester,
    scopeFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val route = Modifier.nfFocusRoute(up = FocusRequester.Cancel, down = belowFocusRequester)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = TvPagePadding, end = TvPagePadding, top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NfNavTab(
                "首页", NfTab.Home == current, { onTab(NfTab.Home) },
                modifier = route.focusRequester(tabFocus(NfTab.Home))
            )
            NfNavTab(
                "分类", NfTab.Browse == current, { onTab(NfTab.Browse) },
                modifier = route.focusRequester(tabFocus(NfTab.Browse))
            )
            NfNavTab(
                "我的", NfTab.Mine == current, { onTab(NfTab.Mine) },
                modifier = route.focusRequester(tabFocus(NfTab.Mine))
            )
            NfNavTab(
                "搜索", NfTab.Search == current, { onTab(NfTab.Search) },
                modifier = route.focusRequester(tabFocus(NfTab.Search))
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                Text("↻ 刷新中…", color = TvText.Body, style = MaterialTheme.typography.labelMedium)
            } else {
                NfNavTab("↻ 刷新", false, onRefresh, modifier = route)
            }
            NfScopeChip(
                sourceName = sourceName,
                siteName = siteName,
                onClick = onScope,
                modifier = route.focusRequester(scopeFocus)
            )
            NfNavTab("设置", false, onSettings, modifier = route)
        }
    }
}

/**
 * 分区标题（Netflix 的 row header）：左轴固定，右侧可挂计数或操作。
 * 上下留白 20/10 → 12/6 → 8/6：这一条带加上行间距原先合计 44dp，是横向 gap 的近三倍，
 * 十英尺口径下纵向应该和横向同量级，所以收的是这条带而不是卡片。
 * 上边距再让 4dp：折痕那圈黑由 Hero 的收色带负责，标题再压一大块就把「海报下缘」推得更远。
 */
@Composable
fun NfRowTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = TvPagePadding, end = TvPagePadding, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = TvText.Title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * 竖版海报卡（2:3）。焦点态 = 放大 8% + 上移抬起 + 白描边；入场时淡入并自下 [EnterRise] 归位。
 * 放在 LazyRow / LazyVerticalGrid 里时，行的纵向 contentPadding 至少给 [NfRowVerticalPadding]，
 * 否则抬起的像素会被 Lazy 容器的边界裁掉。
 */
@Composable
fun NfPosterCard(
    item: MediaEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Prefs.posterSize.tvDp.dp,
    fillCell: Boolean = false,
    anchorStart: Boolean = false,
    entrance: Float = 1f
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val liftPx = with(density) { CardLift.toPx() }
    val risePx = with(density) { EnterRise.toPx() }
    val scale = animateFloatAsState(if (focused) 1.08f else 1f, tween(Motion.FAST, easing = Motion.Ease), label = "nfCard")
    val lift = animateFloatAsState(if (focused) liftPx else 0f, tween(Motion.FAST, easing = Motion.Ease), label = "nfCardLift")
    val cardShape = remember { Radius.Md }

    Column(
        modifier
            .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(width))
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                alpha = entrance
                scaleX = scale.value
                scaleY = scale.value
                translationY = -lift.value + (1f - entrance) * risePx
                if (anchorStart) translationX = (scale.value - 1f) * size.width / 2f
                shape = cardShape
                clip = true
            }
            .background(AppColors.Surface)
            .then(
                if (focused) Modifier.border(2.5.dp, Color.White, cardShape)
                else Modifier.border(1.dp, Edge.Hairline, cardShape)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        // 内容体单独一个组合函数、只收稳定原语参数：焦点态翻转会重组卡片外壳
        // （zIndex / 描边 / 抬起都跟着变），参数没变时内容体整块被跳过，图片和文字不跟着重排。
        NfPosterBody(
            model = item.posterUrl.ifBlank { item.backdropUrl },
            title = item.title,
            subtitle = item.year.takeIf { it > 0 }?.toString().orEmpty(),
            fillCell = fillCell,
            width = width
        )
    }
}

/**
 * [NfPosterCard] 的内容体：图 + 标题。
 *
 * 参数刻意只有 [String] / [Boolean] / [Dp] 这类稳定原语，这样它是可跳过的 ——
 * 焦点态翻转只重组外壳，这块原样复用。
 */
@Composable
private fun NfPosterBody(
    model: String,
    title: String,
    subtitle: String,
    fillCell: Boolean,
    width: Dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (fillCell) Modifier.aspectRatio(2f / 3f) else Modifier.height(width * 1.5f))
            .background(AppColors.SurfaceHi)
    ) {
        PosterArt(model = model, contentDescription = title) {
            PosterFallback(
                title = title,
                subtitle = subtitle,
                titleFontSize = MaterialTheme.typography.titleMedium.fontSize,
                subtitleFontSize = MaterialTheme.typography.bodySmall.fontSize
            )
        }
    }
    Text(
        title,
        color = TvText.Section,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/**
 * 继续观看横版卡：16:9，图按「横版截取」填满 —— PosterArt 内部就是 ContentScale.Crop，
 * 竖版海报进来取中间一条带，横版 backdrop 正好铺满。底部压渐变放片名、集数和进度。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NfContinueCard(
    item: MediaEntity,
    cardWidth: Dp,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    anchorStart: Boolean = false,
    entrance: Float = 1f
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val liftPx = with(density) { CardLift.toPx() }
    val risePx = with(density) { EnterRise.toPx() }
    val scale = animateFloatAsState(if (focused) 1.06f else 1f, tween(Motion.FAST, easing = Motion.Ease), label = "nfCont")
    val lift = animateFloatAsState(if (focused) liftPx else 0f, tween(Motion.FAST, easing = Motion.Ease), label = "nfContLift")
    val cardShape = remember { Radius.Md }
    val progress = if (item.durationMs > 0L) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f

    // 长按 OK / Menu 键唤出操作菜单。按键拦截必须排在 clickable 之前：
    // 点击发生在 clickable 自己的 preview 处理里，外层 preview 先拿到事件才能把 KeyUp 吃掉。
    var centerDownAt by remember { mutableLongStateOf(0L) }
    val keyMenu: Modifier = if (onMenu == null) Modifier else Modifier.onPreviewKeyEvent { e ->
        val native = e.nativeKeyEvent
        val isCenter = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            native.keyCode == android.view.KeyEvent.KEYCODE_ENTER
        when {
            native.keyCode == android.view.KeyEvent.KEYCODE_MENU -> {
                if (native.action == android.view.KeyEvent.ACTION_DOWN) onMenu()
                true
            }
            isCenter && native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> {
                centerDownAt = android.os.SystemClock.elapsedRealtime()
                false
            }
            isCenter && native.action == android.view.KeyEvent.ACTION_UP -> {
                val held = if (centerDownAt == 0L) 0L else android.os.SystemClock.elapsedRealtime() - centerDownAt
                centerDownAt = 0L
                // 短按必须把 KeyUp 交回 clickable，否则点击起不来；只有长按才吃掉它
                held >= 500L
            }
            else -> false
        }
    }

    Box(
        modifier
            .then(keyMenu)
            .width(cardWidth)
            .aspectRatio(16f / 9f)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                alpha = entrance
                scaleX = scale.value
                scaleY = scale.value
                translationY = -lift.value + (1f - entrance) * risePx
                if (anchorStart) translationX = (scale.value - 1f) * size.width / 2f
                shape = cardShape
                clip = true
            }
            .background(AppColors.SurfaceHi)
            .then(
                if (focused) Modifier.border(2.dp, Color.White, cardShape)
                else Modifier.border(1.dp, Edge.Hairline, cardShape)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        PosterArt(model = item.backdropUrl.ifBlank { item.posterUrl }, contentDescription = item.title) {
            PosterFallback(title = item.title, showTitle = false, titleFontSize = 30.sp)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)))
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth()) {
            Text(
                item.continueTitle,
                color = TvText.Title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val ep = item.episodeLabel().takeIf { it.isNotBlank() && it != item.continueTitle }
            if (ep != null) {
                Text(
                    ep,
                    color = TvText.Body,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress > 0.02f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp)
                        .clip(Radius.Pill)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(AppColors.Brand, Radius.Pill))
                }
            }
        }
    }
}

/** 贴在导航条下面的二级分类条（Netflix 分类页的第二层导航） */
@Composable
fun NfSubNav(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    /** ↓ 的落点（内容网格首卡 / 空态按钮）；不传则交回默认几何搜索 */
    downFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    /** ↑ 的落点（本页在导航条上那颗 tab） */
    upFocusRequester: FocusRequester? = null,
    /** 第一颗 chip 的锚点：内容网格首行按 ↑ 要落到它，而不是靠几何搜索猜 */
    firstChipFocus: FocusRequester? = null
) {
    val state = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        // 选中项被程序改掉（切来源后分类重置）时把它带进视野，否则高亮停在屏幕外
        if (selectedIndex in labels.indices) {
            state.animateScrollToItem((selectedIndex / 2).coerceAtLeast(0))
        }
    }
    LazyRow(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        state = state,
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(labels) { index, label ->
            NfSubChip(
                text = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .nfFocusRoute(up = upFocusRequester, down = downFocusRequester)
                    .then(if (index == 0 && firstChipFocus != null) Modifier.focusRequester(firstChipFocus) else Modifier)
            )
        }
    }
}

@Composable
private fun NfSubChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .clip(Radius.Pill)
            .background(
                when {
                    focused -> AppColors.Brand.copy(alpha = 0.55f)
                    selected -> Color.White.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, Radius.Pill) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = if (focused || selected) TvText.Title else TvText.Body,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 竖版海报网格（分类页 / 我的页 / 搜索结果共用）。
 * 格子间的纵向间距必须容得下焦点抬起的 [CardLift]，所以 [NfRowVerticalPadding] 同时用作
 * contentPadding 和行间距；入场只在「这一屏第一次拿到数据」那一段时间里逐格播（[nfFirstFillStagger]）。
 *
 * 滚动位置与落点按 [pageKey] 记账：进详情把这页子树销毁重建，返回时要站在原来那一屏、原来那张卡。
 */
@Composable
fun NfPosterGrid(
    items: List<MediaEntity>,
    pageKey: String,
    stand: StandTarget,
    firstFocus: FocusRequester,
    onDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 数据追加型分页：位置只在首次拿到数据时补一次，之后翻下一页不能再把用户拽回上一页 */
    restoreScrollOnce: Boolean = true,
    /** 滚到倒数 12 格：在线分页的下一页由这里请求（VM 侧对同分类的并发请求有 loading 门禁） */
    onNearEnd: (() -> Unit)? = null,
    /** 首行卡片按 ↑ 的落点（分类 chip 或本页那颗 tab） */
    upFocusRequester: FocusRequester? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val columns = tvGridColumns()
    // 记录要在建 gridState 之前读出来：数据还没补齐时 LazyGrid 会把位置 clamp 到末尾，
    // 并通过 gridState 的写回把原记录改小，之后就再也回不到离开时那一屏
    val saved = remember(pageKey) { ReturnSpot.scrollOf(pageKey) }
    val gridState = ReturnSpot.gridState(pageKey)
    val stagger = nfFirstFillStagger(items.size, pageKey)
    val firstId = items.firstOrNull()?.id
    var scrollRestored by remember(pageKey) { mutableStateOf(false) }
    LaunchedEffect(pageKey, items.size) {
        if (!restoreScrollOnce || scrollRestored || items.isEmpty()) return@LaunchedEffect
        saved?.let { runCatching { gridState.scrollToItem(it.index, it.offset) } }
        scrollRestored = true
    }
    if (onNearEnd != null) {
        val nearEnd by remember(gridState) {
            derivedStateOf {
                val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val total = gridState.layoutInfo.totalItemsCount
                total > 0 && last >= total - 12
            }
        }
        LaunchedEffect(nearEnd) { if (nearEnd) onNearEnd() }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = TvPagePadding, end = TvPagePadding,
            top = NfRowVerticalPadding, bottom = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(NfCardGap),
        verticalArrangement = Arrangement.spacedBy(NfRowVerticalPadding)
    ) {
        gridItemsIndexed(items, key = { _, it -> "nf_grid_${it.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
            val ownFocus = remember(item.id) { FocusRequester() }
            NfPosterCard(
                item = item,
                onClick = { onDetail(item.id) },
                fillCell = true,
                anchorStart = index % columns == 0,
                entrance = nfEntrance(index, stagger),
                // 首卡同时是本页的入口锚点，记账复用它的 requester，不挂第二个
                modifier = Modifier
                    .then(
                        if (index < columns)
                            Modifier.nfFocusRoute(up = upFocusRequester)
                        else Modifier
                    )
                    .then(
                        ReturnSpot.cardStand(
                            pageKey = pageKey,
                            rowKey = "grid",
                            mediaId = item.id,
                            target = stand,
                            requester = if (item.id == firstId) firstFocus else ownFocus
                        )
                    )
            )
        }
        if (footer != null) {
            item(key = "nf_grid_footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    }
}
