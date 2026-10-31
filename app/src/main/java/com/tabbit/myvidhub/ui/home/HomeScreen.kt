package com.tabbit.myvidhub.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient.TvBoxSite
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.ReturnSpot
import com.tabbit.myvidhub.ui.components.ContinueCard
import com.tabbit.myvidhub.ui.components.HeroBanner
import com.tabbit.myvidhub.ui.components.PosterCard
import com.tabbit.myvidhub.ui.components.SectionTitle
import com.tabbit.myvidhub.ui.components.ShimmerCard
import com.tabbit.myvidhub.ui.components.usesSourceEpisodeName
import com.tabbit.myvidhub.ui.listState
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.themeBackdrop
import com.tabbit.myvidhub.ui.theme.themeSeamFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页精选大图轮播 → 继续观看 → 收藏 → [动态分区]
 *
 * 懒加载策略：每个分类只展示最新 10 条（横向滑动画廊，条数由 ViewModel 控制），
 * 点「更多」进入分类浏览页再按类型/题材分页加载整库，
 * 避免一次性把几百上千个条目全部刷进首页。
 *
 * 顶栏（软件名/刷新/搜索）悬浮在轮播图之上，下滑时平滑淡出，
 * 顶到轮播图顶部时再淡入。
 */
@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    onOpenSearch: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenGenre: (kind: String?, genre: String?) -> Unit,
    onOpenCategory: (typeId: String, typeName: String) -> Unit,
    onPlay: (MediaEntity) -> Unit
) {
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val featured by vm.featured.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val sources by vm.enabledSources.collectAsStateWithLifecycle()

    val cardWidth = if (isTv) Prefs.posterSize.tvDp.dp else Prefs.posterSize.mobileDp.dp

    // 「继续观看」卡片长按弹出的操作菜单（TV 端的对应实现在 TvHomeScreen，走遥控器长按 OK）
    var continueMenu by remember { mutableStateOf<MediaEntity?>(null) }

    // 当前生效的来源：手动选择的来源，未选择（或已删除/停用）时回退到第一个启用中的来源
    val selectedSource = sources.firstOrNull { it.id == vm.homeSourceId } ?: sources.firstOrNull()

    // 首页内容按选中的来源/站点（线路）过滤；无来源时原样返回
    val filteredLibrary = remember(library, selectedSource?.id, vm.homeSiteKey) { vm.filterForHome(library) }
    val filteredFeatured = remember(featured, selectedSource?.id, vm.homeSiteKey) { vm.filterForHome(featured) }
    // 继续观看只看当前来源/线路的那份记录：换来源就看到另一份追看记录
    val filteredContinue = remember(continueList, selectedSource?.id, vm.homeSiteKey) {
        vm.filterForHome(continueList)
    }

    // 轮播取当前线路 homeContent 的前 6 条（源站推荐顺序），来源与线路一致；
    // 没拉到线路数据时回退精选/本地库
    val heroItems = remember(filteredFeatured, filteredLibrary, vm.siteHomeItems) {
        val fromSite = vm.siteHomeItems
            .filter { it.posterUrl.isNotBlank() || it.backdropUrl.isNotBlank() }
            .distinctBy { it.id }
        if (fromSite.isNotEmpty()) {
            fromSite.take(6)
        } else {
            val primary = filteredFeatured.filter { it.backdropUrl.isNotBlank() }
            if (primary.size >= 3) primary.take(6)
            else (primary + filteredLibrary.filter { it.posterUrl.isNotBlank() }).distinctBy { it.id }.take(6)
        }
    }

    // 来源/站点（线路）选择器数据：选了 TVBox 源且站点列表已拉取 → 顶栏动态显示「线路」按钮
    val siteList = selectedSource?.id?.let { vm.homeSitesBySource[it] }.orEmpty()
    val namedSites = siteList.filter { it.key.isNotBlank() }
    val showLineButton = selectedSource?.kind == SourceKind.TVBOX && namedSites.isNotEmpty()
    val currentSite = namedSites.firstOrNull { it.key == vm.homeSiteKey }

    // 冷启动不会走 selectHomeSource，这里主动拉一次线路列表，否则顶栏「线路」入口一直不出现
    LaunchedEffect(selectedSource?.id) {
        selectedSource?.id?.let { vm.ensureHomeSites(it) }
    }

    // 首页纵向位置 + 每条横向行的位置都记在 ReturnSpot：进详情再返回（这一页子树被
    // AnimatedContent 整个销毁重建）时，组合的第一帧就站在离开时的位置。
    val listState = ReturnSpot.listState(PhoneHomeKey)
    // 软件名淡出距离：轮播图高度的 ~55%
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val heroCollapseDist = (configuration.screenWidthDp * 0.55f).toInt()
    // 注意：alpha 计算放在 graphicsLayer 的 draw 阶段（下面 Column 里），
    // 不在这里在 composition 层读 firstVisibleItemScrollOffset —— 那会导致每像素滚动都触发整页重组

    // 背景走全 app 统一的那套光晕层：原来这里在 LazyColumn 上写死了一条
    // 「Brand@0.09 → Bg」的顶部渐变，往下整屏都是纯黑，换主题色在首页读不出来；
    // 而且挂在滚动节点上，光晕会跟着内容一起滚走。
    Box(Modifier.fillMaxSize().themeBackdrop()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            // ---------- 精选大图（置顶，顶栏悬浮其上） ----------
            // 轮播区常驻：没有可推荐内容时用同高度占位，避免首页在「加载中 / 空库」之间跳版。
            item {
                if (heroItems.isNotEmpty()) {
                    // 大图渐隐收到的纯 Bg，与下方渗了品牌色的页面背景对不上，
                    // 接缝正压在轮播底边上 —— 用一条收色带把接缝挪进渐变中段（见 themeSeamFade）
                    Column {
                        HeroBanner(
                            items = heroItems,
                            isTv = isTv,
                            onOpen = { onOpenDetail(it.id) },
                            onPlay = { onPlay(it) }
                        )
                        Box(Modifier.themeSeamFade(if (isTv) 40.dp else 28.dp))
                    }
                } else {
                    HeroEmpty(
                        vm = vm,
                        isTv = isTv,
                        libraryEmpty = library.isEmpty(),
                        hasCategories = vm.homeCategories.isNotEmpty()
                    )
                }
            }

            // ---------- 继续观看 ----------
            if (filteredContinue.isNotEmpty()) {
                item { SectionTitle("继续观看") }
                item {
                    LazyRow(
                        state = ReturnSpot.listState("$PhoneHomeKey/continue"),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredContinue, key = { "cw_${it.id}" }) { item ->
                            ContinueCard(
                                item = item,
                                onClick = { onPlay(item) },
                                onLongClick = { continueMenu = item }
                            )
                        }
                    }
                }
            }

            // ---------- 收藏 ----------
            // 收藏是用户主动留下的内容，排在「继续观看」之前，与 TV 端首页同一顺序
            if (favorites.isNotEmpty()) {
                item { SectionTitle("我的收藏", trailing = { CountBadge(favorites.size) }) }
                item {
                    LazyRow(
                        state = ReturnSpot.listState("$PhoneHomeKey/fav"),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(favorites, key = { "fav_${it.id}" }) { item ->
                            Box(Modifier.width(cardWidth)) {
                                PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                            }
                        }
                    }
                }
            }

            // ---------- TVBox 线路动态分类 section：每个分类纵向一个 section，跟 Emby 动态分区风格一致 ----------
            val homeCategories = vm.homeCategories
            if (homeCategories.isNotEmpty()) {
                homeCategories.take(7).forEachIndexed { index, cat ->
                    val catItems = vm.categoryVods[cat.typeId].orEmpty()
                    val isLoading = vm.categoryPreviewLoading[cat.typeId] ?: false
                    // 首屏只预载前 2 个分类，其余等 section 真正滚进可见区再拉（见下面的 item）
                    if (index < 2) {
                        // 首次进入 composition 时懒加载前 LAZY_TAKE=10 条（不阻塞）
                        item(key = "cat_ensure_${cat.typeId}") {
                            // homeRefreshEpoch 变化时重新触发一次，否则刷新后分类行会一直是旧缓存
                            LaunchedEffect(cat.typeId, vm.homeRefreshEpoch) { vm.ensureCategoryPreview(cat.typeId) }
                        }
                    }
                    item(key = "cat_title_${cat.typeId}") {
                        SectionTitle(
                            cat.typeName,
                            trailing = {
                                if (catItems.isNotEmpty()) MoreLink { onOpenCategory(cat.typeId, cat.typeName) }
                                else CountBadge(catItems.size)
                            }
                        )
                    }
                    item(key = "cat_row_${cat.typeId}") {
                        // 这一行滚进可见区才触发加载（首屏 2 个分类已由上面的 ensure 预载）
                        LaunchedEffect(cat.typeId, vm.homeRefreshEpoch) { vm.ensureCategoryPreview(cat.typeId) }
                        LazyRow(
                            state = ReturnSpot.listState("$PhoneHomeKey/cat_${cat.typeId}"),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            when {
                                // 加载中 → 4 个 shimmer 骨架卡
                                isLoading && catItems.isEmpty() -> {
                                    items(4) { Box(Modifier.width(cardWidth)) { ShimmerCard() } }
                                }
                                // 有数据 → 正常卡片
                                catItems.isNotEmpty() -> {
                                    items(catItems.take(LAZY_TAKE), key = { "cat_${cat.typeId}_${it.id}" }) { item ->
                                        Box(Modifier.width(cardWidth)) {
                                            PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                                        }
                                    }
                                }
                                // 加载完但该分类 Guard 返回空（如玩偶/观影线路）→ 空占位提示
                                else -> {
                                    item {
                                        Box(
                                            Modifier
                                                .width(cardWidth * 3)
                                                .height(cardWidth * 3 / 2)
                                                .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                "暂无内容",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AppColors.TextTertiary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 动态分区：根据库内实际存在的 kind / genre 渲染 ----------
            DynamicGallerySections(
                library = filteredLibrary,
                cardWidth = cardWidth,
                onMore = onOpenGenre,
                onOpenDetail = onOpenDetail
            )
        }

        // ---------- 透明悬浮顶栏（左软件名滚动淡出；右工具栏 + 来源/线路按钮常驻） ----------
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：软件名 + 统计，滚动时淡出（alpha 在 draw 阶段计算，不触发 composition 重组）
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        // 当第一个可见 item index 越过 hero（index >= 1）时，alpha 永久归零；
                        // 否则按 heroCollapseDist 内的 offset 线性淡出 —— 这样 index 切换瞬间不会闪回
                        val alpha = if (listState.firstVisibleItemIndex > 0) 0f
                        else if (heroCollapseDist == 0) 1f
                        else {
                            val raw = listState.firstVisibleItemScrollOffset.toFloat() / heroCollapseDist
                            (1f - raw).coerceIn(0f, 1f)
                        }
                        this.alpha = alpha
                    }
            ) {
                Text(
                    "DouDouTV",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    if (vm.scanning) vm.scanMessage.ifBlank { "同步中…" }
                    else "${filteredLibrary.size} 个条目 · ${sources.size} 个来源",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }

            // 来源/线路图标按钮（搜索按钮左侧）＋ 选了 TVBox 源后动态追加的线路按钮
            if (sources.isNotEmpty()) {
                HomeSourceSelector(
                    sources = sources,
                    selectedId = selectedSource?.id,
                    onSelect = { vm.selectHomeSource(it) }
                )
                Spacer(Modifier.width(5.dp))
                if (showLineButton) {
                    HomeLineSelector(
                        sites = namedSites,
                        selectedKey = vm.homeSiteKey,
                        onSelect = { key, site -> vm.selectHomeSite(key, site) }
                    )
                    Spacer(Modifier.width(5.dp))
                }
            }

            // 右侧：搜索/刷新工具栏，常驻不淡出
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Color.White)
            }
            if (vm.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(5.dp).width(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                IconButton(onClick = { vm.refreshHome() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新当前来源", tint = Color.White)
                }
            }
        }
    }

    // ---------- 继续观看卡片长按菜单 ----------
    continueMenu?.let { item ->
        ContinueActionDialog(
            item = item,
            onPlay = {
                continueMenu = null
                onPlay(item)
            },
            onDetail = {
                continueMenu = null
                onOpenDetail(item.id)
            },
            onClear = {
                // 只归零进度，不删媒体行：条目立刻离开「继续观看」，片子还在库里
                vm.clearWatchProgress(item.id)
                continueMenu = null
            },
            onDismiss = { continueMenu = null }
        )
    }
}

/**
 * 手机端「继续观看」卡片长按菜单：继续播放 / 查看详情 / 清除观看记录。
 *
 * 用 [AlertDialog] 而不是自制浮层 —— 手指点按的场景下系统弹窗的遮罩、焦点、
 * Back 关闭都是现成的，与来源页删除确认框（SourcesScreen）同一套视觉。
 */
@Composable
private fun ContinueActionDialog(
    item: MediaEntity,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = {
            Text(
                text = item.title,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = buildString {
                        if (item.seriesKey.isNotBlank() && item.season > 0 && !item.usesSourceEpisodeName) {
                            append("S${item.season.toString().padStart(2, '0')} · E${item.episode.toString().padStart(2, '0')} · ")
                        }
                        append("看到 ")
                        append(continueClock(item.positionMs))
                        if (item.durationMs > 0L) {
                            append(" / ")
                            append(continueClock(item.durationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary
                )
                Spacer(Modifier.height(10.dp))
                ContinueMenuAction("继续播放") { onPlay() }
                ContinueMenuAction("查看详情") { onDetail() }
                ContinueMenuAction("清除观看记录", danger = true) { onClear() }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppColors.TextSecondary)
            }
        }
    )
}

@Composable
private fun ContinueMenuAction(
    text: String,
    hint: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) Color(0xFFE5484D) else AppColors.TextPrimary
        )
        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }
    }
}

/** 菜单里的进度时长：分够一小时才带时位 */
private fun continueClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 动态分区：kind 基础分区 + genres 扩展分区。
 *
 * 1. kind 分区：MOVIE → 电影；SERIES → 剧集（只取顶层 kind，EPISODE 不独立分区）
 * 2. genre 分区：从所有条目的 genres 里提取（按英文逗号 / 中文逗号 / 顿号分隔），
 *    每个出现 ≥2 次的 genre 单独一个 section。Emby/TVBox 源的条目 genres 字段里
 *    通常有「动作,科幻,剧情」这样的逗号分隔字符串，拆分后能得到 3~8 个分区，
 *    解决之前只有 2~3 个 kind 分区太单调的问题。
 *
 * 条目可以同时出现在多个 section（一部科幻片既在「电影」又在「科幻」），
 * 这是主流流媒体的做法，用户接受且有利于发现内容。
 */
private const val LAZY_TAKE = 10
private const val MIN_GENRE_ITEMS = 2
private const val MAX_GENRE_SECTIONS = 4

/** 手机端首页在 [ReturnSpot] 里的页面标识，纵向位置和各行横向位置都挂在它下面 */
private const val PhoneHomeKey = "phone_home"

private fun LazyListScope.DynamicGallerySections(
    library: List<MediaEntity>,
    cardWidth: Dp,
    onMore: (kind: String?, genre: String?) -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    if (library.isEmpty()) return

    // 1. kind 基础分区：MOVIE / SERIES / 其他
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

    // 2. genre 扩展分区：从所有条目的 genres 拆分
    val genreToItems = mutableMapOf<String, MutableList<MediaEntity>>()
    val genrePattern = Regex("[,，、/\\s]+")
    for (item in library) {
        // distinct() 防止 genres 字段有重复标签（如 "科幻,科幻,动作"）导致同一 item 被加两次
        val parts = item.genres.split(genrePattern).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (g in parts) {
            genreToItems.getOrPut(g) { mutableListOf() }.add(item)
        }
    }
    // 过滤数量 < MIN_GENRE_ITEMS、kind 分区已覆盖的 genre（如「电影」genre 映射到 MOVIE kind），再按数量倒序取前 MAX_GENRE_SECTIONS
    val kindAsGenre = setOf("电影", "剧集", "单集")
    val genreBlocks = genreToItems.entries
        .map { (g, items) -> g to items.distinctBy { it.id } }
        .filter { (g, items) -> items.size >= MIN_GENRE_ITEMS && g !in kindAsGenre }
        .sortedByDescending { it.second.size }
        .take(MAX_GENRE_SECTIONS)

    // 先 kind 后 genre
    for ((kind, items) in kindBlocks) {
        val title = when (kind) {
            MediaKind.MOVIE -> "电影"
            MediaKind.SERIES -> "剧集"
            else -> kind
        }
        GallerySection(
            title = title,
            items = items.take(LAZY_TAKE),
            cardWidth = cardWidth,
            onMore = { onMore(kind, null) },
            onOpenDetail = onOpenDetail
        )
    }
    for ((genre, items) in genreBlocks) {
        GallerySection(
            title = genre,
            items = items.take(LAZY_TAKE),
            cardWidth = cardWidth,
            onMore = { onMore(null, genre) },
            onOpenDetail = onOpenDetail
        )
    }
}

/** 横向画廊区块：最新 N 条 + 「更多」入口，超出屏幕左右滑动展示 */
private fun LazyListScope.GallerySection(
    title: String,
    items: List<MediaEntity>,
    cardWidth: Dp,
    onMore: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    if (items.isEmpty()) return
    item { SectionTitle(title, trailing = { MoreLink(onMore) }) }
    item {
        LazyRow(
            state = ReturnSpot.listState("$PhoneHomeKey/sec_$title"),
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(items, key = { "${title}_${it.id}" }) { item ->
                Box(Modifier.width(cardWidth)) {
                    PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                }
            }
        }
    }
}

/** 标题右侧的「更多 ›」入口 */
@Composable
private fun MoreLink(onMore: () -> Unit) {
    Text(
        "更多 ›",
        style = MaterialTheme.typography.labelMedium,
        color = AppColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onMore)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        text = "$count",
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.TextTertiary
    )
}

/** 轮播区占位：和 HeroBanner 同高（TV 430dp / 手机 392dp），居中放空态说明，保证首页版式稳定 */
@Composable
private fun HeroEmpty(vm: LibraryViewModel, isTv: Boolean, libraryEmpty: Boolean, hasCategories: Boolean) {
    val h = if (isTv) 430.dp else 392.dp
    Box(
        // 不再自绘「Brand@0.14 → Bg」：这格位置本来就落在首页的 themeBackdrop 上，
        // 自己糊一层等于把刚接进来的主题背景又盖回纯黑
        Modifier
            .fillMaxWidth()
            .height(h),
        contentAlignment = Alignment.Center
    ) {
        when {
            libraryEmpty -> EmptyState(vm)
            hasCategories -> Column(
                Modifier.padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "暂无推荐内容",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary
                )
                Text(
                    "可从下方分类入口浏览",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextTertiary
                )
            }
            else -> LineEmptyHint(vm.homeSitesError)
        }
    }
}

@Composable
private fun EmptyState(vm: LibraryViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "还没有内容",
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary
        )
        Text(
            "到「来源」页添加一个 Emby 服务器或 TVBox 接口，\n回来后点右上角同步按钮",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextTertiary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "当前 ${vm.sources.value.size} 个来源",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary
        )
    }
}

/** 顶栏来源按钮：图标样式（与搜索/刷新一致），点击弹出来源选择菜单 */
@Composable
private fun HomeSourceSelector(
    sources: List<SourceEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Source, contentDescription = "选择来源", tint = Color.White)
        }
        HomePickerMenu(
            expanded = expanded,
            onDismiss = { expanded = false }
        ) {
            sources.forEach { src ->
                DropdownMenuItem(
                    text = {
                        Text(
                            src.name,
                            color = AppColors.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = if (src.id == selectedId) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = AppColors.Brand) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(src.id)
                    }
                )
            }
        }
    }
}

/** 顶栏线路（TVBox 站点）按钮：图标样式，点击弹出线路选择菜单 */
@Composable
private fun HomeLineSelector(
    sites: List<TvBoxSite>,
    selectedKey: String?,
    onSelect: (String?, TvBoxSite?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // 菜单内是等高的 DropdownMenuItem（48dp），按「下标 × 行高」滚动即可定位到当前线路
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // 选中线路在列表中的下标（0 = 「全部站点」，其余 +1）
    val selectedIndex = remember(sites, selectedKey) {
        if (selectedKey == null) 0
        else sites.indexOfFirst { it.key == selectedKey }.let { if (it < 0) 0 else it + 1 }
    }
    // 打开菜单时自动定位到当前线路，线路有几十条时不用手动翻找
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        // 弹窗内容完成首帧布局后 maxValue 才是真实可滚动距离
        repeat(3) { withFrameNanos { } }
        val itemPx = with(density) { 48.dp.roundToPx() }
        val target = (selectedIndex * itemPx - itemPx).coerceIn(0, scrollState.maxValue)
        scrollState.scrollTo(target)
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.AltRoute, contentDescription = "选择线路", tint = Color.White)
        }
        HomePickerMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            scrollState = scrollState
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "全部站点",
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = if (selectedKey == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null, tint = AppColors.Brand) }
                } else null,
                onClick = {
                    expanded = false
                    onSelect(null, null)
                }
            )
            sites.forEach { site ->
                DropdownMenuItem(
                    text = {
                        Text(
                            site.name,
                            color = AppColors.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = if (selectedKey == site.key) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = AppColors.Brand) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(site.key, site)
                    }
                )
            }
        }
    }
}

/**
 * 选择弹窗容器：固定最大高度 + 纵向滚动，来源/站点再多也不会重叠或超出屏幕。
 * 内容由调用方以纵向排列的 DropdownMenuItem 提供。
 *
 * 注意：这里不能用 LazyColumn —— DropdownMenu 会向内容请求 intrinsic 高度，
 * 而 LazyColumn 基于 SubcomposeLayout 不支持 intrinsic 测量，会直接崩溃。
 */
@Composable
private fun HomePickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .background(AppColors.SurfaceHi, RoundedCornerShape(14.dp))
                .padding(vertical = 6.dp)
                .heightIn(max = 240.dp)
                .verticalScroll(scrollState)
        ) {
            content()
        }
    }
}

/** 库里有内容但当前来源/站点还没同步到内容时的提示；error 非空时把真实失败原因透出 */
@Composable
private fun LineEmptyHint(error: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            error ?: "当前来源/站点暂无内容",
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary
        )
        Text(
            if (error != null) "点击右上角同步按钮重试，或切换其他来源/线路"
            else "点击右上角同步按钮拉取该来源的首页内容",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextTertiary
        )
    }
}

// ================= 分类浏览页 =================

/** 分类标签：kind 与 genre 组合定义一类（都为 null = 全部） */
private data class GenreTab(val label: String, val kind: String?, val genre: String?)

private const val GENRE_PAGE_SIZE = 60

/**
 * 分类浏览页：顶部标签横滑，下方纵向海报网格分页加载。
 * 「动漫」等题材与「电影/剧集」类型共用同一套浏览体验。
 */
@Composable
fun GenreScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    initialKind: String?,
    initialGenre: String?,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    val columns = if (isTv) 6 else 3
    val hSpacing = if (isTv) 14.dp else 9.dp

    var kind by remember { mutableStateOf(initialKind) }
    var genre by remember { mutableStateOf(initialGenre) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 纵向位置按「页 + 当前筛选」分键，进详情返回时从这一筛选上次的位置起滚
    val gridState = ReturnSpot.listState("phone_genre/${kind ?: "all"}/${genre ?: "all"}")

    LaunchedEffect(Unit) {
        tags = runCatching { vm.genreTags() }.getOrDefault(emptyList())
    }

    LaunchedEffect(kind, genre) {
        loading = true
        items = runCatching { vm.browseGenre(kind, genre, GENRE_PAGE_SIZE, 0) }
            .getOrDefault(emptyList())
        loading = false
    }

    // 标签列表：预设类型 + 库内高频题材
    val tabs = remember(tags) {
        buildList {
            add(GenreTab("全部", null, null))
            add(GenreTab("电影", MediaKind.MOVIE, null))
            add(GenreTab("剧集", MediaKind.SERIES, null))
            add(GenreTab("动漫", null, "动漫"))
            tags.forEach { add(GenreTab(it, null, it)) }
        }
    }
    val currentTab = tabs.firstOrNull { it.kind == kind && it.genre == genre }

    // 根容器不滚动（滚动的是下面的网格），所以统一背景直接挂在它上：
    // 原来这里写死了一条「Brand@0.09 → Bg」的顶部渐变，往下整屏纯黑，换主题色在这页读不出来
    Column(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
    ) {
        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextSecondary
                )
            }
            Text(
                "分类浏览",
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        // ---------- 标签（渐变分隔线 + 紧凑 filter chip） ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    // 两头都淡出、只在中段压一层半透明 Bg：原来收到不透明纯色时，
                    // 这条带的底边会在带主题色的背景上压出一条横线；半透明两头收口就没这个界线
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.62f to AppColors.Bg.copy(alpha = 0.5f),
                        1f to Color.Transparent
                    )
                )
                .padding(vertical = 4.dp)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(tabs) { tab ->
                    val selected = currentTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { kind = tab.kind; genre = tab.genre },
                        label = { Text(tab.label, fontWeight = FontWeight.Medium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Brand,
                            selectedLabelColor = Color.White,
                            containerColor = AppColors.SurfaceHi,
                            labelColor = AppColors.TextSecondary
                        )
                    )
                }
            }
        }

        // ---------- 网格 ----------
        when {
            loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand) }

            items.isEmpty() -> Text(
                "该分类下暂无内容",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(36.dp)
            )

            else -> LazyColumn(
                state = gridState,
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item {
                    Text(
                        "共 ${items.size} 条" + if (items.size >= GENRE_PAGE_SIZE) "（持续加载中）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                items(items.chunked(columns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = if (hSpacing > 10.dp) 8.dp else 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing)
                    ) {
                        row.forEach { item ->
                            Box(Modifier.weight(1f)) {
                                PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // 分页加载更多
                if (items.size >= GENRE_PAGE_SIZE) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand)
                            } else {
                                Button(
                                    onClick = {
                                        loadingMore = true
                                        scope.launch {
                                            val more = runCatching {
                                                vm.browseGenre(kind, genre, GENRE_PAGE_SIZE, items.size)
                                            }.getOrDefault(emptyList())
                                            // 追加下一页，去重防止同步间隙产生重复
                                            items = items + more.filterNot { next -> items.any { it.id == next.id } }
                                            loadingMore = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.SurfaceHi,
                                        contentColor = AppColors.TextPrimary
                                    )
                                ) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索页状态。
 *
 * 提升到导航层（PhoneApp）持有，而不是页面内部的 remember：
 * 进详情页会把搜索页踢出组合树，内部 remember 会全部丢，
 * 返回时就出现「搜索结果被清空」。状态放在导航层后，
 * 只有真正退出搜索页（返回上一级 / 切底栏）才 [clear]。
 */
class SearchUiState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<MediaEntity>>(emptyList())
    var grouped by mutableStateOf<Map<String, List<MediaEntity>>>(emptyMap())
    var searching by mutableStateOf(false)
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var submittedKeyword by mutableStateOf("")

    /** 已完成搜索的关键字：从详情返回时据此复用结果，不重复联网搜索 */
    var searchedKeyword by mutableStateOf("")

    /** 热门榜缓存版本，刷新后自增以触发联想重查 */
    var hotEpoch by mutableStateOf(0)

    fun clear() {
        query = ""
        results = emptyList()
        grouped = emptyMap()
        searching = false
        suggestions = emptyList()
        submittedKeyword = ""
        searchedKeyword = ""
    }
}

/**
 * 两行流式布局：子项从左到右排布，超出第一行换行，最多排两行，
 * 放不下的子项不再参与布局（当前 Compose 版本的 FlowRow 还没有 maxLines 参数）。
 */
@Composable
private fun TwoLineFlowRow(
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hGap = horizontalSpacing.roundToPx()
        val vGap = verticalSpacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = maxWidth)) }

        data class Placed(val placeable: androidx.compose.ui.layout.Placeable, val x: Int, val y: Int)
        val placed = mutableListOf<Placed>()
        var x = 0
        var y = 0
        var rowHeight = 0
        var line = 1
        for (p in placeables) {
            if (x > 0 && x + hGap + p.width > maxWidth) {
                if (line == 2) break
                line++
                y += rowHeight + vGap
                x = 0
                rowHeight = 0
            }
            placed += Placed(p, x, y)
            x += if (x == 0) p.width else hGap + p.width
            rowHeight = maxOf(rowHeight, p.height)
        }

        val width = (placed.maxOfOrNull { it.x + it.placeable.width } ?: 0)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placed.maxOfOrNull { it.y + it.placeable.height } ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placed.forEach { it.placeable.placeRelative(it.x, it.y) }
        }
    }
}

/**
 * 全局搜索：本地媒体库 + TVBox 远程搜索，标题模糊匹配 / 拼音首字母前缀联想。
 *
 * 输入防抖 200ms，避免每敲一个字都打一次库；状态由导航层持有，从详情返回不丢。
 */
@Composable
fun SearchScreen(
    vm: LibraryViewModel,
    isTv: Boolean,
    state: SearchUiState,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    var history by remember { mutableStateOf<List<String>>(Prefs.getSearchHistory()) }
    val inputFocus = remember { FocusRequester() }
    // 结果区的位置记录：状态在导航层，从详情返回时这一页重建，位置靠这里找回
    val resultsListState = ReturnSpot.listState("phone_search/results")

    // 首次进搜索页后台拉一次豆瓣热门榜，补齐联想（24h 内走本地缓存，不联网）
    LaunchedEffect(Unit) {
        if (runCatching { vm.refreshHotSuggestions() }.getOrDefault(false)) state.hotEpoch++
    }

    // 联想：输入停顿 200ms 后查库，只有用户输入是字母（拼音首字母）时才做联想
    LaunchedEffect(state.query, state.hotEpoch) {
        val q = state.query.trim()
        if (q.isEmpty()) {
            state.suggestions = emptyList()
            return@LaunchedEffect
        }
        if (state.submittedKeyword.isNotEmpty()) return@LaunchedEffect
        if (q.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            delay(200)
            state.suggestions = runCatching { vm.pinyinSuggest(q.lowercase()) }.getOrDefault(emptyList())
        } else {
            state.suggestions = emptyList()
        }
    }

    // 实际搜索：只有 submittedKeyword 变化（回车/点按钮触发）才执行；
    // 从详情返回时关键字没变，直接复用已有结果，不重复搜索。
    // 本地结果先上屏（Room，几十毫秒），远程 TVBox 结果按站点流式追加：
    // 哪个站点先搜完就先把它的命中追加到列表，不再整批等到最后。
    LaunchedEffect(state.submittedKeyword) {
        val q = state.submittedKeyword.trim()
        if (q.isEmpty() || q == state.searchedKeyword) return@LaunchedEffect
        state.searching = true
        val local = runCatching { vm.searchMedia(q) }.getOrDefault(emptyList())
        state.results = local
        state.grouped = local.groupBy { it.sourceName.ifBlank { "未知来源" } }
        state.searchedKeyword = q

        /** 把一批远程结果并进当前列表（按 remoteId 去重） */
        fun appendRemote(batch: List<MediaEntity>) {
            val seen = state.results.map { it.remoteId }.toSet()
            val merged = state.results + batch.filter { it.remoteId !in seen }
            state.results = merged
            state.grouped = merged.groupBy { it.sourceName.ifBlank { "未知来源" } }
        }

        val remote = vm.searchRemote(q) { partial ->
            // 远程返回前用户可能已经发起了下一次搜索，旧结果不能覆盖当前列表
            if (state.submittedKeyword != q) return@searchRemote
            appendRemote(partial)
            // 首批结果已经上屏，就不再让用户对着「搜索中」干等
            state.searching = false
        }
        if (state.submittedKeyword != q) return@LaunchedEffect
        appendRemote(remote)
        state.searching = false
    }

    // 进页自动聚焦输入框
    LaunchedEffect(Unit) { runCatching { inputFocus.requestFocus() } }

    val hSpacing = if (isTv) 14.dp else 9.dp

    /** 提交搜索：存历史 + 触发搜索 */
    fun submitSearch() {
        val kw = state.query.trim()
        if (kw.isEmpty()) return
        Prefs.addSearchHistory(kw)
        history = Prefs.getSearchHistory()
        state.submittedKeyword = kw
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索框 + 返回
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextSecondary
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = {
                    state.query = it
                    // 输入变化时清除上次的搜索结果（让用户看到"正在输入"状态）
                    if (state.submittedKeyword.isNotEmpty()) {
                        state.submittedKeyword = ""
                        state.searchedKeyword = ""
                        state.results = emptyList()
                        state.grouped = emptyMap()
                    }
                },
                placeholder = {
                    Text(
                        if (isTv) "搜索片名 / 演员 / 拼音首字母" else "搜索片名 / 拼音首字母 (如 tthr)",
                        color = AppColors.TextTertiary
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(inputFocus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                trailingIcon = {
                    IconButton(onClick = { submitSearch() }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索", tint = AppColors.Brand)
                    }
                }
            )
        }

        // 搜索框下方：历史（空输入/有搜索结果时显示）或联想（有输入且未搜索时显示），两者互斥
        when {
            // 有输入但还没真正搜索 → 显示联想
            state.query.isNotBlank() && state.submittedKeyword.isEmpty() && state.suggestions.isNotEmpty() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.suggestions.take(if (isTv) 8 else 6).forEach { title ->
                        FilterChip(
                            selected = false,
                            onClick = { state.query = title },  // 只填不搜
                            label = { Text(title, maxLines = 1) },
                            modifier = Modifier.height(36.dp)
                        )
                    }
                }
            }
            // 空输入或已搜索过 → 显示历史（换行展示，最多 2 行 10 条，末尾带清空按钮）
            history.isNotEmpty() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    TwoLineFlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 6.dp
                    ) {
                        history.take(10).forEach { kw ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    state.query = kw
                                    submitSearch()  // 点历史直接搜
                                },
                                label = { Text(kw, maxLines = 1) },
                                modifier = Modifier.height(36.dp)
                            )
                        }
                    }
                    // 清空按钮固定在历史区末尾，一次清掉全部历史（不受两行裁剪影响）
                    TextButton(
                        onClick = {
                            Prefs.clearSearchHistory()
                            history = emptyList()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = null,
                            tint = AppColors.TextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "清空",
                            maxLines = 1,
                            color = AppColors.TextTertiary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        when {
            state.submittedKeyword.isEmpty() && state.query.isBlank() ->
                HintText("输入关键字或拼音首字母（如 tthr = 偷天换日），然后回车或点搜索")

            state.searching && state.results.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(strokeWidth = 2.dp, color = AppColors.Brand) }

            state.submittedKeyword.isNotEmpty() && state.results.isEmpty() ->
                HintText("没有找到「${state.submittedKeyword}」相关内容")

            // 有结果：按来源分组展示
            else -> LazyColumn(
                state = resultsListState,
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item { SectionTitle("搜索结果 ${state.results.size} 条 · ${state.grouped.size} 个来源") }
                state.grouped.forEach { (srcName, itemsOfSource) ->
                    item {
                        SectionTitle(
                            srcName,
                            trailing = { CountBadge(itemsOfSource.size) }
                        )
                    }
                    item {
                        LazyRow(
                            state = ReturnSpot.listState("phone_search/row_$srcName"),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(hSpacing)
                        ) {
                            items(itemsOfSource, key = { "srch_${it.id}" }) { item ->
                                val cardW = cardWidthOf(isTv)
                                Box(Modifier.width(cardW)) {
                                    PosterCard(
                                        item = item,
                                        onClick = {
                                            android.util.Log.d(
                                                "MyVidHubNav",
                                                "search result click id=${item.id} title=${item.title} remoteId=${item.remoteId}"
                                            )
                                            onOpenDetail(item.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** TV 海报卡片宽度，复用 HomeScreen 的 cardWidth */
private fun cardWidthOf(isTv: Boolean) = if (isTv) 136.dp else 112.dp

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.TextTertiary,
        modifier = Modifier.fillMaxWidth().padding(36.dp)
    )
}

/**
 * TVBox 线路分类全量浏览页。
 * 入口：首页 TVBox 动态分类 section 的「查看全部」MoreLink。
 * 分页策略：先拉 page=1（最多 72 条/页），到底后点「加载更多」继续 page++；
 * 源站若忽略 page 参数反复返回同一页，去重后没有新增即判定到底，避免「点加载更多永远不涨」。
 *
 * 顶部带当前线路的分类切换（来自 homeCategories）：进页后不用退回首页就能换分类，
 * 分类内容不落库，换分类即重新拉第一页。
 */
@Composable
fun TvBoxCategoryScreen(
    vm: LibraryViewModel,
    typeId: String,
    typeName: String,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit
) {
    // 列数跟随设置页「缩略图大小」：小 4 列 / 中 3 列 / 大 2 列
    val columns = when (Prefs.posterSize) {
        Prefs.PosterSize.SMALL -> 4
        Prefs.PosterSize.MEDIUM -> 3
        Prefs.PosterSize.LARGE -> 2
    }
    val hSpacing = 9.dp

    // 进页时带着首页的那个分类，之后在页内原地切换
    var currentTypeId by remember(typeId) { mutableStateOf(typeId) }
    val categories = vm.homeCategories
    val currentName = categories.firstOrNull { it.typeId == currentTypeId }?.typeName ?: typeName

    var vods by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // 每个分类各记一份滚动位置
    val gridState = ReturnSpot.listState("phone_cat_$currentTypeId")

    LaunchedEffect(currentTypeId, retryTick) {
        loading = true
        loadingMore = false
        loadError = null
        exhausted = false
        vods = emptyList()
        page = 1
        val result = runCatching { vm.tvBoxCategoryPage(currentTypeId, 1) }
        result.onFailure { loadError = it.message ?: "网络或接口异常" }
        vods = result.getOrDefault(emptyList())
        page = 2
        loading = false
    }

    val loadMore: () -> Unit = {
        if (!loadingMore) {
            loadingMore = true
            val reqTypeId = currentTypeId
            scope.launch {
                val result = runCatching { vm.tvBoxCategoryPage(reqTypeId, page) }
                if (reqTypeId != currentTypeId) {
                    loadingMore = false
                    return@launch
                }
                result.onFailure { loadError = it.message ?: "网络或接口异常" }
                val more = result.getOrDefault(emptyList())
                if (more.isEmpty()) {
                    exhausted = true
                } else {
                    val seen = vods.mapTo(HashSet(vods.size)) { it.id }
                    val fresh = more.filter { seen.add(it.id) }
                    // 源站忽略 page 时每页都相同：没有新增就当到底，
                    // 否则 page 一直加、列表永远停在原地（用户看到的就是「只有 40 部」）。
                    if (fresh.isEmpty()) exhausted = true
                    else {
                        vods = vods + fresh
                        page += 1
                    }
                }
                loadingMore = false
            }
        }
    }

    // 同 GenreScreen：根容器不滚动，统一背景直接挂在它上，替掉原来那条写死的顶部渐变
    Column(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
    ) {
        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColors.TextSecondary
                )
            }
            Text(
                currentName,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        // ---------- 分类切换（当前线路 homeContent 的 class 数组） ----------
        if (categories.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories, key = { "phone_cat_chip_${it.typeId}" }) { cat ->
                    FilterChip(
                        selected = cat.typeId == currentTypeId,
                        onClick = {
                            if (cat.typeId != currentTypeId) currentTypeId = cat.typeId
                        },
                        label = {
                            Text(
                                cat.typeName,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Brand,
                            selectedLabelColor = Color.White,
                            containerColor = AppColors.SurfaceHi,
                            labelColor = AppColors.TextSecondary
                        )
                    )
                }
            }
        }

        // ---------- 网格 ----------
        when {
            loading -> LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp)) {
                items(List(9) { it }.chunked(columns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing)
                    ) {
                        row.forEach { Box(Modifier.weight(1f)) { ShimmerCard() } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            vods.isEmpty() -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(36.dp)
            ) {
                Text(
                    text = if (loadError != null) "加载失败：$loadError" else "该分类下暂无内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextTertiary
                )
                if (loadError != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { retryTick++ },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.SurfaceHi,
                            contentColor = AppColors.TextPrimary
                        )
                    ) { Text("重试") }
                }
            }

            else -> LazyColumn(
                state = gridState,
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(vods.chunked(columns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(hSpacing)
                    ) {
                        row.forEach { item ->
                            Box(Modifier.weight(1f)) {
                                PosterCard(item = item, onClick = { onOpenDetail(item.id) })
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // 分页状态行：加载中 / 失败重试 / 加载更多 / 已到底
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            loadingMore -> CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = AppColors.Brand
                            )

                            loadError != null -> Button(
                                onClick = { loadError = null; loadMore() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.SurfaceHi,
                                    contentColor = AppColors.TextPrimary
                                )
                            ) { Text("加载失败，点击重试") }

                            exhausted -> Text(
                                "没有更多了 · 共 ${vods.size} 部",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextTertiary
                            )

                            else -> Button(
                                onClick = loadMore,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.SurfaceHi,
                                    contentColor = AppColors.TextPrimary
                                )
                            ) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}
