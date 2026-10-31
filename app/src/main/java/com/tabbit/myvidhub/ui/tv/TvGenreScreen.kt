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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val gridState = ReturnSpot.gridState(filterKey)
    val firstCardFocus = remember { FocusRequester() }
    val stand = remember { StandTarget() }
    val firstId = items.firstOrNull()?.id

    LaunchedEffect(Unit) {
        tags = runCatching { vm.genreTags() }.getOrDefault(emptyList())
    }

    // 条件变化时重置并加载首屏
    LaunchedEffect(kind, genre) {
        loading = true
        endReached = false
        items = runCatching { vm.browseGenre(kind, genre, TV_PAGE_SIZE, 0) }
            .getOrDefault(emptyList())
        if (items.size < TV_PAGE_SIZE) endReached = true
        loading = false
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
        val more = runCatching { vm.browseGenre(kind, genre, TV_PAGE_SIZE, items.size) }
            .getOrDefault(emptyList())
        if (more.isEmpty()) endReached = true else items = items + more
        loadingMore = false
    }

    // 首屏数据到位后把焦点交给卡片。这一页原本没有任何抢焦，进页后焦点落在
    // 谁身上完全交给默认焦点搜索（同文件的分类页是主动抢的），表现就是第一下方向键像没反应。
    // 离开时站在哪张卡上就还给那张卡（落点由卡片在组合时交出 requester），
    // 没有记录（首次进入 / 换了筛选）才退回第一张。
    tvClaimFocusOnEnter(
        tick = items.isNotEmpty(),
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
                items(items, key = { "tv_g_${it.id}" }) { item ->
                    val ownFocus = remember(item.id) { FocusRequester() }
                    TvPosterCard(
                        item = item,
                        onClick = { onDetail(item.id) },
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
 */
@Composable
fun TvCategoryScreen(
    vm: LibraryViewModel,
    typeId: String,
    typeName: String,
    onBack: () -> Unit,
    onDetail: (Long) -> Unit
) {
    var items by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }

    // 每个分类各记一份位置与落点：页标识直接带上 typeId
    val pageKey = "tv_cat/$typeId"
    val gridState = ReturnSpot.gridState(pageKey)
    val firstFocus = remember { FocusRequester() }
    val stand = remember { StandTarget() }
    val firstId = items.firstOrNull()?.id

    LaunchedEffect(typeId) {
        loading = true
        page = 1
        exhausted = false
        items = runCatching { vm.tvBoxCategoryPage(typeId, page) }.getOrDefault(emptyList())
        page = 2
        loading = false
    }

    // 首屏数据到位后把焦点交给卡片，遥控器立刻可用；离开时站在哪张卡就还给哪张
    tvClaimFocusOnEnter(
        tick = items.isNotEmpty(),
        anchorsProvider = { listOfNotNull(stand.requester, firstFocus) }
    )

    // 接近底部自动翻页
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 12
        }
    }
    LaunchedEffect(shouldLoadMore, loading, loadingMore, exhausted) {
        if (!shouldLoadMore || loading || loadingMore || exhausted) return@LaunchedEffect
        loadingMore = true
        val more = runCatching { vm.tvBoxCategoryPage(typeId, page) }.getOrDefault(emptyList())
        if (more.isEmpty()) {
            exhausted = true
        } else {
            items = items + more.filterNot { next -> items.any { it.id == next.id } }
            page += 1
        }
        loadingMore = false
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
                typeName,
                color = TvText.Title,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.width(16.dp))
            if (!loading) {
                Text(
                    "${items.size} 个条目",
                    color = TvText.Meta,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        when {
            loading -> LazyVerticalGrid(
                columns = GridCells.Fixed(tvGridColumns()),
                contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(18) { ShimmerCard() }
            }

            items.isEmpty() -> TvEmptyState("这个分类下还没有内容", "换个线路，或先在来源里同步媒体库")

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(tvGridColumns()),
                contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { "tv_cat_${it.id}" }) { item ->
                    val ownFocus = remember(item.id) { FocusRequester() }
                    TvPosterCard(
                        item = item,
                        onClick = { onDetail(item.id) },
                        fillCell = true,
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
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("正在加载更多…", color = TvText.Meta)
                        }
                    }
                }
            }
        }
    }
}
