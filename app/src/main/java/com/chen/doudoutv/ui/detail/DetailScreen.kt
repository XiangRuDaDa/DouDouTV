package com.chen.doudoutv.ui.detail

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chen.doudoutv.MainActivity
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.emby.EmbyClient
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.data.model.splitCastNames
import com.chen.doudoutv.player.PlayerFactory
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.components.AppButton
import com.chen.doudoutv.ui.components.AppButtonVariant
import com.chen.doudoutv.ui.components.ButtonMaterial
import com.chen.doudoutv.ui.components.EpisodeStill
import com.chen.doudoutv.ui.components.FormatBadgesRow
import com.chen.doudoutv.ui.components.GlassBarClearance
import com.chen.doudoutv.ui.components.matchEpisodeInSource
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 剧集/电影详情页（沉浸式头图形态）。
 *
 * 结构自上而下：
 *   backdrop 沉浸头图（渐变沉底，返回键浮于其上）
 *   → 海报 + 标题 + 评分/年份/类型
 *   → 播放按钮 + 剧情简介
 *   → 季切换（仅剧集）
 *   → 分集网格
 *
 * TVBox 来源有个特殊点：列表（homeContent）不含播放地址，必须进详情时才请求 detailContent。
 * 因此首次进入会先拉一次详情，这段时间显示加载态。
 */
@Composable
fun DetailScreen(
    vm: LibraryViewModel,
    mediaId: Long,
    isTv: Boolean,
    onPlay: (Long) -> Unit
) {
    var media by remember { mutableStateOf<MediaEntity?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }

    /** 当前条目是否来自 Emby：只有 Emby 分集走剧照缩略图卡，TVBox 保持原文字行 */
    var isEmbySource by remember(mediaId) { mutableStateOf(false) }

    /**
     * 从「继续观看」进来、页面改绑到剧集壳之后，记住用户真正在看的那条分集。
     * 剧集壳自己没有可播地址，主按钮的播放目标仍要落回这一集。
     * 其他入口进来时恒为 null，播放目标照旧由 playTarget 从选集里挑。
     */
    var resumeEpisode by remember(mediaId) { mutableStateOf<MediaEntity?>(null) }

    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf(0) }

    /** TVBox 多播放源（云盘/线路）：全部可选源 + 当前选中的源名 */
    var playSources by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPlaySource by remember { mutableStateOf<String>("") }

    /** 播放源 chips 的滚动状态：切换线路时把当前选中项滚进可见区域 */
    val playSourceListState = rememberLazyListState()

    /** 「跳过片头」预设面板（底部弹层）显隐 */
    var showSkipSheet by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // 详情页期间把 MainActivity 的 finish() 拦下来：爬虫壳拉详情（网盘线路）时会顺手结束宿主，
    // 不拦就会「点详情直接闪退」。
    DisposableEffect(Unit) {
        MainActivity.detailVisible = true
        onDispose { MainActivity.detailVisible = false }
    }

    /** Emby 刮削信息（导演/演员/分级/工作室） */
    var scraped by remember { mutableStateOf<EmbyClient.ItemDetail?>(null) }

    /** TMDB 刮削信息（海报/背景图/Logo/评分/简介/演职员），作为 Emby 的补充 */
    var tmdb by remember { mutableStateOf<com.chen.doudoutv.data.tmdb.TmdbClient.ScrapeResult?>(null) }

    /**
     * 海报主色：驱动整页背景渐变，营造沉浸感。
     *
     * 初值不再是 Color.Unspecified。Unspecified 会走到「近黑兜底色」分支，
     * 而真正的取色必须等海报图片下载解码完成（国内直连 TMDB 图片常常要好几秒），
     * 于是用户看到的就是「刚进详情页海报下方一片黑，滑动一下颜色才出来」。
     * 这里在组合阶段就用库里已有的条目算出一个确定的主色，保证第一帧就有色：
     *   1. 上次算过并落盘的主色（最快，零成本）
     *   2. 内存里 FocusColorCache 的封面主色
     *   3. 标题哈希色（永远有结果，且同一部片每次进去颜色一致）
     * 图片解码完成后会被真实封面主色平滑替换，用户只会看到一次渐变收敛。
     */
    // 注意：这里不能用 vm.mediaOf()，那是 suspend 函数，组合阶段调用会编译不过。
    // 单行订阅这一条媒体（不订阅整库：整表 Flow 每次失效都要把全部条目重读进内存，
    // 而详情页只需要这颗种子色）；库数据晚于首帧到达时 flow 会补发，remember 的 key
    // 跟着标题变，初值自动补上正确颜色，不会停在兜底黑。
    val seedMedia by remember(mediaId) { vm.seedMediaFlow(mediaId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var dominantColor by remember(mediaId, seedMedia?.title) {
        mutableStateOf(
            seedMedia?.let { seed ->
                val seedUrl = seed.posterUrl.ifBlank { seed.backdropUrl }
                val persisted = Prefs.detailTone(seed.title).takeIf { it != 0 }
                if (persisted != null) Color(persisted)
                else FocusColorCache.get(seedUrl) ?: fallbackColorFromTitle(seed.title)
            } ?: fallbackColorFromTitle(seedMedia?.title.orEmpty())
        )
    }

    LaunchedEffect(mediaId) {
        loading = true
        scraped = null
        tmdb = null
        // 远程搜索结果是负 id 的临时条目：先按所属 TVBox 源落库换成真实 id，
        // 否则 mediaOf() 查不到会显示「条目不存在」
        var id = mediaId
        var m = vm.mediaOf(id)
        if (m == null && id < 0) {
            val realId = runCatching { vm.materializeRemote(id) }.getOrNull()
            if (realId != null && realId > 0) {
                id = realId
                m = vm.mediaOf(id)
            }
        }
        // 「继续观看」进来的是 EPISODE 行：海报/年份/简介/选集全都挂在剧集壳上，
        // 绑分集行整页几乎是空的。seriesKey 里已经带 sourceId + siteKey + vodId，
        // 查到的壳就是这条记录所属的那个数据源的整部剧，不存在跨源歧义，直接换绑、不用问。
        // 壳不在库里（换源重入库、老数据）时保持分集行渲染。
        if (m != null && m.kind == MediaKind.EPISODE) {
            val shell = vm.shellOf(m.seriesKey)
            if (shell != null) {
                resumeEpisode = m.takeIf { it.positionMs > 0 }
                id = shell.id
                m = shell
            }
        }
        media = m
        if (m != null) {
            // Emby 源：加载刮削信息（电影/剧集都支持）
            isEmbySource = vm.sourceOf(m.sourceId)?.kind == SourceKind.EMBY
            if (isEmbySource) {
                scraped = vm.embyDetailOf(m.sourceId, m.remoteId)
            }

            // 刮削结果一律问仓储层：它先读 scraped_meta 表，表里没有才轮到「联网刮削」开关
            // 和凭据决定要不要发请求。这里不能再自己拦一道开关 —— 拦了就把已经刮到、
            // 躺在表里的数据一起藏了，关着开关进详情页会退回源站那张豆瓣图。
            // mediaId 传下去：拿到结果后回填 media 行并打上 scrapedAt
            tmdb = runCatching {
                vm.tmdbScrape(m.title, m.year, m.kind, id)
            }.getOrNull()

            if (m.kind == MediaKind.SERIES) {
                var eps = vm.episodesOf(m.seriesKey)

                // 分集按需拉取（TVBox 详情 / Emby 分集都不随整库同步）
                if (eps.isEmpty()) {
                    val src = vm.sourceOf(m.sourceId)
                    when (src?.kind) {
                        SourceKind.TVBOX -> {
                            vm.loadTvBoxDetail(m.sourceId, m.remoteId)
                            media = vm.mediaOf(id) ?: m
                        }
                        SourceKind.EMBY -> {
                            if (vm.loadEmbyEpisodes(m.sourceId, m.remoteId)) {
                                media = vm.mediaOf(id) ?: m
                            }
                        }
                        null -> Unit
                    }
                }

                // TVBox：读取全部播放源（云盘/线路），默认选第一条；按选中源过滤分集。
                // Emby 没有多源概念，playSources 为空、走原来的全量分集。
                val sources = vm.playSourcesOf(m.seriesKey)
                playSources = sources
                if (sources.isNotEmpty()) {
                    if (selectedPlaySource !in sources) selectedPlaySource = sources.first()
                    eps = vm.episodesBySource(m.seriesKey, selectedPlaySource)
                } else {
                    eps = vm.episodesOf(m.seriesKey)
                }

                episodes = eps
                selectedSeason = eps.firstOrNull()?.season ?: 1
            }

            // 演员/更新备注是这次才入库的列，老条目里是空串（而分集守卫又让它们没机会重拉）。
            // 补一次「只写壳」的详情请求：不删不插分集，播放进度与选集列表都不受影响。
            val shell = vm.mediaOf(id) ?: m
            if (shell.kind == MediaKind.SERIES && shell.actors.isBlank() && shell.remarks.isBlank() &&
                vm.sourceOf(shell.sourceId)?.kind == SourceKind.TVBOX &&
                vm.refreshTvBoxMeta(shell.sourceId, shell.remoteId)
            ) {
                media = vm.mediaOf(id) ?: shell
            }
        }
        loading = false
    }

    // 取色：用 Coil ImageLoader 直接解码海报 bitmap（比 AsyncImage.onSuccess 里取 drawable 更可靠）。
    // 任何环节失败都回退到「标题哈希色」，保证页面背景永远有色、不会是纯黑。
    val context = LocalContext.current
    LaunchedEffect(media?.posterUrl, media?.backdropUrl, tmdb?.posterUrl, tmdb?.backdropUrl) {
        val m = media ?: return@LaunchedEffect
        // 取色要跟页面上真正显示的那张图一致，否则刮来的 TMDB 海报配的还是豆瓣图的色调
        val scrapedArt = tmdb?.posterUrl.orEmpty().ifBlank { tmdb?.backdropUrl.orEmpty() }
        val url = scrapedArt.ifBlank { m.posterUrl.ifBlank { m.backdropUrl } }
        if (url.isBlank()) {
            dominantColor = fallbackColorFromTitle(m.title)
            return@LaunchedEffect
        }
        val cached = FocusColorCache.get(url)
        if (cached != null) {
            dominantColor = cached
            Prefs.putDetailTone(m.title, cached.toArgb())
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.Default) { loadPosterBitmap(context, url) }
        val color = bmp?.let { runCatching { focusColorOf(it) }.getOrNull() }
            ?: fallbackColorFromTitle(m.title)
        FocusColorCache.put(url, color)
        // 一并写盘：下次冷启动进同一部片，第一帧就能拿到这个颜色
        Prefs.putDetailTone(m.title, color.toArgb())
        dominantColor = color
    }

    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val visibleEpisodes = remember(episodes, selectedSeason) {
        if (seasons.size <= 1) episodes else episodes.filter { it.season == selectedSeason }
    }

    /**
     * 手机端分集分页：一部剧几十上百集时一次性铺出来，手机上要滑很久才够到最后一集。
     * 每页 20 集（两列 × 10 行），顶部给页码条直接跳。电视端不切分页，保持原来的整季铺开。
     */
    val episodePageSize = 20
    val episodePageCount = remember(visibleEpisodes, episodePageSize) {
        if (visibleEpisodes.isEmpty()) 0 else (visibleEpisodes.size + episodePageSize - 1) / episodePageSize
    }
    var episodePage by remember(mediaId) { mutableStateOf(0) }
    // 换季 / 换播放源回到第一页：分集集合已经换了，还停在第 5 页会看到不相干的内容
    LaunchedEffect(selectedSeason, selectedPlaySource) { episodePage = 0 }
    // 集数变少（换了更短的季）时把越界的页码收回来
    LaunchedEffect(episodePageCount) {
        if (episodePage >= episodePageCount) episodePage = 0
    }
    val pagedEpisodes = remember(visibleEpisodes, episodePage, episodePageSize) {
        if (isTv) visibleEpisodes
        else visibleEpisodes.drop(episodePage * episodePageSize).take(episodePageSize)
    }

    /**
     * 从封面图提取"聚焦色"，并把整页底色压成它的深色版本。
     *
     * 之前的实现只用了 Palette 的 darkMuted 并给很低的 alpha，暗色海报基本等于纯黑；
     * 这里改成：优先取有饱和度的色块 → 保证亮度/饱和度下限 → 整页用不透明深色铺满，
     * 所以无论海报明暗，页面都会带上它自己的色调。
     */
    // 海报占屏比例：bgBrush 渐变起点必须对齐这个值，否则海报底部会和页面背景出现硬切。
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val headerRatio = if (isTv) 0.52f else 0.58f
    val pageTones = remember(dominantColor) {
        if (dominantColor == Color.Unspecified) {
            AppColors.Bg to lerp(AppColors.Bg, Color.Black, 0.55f)
        } else {
            pageTonesOf(dominantColor)
        }
    }
    val animatedTop by animateColorAsState(
        targetValue = pageTones.first,
        animationSpec = tween(Motion.SLOW),
        label = "page_top"
    )
    val animatedBottom by animateColorAsState(
        targetValue = pageTones.second,
        animationSpec = tween(Motion.SLOW),
        label = "page_bottom"
    )

    // 整页填充色：顶部 0~headerRatio 基本是主色，headerRatio 之后往深色渐变。
    // 关键：r 之后先保留一段纯 seamColor 缓冲带（r ~ r+0.06），再开始缓降。
    // 这样海报底部（headerScrim 已不透明）和页面背景有一段同色重叠区，
    // 哪怕有 1~2px 的渲染偏差，也落在纯色区内，肉眼绝对看不到交界线。
    val bgBrush = remember(animatedTop, animatedBottom, headerRatio) {
        val r = headerRatio
        val seam = lerp(animatedTop, animatedBottom, 0.08f)
        val early = r * 0.86f
        val earlyColor = lerp(animatedTop, animatedBottom, 0.04f)
        // 缓冲带终点：r 之后 6% 仍保持纯 seamColor
        val bufferEnd = r + (1f - r) * 0.06f
        val p1 = r + (1f - r) * 0.26f
        val p1Color = lerp(animatedTop, animatedBottom, 0.18f)
        val p2 = r + (1f - r) * 0.50f
        val p2Color = lerp(animatedTop, animatedBottom, 0.38f)
        val p3 = r + (1f - r) * 0.75f
        val p3Color = lerp(animatedTop, animatedBottom, 0.60f)
        Brush.verticalGradient(
            0f to animatedTop,
            early to earlyColor,
            r to seam,
            bufferEnd to seam,              // 缓冲带：与 headerScrim 底部重叠的纯色区
            p1 to p1Color,
            p2 to p2Color,
            p3 to p3Color,
            1f to animatedBottom
        )
    }

    // 封面 → 填充色的渐变过渡。
    // 关键：不透明度要一路缓降到 1f 才封顶，不能在 0.78 就压满 ——
    // 那样海报底部 22% 变成纯色块，标题看着像「海报截到影片名」，而不是浮在海报上。
    // 交界线仍然不会露：bgBrush 在 r~r+0.06 有一段纯 seamColor 缓冲带，
    // 和这条渐变终点（同为 seamColor）重叠，1~2px 的渲染偏差落在纯色区内。
    val headerScrim = remember(animatedTop, animatedBottom) {
        val seamColor = lerp(animatedTop, animatedBottom, 0.08f)
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.42f),
            0.26f to Color.Transparent,
            0.38f to seamColor.copy(alpha = 0.06f),
            0.50f to seamColor.copy(alpha = 0.18f),
            0.60f to seamColor.copy(alpha = 0.38f),
            0.72f to seamColor.copy(alpha = 0.60f),
            0.86f to seamColor.copy(alpha = 0.85f),
            1f to seamColor
        )
    }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Brand)
            }
        } else if (media == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("条目不存在", color = AppColors.TextSecondary)
            }
        } else {
            val item = media!!
            // 合并 Emby / TMDB 刮削结果。
            // 图（海报/背景/Logo）：一律刮来的优先，源站那张常常是豆瓣缩略图；
            // 文本（简介/评分）：仍然条目优先，源站给的简介跟这条播放数据是配套的。
            val effectiveLogo = tmdb?.logoUrl.orEmpty().ifBlank { item.logoUrl }
            val effectivePoster = tmdb?.posterUrl.orEmpty().ifBlank { item.posterUrl }
            val effectiveBackdrop = tmdb?.backdropUrl.orEmpty().ifBlank { item.backdropUrl }
            val effectiveOverview = item.overview.ifBlank { tmdb?.overview.orEmpty() }
            val effectiveRating = if (item.rating > 0) item.rating else tmdb?.rating ?: 0.0

            // 主演名单：Emby 刮削优先、TMDB 补齐，最后回落到源自己给的 actors；
            // 源给的名单没有头像，走 CastCard 的首字占位样式
            val castList = (scraped?.actors?.map { it.toCast() }
                ?: tmdb?.actors?.map { it.toCast() }.orEmpty()
                    .takeIf { it.isNotEmpty() }
                ?: splitCastNames(item.actors).take(12).map { CastPerson(it, "", "") })

            // 简介折叠态。溢出与否读真实排版结果（hasVisualOverflow），不用字数当代理：
            // 同样长度的简介在不同字号/屏宽下行数差很多，按字数判断会漏展开入口或凭空多一个。
            var descExpanded by remember(mediaId) { mutableStateOf(false) }
            var descOverflow by remember(mediaId, effectiveOverview) { mutableStateOf(false) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp + GlassBarClearance)
            ) {
                // ---------- 顶部海报：全宽沉浸头图，标题覆盖在海报下部 ----------
                item {
                    // 海报高度 = 动态算出来和上面 headerRatio 对齐
                    val headerH = screenH * headerRatio

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(headerH)
                    ) {
                        val art = effectivePoster.ifBlank { effectiveBackdrop }
                        if (art.isNotBlank()) {
                            AsyncImage(
                                model = art,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // 没有海报时也别用中性深灰（在大片主色里就是一块黑），
                            // 直接用当前主色，视觉上始终是同一套色调
                            Box(Modifier.fillMaxSize().background(animatedTop))
                        }

                        // 海报 → 页面主色：渐变 + 透明度双重过渡（不再有生硬截断）
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(headerScrim)
                        )

                        // 标题覆盖层：水平居中、贴海报下沿（距底 16dp）
                        Column(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            // 标题：优先用刮削到的 Logo 图，文字次之
                            if (effectiveLogo.isNotBlank()) {
                                AsyncImage(
                                    model = effectiveLogo,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .height(80.dp)
                                        .widthIn(max = 360.dp)
                                )
                            } else {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }

                            // 类型字段（电影 / 剧集，剧集额外带集数）+ 年份/评分
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TypeChip(if (item.kind == MediaKind.SERIES) "剧集" else "电影")
                                if (item.kind == MediaKind.SERIES && visibleEpisodes.isNotEmpty()) {
                                    TypeChip("共 ${visibleEpisodes.size} 集")
                                }
                                val meta = detailMeta(item, effectiveRating)
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.80f)
                                    )
                                }
                            }

                            // 第三行：格式徽章（TMDB 图标 + 画质）
                            // Emby 走刮削得到的 MediaSources；TVBox 协议没有这些字段，
                            // 用详情页入库时的文件名推测值，播过一次后由 Media3 真实轨道覆盖。
                            // 有数据才显示，什么都没有就自然留出空行不占位
                            FormatBadgesRow(
                                tmdbEnabled = tmdb != null,
                                resolution = scraped?.videoResolution.orEmpty().ifBlank { item.videoResolution },
                                hdr = scraped?.hdrType.orEmpty().ifBlank { item.hdrType },
                                codec = scraped?.videoCodec.orEmpty().ifBlank { item.videoCodec },
                                audio = item.audioType
                            )
                        }
                    }
                }

                // ---------- 播放按钮 + 分辨率 + 文件大小 ----------
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // resumeEpisode 只在「继续观看」进来改绑到剧集壳之后有值：壳自己没有可播地址，
                        // 播放目标要落回用户真正在看的那一集（它可能属于另一条云盘，visibleEpisodes 未必含它）
                        val target = resumeEpisode ?: playTarget(item, visibleEpisodes)
                        // 主按钮：品牌渐变实底压黑字
                        AppButton(
                            text = if (item.kind == MediaKind.SERIES) {
                                val ep = resumeEpisode
                                    ?: visibleEpisodes.firstOrNull { it.positionMs > 0 }
                                    ?: visibleEpisodes.firstOrNull()
                                ep?.let { "继续观看  ${it.title}" } ?: "开始播放"
                            } else "播放",
                            onClick = { if (target != null) onPlay(target.id) },
                            enabled = target != null,
                            variant = AppButtonVariant.Primary,
                            icon = Icons.Filled.PlayArrow,
                            iconSize = 20.dp,
                            // 与右侧收藏按钮同高（48dp）：主按钮默认只有 40dp，
                            // 两颗并排时一高一低，看着像没对齐
                            minHeight = 48.dp,
                            modifier = Modifier.weight(0.72f)
                        )

                        // 右侧：仅文件大小
                        val size = scraped?.fileSizeBytes?.takeIf { it > 0 }
                        if (size != null) {
                            Text(
                                text = PlayerFactory.formatBytes(size),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.68f),
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.28f)
                            )
                        }

                        val fav = item.favorite
                        IconButton(
                            onClick = {
                                val next = !fav
                                vm.toggleFavorite(item.id, next)
                                media = media?.copy(favorite = next)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(Radius.Md)
                                .background(
                                    // 已收藏 = 品牌渐变实底压黑心；未收藏 = 磨砂玻璃压白心
                                    if (fav) {
                                        Brush.verticalGradient(
                                            ButtonMaterial.primaryStops(AppColors.Brand, AppColors.BrandSoft)
                                        )
                                    } else {
                                        Brush.verticalGradient(ButtonMaterial.glassStops())
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (fav) ButtonMaterial.highlightEdge else ButtonMaterial.glassEdge,
                                    Radius.Md
                                )
                        ) {
                            Icon(
                                if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (fav) "取消收藏" else "收藏",
                                tint = if (fav) Color.Black else Color.White
                            )
                        }
                    }
                }

                // ---------- 简介 ----------
                if (effectiveOverview.isNotBlank()) {
                    item {
                        Column(Modifier.padding(horizontal = 18.dp)) {
                            Text(
                                "简介",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                effectiveOverview,
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.TextSecondary,
                                maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                                overflow = if (descExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                onTextLayout = { layout ->
                                    if (!descExpanded) descOverflow = layout.hasVisualOverflow
                                }
                            )
                            if (descExpanded || descOverflow) {
                                Text(
                                    if (descExpanded) "收起" else "展开更多",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AppColors.Brand,
                                    modifier = Modifier
                                        .clip(Radius.Sm)
                                        .clickable { descExpanded = !descExpanded }
                                        .padding(horizontal = 2.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // ---------- 刮削信息（标语/分级/工作室/导演）：Emby 优先，TMDB 补齐 ----------
                run {
                    val tagline = scraped?.tagline.orEmpty().ifBlank { tmdb?.tagline.orEmpty() }
                    val chips = buildList {
                        scraped?.let {
                            if (it.officialRating.isNotBlank()) add(it.officialRating)
                            addAll(it.studios.take(2))
                        }
                    }
                    val directors = (scraped?.directors?.map { it.toCast() }
                        ?: tmdb?.directors?.map { it.toCast() }.orEmpty()
                            .takeIf { it.isNotEmpty() }
                        ?: splitCastNames(item.directors).take(3).map { CastPerson(it, "", "") })

                    if (tagline.isNotBlank() || chips.isNotEmpty() || directors.isNotEmpty()) {
                        item {
                            Column(
                                Modifier
                                    .padding(horizontal = 18.dp)
                                    .padding(top = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (tagline.isNotBlank()) {
                                    Text(
                                        "“$tagline”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppColors.TextSecondary,
                                        fontStyle = FontStyle.Italic
                                    )
                                }

                                if (chips.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        chips.forEach { label ->
                                            Box(
                                                Modifier
                                                    .clip(Radius.Sm)
                                                    .background(AppColors.SurfaceHi)
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = AppColors.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }

                                if (directors.isNotEmpty()) {
                                    Text(
                                        "导演  " + directors.joinToString("  ") { it.name },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 季切换 ----------
                if (seasons.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons) { s ->
                                FilterChip(
                                    selected = selectedSeason == s,
                                    onClick = { selectedSeason = s },
                                    label = { Text("第 $s 季") },
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
                }

                // ---------- 分集 ----------
                if (item.kind == MediaKind.SERIES) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .padding(top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "剧集",
                                style = MaterialTheme.typography.titleLarge,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${visibleEpisodes.size} 集",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                            // 「跳过片头」胶囊：剧集 + 全局开关开启才出现，点击弹预设面板。
                            // 配置写在剧集壳行上：全剧生效、每部剧只配一次。
                            if (Prefs.skipIntroEnabled && item.seriesKey.isNotBlank()) {
                                val introMs = item.introSkipMs
                                Text(
                                    text = if (introMs > 0) "跳过片头 · ${fmtIntroMs(introMs)}"
                                    else "跳过片头 · 关",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (introMs > 0) AppColors.Brand else AppColors.TextSecondary,
                                    modifier = Modifier
                                        .padding(start = 10.dp)
                                        .clip(Radius.Pill)
                                        .background(
                                            if (introMs > 0) AppColors.Brand.copy(alpha = 0.16f)
                                            else AppColors.SurfaceHi
                                        )
                                        .clickable { showSkipSheet = true }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // ---------- 播放源 / 云盘切换 ----------
                    // 同一部剧有多条云盘时，展示为横向可选 chips；默认第一条，点击切换该源的分集。
                    if (playSources.size > 1) {
                        item {
                            // 切换线路后把当前选中的 chip 滚进可见区域（放在 item 内：行已组合时才滚动）
                            LaunchedEffect(selectedPlaySource, playSources) {
                                val idx = playSources.indexOf(selectedPlaySource)
                                if (idx > 0) playSourceListState.animateScrollToItem(idx)
                            }
                            LazyRow(
                                state = playSourceListState,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(playSources) { src ->
                                    FilterChip(
                                        selected = selectedPlaySource == src,
                                        onClick = {
                                            // 换源前记住「用户要看的是哪一集」：旧分集行继续挂在
                                            // resumeEpisode 上的话，换了网盘再按「继续观看」会播回上一条源。
                                            val anchor = resumeEpisode
                                                ?: playTarget(item, visibleEpisodes)
                                                    ?.takeIf { it.kind == MediaKind.EPISODE }
                                            selectedPlaySource = src
                                            scope.launch {
                                                val next = vm.episodesBySource(item.seriesKey, src)
                                                val same = matchEpisodeInSource(anchor, next)
                                                episodes = next
                                                selectedSeason = when {
                                                    same != null -> same.season
                                                    next.isNotEmpty() -> next.first().season
                                                    else -> 1
                                                }
                                                // 续播锚点只在「这页本来就是靠它开的」时跟着换源，
                                                // 没锚点的正常进入不改主按钮语义
                                                if (resumeEpisode != null) resumeEpisode = same
                                            }
                                        },
                                        label = { Text(src, maxLines = 1) },
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
                    }

                    if (visibleEpisodes.isEmpty()) {
                        item {
                            Text(
                                "暂无分集，可能是来源接口未返回播放地址",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextTertiary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        // ---------- 分页条（仅手机端；集数不多时不出现） ----------
                        if (!isTv && episodePageCount > 1) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(episodePageCount) { p ->
                                        val from = p * episodePageSize + 1
                                        val to = minOf((p + 1) * episodePageSize, visibleEpisodes.size)
                                        FilterChip(
                                            selected = episodePage == p,
                                            onClick = { episodePage = p },
                                            label = { Text("$from-$to") },
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
                        }

                        if (isEmbySource) {
                            // Emby：横向剧照货架，手指左右滑动浏览；分页条仍在上方跳页。
                            item {
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth().height(90.dp),
                                    contentPadding = PaddingValues(horizontal = 18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    itemsIndexed(pagedEpisodes, key = { _, ep -> ep.id }) { _, ep ->
                                        EpisodeCard(
                                            episode = ep,
                                            isEmby = true,
                                            modifier = Modifier.width(160.dp),
                                            onClick = { onPlay(ep.id) }
                                        )
                                    }
                                }
                            }
                        } else {
                        itemsIndexed(
                            pagedEpisodes.chunked(if (isTv) 4 else 2),
                            // key 带位置 + 行首集 id：切线路/翻页时同一位置换了数据就换 key，
                            // 没换的行不重新组合，选集卡不用重画
                            key = { index, row -> "ep_row_${index}_${row.first().id}" },
                            contentType = { _, _ -> "ep_row" }
                        ) { _, row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { ep ->
                                    Box(Modifier.weight(1f)) {
                                        EpisodeCard(
                                            episode = ep,
                                            isEmby = isEmbySource,
                                            onClick = { onPlay(ep.id) }
                                        )
                                    }
                                }
                                repeat((if (isTv) 4 else 2) - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        }
                    }
                }

                // ---------- 主演：排在剧集之后 ----------
                // 原来夹在简介与选集之间，滚到选集前总要先路过一排头像；
                // 挪到选集下面，进详情先看到剧情与选集，主演放在末尾。
                if (castList.isNotEmpty()) {
                    item {
                        Column(
                            Modifier
                                .padding(horizontal = 18.dp)
                                .padding(top = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "主演",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 4.dp)
                            ) {
                                items(castList, key = { it.name + it.role }) { person ->
                                    CastCard(person)
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 「跳过片头」预设面板 ----------
            if (showSkipSheet && item.kind == MediaKind.SERIES && item.seriesKey.isNotBlank()) {
                SkipIntroSheet(
                    currentMs = item.introSkipMs,
                    onDismiss = { showSkipSheet = false },
                    onPick = { ms ->
                        showSkipSheet = false
                        scope.launch {
                            // 写成功后重读壳行，胶囊文案立刻反映新值
                            if (vm.setIntroSkip(item.seriesKey, ms)) {
                                media = vm.mediaOf(item.id) ?: media
                            }
                        }
                    }
                )
            }
        }
    }
}

/** 片头时长的展示文案：1:30 / 12:30 这种分秒格式 */
internal fun fmtIntroMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * 「跳过片头」底部面板：预设档位 chips + 自定义 mm:ss 输入。
 * 选中即写入剧集壳行（全剧生效），0 表示清除（不跳）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkipIntroSheet(currentMs: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val presets = listOf(0L to "不跳", 30_000L to "30s", 60_000L to "60s", 90_000L to "90s",
        120_000L to "120s", 150_000L to "150s", 180_000L to "180s")
    var custom by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppColors.Surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "跳过片头（全剧生效，只需配置一次）",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (ms, label) ->
                    FilterChip(
                        selected = currentMs == ms,
                        onClick = { onPick(ms) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Brand,
                            selectedLabelColor = Color.White,
                            containerColor = AppColors.SurfaceHi,
                            labelColor = AppColors.TextSecondary
                        )
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("自定义 分:秒，如 1:30") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                        focusedBorderColor = AppColors.Brand,
                        unfocusedBorderColor = AppColors.SurfaceHi
                    )
                )
                AppButton(
                    text = "确定",
                    variant = AppButtonVariant.Primary,
                    onClick = {
                        parseIntroInput(custom)?.let { onPick(it) }
                    }
                )
            }
        }
    }
}

/** 解析 mm:ss / h:mm:ss / 纯秒数输入，非法返回 null；上限 30 分钟 */
internal fun parseIntroInput(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val sec = if (text.contains(':')) {
        val parts = text.split(':')
        if (parts.size > 3 || parts.any { it.isBlank() || !it.all { c -> c.isDigit() } }) return null
        val nums = parts.map { it.toLong() }
        when (nums.size) {
            1 -> nums[0]
            2 -> nums[0] * 60 + nums[1]
            else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
        }
    } else {
        if (!text.all { it.isDigit() }) return null
        text.toLong()
    }
    if (sec <= 0 || sec > 1800) return null
    return sec * 1000L
}

/** 决定「播放」按钮跳哪一集：优先未看完的，其次第一集 */
private fun playTarget(item: MediaEntity, episodes: List<MediaEntity>): MediaEntity? =
    if (item.kind == MediaKind.SERIES) {
        episodes.firstOrNull { it.positionMs > 0 } ?: episodes.firstOrNull()
    } else item

/** 统一演职员展示类型，抹平 EmbyClient.Person / TmdbClient.Person 的差异 */
private data class CastPerson(val name: String, val role: String, val imageUrl: String)

private fun EmbyClient.Person.toCast() = CastPerson(name, role, imageUrl)
private fun com.chen.doudoutv.data.tmdb.TmdbClient.Person.toCast() = CastPerson(name, role, imageUrl)

/**
 * 年份 + 评分行。
 * 注意这里不再输出「剧集」二字——标题区已经用 TypeChip 单独标了类型，
 * 两处都写会出现「剧集 · 剧集」的重复。
 */
private fun detailMeta(item: MediaEntity, rating: Double = item.rating): String {
    val parts = mutableListOf<String>()
    if (item.year > 0) parts += item.year.toString()
    if (rating > 0) parts += "★ %.1f".format(rating)
    return parts.joinToString("  ·  ")
}

/** 类型胶囊标签：电影 / 剧集 / 集数，半透明底叠在海报上仍清晰 */
@Composable
private fun TypeChip(label: String) {
    Box(
        Modifier
            .clip(Radius.Pill)
            .background(Color.White.copy(alpha = 0.20f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 演员卡：圆形头像 + 姓名 + 角色 */
@Composable
private fun CastCard(person: CastPerson) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
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
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            person.name,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (person.role.isNotBlank()) {
            Text(
                person.role,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: MediaEntity,
    isEmby: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val shape = Radius.Md
    val playing = episode.positionMs > 0

    // Emby 来源：16:9 剧照缩略图卡（剧照入库时已写进 posterUrl）。
    // TVBox 没有剧照，仍走下面的文字行，样式一字不动。
    if (isEmby) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AppColors.Surface)
                .then(if (focused) Modifier.border(2.dp, AppColors.Brand, shape) else Modifier)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .focusable()
        ) {
            EpisodeStill(episode)
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.Surface)
            .then(if (focused) Modifier.border(2.dp, AppColors.Brand, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable()
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (episode.episode > 0) "E%02d".format(episode.episode) else "•",
            style = MaterialTheme.typography.labelMedium,
            color = if (playing) AppColors.Brand else AppColors.TextTertiary
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (playing && episode.durationMs > 0) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(Radius.Pill)
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (episode.positionMs.toFloat() / episode.durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            )
                            .height(2.5.dp)
                            .background(AppColors.Brand)
                    )
                }
            }
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}
