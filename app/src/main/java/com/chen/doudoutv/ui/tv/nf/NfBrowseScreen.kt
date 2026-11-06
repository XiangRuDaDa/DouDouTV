package com.chen.doudoutv.ui.tv.nf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.StandTarget
import com.chen.doudoutv.ui.components.ShimmerCard
import com.chen.doudoutv.ui.tv.TvPagePadding
import com.chen.doudoutv.ui.tv.tvClaimFocusOnEnter
import com.chen.doudoutv.ui.tv.tvGridColumns
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.TvText

/** [ReturnSpot] 的页面标识：每个分类各记一份滚动位置与落点，key 里直接带上分类 */
private const val NfBrowseKey = "nf_browse"

/**
 * 「分类」页：首页挪走的那些分类行在这里整屏铺开。
 *
 * 两条数据链路按来源类型分开：
 *   · TVBox —— 用源站下发的分类，内容走「查看更多」那套在线分页（[LibraryViewModel.browseItems]）；
 *     分页必须落到具体线路，所以「全部站点」时这一页没有在线内容可拉，给切换入口。
 *   · Emby —— 用入库的 genres 字段聚合出动态分类，纯本地、不产生网络请求。
 *
 * 版式：导航条 → 贴头的分类 chip 条 → 整屏竖版海报网格。页标题与条目计数不占位。
 */
@Composable
fun NfBrowseScreen(
    vm: LibraryViewModel,
    onTab: (NfTab) -> Unit,
    onDetail: (Long) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val sources by vm.enabledSources.collectAsStateWithLifecycle()
    val selectedSource = sources.firstOrNull { it.id == vm.homeSourceId } ?: sources.firstOrNull()
    val isTvBox = selectedSource?.kind == SourceKind.TVBOX

    val chrome = rememberNfChrome()
    val stand = remember { StandTarget() }

    // 换来源 / 换线路把选择重置：两个维度下的分类集合完全不同；
    // 「进详情再返回」是同一个 scopeKey，选择要从进程级记忆接着上次读 —— 落回第一个分类会被读成「返回时刷新了数据」
    val scopeKey = "${selectedSource?.id}_${vm.homeSiteKey}"
    var onlineIdx by remember(scopeKey) { mutableStateOf(NfBrowseMemory.get("on_$scopeKey") ?: 0) }
    var localIdx by remember(scopeKey) { mutableStateOf(NfBrowseMemory.get("lo_$scopeKey") ?: 0) }

    val onlineCats = vm.homeCategories
    val localItems = remember(library, vm.homeSourceId, vm.homeSiteKey) { vm.filterForHome(library) }
    val localCats = remember(localItems) { nfLocalCategories(localItems) }

    val safeOnlineIdx = onlineIdx.coerceIn(0, (onlineCats.size - 1).coerceAtLeast(0))
    val safeLocalIdx = localIdx.coerceIn(0, (localCats.size - 1).coerceAtLeast(0))
    val currentTypeId = onlineCats.getOrNull(safeOnlineIdx)?.typeId

    val labels = if (isTvBox) onlineCats.map { it.typeName } else localCats.map { it.name }
    val selIndex = if (isTvBox) safeOnlineIdx else safeLocalIdx
    val showSubNav = labels.size > 1
    val firstChipFocus = remember(labels.size) { FocusRequester() }
    // 网格首行按 ↑ 的落点：有分类条交回第一颗 chip，只有一个分类时交回本页那颗「分类」tab（规范 R1）
    val gridUpFocus = if (showSubNav) firstChipFocus else chrome.tabOf(NfTab.Browse)

    // 记忆的是「修正后」的下标：分类集合变短时被 clamp 过，下次进来不该再跳一格
    LaunchedEffect(scopeKey, selIndex) {
        NfBrowseMemory.save(if (isTvBox) "on_$scopeKey" else "lo_$scopeKey", selIndex)
    }

    // 本页只抢一次焦：有内容交给网格首卡，没内容交给空态那颗按钮，
    // 不出现「没人持焦、按一下才亮」（规范 R6/R7）
    val sitePicked = vm.homeSiteKey != null
    val onlineItems = if (isTvBox && sitePicked) vm.browseItems(currentTypeId.orEmpty()) else emptyList()
    val localShown = if (!isTvBox) localCats.getOrNull(safeLocalIdx)?.let { cat -> localItems.filter(cat.matches) }
        ?: emptyList() else emptyList()
    val contentReady = if (isTvBox) sitePicked && onlineItems.isNotEmpty() else localShown.isNotEmpty()
    // 分类名要等这条线路的 homeContent 回来才有：换线路那次拉取不带 scanning，光看 scanning 会把「还在读」误报成「没有分类」
    val catsLoading = vm.scanning || vm.homeSiteLoading

    // 页面锚点按「首卡是谁」重新生成，网格与空态 CTA 各用一个实例：
    // 同一实例先后挂在两个节点上时，旧节点销毁后实例仍留着那条记录，requestFocus 不抛异常但焦点不动
    val gridFirstId = (if (isTvBox) onlineItems else localShown).firstOrNull()?.id
    val gridFirstFocus = remember(gridFirstId) { FocusRequester() }
    val emptyFocus = remember(contentReady) { FocusRequester() }
    /** 这一屏真正能接住 ↓ 的元件：有内容是网格首卡，没内容是空态那颗按钮 */
    val contentAnchor = if (contentReady) gridFirstFocus else emptyFocus

    // 首屏数据到位后把焦点交给卡片，遥控器立刻可用；离开时站在哪张卡就还给哪张（规范 R6/R7）
    tvClaimFocusOnEnter(
        tick = listOf(contentReady, scopeKey),
        anchorsProvider = { listOfNotNull(stand.requester, contentAnchor) }
    )

    LaunchedEffect(currentTypeId, vm.homeRefreshEpoch) {
        if (isTvBox && sitePicked) currentTypeId?.let { vm.ensureBrowsePage(it) }
    }

    NfScaffold(
        vm = vm,
        current = NfTab.Browse,
        chrome = chrome,
        // 每一向都显式给落点（规范 R1）：有分类条 → 第一颗 chip，没有 → 这一屏的接焦元件
        navInFlow = true,
        belowFocusRequester = if (showSubNav) firstChipFocus else contentAnchor,
        onTab = onTab,
        onSettings = onSettings,
        onManageSources = onSources
    ) {
        Column(Modifier.fillMaxSize()) {
            if (showSubNav) {
                NfSubNav(
                    labels = labels,
                    selectedIndex = selIndex,
                    onSelect = { i -> if (isTvBox) onlineIdx = i else localIdx = i },
                    downFocusRequester = contentAnchor,
                    upFocusRequester = chrome.tabOf(NfTab.Browse),
                    firstChipFocus = firstChipFocus
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !isTvBox && localShown.isEmpty() -> NfInlineEmpty(
                        text = if (vm.scanning) "正在同步这个 Emby 媒体库…" else "这个库里还没有可分类的内容",
                        hint = if (vm.scanning) null else "先在顶栏刷新，或切换到别的来源",
                        actionLabel = if (vm.scanning) null else "↻ 立即同步",
                        onAction = if (vm.scanning) null else { { vm.refreshHome() } },
                        focusRequester = emptyFocus
                    )

                    !isTvBox -> NfPosterGrid(
                        items = localShown,
                        pageKey = "$NfBrowseKey/local_${localCats.getOrNull(safeLocalIdx)?.name}",
                        stand = stand,
                        firstFocus = gridFirstFocus,
                        upFocusRequester = gridUpFocus,
                        onDetail = onDetail,
                        modifier = Modifier.fillMaxSize()
                    )

                    !sitePicked -> NfInlineEmpty(
                        text = "TVBox 要先选定一条线路",
                        hint = "分类内容按线路请求，「全部站点」是聚合视图、没有分页入口",
                        actionLabel = "选择线路",
                        onAction = { chrome.openScopeDrawer = true },
                        focusRequester = emptyFocus
                    )

                    onlineCats.isEmpty() -> NfInlineEmpty(
                        // 切来源/切线路后 homeContent 还在路上（换线路那次拉取不带 scanning 标志），
                        // 这时候判「没有下发分类」是误报
                        text = if (catsLoading) "正在读取这条线路的分类…" else "这条线路没有下发分类",
                        hint = if (catsLoading) null else "换一条线路，或先在来源里同步",
                        actionLabel = if (catsLoading) null else "↻ 立即同步",
                        onAction = if (catsLoading) null else { { vm.refreshHome() } },
                        focusRequester = emptyFocus
                    )

                    onlineItems.isEmpty() && vm.browseItemsError(currentTypeId.orEmpty()) != null -> NfInlineEmpty(
                        text = "加载失败：${vm.browseItemsError(currentTypeId.orEmpty())}",
                        hint = "重试这一页，或者换一条线路",
                        actionLabel = "重试",
                        onAction = { currentTypeId?.let { vm.retryBrowsePage(it) } },
                        focusRequester = emptyFocus
                    )

                    onlineItems.isEmpty() -> NfSkeletonGrid()

                    else -> NfPosterGrid(
                        items = onlineItems,
                        pageKey = "$NfBrowseKey/$currentTypeId",
                        stand = stand,
                        firstFocus = gridFirstFocus,
                        upFocusRequester = gridUpFocus,
                        onDetail = onDetail,
                        modifier = Modifier.fillMaxSize(),
                        onNearEnd = { currentTypeId?.let { vm.loadMoreBrowsePage(it) } },
                        footer = {
                            NfGridFooter(
                                loading = vm.browseItemsLoading(currentTypeId.orEmpty()),
                                ended = vm.browseItemsEnded(currentTypeId.orEmpty()),
                                error = vm.browseItemsError(currentTypeId.orEmpty())
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * 分类选择进度表：按「来源_线路」记住这一路选到第几个分类。
 *
 * 放在进程级而不是 ViewModel：本页组件在进详情时被摘出组合，`remember` 会连状态一起销毁，
 * 返回后重新从 0 开始就等于换了一个分类，整屏内容跟着换 —— 用户读到的是「返回时刷新了」。
 */
private object NfBrowseMemory {
    private val selected = HashMap<String, Int>()
    fun get(key: String): Int? = synchronized(selected) { selected[key] }
    fun save(key: String, index: Int): Unit = synchronized(selected) { selected[key] = index }
}

/** 网格底部那一行的状态：加载中 / 已到底 / 上一页失败 */
@Composable
private fun NfGridFooter(loading: Boolean, ended: Boolean, error: String?) {
    val text = when {
        loading -> "加载中…"
        error != null -> "下一页失败：$error"
        ended -> "已经到底了"
        else -> ""
    }
    if (text.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = AppColors.Brand,
                strokeWidth = 2.dp,
                modifier = Modifier.width(22.dp).height(22.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = TvText.Meta, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 首屏骨架：列数与真实网格一致，卡片陆续替换时不会整屏抖 */
@Composable
private fun NfSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(tvGridColumns()),
        contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = NfRowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(NfCardGap),
        verticalArrangement = Arrangement.spacedBy(NfRowVerticalPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        items(18) { ShimmerCard() }
    }
}

/**
 * Emby 的动态分类：全部 → 类型（电影 / 剧集）→ 入库 genres 里按出现次数排前的标签。
 *
 * 分类集合完全由已同步到 media 表的数据算出来，因此换来源、换库都自动跟着变，
 * 也不需要额外请求 Emby 的 /Genres 接口。
 */
private data class NfLocalCategory(val name: String, val matches: (MediaEntity) -> Boolean)

private val NfGenreSeparator = Regex("[,，、|/]+")

/** 这些标签本身就是类型，已经做成「电影 / 剧集」两颗 chip，不再重复出现在题材列表里 */
private val NfKindLikeGenres = setOf("电影", "剧集", "电视剧", "动漫", "综艺")

private fun MediaEntity.genreTokens(): List<String> =
    genres.split(NfGenreSeparator).map { it.trim() }.filter { it.isNotBlank() }

private fun nfLocalCategories(items: List<MediaEntity>): List<NfLocalCategory> {
    val out = mutableListOf<NfLocalCategory>()
    out += NfLocalCategory("全部") { true }
    val movieCount = items.count { it.kind == MediaKind.MOVIE }
    val seriesCount = items.size - movieCount
    if (movieCount > 0) out += NfLocalCategory("电影") { it.kind == MediaKind.MOVIE }
    if (seriesCount > 0) out += NfLocalCategory("剧集") { it.kind != MediaKind.MOVIE }

    val counts = LinkedHashMap<String, Int>()
    items.forEach { item ->
        item.genreTokens().distinct().forEach { tag ->
            if (tag !in NfKindLikeGenres) counts[tag] = (counts[tag] ?: 0) + 1
        }
    }
    counts.entries
        .sortedByDescending { it.value }
        .take(14)
        .forEach { (tag, _) ->
            out += NfLocalCategory(tag) { it.genreTokens().contains(tag) }
        }
    return out
}
