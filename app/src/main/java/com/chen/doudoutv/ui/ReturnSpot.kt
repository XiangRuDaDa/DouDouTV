package com.chen.doudoutv.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/**
 * 「离开时站在哪、回来还站在哪」的进程级记录。
 *
 * 为什么要单独一个对象，而不是 remember / ViewModel：
 *  · TV 端和手机端的页面切换都是 AnimatedContent 换整棵子树 —— 进详情时首页那一棵树被整个销毁，
 *    组合级的 remember 全没了，退回来必然从第一行、Hero 播放键重新开始；
 *  · MainActivity 会被爬虫壳的 finish() 触发 recreate()，ViewModel 跟着一起清；
 *  · 记录就是几个整数加一个字符串，进程级最省事，新进程自然从零开始。
 *
 * 两类记录：
 *  · [Scroll] —— 每个滚动容器一条（页面的纵向列表、首页每行的横向列表），滚动时实时写回；
 *  · stand —— 每个页面一条，记「焦点最后停在哪个落点」。卡片落点用 [#cardSpot] 编码，
 *    固定按钮（详情页播放键、选集控制条）直接用名字。
 */
object ReturnSpot {

    /** 滚动位置：纵向列表用 index+offset，纯 ScrollState 的页面只用 offset */
    data class Scroll(val index: Int, val offset: Int)

    private val scrolls = HashMap<String, Scroll>()
    private val stands = HashMap<String, String>()

    @Synchronized
    fun scrollOf(key: String): Scroll? = scrolls[key]

    @Synchronized
    fun saveScroll(key: String, scroll: Scroll) {
        scrolls[key] = scroll
    }

    @Synchronized
    fun standOf(pageKey: String): String? = stands[pageKey]

    @Synchronized
    fun saveStand(pageKey: String, spot: String) {
        stands[pageKey] = spot
    }

    /** 卡片落点：`行key#媒体id`。用 id 认卡，行内顺序变了也不会认错成别的片子。 */
    fun cardSpot(rowKey: String, mediaId: Long): String = "$rowKey#$mediaId"

    /** 离开时是不是站在 [rowKey] 这一行的某张卡上；是就返回那张卡的媒体 id */
    fun standCard(pageKey: String, rowKey: String): Long? {
        val spot = standOf(pageKey) ?: return null
        val i = spot.lastIndexOf('#')
        if (i <= 0 || i == spot.length - 1) return null
        if (spot.substring(0, i) != rowKey) return null
        return spot.substring(i + 1).toLongOrNull()
    }
}

/**
 * 落点卡片 requester 的交接点：卡片拿到焦点时、或被确认「就是离开时那张卡」时，
 * 把自己的 requester 放进来；页面抢焦点时把它取出去当第一顺位锚点，取不到就退回入口锚点。
 */
class StandTarget {
    var requester: FocusRequester? = null
}

/**
 * 焦点活动脉冲：卡片焦点态一翻就打点（见 [cardStand] 的 onFocusChanged）。
 *
 * 为什么需要它：移动焦点时列表并不在滚动，只看 [LazyListState.isScrollInProgress] 的「让路」
 * 判断整个失效 —— hero 的 Ken Burns 全屏缩放正好和卡片放大动画撞在同一帧，
 * 表现就是「按方向键就掉帧」。
 *
 * [movingState] 在每次打点后保持 [QUIET_MS] 毫秒（覆盖卡片 120ms 的放大动画加余量），
 * 连续按方向键会不断续期。写状态用主线程 Handler 定时复位，不需要协程作用域，
 * 也就不会把复位绑死在某个正在组合的元件上。
 */
object FocusPulse {
    private const val QUIET_MS = 260L

    private val moving = mutableStateOf(false)

    /** 还有人在动（焦点刚换过卡） */
    val movingState: State<Boolean> get() = moving

    private val mainHandler = Handler(Looper.getMainLooper())
    private val settle = Runnable { moving.value = false }

    /** 卡片焦点态翻转时调用 */
    fun flip() {
        moving.value = true
        mainHandler.removeCallbacks(settle)
        mainHandler.postDelayed(settle, QUIET_MS)
    }
}

/**
 * 预组合档位（Compose 1.9 新增的 [LazyLayoutCacheWindow]）。
 *
 * 视口外再多留这么一截，容器会提前把相邻的行组合好：
 *  · 滚动时不再出现「先空白、下一帧才有图」的空行、空卡，甩动更连贯；
 *  · 上下滚动回来看过的内容不用重新组合，来回切更跟手。
 *
 * 窗口按「一屏里装得下几行」定，不按视口比例定 —— 比例在电视（960×540dp）和
 * 手机（窄长屏）上的含义差太远，按 dp 定量才 predictable：
 *  · 电视端一屏 6 列，网格一行约 230dp、首页横排一格约 142dp。
 *    往前 480dp 够两行网格或三格横排，往后 240dp 够一行。
 *  · 手机端一格约 122dp、一行海报约 165dp。往前 280dp 够两格多，往后 140dp 够一格。
 *
 * 用枚举而不是直接把 [LazyLayoutCacheWindow] 暴露在签名里：那个类型带
 * `@ExperimentalFoundationApi`，出现在公开签名上会让每个调用点都被迫写 opt-in，
 * 二十多个页面挨个加注解不划算。这里把实验 API 关在这一个文件里。
 */
enum class PrecomposeWindow {
    /** 不预组合：保持 Compose 默认行为（视口外即释放） */
    Off,

    /** 电视端：往前两行、往后一行 */
    Tv,

    /** 手机端：往前两格多、往后一格 */
    Phone
}

/**
 * 建带预组合窗口的列表 state。
 *
 * 窗口类型是实验 API，所以整个构造过程关在这个私有函数里 ——
 * 公开的 [listState] / [gridState] 签名上只出现 [PrecomposeWindow] 这个普通枚举，
 * 调用方（二十多个页面）一个 opt-in 注解都不用写。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun newListState(tier: PrecomposeWindow, index: Int, offset: Int): LazyListState = when (tier) {
    PrecomposeWindow.Off -> LazyListState(index, offset)
    PrecomposeWindow.Tv -> LazyListState(LazyLayoutCacheWindow(ahead = 480.dp, behind = 240.dp), index, offset)
    PrecomposeWindow.Phone -> LazyListState(LazyLayoutCacheWindow(ahead = 280.dp, behind = 140.dp), index, offset)
}

/** 网格版的同款构造 */
@OptIn(ExperimentalFoundationApi::class)
private fun newGridState(tier: PrecomposeWindow, index: Int, offset: Int): LazyGridState = when (tier) {
    PrecomposeWindow.Off -> LazyGridState(index, offset)
    PrecomposeWindow.Tv -> LazyGridState(LazyLayoutCacheWindow(ahead = 480.dp, behind = 240.dp), index, offset)
    PrecomposeWindow.Phone -> LazyGridState(LazyLayoutCacheWindow(ahead = 280.dp, behind = 140.dp), index, offset)
}

/** 列表（纵向/横向都用它）：按记录里的位置起滚，之后的滚动实时记回去。 */
@Composable
fun ReturnSpot.listState(key: String, precompose: PrecomposeWindow = PrecomposeWindow.Off): LazyListState {
    val saved = scrollOf(key)
    // 不用 rememberLazyListState：它内部走 rememberSaveable，恢复时机和值都不由这里决定，
    // 而这套记录要的就是「组合重建的第一帧就站在离开时的位置」。
    val state = remember(key) {
        newListState(precompose, saved?.index ?: 0, saved?.offset ?: 0)
    }
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> saveScroll(key, ReturnSpot.Scroll(index, offset)) }
    }
    return state
}

/** 网格页（分类 / 查看全部 / 搜索）的版本 */
@Composable
fun ReturnSpot.gridState(key: String, precompose: PrecomposeWindow = PrecomposeWindow.Off): LazyGridState {
    val saved = scrollOf(key)
    val state = remember(key) {
        newGridState(precompose, saved?.index ?: 0, saved?.offset ?: 0)
    }
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> saveScroll(key, ReturnSpot.Scroll(index, offset)) }
    }
    return state
}

/**
 * 卡片落点：焦点停在一张海报卡上时，行 key + 卡片 id 一起记进 [ReturnSpot]（页面标识由调用方给）。
 *
 * requester 由调用方传进来，而不是这里每条卡自己生成：网格页的首卡还挂着页面级的入口锚点，
 * 同一焦点节点挂两个 requester 在 1.6.8 上不可靠，所以记账必须复用调用方已有的那一个。
 *
 * 恢复走「交接点」[StandTarget] 而不是页面级共用一个 requester，因为两种回来的路径不一样：
 *  · 从详情页返回 —— 这一页的子树被整个销毁重建，卡片会重新生成自己的 requester，
 *    所以由「我就是离开时那张卡」的卡片在组合时把新的 requester 交出去；
 *  · 从播放器返回 —— 子树还在，requester 一直是活的，焦点上一次拿到时就已经交出去了。
 */
@Composable
fun ReturnSpot.cardStand(
    pageKey: String,
    rowKey: String,
    mediaId: Long,
    target: StandTarget,
    requester: FocusRequester
): Modifier {
    if (mediaId == standCard(pageKey, rowKey)) {
        // 普通对象字段、不是 Compose 状态：交出 requester 不该把整行拖进重组
        remember(requester) { target.requester = requester }
    }
    return Modifier
        .focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus) {
                // 焦点一落卡就打点，让 hero 的全屏 Ken Burns 让路（见 [FocusPulse]）
                FocusPulse.flip()
                saveStand(pageKey, ReturnSpot.cardSpot(rowKey, mediaId))
                target.requester = requester
            }
        }
}
