package com.tabbit.myvidhub.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import com.tabbit.myvidhub.MainActivity
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.HtmlText
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.scrape.ScrapeEngine
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.ui.components.TmdbCreditLine
import com.tabbit.myvidhub.ui.theme.TvText
import androidx.compose.runtime.collectAsState

/**
 * 电视端简介页（独立实现，不复用手机端 DetailScreen）。
 *
 * 交互模型：**单页滚动 + 焦点分区**，参考 Netflix 的电视端详情页。
 *   · 信息不再按层级显隐 —— 标题、简介、导演、主演、选集从进页起就在组合树里，
 *     方向键只负责在焦点锚点之间移动，视口跟着焦点滚动；
 *   · 因此不存在「按下键解锁剧情」「按上键把选集收掉」这两种模态行为：
 *     上键永远只回上一个区域，内容不会消失；
 *   · 只读区块（简介、导演、主演、署名）不接收焦点，下键会直接跳过它们；
 *   · 页面以选集收尾：焦点走不出选集网格，放在选集下方的内容等于看不见，
 *     所以导演排在简介之后、主演之前，TMDB 署名排在主演之后，都在同一屏里；
 *   · 首屏仍保留「大图优先」：55% 顶部占位把标题与操作区压到屏幕下半部，
 *     下键进入第一集时，简介、主演、选集首行同屏可见。
 *
 * 版式与观看距离：
 *   · 左侧大海报 + 右侧文案的两栏布局，替代手机端「上下堆叠」；
 *   · 操作按钮 2~3 个大按钮横排，遥控器左右即可选中，不做长列表；
 *   · 分集改为网格卡片（10 列 × 4 行），可按季切换、可翻页：
 *     几百集的剧横排滚动要按几百次右键，网格既能方向键跳着走，
 *     又能一屏看到已看进度，找「看到哪了」比手机端还快。
 *
 * 背景沿用「封面聚焦色填充 + 封面到填充色的长距离溶解过渡」。
 *
 * 焦点锚点链（本页只认这一条链，全部显式声明，规范 R1/R4）：
 *   收藏 ⇅ 播放/从头开始 ⇅ 选集控制条（源 · 季 · 翻页）⇅ 选集网格
 *   · 下键逐层走，一层都没有才跳过只读区块；
 *   · 在控制条上完成的切换（换源、换季、翻页）焦点留在控制条，主动按下才进网格；
 *   · ↑ 走到收藏即停，并把滚动位置送回首屏构图（海报 + 标题大图态），
 *     不能只靠 bring-into-view 的最小滚动 —— 那样标题会顶在屏幕顶上回不去。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
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

    // 详情页期间把 MainActivity 的 finish() 拦下来：爬虫壳拉详情（网盘线路）时会顺手结束宿主，
    // 不拦就会「点详情直接闪退」。
    DisposableEffect(Unit) {
        MainActivity.detailVisible = true
        onDispose { MainActivity.detailVisible = false }
    }

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
     * 焦点锚点。
     *
     * 页面只有一条滚动轴，信息全程在场，方向键只负责在这些锚点之间移动：
     *   收藏 ↑ 页面顶部 / ↓ 播放
     *   播放 ↓ 选集控制条（有则）→ 第一集
     *   剧集首行 ↑ 控制条（无则播放）
     * 不再用全局 onPreviewKeyEvent 切换内容显隐，避免「上键把选集收掉」。
     */
    val favoriteFocusRequester = remember { FocusRequester() }
    val playFocusRequester = remember { FocusRequester() }
    val controlsFocusRequester = remember { FocusRequester() }
    val firstEpFocusRequester = remember { FocusRequester() }

    /** 界面每次回到前台（进出播放器）都要重新站好焦点，见 [tvResumeTick] */
    val resumeTick = tvResumeTick()

    val scope = rememberCoroutineScope()

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
        // 首页分类行是内存临时条目（负 id）：先落库换成真实 id，否则 mediaOf() 查不到
        var id = mediaId
        var m = vm.mediaOf(id)
        if (m == null && id < 0) {
            val realId = runCatching { vm.materializeRemote(id) }.getOrNull()
            if (realId != null && realId > 0) {
                id = realId
                m = vm.mediaOf(id)
            }
        }
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
            val latest = vm.mediaOf(id) ?: m
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
                        vm.tmdbScrape(latest.title, latest.year, latest.kind, id)
                    }.getOrNull()
                    // 刮削会把海报/Logo 回填进库，取回最新的再渲染
                    vm.mediaOf(id)?.let { media = it }
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

    /**
     * 进页与「界面每次回到前台」都把焦点站回播放键（规范 R7），进页即可直接按 OK 播放。
     * key 只带「数据到位与否 + resume 次数」：刮削回填会换掉 media 实例，
     * 但 id 不变，所以不会把用户已经站好的焦点从选集抢走（等价于原来的 initialFocusDone 只落一次）。
     */
    tvClaimFocusOnEnter(
        tick = listOf(resumeTick, if (loading) null else media?.id),
        anchors = *arrayOf(playFocusRequester),
        settleMillis = 120
    )

    val item = media

    // 主演名单：Emby 刮削优先、TMDB 补齐，取前 5 位（电视一行放得下的量）。
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
    val epRows = remember(pagedEpisodes) { pagedEpisodes.chunked(TV_GRID_COLS) }
    val hasEpisodes = episodes.isNotEmpty()
    /** 选集控制条只有在「多源」「多季」或「多页」时才存在：
     *  它是网格首行上键的落点，所以条件必须和上面控制条的显示条件完全一致，
     *  否则翻页按钮会变成按不到的孤立节点。
     *  controlsFocusRequester 按「源首颗 → 季首颗 → 上一页」的顺序只挂一颗。 */
    val hasControlAnchor = playSources.size > 1 || tvSeasons.size > 1 || totalPages > 1

    // 换季 / 换源 / 翻页会换掉整组卡片，但按键落点在控制条上，控制条本身不随内容变化消失，
    // 焦点因此不会掉 —— 这里刻意不再做任何抢焦。
    // 原来的 restoreGridFocus 会在每次切换后把焦点抢到第一集，
    // 表现就是「切完云盘，焦点自己跑到剧集上了」（规范 R4：离开哪一层就留在哪一层）。

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

    Box(
        Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        // 背景横版图铺满整个页面，向下溶解到主色填充。
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
        val scrollState = rememberScrollState()
        val contentAnchor = remember { BringIntoViewRequester() }

        /**
         * 一次下键的完整转场：内容块顶部对齐 → 焦点交给 [target]。
         *
         * 只依赖焦点的自动 brings-into-view，视口会「最小滚动」，落点虽然露出来了，
         * 简介顶部却常常被切掉；先把内容锚点滚到顶部再聚焦，才能稳定做到
         * 「一屏看到简介 + 主演 + 选集首行」，这也是「一次下键看全」的关键。
         */
        fun enterEpisodeSection(target: FocusRequester) {
            scope.launch {
                runCatching { contentAnchor.bringIntoView() }
                withFrameNanos { }
                // 控制条这次没渲染出来（单源单季单页）时退回网格首格
                tvMoveFocus(target, firstEpFocusRequester)
            }
        }

        /**
         * 播放区按下 ↓ 的落点。有控制条就先落控制条：
         * 原来这里写死第一集，从播放键一按就越过源/季切换直达剧集，
         * 想换云盘还得先按 ↑ 走回来（规范 R1/R4：一层一层走，不许跨层抢）。
         */
        val belowPlay: FocusRequester = when {
            !hasEpisodes -> FocusRequester.Cancel
            hasControlAnchor -> controlsFocusRequester
            else -> firstEpFocusRequester
        }

        /** 焦点回到首屏锚点时，构图也要回到首屏（规范 R7）。
         *  只靠 bring-into-view 的最小滚动，标题与大海报会留在视口外，
         *  用户看到的就是「名字顶在屏幕顶上，再按上键也回不到刚进页那个状态」。 */
        fun restoreHeaderComposition() {
            if (scrollState.value > 0) scope.launch { runCatching { scrollState.animateScrollTo(0) } }
        }

        /**
         * 单页滚动。所有内容一次性组合，方向键只移动焦点：
         *   · 首屏 = 大图 + 标题 / 元信息 / 操作按钮；
         *   · 下键把焦点交给第一集，滚动容器随焦点 bring-into-view 滚动，
         *     简介、主演、选集首行因此能同屏出现；
         *   · 没有「整页翻转」动画，也就不会出现旧页退场时内容被卸载、焦点丢失。
         */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            // 顶部占位：把首屏骨架推到屏幕 55% 处，
            // 落在 headerScrim 主色已较浓的区域，不挡海报主体、沉浸感更强
            Spacer(Modifier.height(screenH * 0.55f))

            if (item == null) {
                TvEmptyState(
                    if (loading) "加载中…" else "找不到该内容",
                    modifier = Modifier.fillMaxWidth().height(420.dp)
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    // ========== 首屏骨架：标题 / 收藏 / 元信息 / 徽章 / 操作 ==========
                    // 只占左 72%：电视是 16:9 横屏，首屏只有标题、元信息、
                    // 徽章和一个主按钮，铺满整宽会变成「左边一列字、右边一大片空」。
                    Column(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .padding(start = TvPagePadding, end = 12.dp)
                    ) {
                        // 标题位：Logo 与文字标题共用同一块「标题槽」，
                        // 起始高度一致、Logo 高度封顶，刮削成功与否页面骨架都长得一样。
                        val titleSlotHeight = 48.dp
                        var logoFailed by remember(logo) { mutableStateOf(false) }
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
                                        color = TvText.Title,
                                        style = MaterialTheme.typography.displayLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            // 收藏是焦点链最顶端：↑ 到此为止，同时把构图送回「海报 + 大标题」的首屏态；
                            // 下键回播放。
                            // focusRequester 必须挂在这里，否则播放按钮的上键是个空引用、按了没反应。
                            TvIconButton(
                                icon = if (item.favorite) Icons.Filled.Favorite
                                else Icons.Filled.FavoriteBorder,
                                active = item.favorite,
                                contentDescription = if (item.favorite) "取消收藏" else "收藏",
                                sizeDp = 40.dp,
                                modifier = Modifier
                                    .focusRequester(favoriteFocusRequester)
                                    // 焦点走到这一层 = 用户已经按回到顶，构图跟着回首屏（规范 R7）
                                    .onFocusChanged { if (it.hasFocus) restoreHeaderComposition() }
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                            restoreHeaderComposition()
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        down = playFocusRequester
                                    },
                                onClick = { vm.toggleFavorite(item.id, !item.favorite) }
                            )
                        }

                        // 元信息放在标题与徽章之间，形成层级节奏：
                        // 大标题 → 元信息 → 徽章 → 按钮，各档字号都由 TvTypography 决定
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
                        // 没看过的剧先选集，看过的直接续播。
                        val seriesUnwatched = item.kind == MediaKind.SERIES && item.positionMs <= 5_000L
                        // 操作按钮的下键进选集区：只读的简介 / 主演不接收焦点，
                        // 所以不会出现「按几次才滚到剧集」。
                        // 下键：先把内容块滚到顶部再交给落点（默认控制条首颗）；预览阶段消费，
                        // 避免默认焦点搜索先一步把视口滚到「只露出卡片」的位置。
                        val episodeDownModifier = Modifier.onPreviewKeyEvent { e ->
                            if (hasEpisodes && e.type == KeyEventType.KeyDown &&
                                e.key == Key.DirectionDown
                            ) {
                                enterEpisodeSection(belowPlay)
                                true
                            } else {
                                false
                            }
                        }
                        // 焦点走到操作区 = 用户已经按回顶部，构图同步送回「海报 + 大标题」态
                        val restoreHeaderOnFocus = Modifier.onFocusChanged {
                            if (it.hasFocus) restoreHeaderComposition()
                        }
                        val playFocusModifier = Modifier
                            .focusRequester(playFocusRequester)
                            .then(restoreHeaderOnFocus)
                            .focusProperties {
                                up = favoriteFocusRequester
                                down = belowPlay
                            }
                            .then(episodeDownModifier)
                        Spacer(Modifier.height(18.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (seriesUnwatched) {
                                // 没看过的剧：不猜「从哪集开始」，让用户自己点。
                                // 按下即把焦点送进第一集，页面随焦点滚到选集。
                                // 这里刻意落到网格、不落控制条：按钮写的是「选择剧集」，
                                // 要换云盘 / 换季从首行按 ↑ 回控制条即可。
                                TvPlayButton(
                                    text = "选择剧集",
                                    size = TvButtonSize.Lg,
                                    modifier = playFocusModifier,
                                    onClick = {
                                        epPage = 0
                                        enterEpisodeSection(firstEpFocusRequester)
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
                                    size = TvButtonSize.Lg,
                                    modifier = playFocusModifier,
                                    onClick = { onPlay(item.id) }
                                )
                                if (item.positionMs > 5_000L) {
                                    TvPillButton(
                                        text = "从头开始",
                                        primary = false,
                                        // 次操作取中档：比主按钮小一档，但仍是同一字号
                                        size = TvButtonSize.Md,
                                        modifier = Modifier
                                            .then(restoreHeaderOnFocus)
                                            .focusProperties {
                                                up = favoriteFocusRequester
                                                down = belowPlay
                                            }
                                            .then(episodeDownModifier),
                                        onClick = {
                                            vm.resetPosition(item.id)
                                            onPlay(item.id)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ========== 内容区：简介 / 导演 / 主演 / 署名 / 选集 ==========
                    // 全部常驻组合，不再有「展开第几层」的条件渲染。
                    // contentAnchor 挂在内容块顶部：下键进入选集时把它对齐到视口顶部。
                    Column(
                        Modifier
                            .bringIntoViewRequester(contentAnchor)
                            .padding(start = TvPagePadding, end = TvPagePadding)
                    ) {
                        // ---------- 简介：只读，下键会直接跳过它 ----------
                        // 数据源（TVBox / Emby）的简介常带 <span>、<br> 之类的 HTML 片段，
                        // 直接交给 Text 会把这些标签当正文显示，先统一清成纯文本
                        val overview = (
                            tmdb?.overview?.takeIf { it.isNotBlank() }
                                ?: scraped?.tagline?.takeIf { it.isNotBlank() }
                                ?: item.overview
                            ).let { HtmlText.strip(it) }
                        if (overview.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = overview,
                                color = TvText.Body,
                                style = MaterialTheme.typography.bodyLarge,
                                // 4 行以内：保证简介 + 主演 + 选集首行能挤进同一屏
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // ---------- 导演：紧接简介，只读、不接收焦点 ----------
                        val directors = (tmdb?.directors?.takeIf { it.isNotEmpty() }?.map { it.name }
                            ?: scraped?.directors?.map { it.name })
                            ?.joinToString(" / ").orEmpty()
                        if (directors.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            TvInfoLine("导演", directors)
                        }

                        // ---------- 主演：上移到选集之前，和简介同屏 ----------
                        if (tvCast.isNotEmpty()) {
                            TvSectionTitle(
                                text = "主演",
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(top = 14.dp, bottom = 6.dp)
                            )
                            TvCastRow(tvCast)
                        }

                        // ---------- 数据来源署名：只读，位置仍要在焦点滚得到的区域 ----------
                        // TMDB 条款要求署名可见：放在选集下方等于藏在焦点走不到的地方，等于没署。
                        if (tmdb != null) {
                            Spacer(Modifier.height(10.dp))
                            TmdbCreditLine()
                        }

                        // ---------- 选集：标题 + 控制条 + 网格 ----------
                        if (hasEpisodes) {
                            TvSectionTitle(
                                text = if (tvSeasons.size > 1) {
                                    "选集（第 $selectedSeason 季 · 共 ${seasonEpisodes.size} 集）"
                                } else {
                                    "选集（共 ${episodes.size} 集）"
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(top = 16.dp, bottom = 6.dp)
                            )

                            // 控制条：多源 / 多季 / 翻页。
                            // 有源或季时它是「播放 ↔ 选集」之间的焦点站；只有翻页时不占焦点站。
                            if (playSources.size > 1 || tvSeasons.size > 1 || totalPages > 1) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val pillFocus = Modifier.focusProperties {
                                        up = playFocusRequester
                                        down = if (hasEpisodes) firstEpFocusRequester else FocusRequester.Cancel
                                    }
                                    // 播放源 / 云盘切换：多条源时展示，默认第一条
                                    if (playSources.size > 1) {
                                        playSources.forEachIndexed { index, src ->
                                            TvPillButton(
                                                text = src,
                                                primary = selectedPlaySource == src,
                                                modifier = if (index == 0) {
                                                    pillFocus.focusRequester(controlsFocusRequester)
                                                } else pillFocus,
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
                                        tvSeasons.forEachIndexed { index, sn ->
                                            TvPillButton(
                                                text = if (sn > 0) "第 $sn 季" else "正片",
                                                primary = sn == selectedSeason,
                                                // 源和季都在时，锚点交给第一颗源按钮，避免两个节点挂同一个 requester
                                                modifier = if (playSources.size <= 1 && index == 0) {
                                                    pillFocus.focusRequester(controlsFocusRequester)
                                                } else pillFocus,
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
                                            // 单源单季但分集多页时，翻页按钮就是控制条的第一个元件。
                                            // requester 得跟着挂到这里，否则网格首行的上键指向一个
                                            // 没挂载的节点，按下去没反应、翻页按钮彻底进不去。
                                            modifier = if (playSources.size <= 1 && tvSeasons.size <= 1) {
                                                pillFocus.focusRequester(controlsFocusRequester)
                                            } else pillFocus,
                                            onClick = {
                                                if (pageIndex > 0) {
                                                    epPage = pageIndex - 1
                                                }
                                            }
                                        )
                                        Text(
                                            "${pageIndex + 1} / $totalPages",
                                            color = Color.White.copy(alpha = 0.55f),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        TvPillButton(
                                            text = "下一页 ›",
                                            primary = false,
                                            modifier = pillFocus,
                                            onClick = {
                                                if (pageIndex < totalPages - 1) {
                                                    epPage = pageIndex + 1
                                                }
                                            }
                                        )
                                        // 末集直达：几百集的剧不用一路翻到底
                                        TvPillButton(
                                            text = "末集",
                                            primary = false,
                                            modifier = pillFocus,
                                            onClick = {
                                                epPage = totalPages - 1
                                            }
                                        )
                                    }
                                }
                            }

                            // 网格用 chunked 手动分行：LazyVerticalGrid 嵌在滚动容器里会因为
                            // 双向无限高度约束直接崩，分行 Row 是最稳的做法
                            epRows.forEachIndexed { rowIdx, rowEps ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowEps.forEachIndexed { idx, ep ->
                                        TvEpisodeCard(
                                            episode = ep,
                                            modifier = Modifier.weight(1f),
                                            // 首行第一格挂焦点器，「选择剧集」与换季/翻页后直接落这里
                                            focusRequester = if (rowIdx == 0 && idx == 0) {
                                                firstEpFocusRequester
                                            } else null,
                                            // 首行再按上：回控制条（没有就回播放）
                                            focusUp = if (rowIdx == 0) {
                                                if (hasControlAnchor) controlsFocusRequester
                                                else playFocusRequester
                                            } else null,
                                            // 末行再按下：留在原地，不让焦点跳出选集
                                            focusDown = if (rowIdx == epRows.lastIndex) {
                                                FocusRequester.Cancel
                                            } else null,
                                            onClick = { onPlay(ep.id) }
                                        )
                                    }
                                    repeat(TV_GRID_COLS - rowEps.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        } else if (!loading && item.kind == MediaKind.SERIES) {
                            // 剧集但分集为空：加载失败时给用户一个明确提示，而不是一块空白
                            TvEmptyState(
                                "暂无分集信息",
                                hint = "数据源可能未返回剧集列表，请稍后重试",
                                modifier = Modifier.fillMaxWidth().height(160.dp)
                            )
                        }
                        // 页面到选集为止：选集下方的内容焦点走不到，
                        // 导演与署名已上移到主演之后。
                    }
                }
            }

            Spacer(Modifier.height(60.dp))
        }
        // 遥控器 Back 键 = TvApp.BackHandler 统一处理，不再显示页面内返回按钮
    }
}

@Composable
private fun TvInfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = TvText.Muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(58.dp)
        )
        Text(
            value,
            color = TvText.Body,
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
 * 而且看不到已看进度。改成卡片后可以按网格方向键移动，一屏能看到 40 集，
 * 已看的集数带进度条，一眼就能找到接到哪里。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvEpisodeCard(
    episode: MediaEntity,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** 首行再按上时要去哪里；null = 交给默认焦点搜索（上一行） */
    focusUp: FocusRequester? = null,
    /** 末行再按下时的行为；FocusRequester.Cancel = 留在原地 */
    focusDown: FocusRequester? = null,
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
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (watched) AppColors.Brand.copy(alpha = 0.18f) else AppColors.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AppColors.Brand else AppColors.SurfaceHi,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            // 焦点路由必须排在 focusable 之前，才挂得到这个焦点目标上
            .focusProperties {
                if (focusUp != null) up = focusUp
                if (focusDown != null) down = focusDown
            }
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
            color = if (watched) AppColors.BrandSoft else TvText.Title,
            style = MaterialTheme.typography.titleSmall,
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
 * 徽章行：元信息下面那一行。
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
                        placeholder -> TvText.Hairline
                        else -> TvText.Body
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** 电视端主演行用的轻量数据结构，避免直接依赖 Emby / TMDB 两套 Person 类型 */
private data class TvCast(val name: String, val role: String, val imageUrl: String)

/**
 * 主演头像行：圆形头像 + 姓名 + 角色。
 *
 * 上移到选集之前后，它是「简介 → 导演 → 主演 → 选集」这一屏的一部分，
 * 所以头像从 72dp 收到 56dp，一行五个仍能在三米外看清脸，
 * 又不会把选集首行挤出屏幕。
 */
@Composable
private fun TvCastRow(cast: List<TvCast>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        cast.forEach { person ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(76.dp)
            ) {
                Box(
                    Modifier
                        .size(56.dp)
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
                            color = TvText.Meta,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    person.name,
                    color = TvText.Section,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (person.role.isNotBlank()) {
                    Text(
                        person.role,
                        color = TvText.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
