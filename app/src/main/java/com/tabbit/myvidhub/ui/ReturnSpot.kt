package com.tabbit.myvidhub.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

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

/** 列表（纵向/横向都用它）：按记录里的位置起滚，之后的滚动实时记回去。 */
@Composable
fun ReturnSpot.listState(key: String): LazyListState {
    val saved = scrollOf(key)
    // 不用 rememberLazyListState：它内部走 rememberSaveable，恢复时机和值都不由这里决定，
    // 而这套记录要的就是「组合重建的第一帧就站在离开时的位置」。
    val state = remember(key) { LazyListState(saved?.index ?: 0, saved?.offset ?: 0) }
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> saveScroll(key, ReturnSpot.Scroll(index, offset)) }
    }
    return state
}

/** 网格页（分类 / 查看全部 / 搜索）的版本 */
@Composable
fun ReturnSpot.gridState(key: String): LazyGridState {
    val saved = scrollOf(key)
    val state = remember(key) { LazyGridState(saved?.index ?: 0, saved?.offset ?: 0) }
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
                saveStand(pageKey, ReturnSpot.cardSpot(rowKey, mediaId))
                target.requester = requester
            }
        }
}
