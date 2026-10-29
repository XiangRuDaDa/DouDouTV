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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.ShimmerCard
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.TvText
import kotlinx.coroutines.launch

private const val TV_PAGE_SIZE = 60

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

    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

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

    // 接近底部时增量加载
    val shouldLoadMore by remember {
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

    // 题材标签：全部 / 电影 / 剧集 / 动漫 + 库内高频题材
    val chips = remember(tags) {
        buildList {
            add(Triple("全部", null as String?, null as String?))
            add(Triple("电影", MediaKind.MOVIE, null))
            add(Triple("剧集", MediaKind.SERIES, null))
            add(Triple("动漫", null, "动漫"))
            tags.forEach { add(Triple(it, null, it)) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
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
                        onClick = {
                            kind = k
                            genre = g
                            scope.launch { gridState.scrollToItem(0) }
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
                    TvPosterCard(
                        item = item,
                        onClick = { onDetail(item.id) },
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

    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(typeId) {
        loading = true
        page = 1
        exhausted = false
        items = runCatching { vm.tvBoxCategoryPage(typeId, page) }.getOrDefault(emptyList())
        page = 2
        loading = false
    }

    // 首屏数据到位后把焦点交给第一张卡片，遥控器立刻可用
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

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
            .background(AppColors.Bg)
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
                    TvPosterCard(
                        item = item,
                        onClick = { onDetail(item.id) },
                        fillCell = true,
                        modifier = Modifier.then(
                            if (item.id == items.first().id) Modifier.focusRequester(firstFocus) else Modifier
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
