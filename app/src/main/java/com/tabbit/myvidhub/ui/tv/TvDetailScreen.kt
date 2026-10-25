package com.tabbit.myvidhub.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.detail.FocusColorCache
import com.tabbit.myvidhub.ui.detail.fallbackColorFromTitle
import com.tabbit.myvidhub.ui.detail.focusColorOf
import com.tabbit.myvidhub.ui.detail.loadPosterBitmap
import com.tabbit.myvidhub.ui.detail.pageTonesOf
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.scrape.ScrapeEngine
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.ui.components.FormatBadgesRow
import com.tabbit.myvidhub.ui.components.TmdbCreditLine
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 电视端简介页（独立实现，不复用手机端 DetailScreen）。
 *
 * 结构差异来自观看距离：
 *   · 左侧大海报 + 右侧文案的两栏布局，替代手机端「上下堆叠」；
 *   · 操作按钮 2~3 个大按钮横排，遥控器左右即可选中，不做长列表；
 *   · 分集改为网格卡片（6 列 × 4 行 = 每页 24 集），可按季切换、可翻页：
 *     几百集的剧横排滚动要按几百次右键，网格既能方向键跳着走，
 *     又能一屏看到 24 集的已看进度，找「看到哪了」比手机端还快。
 *
 * 背景沿用「封面聚焦色填充 + 封面到填充色的长距离溶解过渡」：
 * 头图下半段逐渐变成整页底色，两者之间没有硬边。
 */
@Composable
fun TvDetailScreen(
    vm: LibraryViewModel,
    mediaId: Long,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit
) {
    var media by remember { mutableStateOf<MediaEntity?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var scraped by remember { mutableStateOf<EmbyClient.ItemDetail?>(null) }

    /** TVBox 多播放源（云盘/线路） */
    var playSources by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPlaySource by remember { mutableStateOf<String>("") }

    /**
     * TMDB 刮削结果。
     *
     * 这个页面之前完全没有接 TMDB，所以「刮削前 / 刮削后」看起来一模一样 ——
     * 手机端有海报、艺术字 Logo、评分、简介、演职员，电视端却只有 Emby 的基础字段。
     * 这里和手机端 DetailScreen 走同一条链路（同样的策略判定 + 新鲜期判断），
     * 保证两端刮削结果一致。
     */
    var tmdb by remember { mutableStateOf<com.tabbit.myvidhub.data.tmdb.TmdbClient.ScrapeResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    /** 分季 + 分页：几百集的剧直接一屏铺满很难找，先按季过滤再按页翻 */
    var selectedSeason by remember(mediaId) { mutableStateOf(0) }
    var epPage by remember(mediaId) { mutableStateOf(0) }

    /**
     * 分层展开层级：0 = 只有名字，1 = +简介/评分/徽章，2 = +剧集，3 = +主演。
     *
     * 按遥控器「下」逐层展开，「上」逐层收起，回到 0 就是刚进页面那一屏。
     * 用单值状态机而不是四个布尔量，可以保证「依次」的语义不会被打乱
     * （比如剧集还没展开时，主演不可能提前出现）。
     */
    var level by remember(mediaId) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /** 进页后遥控器焦点直接落在「播放」按钮上，不用先按一堆方向键 */
    val playFocusRequester = remember { FocusRequester() }

    // 首帧就用「上次算好的主色 / 内存缓存 / 标题哈希色」，避免进入瞬间一片黑
    // 同手机端：mediaOf() 是 suspend，组合期不能调，改从已订阅的 library 同步取，
    // 并把 remember 的 key 挂到标题上，库数据晚到时自动重新给初值
    val libraryState by vm.library.collectAsState()
    val seedMedia = remember(libraryState, mediaId) {
        libraryState.firstOrNull { it.id == mediaId }
    }
    var dominant by remember(mediaId, seedMedia?.title) {
        mutableStateOf(
            seedMedia?.let { seed ->
                val persisted = Prefs.detailTone(seed.title).takeIf { it != 0 }
                if (persisted != null) Color(persisted)
                else FocusColorCache.get(seed.posterUrl.ifBlank { seed.backdropUrl })
                    ?: fallbackColorFromTitle(seed.title)
            } ?: AppColors.Bg
        )
    }

    LaunchedEffect(mediaId) {
        loading = true
        // 整个加载过程兜底：任何 IO/解析异常都不允许冒泡到 Composition，
        // 否则电视端会直接崩溃退出（用户看到的就是「点详情闪退」）。
        try {
        val m = vm.mediaOf(mediaId)
        media = m
        if (m != null) {
            val src = vm.sourceOf(m.sourceId)
            if (src?.kind == SourceKind.EMBY) {
                scraped = runCatching { vm.embyDetailOf(m.sourceId, m.remoteId) }.getOrNull()
                if (m.kind == MediaKind.SERIES) {
                    runCatching { vm.loadEmbyEpisodes(m.sourceId, m.remoteId) }
                }
            } else if (src?.kind == SourceKind.TVBOX && m.kind == MediaKind.SERIES) {
                runCatching { vm.loadTvBoxDetail(m.sourceId, m.remoteId) }
            }
            val latest = vm.mediaOf(mediaId) ?: m
            media = latest

            // TVBox 多播放源：读取全部云盘/线路，默认选第一条，按选中源过滤分集；
            // Emby 无源概念，playSources 为空时走全量分集。
            val sources = runCatching { vm.playSourcesOf(latest.seriesKey) }.getOrDefault(emptyList())
            playSources = sources
            if (sources.isNotEmpty()) {
                if (selectedPlaySource !in sources) selectedPlaySource = sources.first()
                episodes = runCatching { vm.episodesBySource(latest.seriesKey, selectedPlaySource) }
                    .getOrDefault(emptyList())
            } else {
                episodes = runCatching { vm.episodesOf(latest.seriesKey) }.getOrDefault(emptyList())
            }
            episodes.firstOrNull()?.season?.let { if (selectedSeason == 0) selectedSeason = it }

            // TMDB 刮削：与手机端同一套判定（凭据存在 + 未被近期刮过）
            if (ScrapeStrategy.refresh() == ScrapeEngine.TMDB) {
                val fresh = latest.scrapedAt > 0L &&
                    System.currentTimeMillis() - latest.scrapedAt < TV_SCRAPE_FRESH_MS
                val localReady = latest.posterUrl.isNotBlank() || latest.logoUrl.isNotBlank()
                if (!(fresh && localReady)) {
                    tmdb = runCatching {
                        vm.tmdbScrape(latest.title, latest.year, latest.kind, mediaId)
                    }.getOrNull()
                    // 刮削会把海报/Logo 回填进库，取回最新的再渲染
                    vm.mediaOf(mediaId)?.let { media = it }
                }
            }
        }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            loading = false
        }
    }

    // 取色：与手机端一致，用 Coil ImageLoader 直接解码 bitmap，失败回退标题哈希色
    val tvContext = LocalContext.current
    // 依赖里带上 tmdb?.posterUrl：刮削完成后海报换了，主色要跟着重算
    LaunchedEffect(media?.posterUrl, media?.backdropUrl, tmdb?.posterUrl) {
        val m = media ?: return@LaunchedEffect
        val url = tmdb?.posterUrl.orEmpty().ifBlank {
            m.posterUrl.ifBlank { m.backdropUrl }
        }.ifBlank {
            m.backdropUrl
        }
        if (url.isBlank()) {
            dominant = fallbackColorFromTitle(m.title)
            return@LaunchedEffect
        }
        val cached = FocusColorCache.get(url)
        if (cached != null) {
            dominant = cached
            Prefs.putDetailTone(m.title, cached.toArgb())
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.Default) { loadPosterBitmap(tvContext, url) }
        val color = bmp?.let { runCatching { focusColorOf(it) }.getOrNull() }
            ?: fallbackColorFromTitle(m.title)
        FocusColorCache.put(url, color)
        Prefs.putDetailTone(m.title, color.toArgb())
        dominant = color
    }

    // 加载完成后把焦点落到「播放」按钮上，进页即可直接按 OK 播放
    LaunchedEffect(media, loading) {
        if (!loading && media != null) {
            kotlinx.coroutines.delay(120)
            runCatching { playFocusRequester.requestFocus() }
        }
    }

    val item = media

    // ---------- 分集：按季过滤 + 按页切块 ----------
    val tvSeasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val seasonEpisodes = remember(episodes, selectedSeason, tvSeasons) {
        if (tvSeasons.size <= 1) episodes else episodes.filter { it.season == selectedSeason }
    }
    val totalPages = remember(seasonEpisodes) {
        ((seasonEpisodes.size + TV_EPS_PAGE - 1) / TV_EPS_PAGE).coerceAtLeast(1)
    }
    // 换季时页码可能越界，这里钳一下，避免出现空白页
    val pageIndex = epPage.coerceIn(0, totalPages - 1)
    val pagedEpisodes = remember(seasonEpisodes, pageIndex) {
        seasonEpisodes.drop(pageIndex * TV_EPS_PAGE).take(TV_EPS_PAGE)
    }

    val tones = remember(dominant) {
        if (dominant == Color.Unspecified) {
            AppColors.Bg to lerp(AppColors.Bg, Color.Black, 0.55f)
        } else {
            pageTonesOf(dominant)
        }
    }
    val top by animateColorAsState(tones.first, tween(320), label = "tv_top")
    val bottom by animateColorAsState(tones.second, tween(320), label = "tv_bottom")

    val bgBrush = remember(top, bottom) {
        Brush.verticalGradient(
            0f to top,
            0.34f to top,
            0.72f to lerp(top, bottom, 0.72f),
            1f to bottom
        )
    }
    // 头图 → 填充色的溶解过渡
    val headerScrim = remember(top) {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.30f),
            0.28f to Color.Transparent,
            0.55f to top.copy(alpha = 0.34f),
            0.80f to top.copy(alpha = 0.82f),
            1f to top
        )
    }

    val backdrop = tmdb?.backdropUrl.orEmpty().ifBlank {
        item?.let { it.backdropUrl.ifBlank { it.posterUrl } }.orEmpty()
    }
    val logo = item?.logoUrl.orEmpty().ifBlank { tmdb?.logoUrl.orEmpty() }

    // 展开后把视图滚到新增内容附近，否则新出现的区块在屏幕外，用户看不到变化
    LaunchedEffect(level) {
        if (level > 0) {
            kotlinx.coroutines.delay(140)
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) runCatching { listState.animateScrollToItem(last) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bgBrush)
            // 方向键在这里统一接管：预览阶段就能拦到，不会被内部焦点搜索先吃掉。
            // 只在「需要展开/收起」时消费事件，其余情况放行，保持正常的焦点移动。
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (e.key) {
                        Key.DirectionDown -> if (level < 3) {
                            level += 1
                            true
                        } else false
                        Key.DirectionUp -> if (level > 0) {
                            level -= 1
                            true
                        } else false
                        else -> false
                    }
                }
            }
    ) {
        // 背景横版图铺满整个页面，向下溶解到主色填充。
        // 与手机端一致：图像 → 主色 的长距离过渡，保留我们自己的取色风格，
        // 而不是截图那种纯图像底，保证整站视觉统一。
        if (backdrop.isNotBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = item?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 纵向：顶部 30% 黑 → 中段透明 → 底部完全收进主色
            Box(
                Modifier
                    .fillMaxSize()
                    .background(headerScrim)
            )
            // 横向左侧加深：文字都堆在左半屏，需要更厚的暗层保证可读性
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.62f),
                            0.45f to Color.Black.copy(alpha = 0.30f),
                            0.75f to Color.Transparent
                        )
                    )
            )
        }

        val screenH = LocalConfiguration.current.screenHeightDp.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // 顶部占位：把 L0（标题/评分/按钮）推到屏幕 45% 处，
            // 落在 headerScrim 主色已较浓的区域，不挡海报主体、沉浸感更强
            item {
                Spacer(Modifier.height(screenH * 0.45f))
            }

            // ---------- 标题区：艺术字 Logo 优先，文字标题兜底 ----------
            item {
                if (item == null) {
                    val boxMod = Modifier.fillMaxWidth().height(420.dp)
                    if (loading) {
                        TvEmptyState("加载中…", modifier = boxMod)
                    } else {
                        TvEmptyState("找不到该内容", modifier = boxMod)
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 56.dp, end = 56.dp)
                    ) {
                        // 标题位：Logo 与文字标题共用同一块「标题槽」。
                        // 以前有刮削时是一张很大的艺术字图、没有刮削时是两行大字，
                        // 两种片子的首屏高度和重心完全不同。现在统一成：起始高度一致、
                        // Logo 高度封顶，刮削成功与否页面骨架都长得一样。
                        val titleSlotHeight = 48.dp
                        var logoFailed by remember(logo) { mutableStateOf(false) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = titleSlotHeight),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (logo.isNotBlank() && !logoFailed) {
                                AsyncImage(
                                    model = logo,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Fit,
                                    onError = { logoFailed = true },
                                    modifier = Modifier
                                        .heightIn(max = titleSlotHeight)
                                        .widthIn(max = 340.dp)
                                )
                            } else {
                                Text(
                                    item.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // ===== L0 常驻骨架：评分胶囊 + 片源徽章始终同一行 =====
                        // 关键点：这里不做「有数据才渲染」的分支。有刮削、没刮削都必须
                        // 出同样的结构，缺数据只把文字降级（TMDB x.x → 评分 x.x → 暂无评分），
                        // 这样两种片子的首屏高度和节奏才一致，不会一个多一块、一个少一块。
                        val tmdbRating = tmdb?.rating ?: 0.0
                        val hasTmdbRating = tmdb != null && tmdbRating > 0.0
                        val shownRating = when {
                            hasTmdbRating -> tmdbRating
                            item.rating > 0.0 -> item.rating
                            else -> 0.0
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 评分胶囊：恒定存在，三种状态只换文字和底色深浅
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (shownRating > 0.0) Color.White
                                        else Color.White.copy(alpha = 0.16f)
                                    )
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    when {
                                        hasTmdbRating -> "TMDB " + "%.1f".format(tmdbRating)
                                        shownRating > 0.0 -> "评分 " + "%.1f".format(shownRating)
                                        else -> "暂无评分"
                                    },
                                    color = if (shownRating > 0.0) Color.Black
                                    else Color.White.copy(alpha = 0.62f),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // 评分胶囊已带「TMDB」字样，徽章行不再重复放 TMDB
                            FormatBadgesRow(
                                tmdbEnabled = tmdb != null && !hasTmdbRating,
                                resolution = scraped?.videoResolution.orEmpty(),
                                hdr = scraped?.hdrType.orEmpty(),
                                codec = scraped?.videoCodec.orEmpty()
                            )
                        }

                        // 操作按钮行：播放 + 从头开始（看过后才出现） + 收藏（圆形图标）
                        // 按钮整体收小一档、收藏改用图标，视觉重心让回给海报。
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 主按钮：剧集带具体集号，电影就是「立即播放」
                            TvPlayButton(
                                text = run {
                                    if (item.kind == MediaKind.SERIES && item.episode > 0) {
                                        if (item.positionMs > 5_000L) "继续观看 S${item.season}E${item.episode}"
                                        else "开始观看 S${item.season}E${item.episode}"
                                    } else {
                                        if (item.positionMs > 5_000L) "继续播放" else "立即播放"
                                    }
                                },
                                compact = true,
                                modifier = Modifier.focusRequester(playFocusRequester),
                                onClick = { onPlay(item.id) }
                            )
                            if (item.positionMs > 5_000L) {
                                TvPillButton(
                                    text = "从头开始",
                                    primary = false,
                                    compact = true,
                                    onClick = {
                                        vm.resetPosition(item.id)
                                        onPlay(item.id)
                                    }
                                )
                            }
                            TvIconButton(
                                icon = if (item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                active = item.favorite,
                                contentDescription = if (item.favorite) "取消收藏" else "收藏",
                                onClick = { vm.toggleFavorite(item.id, !item.favorite) }
                            )
                        }

                        // ========== L1：剧情简介（评分与徽章已在 L0 常驻） ==========
                        // 默认不显示，按下键才展开；这样进页面第一眼只有标题、评分、
                        // 徽章和播放按钮，海报背景仍能占满整屏，是「大图沉浸」的前提。
                        AnimatedVisibility(
                            visible = level >= 1,
                            enter = fadeIn(tween(260)) + expandVertically(tween(260)),
                            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
                        ) {
                            Column {
                                val overview = tmdb?.overview?.takeIf { it.isNotBlank() }
                                    ?: scraped?.tagline?.takeIf { it.isNotBlank() }
                                    ?: item.overview
                                if (overview.isNotBlank()) {
                                    Spacer(Modifier.height(22.dp))
                                    Text(
                                        overview,
                                        color = Color.White.copy(alpha = 0.82f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // 元信息行：年份 · 类型 · 时长 · 剧集数
                                Spacer(Modifier.height(18.dp))
                                TvMetaRow(item)
                            }
                        }

                        // ========== L3：主演 / 导演 / 类型 / 署名 ==========
                        AnimatedVisibility(
                            visible = level >= 3,
                            enter = fadeIn(tween(260)) + expandVertically(tween(260)),
                            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
                        ) {
                            Column {
                                val directors = (tmdb?.directors?.takeIf { it.isNotEmpty() }?.map { it.name }
                                    ?: scraped?.directors?.map { it.name })
                                    ?.joinToString(" / ").orEmpty()
                                if (directors.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    TvInfoLine("导演", directors)
                                }
                                val actors = (tmdb?.actors?.takeIf { it.isNotEmpty() }?.map { it.name }
                                    ?: scraped?.actors?.map { it.name })
                                    ?.take(8)?.joinToString(" / ").orEmpty()
                                if (actors.isNotBlank()) {
                                    TvInfoLine("主演", actors)
                                }
                                if (item.genres.isNotBlank()) {
                                    TvInfoLine("类型", item.genres)
                                }
                                if (tmdb != null) {
                                    Spacer(Modifier.height(18.dp))
                                    TmdbCreditLine()
                                }
                            }
                        }

                        // 层级提示：告诉用户还剩下几层，避免「信息被藏起来但不知道」的情况
                        if (level < 3) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                if (level == 0) "▼ 按下键查看剧情、评分与剧集"
                                else if (level == 1) "▼ 按下键查看剧集　　▲ 按上键收起"
                                else "▲ 按上键收起",
                                color = Color.White.copy(alpha = 0.42f),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // ---------- 分集（网格 + 分季 + 翻页）：L2 才出现 ----------
            if (level >= 2 && episodes.isNotEmpty()) {
                item {
                    TvSectionTitle(
                        text = if (tvSeasons.size > 1) {
                            "选集（第 $selectedSeason 季 · 共 ${seasonEpisodes.size} 集）"
                        } else {
                            "选集（共 ${episodes.size} 集）"
                        }
                    ) {
                        // 右上角：季切换 + 翻页，几百集不用一路横划到底
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 播放源 / 云盘切换：多条源时展示，默认第一条
                            if (playSources.size > 1) {
                                playSources.forEach { src ->
                                    TvPillButton(
                                        text = src,
                                        primary = selectedPlaySource == src,
                                        onClick = {
                                            selectedPlaySource = src
                                            epPage = 0
                                            scope.launch {
                                                val item2 = media
                                                if (item2 != null) {
                                                    episodes = vm.episodesBySource(item2.seriesKey, src)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                            if (tvSeasons.size > 1) {
                                tvSeasons.forEach { sn ->
                                    TvPillButton(
                                        text = if (sn > 0) "第 $sn 季" else "正片",
                                        primary = sn == selectedSeason,
                                        onClick = {
                                            selectedSeason = sn
                                            epPage = 0
                                        }
                                    )
                                }
                            }
                            if (totalPages > 1) {
                                TvPillButton(
                                    text = "‹ 上一页",
                                    primary = false,
                                    onClick = { if (pageIndex > 0) epPage = pageIndex - 1 }
                                )
                                Text(
                                    "${pageIndex + 1} / $totalPages",
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TvPillButton(
                                    text = "下一页 ›",
                                    primary = false,
                                    onClick = { if (pageIndex < totalPages - 1) epPage = pageIndex + 1 }
                                )
                                // 末集直达：几百集的剧不用一路翻到底，参考截图的「末集」
                                TvPillButton(
                                    text = "末集",
                                    primary = false,
                                    onClick = { epPage = totalPages - 1 }
                                )
                            }
                        }
                    }
                }
                // 网格用 chunked 手动分行：LazyVerticalGrid 嵌在 LazyColumn 里会因为
                // 双向无限高度约束直接崩，分行 Row 是最稳的做法
                pagedEpisodes.chunked(TV_GRID_COLS).forEachIndexed { rowIdx, rowEps ->
                    item(key = "ep_row_${pageIndex}_$rowIdx") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowEps.forEach { ep ->
                                TvEpisodeCard(
                                    episode = ep,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPlay(ep.id) }
                                )
                            }
                            repeat(TV_GRID_COLS - rowEps.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else if (level >= 2 && !loading && item != null && item.kind == MediaKind.SERIES) {
                // 剧集但分集为空：加载失败时给用户一个明确提示，而不是一块空白
                item {
                    TvEmptyState(
                        "暂无分集信息",
                        hint = "数据源可能未返回剧集列表，请稍后重试",
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                }
            }
        }
        // 遥控器 Back 键 = TvApp.BackHandler 统一处理，不再显示页面内返回按钮
    }
}

@Composable
private fun TvInfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(58.dp)
        )
        Text(
            value,
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 每页显示的分集数：电视端一行 6 个、共 4 行，一屏看完，不用横向滑 */
private const val TV_EPS_PAGE = 24

/** 分集网格列数 */
private const val TV_GRID_COLS = 6

/** 本地刮削结果的新鲜期：一周内不重复请求 TMDB */
private const val TV_SCRAPE_FRESH_MS = 7L * 24 * 60 * 60 * 1000

/**
 * TV 端分集卡片。
 *
 * 为什么不用原来的胶囊按钮：几百集的剧横排滚动，遥控器要按几百次右键才能翻到中间，
 * 而且看不到已看进度。改成卡片后可以按网格方向键移动，一屏能看到 24 集，
 * 已看的集数带进度条，一眼就能找到接到哪里。
 */
@Composable
private fun TvEpisodeCard(
    episode: MediaEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(140), label = "ep_card")

    val label = if (episode.episode > 0) {
        if (episode.season > 0) "S${episode.season}E${episode.episode}" else "第 ${episode.episode} 集"
    } else {
        episode.title
    }
    val watched = episode.positionMs > 5_000L
    val progress = if (episode.durationMs > 0L) {
        (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (watched) AppColors.Brand.copy(alpha = 0.18f) else AppColors.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AppColors.Brand else AppColors.SurfaceHi,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            color = if (watched) AppColors.BrandSoft else AppColors.TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        // 已看进度：一眼能看出这部剧看到哪了
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.SurfaceHi)
        ) {
            if (progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(AppColors.Brand)
                )
            }
        }
    }
}

/**
 * 徽章行：评分下面那一行。
 *
 * 作用是把「片源质量」这类离散事实压缩成一排小胶囊，一眼扫过即可，
 * 不占用正文空间。TMDB 徽章只在真的拿到 TMDB 数据时才出现，
 * 避免开关关闭时界面上出现不该有的来源标识。
 */
@Composable
private fun FormatBadgesRow(
    tmdbEnabled: Boolean,
    resolution: String,
    hdr: String,
    codec: String
) {
    val badges = mutableListOf<String>()
    if (tmdbEnabled) badges.add("TMDB")
    if (resolution.isNotBlank()) badges.add(resolution)
    if (hdr.isNotBlank()) badges.add(hdr)
    if (codec.isNotBlank()) badges.add(codec.uppercase())
    // 一条徽章都没有时也要占位（渲染一个灰色「—」），
    // 否则整行直接消失，有没有刮削的页面骨架会不一样，首屏高度会跳。
    val placeholder = badges.isEmpty()
    if (placeholder) badges.add("—")

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEach { label ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (label == "TMDB") AppColors.Brand.copy(alpha = 0.20f)
                        else Color.White.copy(alpha = 0.10f)
                    )
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    label,
                    color = when {
                        label == "TMDB" -> AppColors.BrandSoft
                        placeholder -> Color.White.copy(alpha = 0.34f)
                        else -> Color.White.copy(alpha = 0.72f)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
