package com.chen.doudoutv.ui.tv.nf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.ui.ReturnSpot
import com.chen.doudoutv.ui.StandTarget
import com.chen.doudoutv.ui.PrecomposeWindow
import com.chen.doudoutv.ui.cardStand
import com.chen.doudoutv.ui.components.PosterArt
import com.chen.doudoutv.ui.components.PosterFallback
import com.chen.doudoutv.ui.components.continueTitle
import com.chen.doudoutv.ui.components.episodeLabel
import com.chen.doudoutv.ui.gridState
import com.chen.doudoutv.ui.tv.TvPagePadding
import com.chen.doudoutv.ui.tv.tvPosterColumns
import com.chen.doudoutv.ui.tv.tvMoveFocus
import com.chen.doudoutv.ui.tv.tvDrawFocusRing
import com.chen.doudoutv.ui.tv.tvFocusRing
import com.chen.doudoutv.ui.tv.tvKeepFocusedItemAlive
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Edge
import com.chen.doudoutv.ui.theme.Motion
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.TvText

/**
 * Netflix 基准布局（TV 端唯一在用的一套）的共用件，经典版 `ui/tv` 的页面仍在但入口已下线。
 *
 * 焦点机制直接复用经典版那套纯机制 helper（tvMoveFocus / tvClaimFocusOnEnter），不写第二份。
 * 相对旧件新增一件事：焦点「移动效果」两档可切（[Prefs.tvCardMotion]），
 * 默认只放大图片那一层、片名不参与，另一档整卡不涨只上抬。
 *
 * 卡片不做逐格入场动画：切 tab 那一帧要同时组合约 30 张卡，每张各挂一条 delay 协程
 * 加一个 Animatable，再叠 220ms 的整屏 alpha/位移合成，弱盒子上就是可见的掉帧。
 * 数据到位直接满态出现，和「切页 0ms 直切」是同一个口径。
 */

/** 焦点抬起量。必须小于所在行的纵向 contentPadding（[NfRowVerticalPadding]），否则抬出容器边界被裁掉 */
private val CardLift = 8.dp

/**
 * 海报卡「上抬」档的位移量：不放大，只把整卡抬起来。
 * 12dp 是行内余量 [NfRowVerticalPadding]（14dp）之内能给的极限，再大就被容器裁掉顶边。
 */
private val PosterLift = 12.dp

/**
 * 海报卡「放大」档的缩放倍率：只有图片这一层涨，片名不参与。
 * 图片是 2:3，涨 5% 上下各出 (卡宽×1.5×0.05)/2 —— 最大档 192dp 也才 7.2dp，
 * 上面余量 14dp、下面到片名有 8dp 间距，两头都撞不到线。
 */
private const val ArtZoom = 1.05f

/** 卡与卡的间距 */
val NfCardGap = 16.dp

/** 抬起动画所需的行内纵向余量 */
val NfRowVerticalPadding = 14.dp

/**
 * 网格首行上方的余量：行内还有下一行兜着，首行上面什么都没有。
 * 纵向滚动容器只在左右各放宽 30dp 不裁，上下是贴着容器边裁的（ClipScrollableContainer），
 * 所以首行焦点态向外扩的那一圈必须整个装进这一段，否则海报上缘被切、
 * 看起来就像被上面那条分类栏压住。
 *
 * 两档移动效果取大者：上抬档固定抬 PosterLift（12dp）；放大档图片绕中心涨 5%，
 * 向上出 0.025×海报高，海报高 = 单元宽×1.5，单元宽随列数走（LARGE 档 4 列封顶、
 * 单元宽 ≤ 288dp，向上至多 10.8dp）。18dp 两档都装得下，再往上缩就会切到首行海报上缘。
 * 默认/小卡档列更多、单元更窄，外扩更小，这一段只显宽不显窄。
 * 只加在顶部，底部不用，最后一行下面有 contentPadding 兜。
 * 首项是文字表头的网格（我的 / 搜索结果分组）不用这一段，表头不聚焦、不会被裁。
 */
val NfGridTopPadding = 18.dp

/**
 * 一级导航的落点。前四颗是导航条上可见的 tab。
 *
 * [Settings] 是「外壳专用伪 tab」：设置页要像分类 / 搜索一样嵌在常驻顶栏下面，
 * 借这套槽位与焦点交棒管线，但它不在四颗 tab 那一行里出现（那行是写死的），
 * 选中态落在右簇的齿轮键上。它不参与 tab 互切的栈规则（仍是 push / 出栈）。
 */
enum class NfTab { Home, Browse, Mine, Search, Settings }

/**
 * 「刚点过导航条 tab」的进程级标记：点哪颗、哪页接住。
 *
 * 页面在 tab 互切时整块销毁重建，入口抢焦（tvClaimFocusOnEnter）默认会把焦点交给内容首卡，
 * 读起来就是「切完页焦点自己跑进去了」。点击时先记下目标 tab，目标页第一轮组合取走标记、
 * 把锚点换成本页那颗 tab —— 焦点留在标签上，什么时候进内容由用户按 ↓ 决定。
 * 进详情/播放器再返回不经过这里，仍走「站在离开时那张卡」的恢复逻辑。
 */
object NfTabSwitch {
    private var pending: NfTab? = null

    /** 导航条点击时记下目标 tab（在路由切换之前调用） */
    fun arm(tab: NfTab) {
        pending = tab
    }

    /** 目标页第一轮组合取走标记；其他页面/返回路径取不到，行为不变 */
    fun consume(tab: NfTab): Boolean {
        if (pending != tab) return false
        pending = null
        return true
    }
}

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
    left: FocusRequester? = null,
    right: FocusRequester? = null,
): Modifier {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // 上一趟交棒的「180ms 补跳」还挂着就来了新按键：旧的必须先作废。
    // 否则补跳会叠在新按键上，焦点跳两格或回弹，快速连按时读作「卡断掉帧」。
    val pendingHandover = remember { mutableStateOf<Job?>(null) }
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val code = e.key.nativeKeyCode
        val direction = when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
            else -> return@onPreviewKeyEvent false
        }
        val requested = when (direction) {
            FocusDirection.Up -> up
            FocusDirection.Down -> down
            FocusDirection.Left -> left
            else -> right
        }
        // 左右没给显式落点时原样交回默认几何搜索（同一行内移动本来就允许靠几何）
        if (requested == null && (direction == FocusDirection.Left || direction == FocusDirection.Right)) {
            return@onPreviewKeyEvent false
        }
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

/** [nfFocusRoute] 的交棒时序：等一帧 → 跳 → 180ms 补跳 → 仍不动就交回默认几何搜索（仅上下） */
@OptIn(ExperimentalComposeUiApi::class)
private suspend fun nfHandover(
    target: FocusRequester,
    focusManager: FocusManager,
    direction: FocusDirection,
) {
    withFrameNanos { }
    // 带上方向：1.9 的 requestFocus 会按这个方向做最小滚动（往下就往下翻一格，不多滚）
    if (tvMoveFocus(target, direction = direction)) return
    delay(180)
    if (tvMoveFocus(target, direction = direction)) return
    // 左右给了显式落点就不再降级到几何搜索：分类条上方就是一级 tab 栏，
    // 几何搜索在「邻项这一帧还没组合」时会把焦点甩到 tab 上去。
    if (direction == FocusDirection.Up || direction == FocusDirection.Down) {
        runCatching { focusManager.moveFocus(direction) }
    }
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

/** 选中标记（下划线）的厚度：选中与焦点靠形状分开，不靠加粗这条线 */
private val NfSelectedRuleHeight = 2.dp

/** bar 内焦点框的飞行时长 */
private const val NfBarFrameMillis = 150

/** 移动框的圆角，与 [Radius.Md] 同值（DrawScope 要的是 dp 数不是 Shape） */
private val NfBarFrameRadius = 12.dp

private val NfRectConverter = TwoWayConverter<Rect, AnimationVector4D>(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) }
)

/**
 * 一条 bar（导航 tab 条 / 分类 chip 条）内的移动焦点框。
 *
 * 框画在整条 bar 之上的一层：焦点换颗时它从旧矩形平移并形变到新矩形，
 * 读起来是「一个框在走」，而不是「一个灭了另一个亮」。
 *
 * 只在这条 bar 内飞。焦点离开 bar（下到网格、进弹窗）时这一层直接不画，落点自己那圈
 * [tvFocusRing] 瞬时亮起 —— 跨层不飞：1.9 的焦点是瞬时吸附，框飞过去的那 150ms 里
 * 用户已经按了下一个键，读起来就是「框跟不上」。
 *
 * 矩形不走重组：布局回调直接喂给 [Animatable]，值只在绘制期读，滚动和飞行每帧只重绘这一层。
 *
 * **焦点来源不用 `Modifier.onFocusEvent`，用元件自己那份 `collectIsFocusedAsState`。**
 * 两条理由，都是真机撞出来的：
 * ① 1.9 的 `FocusTargetNode.dispatchFocusCallbacks` 同步把子节点的新状态原样推给祖先的
 *    事件节点，切颗时先推一趟 Inactive 一路推到 bar 容器 —— 容器那下「失焦」是假的，
 *    夹在两颗之间，拿它清「当前落点」会把飞行起点抹掉，框永远瞬移。
 * ② 事件节点排在 `clickable` 外层时，那条 `visitSelfAndAncestors(untilType = FocusTarget)`
 *    走到 clickable 自带的焦点目标就停了，压根收不到 —— 框整个不画。
 * 元件那份 `focused` 是驱动文字变白的同一个值，它一直是准的，跟着它走就没有这两个坑。
 *
 * 另外每颗自带 [nfBarFrameFallback]：这一层没有矩形时它自己画一圈静态框。
 * 飞行层再怎么出问题，「点哪里框就在哪里」都不会丢。
 */
class NfBarFocus internal constructor(private val scope: CoroutineScope) {
    /** 当前持焦的那一颗。只在绘制期读，写它只重绘不重组 */
    private var focusedIndex by mutableStateOf<Int?>(null)

    /** 上一次画过的落点。跨颗飞行的起点，失焦不清它 */
    private var lastIndex: Int? = null
    private val coords = HashMap<Int, LayoutCoordinates>()
    private var hostCoords: LayoutCoordinates? = null

    /** 这一颗的目标矩形。飞行还没起飞那一帧先画它，避免空一帧 */
    private var target by mutableStateOf(Rect.Zero)
    private val frame = Animatable(Rect.Zero, NfRectConverter)

    /** 这一层的矩形；null = 焦点不在这条 bar 上（或还没量到），交给单颗自己画 */
    internal val rect: Rect?
        get() {
            if (focusedIndex == null) return null
            val flying = frame.value
            return if (flying.width > 0f && flying.height > 0f) flying
            else target.takeIf { it.width > 0f && it.height > 0f }
        }

    internal fun host(coordinates: LayoutCoordinates) {
        hostCoords = coordinates
        retarget()
    }

    /** 单颗失焦只清自己那一份：新颗的 enter 可能排在它前面，清不到别人头上 */
    internal fun exit(index: Int) {
        if (focusedIndex == index) focusedIndex = null
    }

    internal fun enter(index: Int) {
        focusedIndex = index
        retarget()
    }

    internal fun place(index: Int, coordinates: LayoutCoordinates) {
        coords[index] = coordinates
        if (focusedIndex == index) retarget()
    }

    private fun retarget() {
        val index = focusedIndex ?: return
        val c = coords[index]?.takeIf { it.isAttached } ?: return
        val host = hostCoords?.takeIf { it.isAttached } ?: return
        val origin = host.positionInRoot()
        val next = c.boundsInRoot().translate(-origin.x, -origin.y)
        // 同一颗重复报点（容器和单颗的回调都会进来）不重启动画，否则飞行每帧被打断
        if (next == target) return
        val from = lastIndex
        val fly = from != null && from != index && frame.value.width > 0f
        target = next
        lastIndex = index
        scope.launch {
            // Animatable 内部互斥：新目标取消上一次没飞完的那趟，连按时框从
            // 「当前走到的位置」接着走，不回弹。滚动时 from == index → 瞬移，框跟着列表走
            if (fly) frame.animateTo(next, tween(NfBarFrameMillis, easing = Motion.Ease))
            else frame.snapTo(next)
        }
    }
}

@Composable
private fun rememberNfBarFocus(): NfBarFocus {
    val scope = rememberCoroutineScope()
    return remember(scope) { NfBarFocus(scope) }
}

/** 挂在 bar 的容器上：只记录坐标原点，不参与焦点判定 */
private fun Modifier.nfBarFrameHost(bar: NfBarFocus): Modifier =
    onGloballyPositioned { bar.host(it) }

/** 挂在 bar 里每一颗上：只报坐标。焦点由元件自己那份 focused 经 [NfBarFrameItem] 报 */
private fun Modifier.nfBarFrameCoords(bar: NfBarFocus, index: Int): Modifier =
    onGloballyPositioned { bar.place(index, it) }

/** 把元件自己那份焦点态喂给飞行层。放在元件内部，拿的就是驱动文字变白的同一个值 */
@Composable
private fun NfBarFrameItem(bar: NfBarFocus, index: Int, focused: Boolean) {
    LaunchedEffect(focused) {
        if (focused) bar.enter(index) else bar.exit(index)
    }
}

/**
 * 兜底描边：飞行层拿不出矩形时，这颗自己画一圈静态框（等于没有飞行层时的老行为）。
 * `bar.rect` 只在绘制期读，所以飞行不会把这颗拖进重组。
 */
private fun Modifier.nfBarFrameFallback(bar: NfBarFocus, focused: Boolean): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        if (bar.rect == null) tvDrawFocusRing(Rect(Offset.Zero, size), NfBarFrameRadius.toPx())
    }

/** 盖在 bar 上面的那一层：只画框，不吃触摸也不吃按键 */
private fun Modifier.nfBarFrameOverlay(bar: NfBarFocus): Modifier = drawWithContent {
    drawContent()
    bar.rect?.let { tvDrawFocusRing(it, NfBarFrameRadius.toPx()) }
}

/**
 * 导航条的一颗 tab：选中与焦点用**不同形状**表达 —— 选中 = 白字加粗 + 主题色下划线（常驻，只此一处），
 * 焦点 = 整条 bar 上那圈移动的白描边（[NfBarFocus]），其余 = 灰字。
 *
 * 为什么不再用「白色下划线」表示焦点：两条线形状一样、只差颜色，三米外必然糊成一条；
 * 焦点落在选中那颗上时白线更是直接把品牌线盖掉，等于没有选中提示。
 *
 * 这颗自己不画焦点框、也不放大：框由 bar 那一层统一画，才能在颗与颗之间走起来。
 * 下划线只跟「选中」有关，而选中只在切页时变、页面本来就是整棵重建的，所以不做动画。
 * 未选中时横线画成透明而不是不画：省掉这几 dp 会让选中的那颗比邻居高一截。
 */
@Composable
private fun NfNavTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    bar: NfBarFocus,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    NfBarFrameItem(bar, index, focused)
    Column(
        modifier
            .nfBarFrameFallback(bar, focused)
            // 下划线要 fillMaxWidth 对齐字宽，但 Column 本身必须收在字的固有宽度：
            // 不收的话 fillMaxWidth 会把这颗 tab 撑满整条 Row，把兄弟 tab 和右簇全挤出去
            .width(IntrinsicSize.Max)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = if (selected || focused) Color.White else TvText.Meta,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
        Box(
            Modifier
                .padding(top = 5.dp)
                .height(NfSelectedRuleHeight)
                .fillMaxWidth()
                .clip(Radius.Pill)
                .background(if (selected) AppColors.Brand else Color.Transparent)
        )
    }
}

/**
 * 导航条右簇的统一选框高度：来源胶囊与刷新/设置两颗图标按钮都锁在这个高度上，
 * 焦点白框才不会一颗高一颗矮（来源胶囊原本是 6dp 内边距撑出来的，图标按钮是 9dp）。
 */
private val NfNavControlHeight = 34.dp

/**
 * 作用域按钮：`来源 › 线路`，点它开双列抽屉。圆角矩形 + 细描边（Radius.Md），
 * 末尾不再挂下拉箭头 —— 整颗按钮本身就是入口，箭头只是重复一遍「这里能点」。
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
    val bg = animateColorAsState(
        if (focused) AppColors.Brand.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f),
        tween(Motion.BASE, easing = Motion.Ease), label = "scopeBg"
    )
    Row(
        modifier
            .height(NfNavControlHeight)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .clip(Radius.Md)
            .background(bg.value)
            .tvFocusRing(focused)
            .then(if (focused) Modifier else Modifier.border(1.dp, Edge.HairlineStrong, Radius.Md))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            sourceName,
            color = if (focused) Color.White else TvText.Body,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 120.dp)
        )
        if (!siteName.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TvText.Muted,
                modifier = Modifier.size(14.dp)
            )
            Text(
                siteName,
                color = if (focused) Color.White else TvText.Body,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 120.dp)
            )
        }
    }
}

/**
 * 导航条右簇的纯图标按钮（刷新 / 设置）：圆角矩形淡底只在焦点态出现，平时只留一枚图标。
 * [loading] 时把图标换成转圈，保留「正在刷新」的可见信号。
 */
@Composable
private fun NfNavIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    /** 当前页指示（设置页时齿轮常亮）：未聚焦也留一层品牌淡底 + 品牌色图标 */
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = animateColorAsState(
        when {
            focused -> Color.White.copy(alpha = 0.14f)
            selected -> AppColors.Brand.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        tween(Motion.BASE, easing = Motion.Ease), label = "navIconBg"
    )
    Box(
        modifier
            .size(NfNavControlHeight)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .clip(Radius.Md)
            .background(bg.value)
            .tvFocusRing(focused)
            .then(
                if (!focused && selected) Modifier.border(1.dp, AppColors.Brand.copy(alpha = 0.6f), Radius.Md)
                else Modifier
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = if (focused) Color.White else AppColors.Brand,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    focused -> Color.White
                    selected -> AppColors.BrandSoft
                    else -> TvText.Body
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 导航条：左 = 品牌字 + 首页/分类/我的/搜索（下划线选中），右 = 作用域胶囊 / 刷新 / 设置（后两颗纯图标）。
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
    // 点 tab 先记标记再切路由：目标页的入口抢焦凭它把焦点留在标签上，而不是交给内容首卡。
    // 交焦点这一下是给手指点按用的：切到别的页时新页会自己抢焦，点的却是当前这颗 tab 时不补就没人交。
    val goTab: (NfTab) -> Unit = { tab ->
        tvMoveFocus(tabFocus(tab))
        NfTabSwitch.arm(tab)
        onTab(tab)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = TvPagePadding, end = TvPagePadding, top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DouDouTV",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            // 四颗 tab 共用一层移动焦点框：左右交棒时框平移过去，切页时也跟着走
            // （导航条画在转场之外、不随页面重建，所以这一下飞行是连贯的）
            val tabBar = rememberNfBarFocus()
            Box(Modifier.nfBarFrameHost(tabBar)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    NfNavTab(
                        "首页", NfTab.Home == current, { goTab(NfTab.Home) }, tabBar, 0,
                        modifier = route.nfBarFrameCoords(tabBar, 0).focusRequester(tabFocus(NfTab.Home))
                    )
                    NfNavTab(
                        "分类", NfTab.Browse == current, { goTab(NfTab.Browse) }, tabBar, 1,
                        modifier = route.nfBarFrameCoords(tabBar, 1).focusRequester(tabFocus(NfTab.Browse))
                    )
                    NfNavTab(
                        "我的", NfTab.Mine == current, { goTab(NfTab.Mine) }, tabBar, 2,
                        modifier = route.nfBarFrameCoords(tabBar, 2).focusRequester(tabFocus(NfTab.Mine))
                    )
                    NfNavTab(
                        "搜索", NfTab.Search == current, { goTab(NfTab.Search) }, tabBar, 3,
                        modifier = route.nfBarFrameCoords(tabBar, 3).focusRequester(tabFocus(NfTab.Search))
                    )
                }
                Spacer(Modifier.matchParentSize().nfBarFrameOverlay(tabBar))
            }
        }
        Row(
            modifier = Modifier.weight(1f).padding(start = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NfScopeChip(
                sourceName = sourceName,
                siteName = siteName,
                onClick = onScope,
                modifier = Modifier.weight(1f, fill = false).then(route).focusRequester(scopeFocus)
            )
            NfNavIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "刷新",
                onClick = onRefresh,
                modifier = route,
                loading = scanning
            )
            NfNavIconButton(
                icon = Icons.Filled.Tune,
                contentDescription = "设置",
                onClick = onSettings,
                selected = current == NfTab.Settings,
                modifier = route.focusRequester(tabFocus(NfTab.Settings))
            )
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
 * 竖版海报卡（2:3）：图片 + 排在图片外面的片名，没有底板方框。
 *
 * 焦点态看 [Prefs.tvCardMotion] 两档：
 * · 放大（默认）—— 只有图片那一层涨 5%，片名的位置和字号一动不动；
 * · 上抬 —— 整卡不涨，往上走 [PosterLift]。
 * 两档都叠一圈 1.5dp 细白描边标落点。
 * 放在 LazyRow / LazyVerticalGrid 里时，行的纵向 contentPadding 至少给 [NfRowVerticalPadding]，
 * 否则抬起/涨出来的那几像素会被 Lazy 容器的边界裁掉。
 */
@Composable
fun NfPosterCard(
    item: MediaEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Prefs.posterSize.tvDp.dp,
    fillCell: Boolean = false,
    anchorStart: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val liftPx = with(density) { PosterLift.toPx() }
    // 焦点所在的那一项不许被 Lazy 回收，否则 1.9 会直接把焦点清空（见 tvKeepFocusedItemAlive）
    tvKeepFocusedItemAlive(focused)
    val liftMode = Prefs.tvCardMotion == Prefs.CardMotion.LIFT
    val lift = animateFloatAsState(if (focused && liftMode) liftPx else 0f, tween(Motion.FAST, easing = Motion.Ease), label = "nfCardLift")
    val artScale = animateFloatAsState(if (focused && !liftMode) ArtZoom else 1f, tween(Motion.FAST, easing = Motion.Ease), label = "nfCardArt")
    val cardShape = remember { Radius.Md }

    Column(
        modifier
            .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(width))
            .zIndex(if (focused) 1f else 0f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // 位移必须挂在 clickable（= 焦点目标）的**内层**，不能挂外面：
            // graphicsLayer 的 translationY 会进 Compose 的焦点矩形（NodeCoordinator.rectInParent
            // 里 layer.mapBounds），而 isCandidate(Down) 只比纵向不比横向 —— 获焦卡一上抬，
            // 同一行的邻卡立刻全变成「在它下面」的合法候选，↓ 被吃掉去横向跳格，
            // searchBeyondBounds 因此永不触发，Lazy 网格不再补组合下一行，列表卡死在屏幕底部。
            // 放大档没这毛病是因为 scale 挂在图片那一层、且绕中心涨，焦点矩形没动过。
            .graphicsLayer { translationY = -lift.value }
    ) {
        // 圆角和放大只作用在图片这一层。片名要是跟着一起放大，同一行的字号就齐不了，
        // 涨出来的高度还会往下压到下一行的片名上 —— 图外字幕这版排版要的就是文字层稳定。
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (liftMode) Modifier else Modifier.graphicsLayer {
                        scaleX = artScale.value
                        scaleY = artScale.value
                        // 首卡往右扩：绕中心放大把左缘往左推了半个增量，行首卡就比下面各行更靠左，
                        // 用平移精确补回来（和经典版同一个做法）。
                        if (anchorStart) translationX = (artScale.value - 1f) * size.width / 2f
                    }
                )
                .clip(cardShape)
                .tvFocusRing(focused)
        ) {
            // 内容体单独一个组合函数、只收稳定原语参数：焦点态翻转会重组外壳（层级 / 位移都跟着变），
            // 参数没变时内容体整块被跳过，图片不跟着重排。一屏几十张卡，这块不切开就白吃一遍布局。
            NfPosterBody(
                model = item.posterUrl.ifBlank { item.backdropUrl },
                title = item.title,
                fillCell = fillCell,
                width = width
            )
        }
        Text(
            item.title,
            color = TvText.Section,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * [NfPosterCard] 的图片体：海报，或者按片名哈希出来的渐变封面。
 *
 * 参数刻意只有 [String] / [Boolean] / [Dp] 这类稳定原语，这样它是可跳过的 ——
 * 焦点态翻转只重组外壳，这块原样复用。
 *
 * 封面不再排片名（[PosterFallback] 的 showTitle 关掉，只留一个首字水印）：
 * 名字已经排在盒子外面了，图里再排一遍就是重复。
 */
@Composable
private fun NfPosterBody(
    model: String,
    title: String,
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
                showTitle = false,
                titleFontSize = 30.sp
            )
        }
    }
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
    anchorStart: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current
    val liftPx = with(density) { CardLift.toPx() }
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
            // 焦点目标（clickable）必须排在 graphicsLayer 前面：
            // 缩放和上抬会进 Compose 的焦点矩形（rectInParent 里 layer.mapBounds），
            // 获焦卡一大，同一行的邻卡全变成「在它下面」的合法候选，
            // ↓ 被吃掉去横向跳格，Lazy 行也不再补组合下一行。
            // 变换挪到 clickable 之后，焦点矩形回到卡片本身（与 NfPosterCard 同一顺序）。
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationY = -lift.value
                if (anchorStart) translationX = (scale.value - 1f) * size.width / 2f
                shape = cardShape
                clip = true
            }
            .background(AppColors.SurfaceHi)
            .tvFocusRing(focused)
            .then(if (focused) Modifier else Modifier.border(1.dp, Edge.Hairline, cardShape))
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
    firstChipFocus: FocusRequester? = null,
    /**
     * 逐颗 chip 的锚点，由调用方持有：返回键那一层要落回「当前选中的那颗」分类，
     * 光有第一颗的锚点不够。不传就用内建那一份（只能落到第一颗）。
     */
    chipFocuses: List<FocusRequester>? = null
) {
    val state = rememberLazyListState()
    // 每颗 chip 一个锚点：左右移动逐颗交棒，不靠几何搜索。
    // 分类条正上方就是一级 tab 栏，几何搜索一旦在 LazyRow 里找不到邻项
    // （滚动中邻项被回收那一帧）就会把焦点甩到 tab 上去。
    val builtInAnchors = remember(labels.size) { List(labels.size) { FocusRequester() } }
    val anchors = chipFocuses ?: builtInAnchors
    LaunchedEffect(selectedIndex) {
        // 选中项被程序改掉（切来源后分类重置）或被点选时要把它带进视野，否则高亮停在屏幕外。
        // 只滚到「可见」不够：选中的那颗贴在最左/最右边缘时，左右邻项还没组合，
        // 按一下左右交棒就落空；所以要么不滚（已经在视野中间），要么留出邻项的位置。
        if (selectedIndex !in labels.indices) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            // 首帧还没布局：先把它摆到偏左两格的位置，左右都留出邻项
            state.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
            return@LaunchedEffect
        }
        val first = visible.first().index
        val last = visible.last().index
        val count = last - first + 1
        val target = when {
            selectedIndex <= first -> (selectedIndex - 1).coerceAtLeast(0)
            selectedIndex >= last -> (selectedIndex - count + 2).coerceAtLeast(0)
            else -> null
        }
        if (target != null) state.animateScrollToItem(target)
    }
    val chipBar = rememberNfBarFocus()
    Box(modifier.nfBarFrameHost(chipBar)) {
        LazyRow(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            state = state,
            verticalAlignment = Alignment.CenterVertically,
            // 纵向留白：框画在 chip 边界之内，这 6dp 只是让它不贴着导航条和网格
            contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(labels) { index, label ->
                NfSubChip(
                    text = label,
                    selected = index == selectedIndex,
                    // 手指点按不产生方向键事件，clickable 在触摸模式下不会自己抢焦：
                    // 不补这一下，焦点框会一直停在顶栏那颗 tab 上（遥控器上看不出来，手机上一点就露）。
                    // 必须先交焦点再换分类：换分类会触发本页入口抢焦，它靠「分类栏是否持焦」决定不把焦点拽走。
                    onClick = {
                        tvMoveFocus(anchors[index])
                        onSelect(index)
                    },
                    modifier = Modifier
                        .nfFocusRoute(
                            up = upFocusRequester,
                            down = downFocusRequester,
                            left = if (index == 0) FocusRequester.Cancel else anchors[index - 1],
                            right = if (index == labels.lastIndex) FocusRequester.Cancel else anchors[index + 1]
                        )
                        .nfBarFrameCoords(chipBar, index)
                        .focusRequester(anchors[index])
                        .then(if (index == 0 && firstChipFocus != null) Modifier.focusRequester(firstChipFocus) else Modifier),
                    bar = chipBar,
                    index = index
                )
            }
        }
        Spacer(Modifier.matchParentSize().nfBarFrameOverlay(chipBar))
    }
}

/**
 * 二级分类的一颗 chip：与一级导航 [NfNavTab] 同一套视觉语言 ——
 * 选中 = 亮字加粗 + 主题色下划线，焦点 = 整条分类条上那圈移动的白描边，都不放大。
 * 字号比一级小一档（labelLarge），纵向内边距收紧，整条分类栏贴着导航条排。
 */
@Composable
private fun NfSubChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    bar: NfBarFocus,
    index: Int,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 焦点所在的 chip 不许被 LazyRow 回收：1.9 回收获焦项会直接 clearFocus，
    // 焦点随后落到整页第一个可聚焦节点 —— 也就是一级 tab 栏
    tvKeepFocusedItemAlive(focused)
    NfBarFrameItem(bar, index, focused)
    Column(
        modifier
            .nfBarFrameFallback(bar, focused)
            // 同一级 tab：下划线 fillMaxWidth 对齐字宽，Column 收在固有宽度，不撑满 LazyRow
            .width(IntrinsicSize.Max)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = if (focused || selected) TvText.Title else TvText.Body,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier
                .padding(top = 4.dp)
                .height(NfSelectedRuleHeight)
                .fillMaxWidth()
                .clip(Radius.Pill)
                .background(if (selected) AppColors.Brand else Color.Transparent)
        )
    }
}

/**
 * 竖版海报网格（分类页 / 我的页 / 搜索结果共用）。
 * 行间距取 [NfRowVerticalPadding]，容得下焦点抬起的 [PosterLift]；首行上方另给 [NfGridTopPadding]，
 * 因为纵向滚动容器贴着容器上下边缘裁剪，首行上面没有下一行兜着。
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
    /**
     * 由调用方持有的滚动 state：分类页的返回键要就地把网格滚回顶部，
     * 而 [ReturnSpot.gridState] 是按 key `remember` 的，页面里再调一次拿到的是另一个实例。
     * 不传就按 [pageKey] 内部建（我的页 / 搜索结果不需要外部控制）。
     */
    state: LazyGridState? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val columns = tvPosterColumns()
    // 记录要在建 gridState 之前读出来：数据还没补齐时 LazyGrid 会把位置 clamp 到末尾，
    // 并通过 gridState 的写回把原记录改小，之后就再也回不到离开时那一屏
    val saved = remember(pageKey) { ReturnSpot.scrollOf(pageKey) }
    val gridState = state ?: ReturnSpot.gridState(pageKey, precompose = PrecomposeWindow.Tv)
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
            top = NfGridTopPadding, bottom = 24.dp
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

/**
 * 来源分组：一个来源一组。
 *
 * TVBox 一份配置（含它下面所有站点、所有线路）是一组 —— 首页「继续观看」要的就是
 * 「这个 TVBox 来源追过的剧」整份摆在一行，不按线路再拆；Emby 一台服务器一组，
 * 同名也不会被并到一起。
 *
 * 分组键用来源 id：落库的条目 sourceId 就是它；远程搜索结果不落库、sourceId 是 0，
 * 只能从 remoteId（`remote:来源id:站点:vodId`）里取，取不到才退回来源名，
 * 免得一批脏数据全挤进「来源0」。
 */
internal class NfSourceGroup(
    val key: String,
    val label: String,
    /** 这组是不是 TVBox 来源：只有它才在下面按线路（playSource）再铺一层 */
    val isTvBox: Boolean,
    val items: List<MediaEntity>,
)

internal fun nfGroupBySource(items: List<MediaEntity>, sources: List<SourceEntity>): List<NfSourceGroup> {
    val kindsById = sources.associate { it.id to it.kind }
    val grouped = LinkedHashMap<String, Pair<String, MutableList<MediaEntity>>>()
    // 分组键 → 是不是 TVBox 来源
    val kinds = LinkedHashMap<String, Boolean>()
    for (item in items) {
        val sourceId = item.nfSourceGroupId
        val key = if (sourceId != 0L) "src:$sourceId" else "name:${item.sourceName.ifBlank { "未知来源" }}"
        val isTvBox = when (val kind = if (sourceId != 0L) kindsById[sourceId] else null) {
            // 来源被删或禁用后 kindsById 查不到，只能靠 remoteId 的前缀认
            null -> item.remoteId.startsWith("tvbox:") || item.remoteId.startsWith("remote:")
            else -> kind == SourceKind.TVBOX
        }
        kinds[key] = kinds[key] ?: isTvBox
        grouped.getOrPut(key) { item.sourceName.ifBlank { "未知来源" } to mutableListOf() }.second.add(item)
    }
    // 来源名可以重名（挂两台同名 Emby），重名时补来源 id，否则表头看不出这是两组
    val labelCounts = grouped.values.groupingBy { it.first }.eachCount()
    return grouped.entries.map { (key, labelAndItems) ->
        val duplicated = (labelCounts[labelAndItems.first] ?: 1) > 1 && key.startsWith("src:")
        val label = if (duplicated) "${labelAndItems.first} #${key.substringAfter(":")}" else labelAndItems.first
        NfSourceGroup(key, label, kinds[key] == true, labelAndItems.second.toList())
    }
}

/** 条目所属来源 id：见 [nfGroupBySource] */
private val MediaEntity.nfSourceGroupId: Long
    get() {
        if (sourceId != 0L) return sourceId
        val parts = remoteId.split(":")
        if (parts.size >= 2 && parts[0] == "remote") return parts[1].toLongOrNull() ?: 0L
        return 0L
    }
