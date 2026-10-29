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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient.TvBoxSite
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.ShimmerCard
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.TvText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onCategory: (typeId: String, typeName: String) -> Unit,
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
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // 滚动距离驱动顶栏透明度 —— 注意：**不要用 animateFloatAsState + 直接读 listState**（那会让 LazyColumn 每次
    // scroll 都触发整个 Box 重组）。把计算延后到 TvTopBar 内部 graphicsLayer 的 draw phase 执行：
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // 顶栏淡出所需滚动距离 ≈ 屏幕高度的 16%（dp → px 换算后再比较）
    val heroCollapseDistPx = with(density) {
        (configuration.screenHeightDp * 0.16f).dp.roundToPx()
    }.coerceAtLeast(1)
    // 顶栏只在首屏（Hero 仍可见）时出现：一旦往下滚就完全淡出，不占独立一行、也不常驻。
    val alphaProvider: () -> Float = {
        if (listState.firstVisibleItemIndex > 0) 0f
        else (1f - listState.firstVisibleItemScrollOffset.toFloat() / heroCollapseDistPx).coerceIn(0f, 1f)
    }

    // 动态分区（kind + 题材）是纯 CPU 计算：按 library 缓存一次，
    // 避免每次重组（轮播换图、焦点变化）都把几千条条目重新分组拆题材 —— 电视上这是掉帧的主要来源之一。
    val gallerySections = remember(filteredLibrary) { buildTvGallerySections(filteredLibrary) }

    // 来源/站点（线路）选择器数据
    // 当前生效的来源：手动选择的来源（已删除则回退），未选择时默认第一个配置的来源
    val selectedSource = sources.firstOrNull { it.id == vm.homeSourceId } ?: sources.firstOrNull()
    val siteList = selectedSource?.id?.let { vm.homeSitesBySource[it] }.orEmpty()
    val namedSites = siteList.filter { it.key.isNotBlank() }
    val showSiteButton = selectedSource?.kind == SourceKind.TVBOX && namedSites.isNotEmpty()

    // 冷启动时没人调过 selectHomeSource，线路列表一直是空的 → 顶栏「线路」按钮不出现。
    // 这里主动为当前生效的来源拉一次线路列表（内部有缓存，重复调用不会重复请求）。
    LaunchedEffect(selectedSource?.id) {
        selectedSource?.id?.let { vm.ensureHomeSites(it) }
    }

    // 选择器弹窗开关
    var showSourceDialog by remember { mutableStateOf(false) }
    var showSiteDialog by remember { mutableStateOf(false) }

    // 焦点 requester 链：Hero 播放按钮 ↔ TopBar 任意按钮
    // TopBar 在 Box 顶层悬浮，和 LazyColumn 不在同一焦点子树 → 必须手动在 onPreviewKeyEvent 拦截 DPad
    val syncButtonFocus = remember { FocusRequester() }
    val sourcePillFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    var topBarFocused by remember { mutableStateOf(false) }

    // 顶栏不再常驻：滚出首屏后会完全淡出。一旦焦点落到顶栏（遥控器按上键），
    // 把列表回滚到顶部，让顶栏边淡入边进入视野，避免「焦点在看不见的按钮上」。
    val revealTopBar: () -> Unit = {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    LaunchedEffect(Unit) { if (hero != null) runCatching { heroPlayFocus.requestFocus() } }

    // 最外层统一焦点导航：
    //   Hero 播放按钮 ↑ → TopBar 来源（/「添加来源」）PillButton
    //   TopBar 任意按钮 ↓ → Hero 播放按钮（只要 Hero 还在视口内）
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
            .onPreviewKeyEvent { event ->
                if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && heroFocused) {
                    revealTopBar()
                    // 顶栏与 Hero 不在同一个焦点子树里，这里显式把焦点交给左侧「来源」按钮
                    // （用户视觉上希望焦点从播放键上移到来源链，而不是跳到右侧同步键）。
                    // 请求后校验一帧：若焦点并没有真的进顶栏（requester 暂时不可用等），
                    // 依次退回同步按钮 / 默认几何焦点搜索，保证画面高亮一定跟着焦点走。
                    scope.launch {
                        val handedOver = runCatching {
                            sourcePillFocus.requestFocus()
                            true
                        }.getOrDefault(false)
                        withFrameNanos { }
                        if (!handedOver || !topBarFocused) {
                            val fallback = runCatching {
                                syncButtonFocus.requestFocus()
                                true
                            }.getOrDefault(false)
                            if (!fallback || !topBarFocused) {
                                runCatching { focusManager.moveFocus(FocusDirection.Up) }
                            }
                        }
                    }
                    true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && topBarFocused) {
                    // 只有 Hero 还可见（未滚出视口）才跳 Hero；否则让 LazyColumn 默认处理
                    if (hero != null && listState.firstVisibleItemIndex <= 1) {
                        heroPlayFocus.requestFocus()
                        true
                    } else false
                } else false
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 不再给顶栏预留 64dp：Hero 从屏幕最顶端开始铺满，
            // 顶栏透明地悬浮在画面上，不会出现「顶栏单独占一行、海报被挤下去」的黑条。
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ---------- 主视觉（置顶，TvTopBar 悬浮其上） ----------
            // 轮播区常驻：即使没有内容也保留这一屏高度，避免首页在「加载中 / 空库」之间跳版。
            item {
                if (hero != null) {
                    TvHero(
                        item = hero,
                        heroIndex = heroIndex,
                        heroTotal = heroItems.size,
                        playFocusRequester = heroPlayFocus,
                        onPlay = { onPlay(hero) },
                        onDetail = { onDetail(hero.id) },
                        onPlayFocusChanged = { heroFocused = it }
                    )
                } else {
                    TvHeroEmpty(
                        libraryIsEmpty = library.isEmpty(),
                        hasCategories = vm.homeCategories.isNotEmpty(),
                        error = vm.homeSitesError,
                        scanning = vm.scanning
                    )
                }
            }
            // 选了「只有分类没首页影片」的线路时不提示空：下面的分类入口本身就是内容入口

            // ---------- 继续观看 ----------
            if (continueItems.isNotEmpty()) {
                item { TvSectionTitle("▶ 继续观看") { } }
                item { TvContinueRow(items = continueItems, onDetail = onDetail, onPlay = onPlay) }
            }

            // ---------- TVBox 动态分类预览 sections ----------
            // 电视端去掉了「分类 chip 横条」（单独占一行、把海报往下挤），
            // 分类内容直接以海报行呈现，遥控器上下就是换分类。
            val homeCategories = vm.homeCategories
            homeCategories.take(7).forEach { cat ->
                item(key = "cat_ensure_${cat.typeId}") {
                    // homeRefreshEpoch 变化时重新触发一次，否则刷新后分类行仍是旧缓存
                    LaunchedEffect(cat.typeId, vm.homeRefreshEpoch) { vm.ensureCategoryPreview(cat.typeId) }
                }
                item(key = "cat_title_${cat.typeId}") {
                    // 「查看全部」挂在标题右侧，和手机端 MoreLink 一致（有内容时才出现）
                    TvCategoryTitle(
                        typeId = cat.typeId,
                        typeName = cat.typeName,
                        vm = vm,
                        onCategory = onCategory
                    )
                }
                item(key = "cat_row_${cat.typeId}") {
                    // 分类数据（categoryVods / loading）在这个子组合里读取：
                    // 某个分类加载完成只重组它自己那一行，不会把整个首页的 item 列表重建一遍。
                    TvCategoryPreview(typeId = cat.typeId, vm = vm, onDetail = onDetail)
                }
            }

            // ---------- 动态分区：根据 filteredLibrary 实际存在的 kind 渲染 ----------
            gallerySections.forEach { section ->
                item(key = "sec_${section.title}") {
                    TvSectionTitle(section.title) { TvMoreButton { onGenre(section.kind, section.genre) } }
                }
                item(key = "sec_row_${section.title}") {
                    TvPosterRow(items = section.items, onDetail = onDetail)
                }
            }
        }

        // ---------- 透明悬浮顶部入口条：SpaceBetween 布局；左来源链 + 右操作组 ----------
        val displaySourceName = selectedSource?.name?.takeIf { it.isNotBlank() } ?: "未选择来源"
        val displaySiteName = if (showSiteButton) {
            val picked = siteList.firstOrNull { it.key == vm.homeSiteKey }
            picked?.name?.substringBeforeLast("（")?.trim()?.takeIf { it.isNotBlank() } ?: "全部"
        } else null
        TvTopBar(
            alphaProvider = alphaProvider,
            sourceName = displaySourceName,
            sourceKind = selectedSource?.kind,
            siteName = displaySiteName,
            scanning = vm.scanning,
            syncFocusRequester = syncButtonFocus,
            sourceFocusRequester = sourcePillFocus,
            onAnyButtonFocusChanged = { focused ->
                topBarFocused = focused
                if (focused) revealTopBar()
            },
            onSearch = onSearch,
            onHomeSource = { showSourceDialog = true },
            onHomeSite = if (showSiteButton) ({ showSiteDialog = true }) else null,
            onSources = onSources,
            onSettings = onSettings,
            onRefresh = { vm.refreshHome() }
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
    sourceFocusRequester: FocusRequester,
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
                    modifier = Modifier.focusRequester(sourceFocusRequester).onFocusChanged(markTopBarFocus)
                )
            } else {
                TvSourceChainPill(
                    label = if (isTvBox) "来源" else "Emby",
                    value = sourceName,
                    kind = sourceKind,
                    onClick = onHomeSource,
                    modifier = Modifier.focusRequester(sourceFocusRequester),
                    onFocusChanged = markTopBarFocus
                )
                if (onHomeSite != null && !siteName.isNullOrBlank()) {
                    Text("›", color = TvText.Muted, style = MaterialTheme.typography.titleMedium)
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
                Text("刷新中…", color = TvText.Body, style = MaterialTheme.typography.labelMedium)
            } else {
                TvPillButton(
                    "刷新", primary = false, compact = true, onClick = onRefresh,
                    modifier = Modifier
                        .focusRequester(syncFocusRequester)
                        .onFocusChanged(markTopBarFocus)
                )
            }
            TvPillButton("搜索", primary = false, compact = true, onClick = onSearch,
                modifier = Modifier.onFocusChanged(markTopBarFocus))
            TvPillButton("来源", primary = false, compact = true, onClick = onSources,
                modifier = Modifier.onFocusChanged(markTopBarFocus))
            TvPillButton("设置", primary = false, compact = true, onClick = onSettings,
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
    modifier: Modifier = Modifier,
    onFocusChanged: (FocusState) -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "chain")
    val labelColor = when (kind) {
        SourceKind.EMBY -> AppColors.BrandSoft
        SourceKind.TVBOX -> AppColors.Rating
        null -> TvText.Body
    }
    val bg = when {
        focused -> Color.White
        kind == SourceKind.EMBY -> AppColors.Brand.copy(alpha = 0.18f)
        kind == SourceKind.TVBOX -> AppColors.Rating.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val textColor = if (focused) Color.Black else TvText.Title

    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
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

/** 主视觉：满宽横版图，左下压标题与操作，底部渐隐接入页面底色；轮播指示器在右下。
 *  Hero 从屏幕最顶端开始铺满，顶栏透明悬浮其上。 */
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
                    color = TvText.Title,
                    style = MaterialTheme.typography.displayLarge,
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

/** 空态主视觉：轮播区常驻，但没有可推荐内容时占位。
 *  高度与 TvHero 一致（430dp），避免首页在「加载中 / 空库」之间上下跳版。 */
@Composable
private fun TvHeroEmpty(
    libraryIsEmpty: Boolean,
    hasCategories: Boolean,
    error: String?,
    scanning: Boolean
) {
    val text: String
    val hint: String?
    when {
        scanning -> {
            text = "正在同步媒体库…"
            hint = null
        }
        error != null -> {
            text = error
            hint = "点击顶部同步按钮重试，或切换其他来源/线路"
        }
        libraryIsEmpty -> {
            text = "还没有内容"
            hint = "先在「来源」里添加 Emby 或 TVBox，再同步媒体库"
        }
        hasCategories -> {
            text = "暂无推荐内容"
            hint = "可从下方分类入口浏览"
        }
        else -> {
            text = "当前来源/站点暂无内容"
            hint = "切换来源或线路，或点击顶部同步按钮拉取"
        }
    }
    val bg = remember {
        Brush.verticalGradient(0f to AppColors.Surface, 0.7f to AppColors.Bg, 1f to AppColors.Bg)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(430.dp)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        TvEmptyState(text = text, hint = hint)
    }
}

/** 单个 TVBox 分类的海报行（含加载态）。数据在这里读取，避免首页整体跟着重组。 */
@Composable
private fun TvCategoryPreview(
    typeId: String,
    vm: LibraryViewModel,
    onDetail: (Long) -> Unit
) {
    val items = vm.categoryVods[typeId].orEmpty()
    val loading = vm.categoryPreviewLoading[typeId] ?: false
    // 首屏只铺一行（10 条），横向滚到尾部再从内存/下一页追加，避免一次渲染 70+ 张海报
    val visible = vm.categoryVisibleCount[typeId] ?: 10
    when {
        // 加载中 → 和手机端一致的 shimmer 骨架行（不再用转圈）
        loading && items.isEmpty() -> TvShimmerRow()
        items.isNotEmpty() -> TvPosterRow(
            items = items.take(visible),
            onDetail = onDetail,
            onNearEnd = { vm.loadMoreCategoryPreview(typeId) }
        )
    }
}

/** 分类标题 +「查看全部」；条目还没回来时先不挂按钮，避免点进空列表 */
@Composable
private fun TvCategoryTitle(
    typeId: String,
    typeName: String,
    vm: LibraryViewModel,
    onCategory: (String, String) -> Unit
) {
    val hasItems = vm.categoryVods[typeId]?.isNotEmpty() == true
    TvSectionTitle(typeName) {
        if (hasItems) TvMoreButton { onCategory(typeId, typeName) }
    }
}

/** 骨架占位行：宽度与电视海报卡一致，横向铺 6 张 */
@Composable
private fun TvShimmerRow(count: Int = 6) {
    val w = Prefs.posterSize.tvDp.dp
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(count) {
            Box(Modifier.width(w)) { ShimmerCard() }
        }
    }
}

/** 横向海报行：左右滚动，聚焦放大由卡片自身处理；[onNearEnd] 用于滚到尾部追加下一批 */
@Composable
private fun TvPosterRow(
    items: List<MediaEntity>,
    onDetail: (Long) -> Unit,
    cardWidth: androidx.compose.ui.unit.Dp = Prefs.posterSize.tvDp.dp,
    onNearEnd: (() -> Unit)? = null
) {
    val rowState = rememberLazyListState()
    if (onNearEnd != null) {
        val nearEnd by remember(items.size) {
            derivedStateOf {
                val last = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                items.isNotEmpty() && last >= items.size - 3
            }
        }
        LaunchedEffect(nearEnd, items.size) { if (nearEnd) onNearEnd() }
    }
    LazyRow(
        state = rowState,
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
                        color = TvText.Title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.seriesKey.isNotBlank() && item.season > 0) {
                        Text(
                            "S${item.season.toString().padStart(2, '0')} · E${item.episode.toString().padStart(2, '0')}",
                            color = TvText.Body,
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

/** 首页动态分区：标题 + 条目 + 「查看全部」跳转参数（kind 或 genre 二选一） */
internal data class TvGallerySection(
    val title: String,
    val items: List<MediaEntity>,
    val kind: String? = null,
    val genre: String? = null
)

private fun buildTvGallerySections(library: List<MediaEntity>): List<TvGallerySection> {
    if (library.isEmpty()) return emptyList()
    val result = mutableListOf<TvGallerySection>()

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
        result += TvGallerySection(title, items.take(TV_LAZY_TAKE), kind = kind)
    }
    for ((genre, items) in genreBlocks) {
        result += TvGallerySection(genre, items.take(TV_LAZY_TAKE), genre = genre)
    }
    return result
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
                selected -> TvText.Title
                else -> TvText.Section
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
                .width(400.dp)
                .background(AppColors.SurfaceHi, RoundedCornerShape(18.dp))
                .padding(vertical = 26.dp)
        ) {
            Text(
                "选择首页来源",
                color = TvText.Title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 26.dp)
            )
            Spacer(Modifier.height(14.dp))
            LazyColumn(
                Modifier.widthIn(max = 400.dp).heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
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

/** 电视端「选择线路（站点）」弹窗 —— 横网格铺开，线路一屏可见，遥控器上下左右都能走。
 *  宽度/列数按当前设备屏幕自适应（不写死），1080p 电视上默认铺满约 92% 屏幕宽度。 */
@Composable
private fun HomeSiteDialog(
    sites: List<TvBoxSite>,
    selectedKey: String?,
    onPick: (String?, TvBoxSite?) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Dialog(
        onDismissRequest = onDismiss,
        // 关掉平台默认窄宽度，让内容自己按屏幕尺寸决定弹窗宽度
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            // 宽度：屏幕宽度的 80%，夹在 560dp ~ 1280dp 之间（手机/电视都不至于过窄或过宽）
            val dialogWidth = (maxWidth * 0.80f).coerceIn(560.dp, 1280.dp)
            // 列数：按每个选项至少 240dp 估算，2 ~ 6 列
            val columns = ((dialogWidth - 40.dp) / 120.dp).toInt().coerceIn(4, 6)
            // 列表高度上限：屏幕高度的 60%（先取值，避免在 LazyGridScope 里被同名接收者遮蔽）
            val gridMaxHeight = maxHeight * 0.6f
            Column(
                Modifier
                    .width(dialogWidth)
                    .background(AppColors.SurfaceHi, RoundedCornerShape(18.dp))
                    .padding(vertical = 26.dp)
            ) {
                Text(
                    "选择线路（站点）",
                    color = TvText.Title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
                Spacer(Modifier.height(14.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().heightIn(max = gridMaxHeight),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    gridItems(sites, key = { "site_${it.key}" }) { site ->
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
}
