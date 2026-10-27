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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusState
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

    // 继续观看：播放进度 > 5s 且最近 30 天内看过的条目，取最近 8 条
    val continueItems = remember(filteredLibrary) {
        filteredLibrary
            .filter { it.positionMs > 5_000L && it.lastPlayedAt > 0L && it.lastPlayedAt > System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L }
            .sortedByDescending { it.lastPlayedAt }
            .take(8)
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
    // 滚动距离驱动顶栏透明度 —— 注意：**不要用 animateFloatAsState + 直接读 listState**（那会让 LazyColumn 每次
    // scroll 都触发整个 Box 重组）。把计算延后到 TvTopBar 内部 graphicsLayer 的 draw phase 执行：
    val configuration = LocalConfiguration.current
    val heroCollapseDistPx = (configuration.screenHeightDp * 0.40f).toInt().coerceAtLeast(1)
    val alphaProvider: () -> Float = {
        val offset = listState.firstVisibleItemScrollOffset.toFloat()
        (1f - offset / heroCollapseDistPx).coerceIn(0.45f, 1f)  // 最小 0.45f 保底可点击
    }

    // 来源/站点（线路）选择器数据
    // 当前生效的来源：手动选择的来源（已删除则回退），未选择时默认第一个配置的来源
    val selectedSource = sources.firstOrNull { it.id == vm.homeSourceId } ?: sources.firstOrNull()
    val siteList = selectedSource?.id?.let { vm.homeSitesBySource[it] }.orEmpty()
    val namedSites = siteList.filter { it.key.isNotBlank() }
    val showSiteButton = selectedSource?.kind == SourceKind.TVBOX && namedSites.isNotEmpty()

    // 选择器弹窗开关
    var showSourceDialog by remember { mutableStateOf(false) }
    var showSiteDialog by remember { mutableStateOf(false) }

    // 焦点 requester 链：Hero 播放按钮 ↔ TopBar 任意按钮
    // TopBar 在 Box 顶层悬浮，和 LazyColumn 不在同一焦点子树 → 必须手动在 onPreviewKeyEvent 拦截 DPad
    val syncButtonFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    var topBarFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { heroPlayFocus.requestFocus() } }

    // 最外层统一焦点导航：
    //   Hero 播放按钮 ↑ → TopBar 同步按钮（首落点）
    //   TopBar 任意按钮 ↓ → Hero 播放按钮（只要 Hero 还在视口内）
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .onPreviewKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && heroFocused) {
                    syncButtonFocus.requestFocus()
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && topBarFocused) {
                    // 只有 Hero 还可见（未滚出视口）才跳 Hero；否则让 LazyColumn 默认处理
                    if (listState.firstVisibleItemIndex <= 1) {
                        heroPlayFocus.requestFocus()
                        true
                    } else false
                } else false
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // top=64dp 避让悬浮 TvTopBar（padding top 18dp + 按钮 44dp），
            // 让 Hero item 从 y=64dp 开始，图片顶部不被遮挡，播放按钮有正常呼吸空间
            contentPadding = PaddingValues(top = 64.dp, bottom = 32.dp)
        ) {
            // ---------- 主视觉（置顶，TvTopBar 悬浮其上） ----------
            if (hero != null) {
                item {
                    TvHero(
                        item = hero,
                        heroIndex = heroIndex,
                        heroTotal = heroItems.size,
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

            // ---------- 继续观看 ----------
            if (continueItems.isNotEmpty()) {
                item { TvSectionTitle("▶ 继续观看") { } }
                item { TvContinueRow(items = continueItems, onDetail = onDetail, onPlay = onPlay) }
            }

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

        // ---------- 透明悬浮顶部入口条：SpaceBetween 布局；左来源链 + 右操作组 ----------
        val displaySourceName = selectedSource?.name?.takeIf { it.isNotBlank() } ?: "未选择来源"
        val displaySiteName = if (showSiteButton) {
            siteList.firstOrNull { it.key == vm.homeSiteKey }?.name
                ?.substringBeforeLast("（")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: namedSites.firstOrNull()?.name?.substringBeforeLast("（")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: namedSites.firstOrNull()?.key?.takeIf { it.isNotBlank() }
        } else null
        TvTopBar(
            alphaProvider = alphaProvider,
            sourceName = displaySourceName,
            sourceKind = selectedSource?.kind,
            siteName = displaySiteName,
            scanning = vm.scanning,
            syncFocusRequester = syncButtonFocus,
            onAnyButtonFocusChanged = { topBarFocused = it },
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

/** 顶部入口条：SpaceBetween 布局 — 左侧来源链 PillButton，右侧操作组。
 *  所有可聚焦元素（来源/线路 PillButton、同步/搜索/来源/设置按钮）gain focus 时
 *  都通过 onAnyButtonFocusChanged 通知顶层，让 Box 的 onPreviewKeyEvent 能统一处理 D-PAD。 */
@Composable
private fun TvTopBar(
    alphaProvider: () -> Float,
    sourceName: String,
    sourceKind: SourceKind?,
    siteName: String?,
    syncFocusRequester: FocusRequester,
    onAnyButtonFocusChanged: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onHomeSource: () -> Unit,
    onHomeSite: (() -> Unit)? = null,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    scanning: Boolean = false
) {
    val isTvBox = onHomeSite != null || sourceKind == SourceKind.TVBOX
    val isEmpty = sourceKind == null
    val markTopBarFocus: (FocusState) -> Unit = { onAnyButtonFocusChanged(it.isFocused) }

    Row(
        Modifier
            .fillMaxWidth()
            // alpha 在 draw phase 从 listState 计算 —— 不触发 composition 重组，滚动流畅
            .graphicsLayer { this.alpha = alphaProvider() }
            .padding(start = 20.dp, end = 20.dp, top = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- 左：来源链 PillButton ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isEmpty) {
                TvPillButton(
                    text = "＋ 添加来源", primary = true, compact = true, onClick = onSources,
                    modifier = Modifier.onFocusChanged(markTopBarFocus)
                )
            } else {
                TvSourceChainPill(
                    label = if (isTvBox) "来源" else "Emby",
                    value = sourceName,
                    kind = sourceKind,
                    onClick = onHomeSource,
                    onFocusChanged = markTopBarFocus
                )
                if (onHomeSite != null && !siteName.isNullOrBlank()) {
                    Text("›", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                    TvSourceChainPill(
                        label = "线路",
                        value = siteName,
                        kind = null,
                        onClick = onHomeSite,
                        onFocusChanged = markTopBarFocus
                    )
                }
            }
        }

        // ---- 右：操作组 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(color = AppColors.Brand, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("同步中…", color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            } else {
                TvPillButton(
                    "↻ 同步", primary = false, compact = true, onClick = onRefresh,
                    modifier = Modifier
                        .focusRequester(syncFocusRequester)
                        .onFocusChanged(markTopBarFocus)
                )
            }
            TvPillButton("🔍 搜索", primary = false, compact = true, onClick = onSearch,
                modifier = Modifier.onFocusChanged(markTopBarFocus))
            TvPillButton("📺 来源", primary = false, compact = true, onClick = onSources,
                modifier = Modifier.onFocusChanged(markTopBarFocus))
            TvPillButton("⚙ 设置", primary = false, compact = true, onClick = onSettings,
                modifier = Modifier.onFocusChanged(markTopBarFocus))
        }
    }
}

/** 来源链 PillButton — label 用品牌色，value 用白色；来源名 ellipsis 到 ~16 字。
 *  通过 onFocusChanged 把焦点状态冒泡到 TvHomeScreen 顶层，供 onPreviewKeyEvent 统一处理。 */
@Composable
private fun TvSourceChainPill(
    label: String,
    value: String,
    kind: SourceKind?,
    onClick: () -> Unit,
    onFocusChanged: (FocusState) -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "chain")
    val labelColor = when (kind) {
        SourceKind.EMBY -> AppColors.BrandSoft
        SourceKind.TVBOX -> AppColors.Rating
        null -> Color.White.copy(alpha = 0.7f)
    }
    val bg = when {
        focused -> Color.White
        kind == SourceKind.EMBY -> AppColors.Brand.copy(alpha = 0.18f)
        kind == SourceKind.TVBOX -> AppColors.Rating.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val textColor = if (focused) Color.Black else Color.White

    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .onFocusChanged(onFocusChanged)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else labelColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = if (focused) 0.4f else 0.18f)))
        Text(
            text = value,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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

/** 主视觉：满宽横版图，左下压标题与操作，底部渐隐接入页面底色；轮播指示器在右下。
 *  顶部 64dp padding 避让悬浮 TvTopBar，防止 Hero 播放按钮被遮盖。 */
@Composable
private fun TvHero(
    item: MediaEntity,
    heroIndex: Int,
    heroTotal: Int,
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

        // 渐变遮罩 —— remember 缓存避免每次重绘都重建 Brush
        val horizVignette = remember {
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.88f),
                0.45f to Color.Black.copy(alpha = 0.40f),
                1f to Color.Transparent
            )
        }
        val bottomVignette = remember {
            Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to AppColors.Bg
            )
        }
        Box(Modifier.fillMaxSize().background(horizVignette))
        Box(
            Modifier
                .fillMaxSize()
                .background(bottomVignette)
        )

        // 左对齐内容块：logo/标题 + 元信息 + 操作按钮
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 60.dp, end = 320.dp, bottom = 44.dp)
        ) {
            if (item.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(110.dp).widthIn(max = 520.dp)
                )
            } else {
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(14.dp))
            TvMetaRow(item)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPillButton(
                    text = if (item.positionMs > 5_000L) "▶ 继续播放" else "▶ 播放",
                    modifier = Modifier
                        .focusRequester(playFocusRequester)
                        .onFocusChanged { onPlayFocusChanged(it.isFocused) },
                    primary = true,
                    compact = true,
                    onClick = onPlay
                )
                TvPillButton(text = "ⓘ 详情", primary = false, compact = true, onClick = onDetail)
            }
        }

        // 轮播指示器（右下）
        if (heroTotal > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 60.dp, bottom = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(heroTotal) { idx ->
                    val active = idx == heroIndex
                    Box(
                        Modifier
                            .height(10.dp)
                            .width(if (active) 28.dp else 10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (active) Color.White
                                else Color.White.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }
}

/** 横向海报行：左右滚动，聚焦放大由卡片自身处理 */
@Composable
private fun TvPosterRow(
    items: List<MediaEntity>,
    onDetail: (Long) -> Unit,
    cardWidth: androidx.compose.ui.unit.Dp = Prefs.posterSize.tvDp.dp
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(items, key = { "tv_${it.id}" }) { item ->
            TvPosterCard(
                item = item,
                onClick = { onDetail(item.id) },
                width = cardWidth
            )
        }
    }
}

/** 继续观看横排 —— 横向缩略卡，显示进度条 + 集数 */
@Composable
private fun TvContinueRow(
    items: List<MediaEntity>,
    onDetail: (Long) -> Unit,
    onPlay: (MediaEntity) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items, key = { "continue_${it.id}" }) { item ->
            val progress = if (item.durationMs > 0L) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "continue")
            Box(
                Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceHi)
                    .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(interactionSource = interaction, indication = null) {
                        if (item.positionMs > 5_000L) onPlay(item) else onDetail(item.id)
                    }
                    .focusable(interactionSource = interaction)
                    .width(320.dp)
                    .height(120.dp)
            ) {
                AsyncImage(
                    model = item.backdropUrl.ifBlank { item.posterUrl },
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // 底部渐变 + 标题 + 进度条
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)))
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth()
                ) {
                    Text(
                        item.title,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.seriesKey.isNotBlank() && item.season > 0) {
                        Text(
                            "S${item.season.toString().padStart(2, '0')} · E${item.episode.toString().padStart(2, '0')}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (progress > 0.02f) {
                        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(AppColors.Brand, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
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
