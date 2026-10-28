package com.tabbit.myvidhub.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.tabbit.myvidhub.R
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.scrape.ScrapeEngine
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.FormatBadgesRow
import com.tabbit.myvidhub.ui.components.TmdbCreditLine
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/**
 * 剧集/电影详情页（VidHub 形态）。
 *
 * 结构自上而下：
 *   backdrop 沉浸头图（渐变沉底，返回键浮于其上）
 *   → 海报 + 标题 + 评分/年份/类型
 *   → 播放按钮 + 剧情简介
 *   → 季切换（仅剧集）
 *   → 分集网格
 *
 * TVBox 来源有个特殊点：列表接口不含播放地址，必须进详情时才请求 ac=detail。
 * 因此首次进入会先拉一次详情，这段时间显示加载态。
 */
@Composable
fun DetailScreen(
    vm: LibraryViewModel,
    mediaId: Long,
    isTv: Boolean,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit
) {
    var media by remember { mutableStateOf<MediaEntity?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf(0) }

    /** TVBox 多播放源（云盘/线路）：全部可选源 + 当前选中的源名 */
    var playSources by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPlaySource by remember { mutableStateOf<String>("") }

    /** 播放源 chips 的滚动状态：切换线路时把当前选中项滚进可见区域 */
    val playSourceListState = rememberLazyListState()

    val scope = rememberCoroutineScope()

    /** Emby 刮削信息（导演/演员/分级/工作室） */
    var scraped by remember { mutableStateOf<EmbyClient.ItemDetail?>(null) }

    /** TMDB 刮削信息（海报/背景图/Logo/评分/简介/演职员），作为 Emby 的补充 */
    var tmdb by remember { mutableStateOf<com.tabbit.myvidhub.data.tmdb.TmdbClient.ScrapeResult?>(null) }

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
    // 改从已订阅的 library 里同步查；library 一到位 remember 的 key 变化会重新给初值，
    // 因此「库数据晚于首帧到达」也能自动补上正确的颜色，不会停在兜底黑。
    val libraryState by vm.library.collectAsState()
    val seedMedia = remember(libraryState, mediaId) {
        libraryState.firstOrNull { it.id == mediaId }
    }
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
        android.util.Log.d("MyVidHubNav", "detail enter mediaId=$mediaId")
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
        media = m
        android.util.Log.d(
            "MyVidHubNav",
            "detail resolved id=$id found=${m != null} title=${m?.title ?: "-"}"
        )
        if (m != null) {
            // Emby 源：加载刮削信息（电影/剧集都支持）
            if (vm.sourceOf(m.sourceId)?.kind == SourceKind.EMBY) {
                scraped = vm.embyDetailOf(m.sourceId, m.remoteId)
            }

            // 刮削分流：凭据非空才发起 TMDB 请求。
            // 在 LaunchedEffect 里重新判定一次（而不是读 ViewModel 的初始化快照），
            // 这样用户在设置页刚填完密钥、不重启退回来也能立刻用上 TMDB。
            // 凭据为空时 engine == LEGACY，完全沿用原有方案
            // （Emby 自带元数据 / TVBox 返回字段），不发任何外部请求。
            if (ScrapeStrategy.refresh() == ScrapeEngine.TMDB) {
                // 跨重启的第一道拦截：库里已有近期刮削结果且关键字段齐全，就不再联网。
                // 内存缓存重启会丢，但 scrapedAt 是持久化的，
                // 能省掉「每次冷启动都把整个库重新刮一遍」这种最贵的浪费。
                val fresh = m.scrapedAt > 0L &&
                    System.currentTimeMillis() - m.scrapedAt < SCRAPE_FRESH_MS
                val localReady = m.posterUrl.isNotBlank() || m.logoUrl.isNotBlank()
                if (!(fresh && localReady)) {
                    // mediaId 传下去：命中网络结果后会回填数据库并打上 scrapedAt
                    runCatching {
                        vm.tmdbScrape(m.title, m.year, m.kind, id)
                    }.getOrNull()?.let { tmdb = it }
                }
            }

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
        }
        loading = false
    }

    // 取色：用 Coil ImageLoader 直接解码海报 bitmap（比 AsyncImage.onSuccess 里取 drawable 更可靠）。
    // 任何环节失败都回退到「标题哈希色」，保证页面背景永远有色、不会是纯黑。
    val context = LocalContext.current
    LaunchedEffect(media?.posterUrl, media?.backdropUrl) {
        val m = media ?: return@LaunchedEffect
        val url = m.posterUrl.ifBlank { m.backdropUrl }
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
        animationSpec = tween(320),
        label = "page_top"
    )
    val animatedBottom by animateColorAsState(
        targetValue = pageTones.second,
        animationSpec = tween(320),
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
    // 策略：alpha 在 0.78 处就到 1.0，海报底部 22% 区域是纯 seamColor，
    // 与 bgBrush 在 r~r+0.06 的缓冲带重叠。两层同色区域有交叠，
    // 无论海报图片底部是什么颜色，都被完全盖住，交界线从物理上被消除。
    val headerScrim = remember(animatedTop, animatedBottom) {
        val seamColor = lerp(animatedTop, animatedBottom, 0.08f)
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.42f),
            0.26f to Color.Transparent,
            0.38f to seamColor.copy(alpha = 0.06f),
            0.50f to seamColor.copy(alpha = 0.18f),
            0.60f to seamColor.copy(alpha = 0.38f),
            0.70f to seamColor.copy(alpha = 0.68f),
            0.78f to seamColor,              // 此处起完全不透明，海报底部 22% 为纯色
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
            // 合并 Emby / TMDB 刮削结果：优先用条目本身字段，空了再用 TMDB 补齐
            val effectiveLogo = item.logoUrl.ifBlank { tmdb?.logoUrl.orEmpty() }
            val effectivePoster = item.posterUrl.ifBlank { tmdb?.posterUrl.orEmpty() }
            val effectiveOverview = item.overview.ifBlank { tmdb?.overview.orEmpty() }
            val effectiveRating = if (item.rating > 0) item.rating else tmdb?.rating ?: 0.0

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
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
                        val art = effectivePoster.ifBlank {
                            item.backdropUrl.ifBlank { tmdb?.backdropUrl.orEmpty() }
                        }
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

                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }

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

                            // 第三行：格式徽章（TMDB 图标 + Emby 解析到的 分辨率/HDR/Codec）
                            // 有数据才显示，什么都没有就自然留出空行不占位
                            FormatBadgesRow(
                                tmdbEnabled = tmdb != null,
                                resolution = scraped?.videoResolution.orEmpty(),
                                hdr = scraped?.hdrType.orEmpty(),
                                codec = scraped?.videoCodec.orEmpty()
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
                        val target = playTarget(item, visibleEpisodes)
                        Button(
                            onClick = { if (target != null) onPlay(target.id) },
                            enabled = target != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Brand,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(0.72f)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (item.kind == MediaKind.SERIES) {
                                    val ep = visibleEpisodes.firstOrNull { it.positionMs > 0 }
                                        ?: visibleEpisodes.firstOrNull()
                                    ep?.let { "继续观看  ${it.title}" } ?: "开始播放"
                                } else "播放"
                            )
                        }

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
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppColors.SurfaceHi)
                        ) {
                            Icon(
                                if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (fav) "取消收藏" else "收藏",
                                tint = if (fav) AppColors.Brand else AppColors.TextSecondary
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
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                }

                // ---------- 刮削信息（标语/分级/工作室/导演/演员）：Emby 优先，TMDB 补齐 ----------
                run {
                    val tagline = scraped?.tagline.orEmpty().ifBlank { tmdb?.tagline.orEmpty() }
                    val chips = buildList {
                        scraped?.let {
                            if (it.officialRating.isNotBlank()) add(it.officialRating)
                            addAll(it.studios.take(2))
                        }
                    }
                    val directors = (scraped?.directors?.map { it.toCast() }
                        ?: tmdb?.directors?.map { it.toCast() }.orEmpty())
                    val actors = (scraped?.actors?.map { it.toCast() }
                        ?: tmdb?.actors?.map { it.toCast() }.orEmpty())

                    if (tagline.isNotBlank() || chips.isNotEmpty() || directors.isNotEmpty() || actors.isNotEmpty()) {
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
                                                    .clip(RoundedCornerShape(6.dp))
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

                                if (actors.isNotEmpty()) {
                                    Text(
                                        "主演",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = AppColors.TextPrimary
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(top = 4.dp)
                                    ) {
                                        items(actors, key = { it.name + it.role }) { person ->
                                            CastCard(person)
                                        }
                                    }
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
                                            selectedPlaySource = src
                                            scope.launch {
                                                episodes = vm.episodesBySource(item.seriesKey, src)
                                                selectedSeason = episodes.firstOrNull()?.season ?: 1
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
                        items(visibleEpisodes.chunked(if (isTv) 4 else 2)) { row ->
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
        }
    }
}

/** 决定「播放」按钮跳哪一集：优先未看完的，其次第一集 */
/** 刮削结果的新鲜期：7 天内不回源重刮（与内存缓存 TTL 一致） */
private const val SCRAPE_FRESH_MS = 7L * 24 * 60 * 60 * 1000

private fun playTarget(item: MediaEntity, episodes: List<MediaEntity>): MediaEntity? =
    if (item.kind == MediaKind.SERIES) {
        episodes.firstOrNull { it.positionMs > 0 } ?: episodes.firstOrNull()
    } else item

/** 统一演职员展示类型，抹平 EmbyClient.Person / TmdbClient.Person 的差异 */
private data class CastPerson(val name: String, val role: String, val imageUrl: String)

private fun EmbyClient.Person.toCast() = CastPerson(name, role, imageUrl)
private fun com.tabbit.myvidhub.data.tmdb.TmdbClient.Person.toCast() = CastPerson(name, role, imageUrl)

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
            .clip(RoundedCornerShape(999.dp))
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
private fun EpisodeCard(episode: MediaEntity, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val shape = RoundedCornerShape(10.dp)
    val playing = episode.positionMs > 0

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
                        .clip(RoundedCornerShape(2.dp))
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
