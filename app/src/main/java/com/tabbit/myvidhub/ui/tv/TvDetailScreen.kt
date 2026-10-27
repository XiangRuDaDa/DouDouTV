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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
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

    /**
     * 渲染层级与逻辑层级分离。
     *
     * level 是「用户按到第几层」，pageLevel 是「此刻屏幕上画的是第几层」。
     * 两者在过渡过程中故意不同步：先是旧页整块上移淡出，中间那一瞬间把 pageLevel
     * 换成新页并把新页摆到下方，再让新页滑入。这样切换看起来是「翻页」，
     * 而不是内容在原地长高 / 塌缩。
     */
    var pageLevel by remember(mediaId) { mutableStateOf(0) }
    var lastLevel by remember(mediaId) { mutableStateOf(0) }
    val flipShift = remember(mediaId) { Animatable(0f) }
    val flipAlpha = remember(mediaId) { Animatable(1f) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /** 进页后遥控器焦点直接落在「播放」按钮上，不用先按一堆方向键 */
    val playFocusRequester = remember { FocusRequester() }

    /** 「选择剧集」按下后把焦点直接送进第一集，省掉一次方向键 */
    val firstEpFocusRequester = remember { FocusRequester() }

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

    // 主演名单：Emby 刮削优先、TMDB 补齐，取前 5 位（电视一行放得下的量）。
    // 手机端详情页一直有这排圆形头像，电视端之前只有一行「主演 张三 / 李四」
    // 的纯文字，最直观的那层信息反而丢了。
    val tvCast = remember(scraped, tmdb) {
        val fromEmby = scraped?.actors?.takeIf { it.isNotEmpty() }
            ?.map { TvCast(it.name, it.role, it.imageUrl) }
        val fromTmdb = tmdb?.actors?.takeIf { it.isNotEmpty() }
            ?.map { TvCast(it.name, it.role, it.imageUrl) }
        (fromEmby ?: fromTmdb).orEmpty().distinctBy { it.name }.take(5)
    }

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

    /**
     * 整页上下切换。
     *
     * 按「下」：当前页整体上移 + 淡出 → 透明度归零的瞬间换页（并在不可见时把滚动
     * 位置挪到新页开头）→ 新页从底部轻推上来 + 淡入。
     * 按「上」方向相反，新页从顶部落下，符合「翻回去」的空间直觉。
     * 位移用页面高度的比例而不是固定 dp，换分辨率时位移观感一致；不用滑动整屏，
     * 是因为这里内容是同一条列表的不同段落，小位移 + 换页已经足够表达「翻过一页」。
     */
    LaunchedEffect(level) {
        if (level == lastLevel) return@LaunchedEffect
        val down = level > lastLevel

        // ① 旧页退场：向下翻时旧页往上走，向上翻时旧页往下走
        kotlinx.coroutines.coroutineScope {
            launch { flipShift.animateTo(if (down) -0.030f else 0.030f, tween(170, easing = FastOutLinearInEasing)) }
            launch { flipAlpha.animateTo(0f, tween(150, easing = FastOutLinearInEasing)) }
        }

        // ② 不可见的瞬间换页：此时屏幕上没有内容，跳转滚动不会看到跳动
        pageLevel = level
        runCatching { listState.scrollToItem(pageScrollTarget(level)) }
        flipShift.snapTo(if (down) 0.045f else -0.045f)

        // ③ 新页入位：从下方（或上方）推移到正位并淡入
        kotlinx.coroutines.coroutineScope {
            launch { flipShift.animateTo(0f, tween(280, easing = LinearOutSlowInEasing)) }
            launch { flipAlpha.animateTo(1f, tween(230, easing = LinearOutSlowInEasing)) }
        }

        lastLevel = level
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
                        // 按一次 DOWN → 一次性展开到 L3（全部展开），而不是逐级展开让用户按多次
                        Key.DirectionDown -> if (level < 3) {
                            level = 3
                            true
                        } else false
                        // 按一次 UP → 一次性收起回 L0，而不是逐级收起
                        Key.DirectionUp -> if (level > 0) {
                            level = 0
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
            modifier = Modifier
                .fillMaxSize()
                // 整页切换的位移与透明度：位移按页面高度取比例，任何分辨率都同一个观感
                .graphicsLayer {
                    translationY = flipShift.value * size.height
                    alpha = flipAlpha.value
                },
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // 顶部占位：把 L0（标题/评分/按钮）推到屏幕 55% 处，
            // 落在 headerScrim 主色已较浓的区域，不挡海报主体、沉浸感更强
            item {
                Spacer(Modifier.height(screenH * 0.55f))
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
                    // 外层通栏：剧情、元信息、导演、类型这些「要读的内容」铺满整屏，
                    // 右侧不再空出一大片。限宽只作用在下面的 L0 首屏骨架上。
                    Column(Modifier.fillMaxWidth()) {
                        // L0 首屏骨架只占左 58%：电视是 16:9 横屏，首屏只有标题、
                        // 评分胶囊、徽章和一个主按钮，铺满整宽会变成「左边一列字、
                        // 右边一大片空」。左 58% 放文字与按钮，右 42% 留给海报主视觉；
                        // 用比例不用固定 dp，换分辨率、换屏幕比例时左右关系都不会跑偏。
                        Column(
                            Modifier
                                .fillMaxWidth(0.72f)
                                .padding(start = 20.dp, end = 12.dp)
                        ) {
                        // 标题位：Logo 与文字标题共用同一块「标题槽」。
                        // 以前有刮削时是一张很大的艺术字图、没有刮削时是两行大字，
                        // 两种片子的首屏高度和重心完全不同。现在统一成：起始高度一致、
                        // Logo 高度封顶，刮削成功与否页面骨架都长得一样。
                        val titleSlotHeight = 48.dp
                        var logoFailed by remember(logo) { mutableStateOf(false) }
                        // 标题行：标题在左，收藏按钮紧贴标题右侧，两者构成同一个视觉组。
                        // 收藏原来独立站在操作区里 —— 一个圆图标孤零零挨着播放键，
                        // 既抢走主按钮的视觉重心，又让人怀疑它是不是「第二个播放动作」。
                        // 收进标题行后位置在三种状态下恒定，操作区也回到「只有一个主按钮」。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                // fill = false：标题按内容取宽，短标题时收藏紧跟字尾；
                                // 长标题才吃掉剩余宽度，收藏不会被挤出屏幕。
                                Modifier
                                    .weight(1f, fill = false)
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
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            TvIconButton(
                                icon = if (item.favorite) Icons.Filled.Favorite
                                else Icons.Filled.FavoriteBorder,
                                active = item.favorite,
                                contentDescription = if (item.favorite) "取消收藏" else "收藏",
                                sizeDp = 40.dp,
                                onClick = { vm.toggleFavorite(item.id, !item.favorite) }
                            )
                        }

                        // ===== L0 常驻骨架：TvMetaRow（年份·类型·时长）上移到标题下、徽章上 =====
                        // 设计决策：评分胶囊从 L0 彻底移除 —— 海报背景图通常已带 IMDb 评分，
                        // 再加一层「TMDB x.x」白胶囊是重复。TvMetaRow 放在标题与徽章之间，
                        // 形成节奏：标题(40sp 粗) → 元信息(14sp 中) → 徽章(11sp) → 按钮(15sp 粗)
                        Spacer(Modifier.height(8.dp))
                        TvMetaRow(item)
                        Spacer(Modifier.height(10.dp))
                        FormatBadgesRow(
                            tmdbEnabled = false,   // 评分胶囊已删，徽章行不再显示 TMDB 字样
                            resolution = scraped?.videoResolution.orEmpty(),
                            hdr = scraped?.hdrType.orEmpty(),
                            codec = scraped?.videoCodec.orEmpty()
                        )

                        // 操作按钮行：只出一个主按钮，三种内容状态各自对应一个明确动词。
                        // 之前「播放 / 从头开始 / 收藏」三件套并排，按钮越多，每个越不像主操作；
                        // 现在按「有没有看过」分流：没看过的剧先选集，看过的直接续播。
                        val seriesUnwatched = item.kind == MediaKind.SERIES && item.positionMs <= 5_000L
                        Spacer(Modifier.height(18.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (seriesUnwatched) {
                                // 没看过的剧：不猜「从哪集开始」，让用户自己点。
                                // 按下即展开剧集层、滚到网格开头、焦点落在第一集上。
                                TvPlayButton(
                                    text = "选择剧集",
                                    compact = true,
                                    modifier = Modifier.focusRequester(playFocusRequester),
                                    onClick = {
                                        epPage = 0
                                        // 展开交给整页切换动画：它会在换页的不可见瞬间
                                        // 把滚动位置挪到选集开头，这里只负责把焦点送到第一集。
                                        if (level < 2) level = 2
                                        scope.launch {
                                            kotlinx.coroutines.delay(620)
                                            runCatching { firstEpFocusRequester.requestFocus() }
                                        }
                                    }
                                )
                            } else {
                                // 看过 / 电影：主按钮直接播放，剧集带上集号
                                TvPlayButton(
                                    text = run {
                                        if (item.kind == MediaKind.SERIES && item.episode > 0) {
                                            "继续观看 S${item.season}E${item.episode}"
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
                            }
                        }

                        } // ← L0 首屏骨架（58%）到此结束

                        // L1/L3 的正文（剧情、元信息、导演、类型、署名）铺满整屏，
                        // 左内边距与 L0 骨架的 56dp 对齐，展开后左侧边界连成一条直线。
                        Column(Modifier.padding(start = 20.dp, end = 20.dp)) {
                        // ========== L1：剧情简介（评分与徽章已在 L0 常驻） ==========
                        // 默认不显示，按下键才展开；这样进页面第一眼只有标题、评分、
                        // 徽章和播放按钮，海报背景仍能占满整屏，是「大图沉浸」的前提。
                        // 这里不再做「高度展开」动画：展开/收起已经由外层整页切换统一
                        // 负责（旧页上移淡出 → 换页 → 新页滑入）。两层动画叠加会让
                        // 文字在淡入过程中又长高一次，看起来像内容在抽搐。
                        if (pageLevel >= 1) {
                            Column {
                                val overview = tmdb?.overview?.takeIf { it.isNotBlank() }
                                    ?: scraped?.tagline?.takeIf { it.isNotBlank() }
                                    ?: item.overview
                                if (overview.isNotBlank()) {
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = overview,
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Normal,
                                        lineHeight = 28.sp,
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // 元信息行（年份·类型·时长）已在 L0 常驻骨架里，这里不再重复
                            }
                        }

                        // ========== L3：主演 / 导演 / 类型 / 署名 ==========
                        // 同上：只判断显示与否，过渡交给外层整页切换
                        if (pageLevel >= 3) {
                            Column {
                                val directors = (tmdb?.directors?.takeIf { it.isNotEmpty() }?.map { it.name }
                                    ?: scraped?.directors?.map { it.name })
                                    ?.joinToString(" / ").orEmpty()
                                if (directors.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    TvInfoLine("导演", directors)
                                }
                                // 「主演」不再在这里出纯文字：剧集下方已有
                                // 圆形头像 + 姓名 + 角色的主演行，重复一行字只会挤占空间。
                                if (item.genres.isNotBlank()) {
                                    TvInfoLine("类型", item.genres)
                                }
                                if (tmdb != null) {
                                    Spacer(Modifier.height(18.dp))
                                    TmdbCreditLine()
                                }
                            }
                        }

                        } // ← L1/L3 通栏内容到此结束
                        // 层级提示已移到屏幕最底边固定悬浮，见下方 Box 内的吸底块。
                    }
                }
            }

            // ---------- 分集（网格 + 分季 + 翻页）：L2 才出现 ----------
            if (pageLevel >= 2 && episodes.isNotEmpty()) {
                // ===== 选集标题（简洁单行，按钮不挤在这里）=====
                item {
                    TvSectionTitle(
                        text = if (tvSeasons.size > 1) {
                            "选集（第 $selectedSeason 季 · 共 ${seasonEpisodes.size} 集）"
                        } else {
                            "选集（共 ${episodes.size} 集）"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp
                        )
                    )
                }

                // ===== 按钮行：独立 item，整行宽度可用 =====
                // 根因修复：之前把所有 pill（云盘×N + 季×N + 翻页×3）塞进
                // TvSectionTitle 的 trailing Row 里，后者只有标题右边那点剩宽度，
                // 多季/多云盘时 8+ 个 pill 全部溢出裁掉 → 中间大片空白 → 用户以为
                // 「简介和剧集之间间隔太大」。拆成独立 item 后按钮行拿到整宽。
                if (playSources.size > 1 || tvSeasons.size > 1 || totalPages > 1) {
                    item(key = "ep_controls") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
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
                                // 末集直达：几百集的剧不用一路翻到底
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
                                .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowEps.forEachIndexed { idx, ep ->
                                TvEpisodeCard(
                                    episode = ep,
                                    modifier = Modifier.weight(1f),
                                    // 首页第一格挂焦点器，「选择剧集」按下后直接落到这里
                                    focusRequester = if (rowIdx == 0 && idx == 0) {
                                        firstEpFocusRequester
                                    } else null,
                                    onClick = { onPlay(ep.id) }
                                )
                            }
                            repeat(TV_GRID_COLS - rowEps.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // 剧集下方主演行：与手机端详情页对齐，补上圆形头像 + 姓名 + 角色。
                if (tvCast.isNotEmpty()) {
                    item(key = "tv_cast_row") {
                        TvSectionTitle(
                            text = "主演",
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp
                            )
                        )
                        TvCastRow(tvCast)
                    }
                }
            } else if (pageLevel >= 2 && !loading && item != null && item.kind == MediaKind.SERIES) {
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

        // 层级提示：固定吸在屏幕最底边，不随内容滚动。
        // 之前它跟在信息块末尾，展开几层就被推到屏幕外 —— 用户既看不到
        // 「还能再展开」，也看不到「怎么收起来」，提示等于失效。
        // 现在是根 Box 的最后一个子元素，始终浮在内容之上、位置恒定。
        if (level < 3) {
            Text(
                if (level == 0) "▼ 按下键查看剧情、评分与剧集"
                else if (level == 1) "▼ 按下键查看剧集  ▲ 按上键收起"
                else "▲ 按上键收起",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
            )
        }
    }
}

/**
 * 每一层对应的「页首」在列表里的位置。
 *
 * 0 = 顶部占位（看到 55% 处的大图与 L0 首屏），1 = 标题块（剧情/元信息/影人信息），
 * 2 = 选集标题（剧集网格从第一行开始）。整页切换时在不可见的瞬间跳到这里，
 * 于是每一层都像独立的一屏，而不是在同一条长列表里滚动了一小段。
 */
private fun pageScrollTarget(level: Int): Int = when (level) {
    0 -> 0
    1 -> 1
    2 -> 2
    else -> 1
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

/** 每页显示的分集数：电视端一行 10 个、共 4 行，一屏看完，不用横向滑 */
private const val TV_EPS_PAGE = 40

/** 分集网格列数：详情区铺满整屏后从 6 列提到 10 列，
 *  单格宽度维持在 85dp 上下，比原来更宽裕，遥控器焦点框也更好认。 */
private const val TV_GRID_COLS = 10

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
    focusRequester: FocusRequester? = null,
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
            // focusRequester 必须排在 focusable 之前，才挂得到这个焦点目标上
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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

/** 电视端主演行用的轻量数据结构，避免直接依赖 Emby / TMDB 两套 Person 类型 */
private data class TvCast(val name: String, val role: String, val imageUrl: String)

/**
 * 主演头像行：圆形头像 + 姓名 + 角色。
 * 尺寸按十英尺界面放大（72dp 头像），三米外也能看清脸；
 * 通栏铺满，左起 56dp 与上方标题、剧集网格对齐成一条左边界。
 */
@Composable
private fun TvCastRow(cast: List<TvCast>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        cast.forEach { person ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(88.dp)
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(AppColors.SurfaceHi),
                    contentAlignment = Alignment.Center
                ) {
                    if (person.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model = person.imageUrl,
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            person.name.take(1),
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    person.name,
                    color = AppColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (person.role.isNotBlank()) {
                    Text(
                        person.role,
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
