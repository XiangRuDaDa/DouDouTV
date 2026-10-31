package com.tabbit.myvidhub.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.ReturnSpot
import com.tabbit.myvidhub.ui.StandTarget
import com.tabbit.myvidhub.ui.cardStand
import com.tabbit.myvidhub.ui.components.ShimmerCard
import com.tabbit.myvidhub.ui.gridState
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.themeBackdrop
import com.tabbit.myvidhub.ui.theme.TvText
import kotlinx.coroutines.CancellationException

private const val TV_PAGE_SIZE = 60

/** 题材页在 [ReturnSpot] 里的页面标识：滚动位置按「页 + 筛选」分键，落点按页记录 */
private const val TvGenrePageKey = "tv_genre"

/**
 * 电视端分类/浏览页。
 *
 * 与手机端分类页的区别：
 *   · 6 列大卡网格（手机 3 列），每屏信息量适合沙发远观；
 *   · 题材筛选是一整行可左右滚动的横条而不是换行 chips，遥控器只需左右移动不会"卡在换行处"；
 *   · 滚动到底自动加载下一页，同时只在接近底部时触发，避免一次拉满导致卡顿。
 */
@Composable
fun TvGenreScreen(
    vm: LibraryViewModel,
    initialKind: String?,
    initialGenre: String?,
    onBack: () -> Unit,
    onDetail: (Long) -> Unit
) {
    var kind by remember { mutableStateOf(initialKind) }
    var genre by remember { mutableStateOf(initialGenre) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }

    // 落点记录按「页 + 当前筛选」分键：换题材后各算各的位置，回来才不会被别的筛选的记录带偏
    val filterKey = "$TvGenrePageKey/${kind ?: "all"}/${genre ?: "all"}"
    // 记录先读出来再建 gridState：首屏只有一页数据时位置会被 clamp 到末尾并写回记录，
    // 之后就拿不到离开时的真实位置了。
    val savedScroll = remember(filterKey) { ReturnSpot.scrollOf(filterKey) }
    val gridState = ReturnSpot.gridState(filterKey)
    val firstCardFocus = remember { FocusRequester() }
    val stand = remember { StandTarget() }
    val firstId = items.firstOrNull()?.id
    /** 落点是否已解析完（该补的页补到位）；补页期间不抢焦点，免得焦点先落到首卡再跳。 */
    var spotReady by remember(filterKey) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tags = runCatching { vm.genreTags() }.getOrDefault(emptyList())
    }

    // 条件变化时重置并加载首屏
    LaunchedEffect(kind, genre) {
        loading = true
        endReached = false
        spotReady = false
        items = runCatching { vm.browseGenre(kind, genre, TV_PAGE_SIZE, 0) }
            .getOrDefault(emptyList())
        if (items.size < TV_PAGE_SIZE) endReached = true
        // 从详情页返回：离开时那张卡可能在后面的页里，不补到它出现，
        // 它就不在组合树里、没人交出 requester，焦点只能退回第一张卡。
        val targetId = ReturnSpot.standCard(TvGenrePageKey, filterKey)
        while (targetId != null && items.none { it.id == targetId } && !endReached) {
            val more = runCatching { vm.browseGenre(kind, genre, TV_PAGE_SIZE, items.size) }
                .getOrDefault(emptyList())
            if (more.isEmpty()) {
                endReached = true
                break
            }
            items = items + more
            if (more.size < TV_PAGE_SIZE) endReached = true
        }
        savedScroll?.let { runCatching { gridState.scrollToItem(it.index, it.offset) } }
        loading = false
        spotReady = true
    }

    // 接近底部时增量加载。state 按筛选分键，换筛选会换成另一个实例，
    // 所以 derivedStateOf 也必须跟着重建，否则一直读旧 state 的 layoutInfo、翻页永远不触发。
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 8
        }
    }
    LaunchedEffect(shouldLoadMore, endReached, loading) {
        if (!shouldLoadMore || endReached || loading || loadingMore) return@LaunchedEffect
        loadingMore = true
        // 取消（换筛选 / 退出页面）要原样抛出去，不能当成「没有更多」——
        // 吞掉会让 endReached 误置 true，列表停在页边界上。
        val more = try {
            vm.browseGenre(kind, genre, TV_PAGE_SIZE, items.size)
        } catch (e: CancellationException) {
            loadingMore = false
            throw e
        } catch (e: Exception) {
            loadingMore = false
            return@LaunchedEffect
        }
        if (more.isEmpty()) endReached = true else items = items + more
        loadingMore = false
    }

    // 首屏数据到位后把焦点交给卡片。这一页原本没有任何抢焦，进页后焦点落在
    // 谁身上完全交给默认焦点搜索（同文件的分类页是主动抢的），表现就是第一下方向键像没反应。
    // 离开时站在哪张卡上就还给那张卡（落点由卡片在组合时交出 requester），
    // 没有记录（首次进入 / 换了筛选）才退回第一张。
    tvClaimFocusOnEnter(
        tick = spotReady,
        anchorsProvider = { listOfNotNull(stand.requester, firstCardFocus) }
    )

    // 题材标签：全部 / 电影 / 剧集 / 动漫 + 库内高频题材
    //
    // 库内题材必须按标签去重后再追加：TVBox 的类型名常常就叫「电影」，直接 append 会出现
    // 两张一模一样的 chip，而 chip 的 key 用的是标签本身 —— LazyRow 撞到重复 key 会直接崩
    // （java.lang.IllegalArgumentException: Key "chip_电影" was already used）。
    val chips = remember(tags) {
        val fixed = listOf(
            Triple("全部", null as String?, null as String?),
            Triple("电影", MediaKind.MOVIE, null),
            Triple("剧集", MediaKind.SERIES, null),
            Triple("动漫", null, "动漫")
        )
        val labels = fixed.mapTo(mutableSetOf()) { it.first }
        fixed + tags.filter { it !in labels }.distinct().map { Triple(it, null as String?, it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = TvPagePadding, end = TvPagePadding, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 遥控器 Back 键统一由 TvApp.BackHandler 处理
            Text(
                chips.firstOrNull { it.second == kind && it.third == genre }?.first ?: "全部",
                color = TvText.Title,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "${items.size} 个条目",
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // 题材横条：单行可滚动，遥控器左右移动即可（底部渐变分隔线）
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to AppColors.Bg
                    )
                )
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 8.dp),
                contentPadding = PaddingValues(horizontal = TvPagePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(chips, key = { "chip_${it.first}" }) { (label, k, g) ->
                    TvFilterChip(
                        text = label,
                        selected = k == kind && g == genre,
                        // 滚动状态按筛选分键：换一个筛选就是一个新的 state，
                        // 没走过的筛选自然从顶部起，走过的回到自己上次的位置。
                        onClick = {
                            kind = k
                            genre = g
                        }
                    )
                }
            }
        }

        when {
            loading -> TvEmptyState("加载中…")
            items.isEmpty() -> TvEmptyState("这个分类下还没有内容", "换个题材，或先在来源里同步媒体库")
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(tvGridColumns()),
                contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(items, key = { _, it -> "tv_g_${it.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
                    val ownFocus = remember(item.id) { FocusRequester() }
                    TvPosterCard(
                        item = item,
                        onClick = { onDetail(item.id) },
                        // 每行首格放大时锚住左缘，否则越过左边距与首页各行的首列对不齐
                        anchorStart = index % tvGridColumns() == 0,
                        // 首卡同时是这一页的入口锚点，落点记账复用它自己的 requester：
                        // 同一节点挂第二个 requester 在 1.6.8 上不可靠。
                        modifier = ReturnSpot.cardStand(
                            pageKey = TvGenrePageKey,
                            rowKey = filterKey,
                            mediaId = item.id,
                            target = stand,
                            requester = if (item.id == firstId) firstCardFocus else ownFocus
                        ),
                        fillCell = true
                    )
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = AppColors.Brand,
                                strokeWidth = 2.dp,
                                modifier = Modifier.width(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("加载更多…", color = TvText.Meta)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 电视端 TVBox 分类页（首页分类行右上角「查看全部」进入）。
 *
 * 和手机端 TvBoxCategoryScreen 的分页语义一致：page 从 1 开始，
 * 滚到底部自动取下一页，返回空页即视为到底。
 * 差异只有排版：6 列大卡网格 + 首屏 shimmer 骨架（电视上不用转圈，避免整屏空转）。
 *
 * 顶部带整行分类切换（来自当前线路 homeContent 的 class 数组）：
 * 查看全部后想换分类不用退回首页；分类内容不落库，换分类即重新拉第一页。
 */
@Composable
fun TvCategoryScreen(
    vm: LibraryViewModel,
    typeId: String,
    typeName: String,
    onBack: () -> Unit,
    onDetail: (Long) -> Unit
) {
    // 进页时带着首页的那个分类，之后在页内原地切换
    var currentTypeId by remember(typeId) { mutableStateOf(typeId) }
    val categories = vm.homeCategories
    val currentName = categories.firstOrNull { it.typeId == currentTypeId }?.typeName ?: typeName

    /**
     * 数据、页码、到底/失败状态全部住在 ViewModel 的分类缓存里（browseItems 那组接口）。
     *
     * 原来这些数据记在本页的 remember 里，而进详情页会把这一页的子树整个销毁重建 ——
     * 返回即重新请求源站，同一部影片还会被分配一个新的内存负 id，于是落点记录对不上、
     * 焦点退回首卡、滚动位置也被 clamp。改成缓存复用之后：只有第一次进入（以及刷新 /
     * 换线路）请求第 1 页，往下滚请求下一页，返回上层一个请求都不发。
     */
    val items = vm.browseItems(currentTypeId)
    val loadError = vm.browseItemsError(currentTypeId)
    val exhausted = vm.browseItemsEnded(currentTypeId)
    val loadingMore = items.isNotEmpty() && vm.browseItemsLoading(currentTypeId)
    // 来源/线路还没选中（冷启动直接进这一页）时不该画骨架屏，否则永远转圈
    val browseReady = vm.homeSourceId != null && vm.homeSiteKey != null
    val firstLoading = items.isEmpty() && loadError == null && !exhausted && browseReady

    // 每个分类各记一份位置与落点：页标识直接带上 typeId
    val pageKey = "tv_cat/$currentTypeId"
    // 记录要在建 gridState 之前读出来：数据还没补齐时 LazyGrid 会把位置 clamp 到末尾，
    // 并通过 gridState 的写回把原记录改小，之后就再也回不到离开时那一屏。
    val savedScroll = remember(pageKey) { ReturnSpot.scrollOf(pageKey) }
    val gridState = ReturnSpot.gridState(pageKey)
    val firstFocus = remember { FocusRequester() }
    val stand = remember { StandTarget() }
    val firstId = items.firstOrNull()?.id

    LaunchedEffect(currentTypeId, vm.homeRefreshEpoch) {
        vm.ensureBrowsePage(currentTypeId)
    }

    // 离开时那一屏的位置只在「本页第一次拿到数据」时补一次；
    // 之后追加下一页不能再滚回去，否则用户会被拽回上一页。
    var scrollRestored by remember(pageKey) { mutableStateOf(false) }
    LaunchedEffect(pageKey, items.size) {
        if (scrollRestored || items.isEmpty()) return@LaunchedEffect
        savedScroll?.let { runCatching { gridState.scrollToItem(it.index, it.offset) } }
        scrollRestored = true
    }

    // 首屏数据到位后把焦点交给卡片，遥控器立刻可用；离开时站在哪张卡就还给哪张
    tvClaimFocusOnEnter(
        tick = items.isNotEmpty(),
        anchorsProvider = { listOfNotNull(stand.requester, firstFocus) }
    )

    // 接近底部自动翻页
    val shouldLoadMore = remember(gridState) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 12
        }
    }
    // 请求发在 viewModelScope 里，本页只负责「盯着条件喊一嗓子」：
    // 条件翻转会把自己的协程取消掉、被取消的请求又被当成「没有下一页」，
    // 那正是之前「每个分类只加载两页」的成因。VM 侧对同分类的并发请求有 loading 门禁。
    LaunchedEffect(currentTypeId) {
        snapshotFlow { shouldLoadMore.value }.collect { nearEnd ->
            if (nearEnd) vm.loadMoreBrowsePage(currentTypeId)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = TvPagePadding, end = TvPagePadding, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                currentName,
                color = TvText.Title,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.width(16.dp))
            if (!firstLoading) {
                Text(
                    "${items.size} 个条目",
                    color = TvText.Meta,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 分类切换横条：和题材页同一套 chip，遥控器左右移动即可换分类
        if (categories.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = TvPagePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(categories, key = { "tv_cat_chip_${it.typeId}" }) { cat ->
                    TvFilterChip(
                        text = cat.typeName,
                        selected = cat.typeId == currentTypeId,
                        onClick = {
                            if (cat.typeId != currentTypeId) currentTypeId = cat.typeId
                        }
                    )
                }
            }
        }

        when {
            firstLoading -> LazyVerticalGrid(
                columns = GridCells.Fixed(tvGridColumns()),
                contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(18) { ShimmerCard() }
            }

            items.isEmpty() -> TvEmptyState(
                text = if (loadError != null) "加载失败：$loadError" else "这个分类下还没有内容",
                hint = if (loadError != null) null else "换个线路，或先在来源里同步媒体库",
                action = if (loadError != null) {
                    { TvFilterChip(text = "重试", selected = false, onClick = { vm.retryBrowsePage(currentTypeId) }) }
                } else null
            )

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(tvGridColumns()),
                contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(items, key = { _, it -> "tv_cat_${it.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
                    val ownFocus = remember(item.id) { FocusRequester() }
                    TvPosterCard(
                        item = item,
                        onClick = { onDetail(item.id) },
                        fillCell = true,
                        // 每行首格放大时锚住左缘，否则越过左边距与首页各行的首列对不齐
                        anchorStart = index % tvGridColumns() == 0,
                        // 首卡就是这一页的入口锚点，落点记账复用它的 requester，不再挂第二个
                        modifier = ReturnSpot.cardStand(
                            pageKey = pageKey,
                            rowKey = "grid",
                            mediaId = item.id,
                            target = stand,
                            requester = if (item.id == firstId) firstFocus else ownFocus
                        )
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            loadingMore -> {
                                CircularProgressIndicator(
                                    color = AppColors.Brand,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.width(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("加载更多…", color = TvText.Meta)
                            }
                            loadError != null -> TvFilterChip(
                                text = "加载失败，按 OK 重试",
                                selected = false,
                                onClick = { vm.retryBrowsePage(currentTypeId) }
                            )
                            exhausted -> Text(
                                "没有更多了 · 共 ${items.size} 部",
                                color = TvText.Meta,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
