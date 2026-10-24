package com.tabbit.myvidhub.ui.tv

import android.view.KeyEvent
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient.TvBoxSite
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 电视端首页（Netflix 式横版结构，针对遥控器与十英尺观看距离设计）。
 *
 * 和手机端的区别不只是「字大一点」：
 *   · 顶部横向入口条代替底部标签栏，遥控器只需左右移动；
 *   · 首屏是一整屏的横版主视觉，标题与按钮压在左下，进入即有内容感；
 *   · 每个分区是无限横向海报行，聚焦卡片放大 10% 并描白边，保证三米外也能看清选中项；
 *   · 分区切换用纵向滚动，符合电视「上下换类目、左右换条目」的直觉。
 *
 * 遥控器焦点导航：
 *   TvTopBar 在 Box 顶层悬浮（和 LazyColumn 平级），焦点系统默认不跨子树导航。
 *   通过 onPreviewKeyEvent 拦截 KEYCODE_DPAD_DOWN → 手动把焦点交给 TvHero 播放按钮，
 *   让 LazyColumn 内的嵌套 LazyRow 继续处理后续的上下方向键。
 */
@Composable
fun TvHomeScreen(
    vm: LibraryViewModel,
    onSearch: () -> Unit,
    onDetail: (Long) -> Unit,
    onGenre: (kind: String?, genre: String?) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val sources by vm.enabledSources.collectAsStateWithLifecycle()

    // 首页内容按选中的来源/站点（线路）过滤；未选择来源时原样返回
    val filteredLibrary = remember(library, vm.homeSourceId, vm.homeSiteKey) { vm.filterForHome(library) }

    // 优先用有横版背景图的条目做精选，不足时用海报补位
    val heroItems = remember(filteredLibrary) {
        val primary = filteredLibrary.filter { it.backdropUrl.isNotBlank() }
        if (primary.size >= 3) primary.take(5)
        else (primary + filteredLibrary.filter { it.posterUrl.isNotBlank() })
            .distinctBy { it.id }
            .take(5)
    }

    var heroIndex by remember { mutableStateOf(0) }
    LaunchedEffect(heroItems.size) {
        if (heroItems.size > 1) {
            while (true) {
                delay(9_000L)
                heroIndex = (heroIndex + 1) % heroItems.size
            }
        }
    }
    val hero = heroItems.getOrNull(heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0)))

    val listState = rememberLazyListState()
    // 滚动距离驱动顶栏透明度（下滑到 heroHeight 后淡出）
    val configuration = LocalConfiguration.current
    val heroCollapseDistPx = (configuration.screenHeightDp * 0.40f).toInt()
    val rawFade = if (heroCollapseDistPx == 0) 0f
        else (listState.firstVisibleItemScrollOffset.toFloat() / heroCollapseDistPx).coerceIn(0f, 1f)
    val topBarAlpha by animateFloatAsState(targetValue = 1f - rawFade, label = "tv_topbar_fade")

    // 来源/站点（线路）选择器数据
    // 当前生效的来源：手动选择的来源（已删除则回退），未选择时默认第一个配置的来源
    val selectedSource = sources.firstOrNull { it.id == vm.homeSourceId } ?: sources.firstOrNull()
    val siteList = selectedSource?.id?.let { vm.homeSitesBySource[it] }.orEmpty()
    val namedSites = siteList.filter { it.key.isNotBlank() }
    val showSiteButton = selectedSource?.kind == SourceKind.TVBOX && namedSites.isNotEmpty()

    // 选择器弹窗开关
    var showSourceDialog by remember { mutableStateOf(false) }
    var showSiteDialog by remember { mutableStateOf(false) }

    // 焦点 requester 链：同步按钮 ↔ TvHero 播放按钮
    // 统一在最外层 Box 拦截方向键，避免被 LazyColumn 内部的焦点导航先消费掉
    val syncButtonFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }
    // 用状态跟踪当前焦点层级（Hero = true，TopBar = false）
    var heroFocused by remember { mutableStateOf(false) }
    var syncFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { heroPlayFocus.requestFocus() } }

    // 最外层统一焦点导航：根据当前焦点所在层级拦截 DPad Up/Down
    // 必须在最外层做，否则 LazyColumn 内部的焦点导航会先消费掉方向键
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .onPreviewKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                // 焦点在 Hero 播放按钮上时按↑ → 跳到顶部同步按钮
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && heroFocused) {
                    syncButtonFocus.requestFocus()
                    true
                }
                // 焦点在顶部同步按钮上时按↓ → 跳到 Hero 播放按钮
                else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && syncFocused) {
                    heroPlayFocus.requestFocus()
                    true
                } else false
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ---------- 主视觉（置顶，TvTopBar 悬浮其上） ----------
            if (hero != null) {
                item {
                    TvHero(
                        item = hero,
                        playFocusRequester = heroPlayFocus,
                        onPlay = { onPlay(hero) },
                        onDetail = { onDetail(hero.id) },
                        onPlayFocusChanged = { heroFocused = it }
                    )
                }
            } else if (filteredLibrary.isEmpty() && vm.homeCategories.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        if (library.isEmpty()) {
                            TvEmptyState(
                                text = if (vm.scanning) "正在同步媒体库…" else "还没有内容",
                                hint = if (vm.scanning) null else "先在「来源」里添加 Emby 或 TVBox，再同步媒体库"
                            )
                        } else {
                            val err = vm.homeSitesError
                            TvEmptyState(
                                text = err ?: "当前来源/站点暂无内容",
                                hint = if (err != null) "点击顶部同步按钮重试，或切换其他来源/线路"
                                else "切换来源或点击顶部同步按钮拉取该来源的首页内容"
                            )
                        }
                    }
                }
            }
            // 选了「只有分类没首页影片」的线路时不提示空：下面的分类入口本身就是内容入口

            // ---------- 站点分类（动态分区入口）：TVBox 线路 homeContent 返回的 class ----------
            val homeCategories = vm.homeCategories
            if (homeCategories.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(homeCategories, key = { it.typeId }) { cat ->
                            TvCategoryChip(
                                name = cat.typeName,
                                selected = vm.activeCategoryId == cat.typeId,
                                onClick = { vm.selectHomeCategory(cat.typeId) }
                            )
                        }
                    }
                }
            }

            // ---------- 展开的站点分类分区：点分类后展示该分类内容（categoryContent） ----------
            val activeCategory = homeCategories.firstOrNull { it.typeId == vm.activeCategoryId }
            if (activeCategory != null) {
                val catItems = vm.categoryVods[activeCategory.typeId].orEmpty()
                if (vm.categoryLoading && catItems.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand)
                        }
                    }
                } else if (catItems.isNotEmpty()) {
                    item { TvSectionTitle(activeCategory.typeName) {} }
                    item { TvPosterRow(items = catItems, onDetail = onDetail) }
                } else if (vm.categoryError != null) {
                    item {
                        Text(
                            vm.categoryError.orEmpty(),
                            color = AppColors.TextTertiary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            // ---------- 动态分区：根据 filteredLibrary 实际存在的 kind 渲染 ----------
            DynamicTvGallerySections(
                library = filteredLibrary,
                onMore = onGenre,
                onDetail = onDetail
            )
        }

        // ---------- 透明悬浮顶部入口条（压在主视觉之上，滚动淡出；只显示操作按钮，不显示软件名） ----------
        TvTopBar(
            alpha = topBarAlpha,
            scanning = vm.scanning,
            syncFocusRequester = syncButtonFocus,
            onSyncFocusChanged = { syncFocused = it },
            onSearch = onSearch,
            onHomeSource = { showSourceDialog = true },
            onHomeSite = if (showSiteButton) ({ showSiteDialog = true }) else null,
            onSources = onSources,
            onSettings = onSettings,
            onRefresh = { vm.scanAll() }
        )

        // ---------- 首页来源 / 线路（站点）选择弹窗 ----------
        if (showSourceDialog) {
            HomeSourceDialog(
                sources = sources,
                selectedId = vm.effectiveHomeSourceId(),
                onPick = { id ->
                    vm.selectHomeSource(id)
                    showSourceDialog = false
                },
                onDismiss = { showSourceDialog = false }
            )
        }
        if (showSiteDialog) {
            HomeSiteDialog(
                sites = namedSites,
                selectedKey = vm.homeSiteKey,
                onPick = { key, site ->
                    vm.selectHomeSite(key, site)
                    showSiteDialog = false
                },
                onDismiss = { showSiteDialog = false }
            )
        }
    }
}

/** 顶部入口条：同步/搜索/当前来源/当前线路/来源/设置。TV 端不展示软件名称。 */
@Composable
private fun TvTopBar(
    alpha: Float,
    syncFocusRequester: FocusRequester,
    onSyncFocusChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onHomeSource: () -> Unit,
    onHomeSite: (() -> Unit)? = null,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    scanning: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .padding(start = 20.dp, end = 20.dp, top = 18.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (scanning) {
            CircularProgressIndicator(color = AppColors.Brand, strokeWidth = 2.dp)
            Spacer(Modifier.width(18.dp))
        } else {
            TvPillButton(
                "同步", primary = false, onClick = onRefresh,
                modifier = Modifier
                    .focusRequester(syncFocusRequester)
                    .onFocusChanged { onSyncFocusChanged(it.isFocused) }
            )
            Spacer(Modifier.width(12.dp))
        }
        TvPillButton("搜索", primary = false, onClick = onSearch)
        Spacer(Modifier.width(12.dp))
        TvIconButton(Icons.Filled.Source, "选择首页来源", onClick = onHomeSource)
        Spacer(Modifier.width(12.dp))
        if (onHomeSite != null) {
            TvIconButton(Icons.Filled.AltRoute, "选择线路", onClick = onHomeSite)
            Spacer(Modifier.width(12.dp))
        }
        TvPillButton("来源", primary = false, onClick = onSources)
        Spacer(Modifier.width(12.dp))
        TvPillButton("设置", primary = false, onClick = onSettings)
    }
}

/** 电视端图标按钮：聚焦时白底深色图标，未聚焦半透明白底白图标（与文字按钮同尺寸同焦点逻辑） */
@Composable
private fun TvIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "tv_icon")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .focusable()
            .padding(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else Color.White
        )
    }
}

/** 电视端分类 chip（站点动态分区入口）：聚焦放大 + 白边，遥控器左右切换 */
@Composable
private fun TvCategoryChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "tv_cat")
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> AppColors.SurfaceHi
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .focusable()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            name,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> AppColors.TextSecondary
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 主视觉：满宽横版图，左下压标题与操作，底部渐隐接入页面底色 */
@Composable
private fun TvHero(
    item: MediaEntity,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    onPlayFocusChanged: (Boolean) -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(430.dp)
    ) {
        AsyncImage(
            model = item.backdropUrl.ifBlank { item.posterUrl },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.86f),
                        0.45f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to AppColors.Bg
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (item.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(96.dp).widthIn(max = 460.dp)
                )
            } else {
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(0.62f)) {
                TvMetaRow(item, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvPillButton(
                    text = if (item.positionMs > 5_000L) "继续播放" else "立即播放",
                    modifier = Modifier
                        .focusRequester(playFocusRequester)
                        .onFocusChanged { onPlayFocusChanged(it.isFocused) },
                    primary = true,
                    onClick = onPlay
                )
                TvPillButton(text = "详情", primary = false, onClick = onDetail)
            }
        }
    }
}

/** 横向海报行：左右滚动，聚焦放大由卡片自身处理 */
@Composable
private fun TvPosterRow(
    items: List<MediaEntity>,
    onDetail: (Long) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(items, key = { "tv_${it.id}" }) { item ->
            TvPosterCard(
                item = item,
                onClick = { onDetail(item.id) }
            )
        }
    }
}

/**
 * 动态分区：kind 基础分区 + genres 扩展分区。
 *
 * 1. kind 分区：MOVIE → 电影；SERIES → 剧集。
 * 2. genre 分区：从所有条目的 genres 拆分（逗号/顿号分隔），每个 ≥2 条的 genre 一个 section。
 *    最多展示 6 个 genre 分区，按条目数量倒序。
 *
 * 条目可同时出现在多个 section（主流流媒体的做法）。
 */
private const val TV_LAZY_TAKE = 12
private const val TV_MIN_GENRE_ITEMS = 2
private const val TV_MAX_GENRE_SECTIONS = 4

private fun LazyListScope.DynamicTvGallerySections(
    library: List<MediaEntity>,
    onMore: (kind: String?, genre: String?) -> Unit,
    onDetail: (Long) -> Unit
) {
    if (library.isEmpty()) return

    // kind 基础分区
    val kindBlocks = library
        .filter { it.kind.isNotBlank() && it.kind != MediaKind.EPISODE }
        .groupBy { it.kind }
        .entries
        .sortedBy { (kind, _) ->
            when (kind) {
                MediaKind.MOVIE -> 0
                MediaKind.SERIES -> 1
                else -> 2
            }
        }

    // genre 扩展分区
    val genreToItems = mutableMapOf<String, MutableList<MediaEntity>>()
    val genrePattern = Regex("[,，、/\\s]+")
    for (item in library) {
        // distinct() 防止 genres 字段重复标签（如 "科幻,科幻,动作"）导致同一 item 被加两次
        val parts = item.genres.split(genrePattern).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (g in parts) genreToItems.getOrPut(g) { mutableListOf() }.add(item)
    }
    // 额外保险：每个 genre list 内按 item.id 去重
    val kindAsGenre = setOf("电影", "剧集", "单集")
    val genreBlocks = genreToItems.entries
        .map { (g, items) -> g to items.distinctBy { it.id } }
        .filter { (g, items) -> items.size >= TV_MIN_GENRE_ITEMS && g !in kindAsGenre }
        .sortedByDescending { it.second.size }
        .take(TV_MAX_GENRE_SECTIONS)

    for ((kind, items) in kindBlocks) {
        val title = when (kind) {
            MediaKind.MOVIE -> "电影"
            MediaKind.SERIES -> "剧集"
            else -> kind
        }
        item { TvSectionTitle(title) { TvMoreButton { onMore(kind, null) } } }
        item { TvPosterRow(items = items.take(TV_LAZY_TAKE), onDetail = onDetail) }
    }
    for ((genre, items) in genreBlocks) {
        item { TvSectionTitle(genre) { TvMoreButton { onMore(null, genre) } } }
        item { TvPosterRow(items = items.take(TV_LAZY_TAKE), onDetail = onDetail) }
    }
}

/** 电视端弹窗选项行：可聚焦，聚焦/选中态高亮，供遥控器操作 */
@Composable
private fun TvDialogOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> AppColors.Surface
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .focusable()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> AppColors.TextPrimary
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 电视端「选择首页来源」弹窗 */
@Composable
private fun HomeSourceDialog(
    sources: List<SourceEntity>,
    selectedId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .background(AppColors.SurfaceHi, RoundedCornerShape(18.dp))
                .padding(vertical = 26.dp)
        ) {
            Text(
                "选择首页来源",
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 26.dp)
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                Modifier.widthIn(max = 560.dp).heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(sources, key = { _, src -> "src_${src.id}" }) { index, src ->
                    Box(Modifier.then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)) {
                        TvDialogOption(
                            src.name,
                            selected = selectedId == src.id,
                            onClick = { onPick(src.id) }
                        )
                    }
                }
            }
        }
    }
}

/** 电视端「选择线路（站点）」弹窗 */
@Composable
private fun HomeSiteDialog(
    sites: List<TvBoxSite>,
    selectedKey: String?,
    onPick: (String?, TvBoxSite?) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .background(AppColors.SurfaceHi, RoundedCornerShape(18.dp))
                .padding(vertical = 26.dp)
        ) {
            Text(
                "选择线路（站点）",
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 26.dp)
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                Modifier.widthIn(max = 560.dp).heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Box(Modifier.focusRequester(firstFocus)) {
                        TvDialogOption(
                            "全部站点",
                            selected = selectedKey.isNullOrEmpty(),
                            onClick = { onPick(null, null) }
                        )
                    }
                }
                items(sites, key = { "site_${it.key}" }) { site ->
                    TvDialogOption(
                        site.name,
                        selected = selectedKey == site.key,
                        onClick = { onPick(site.key, site) }
                    )
                }
            }
        }
    }
}
