package com.chen.doudoutv.ui.tv

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import coil.compose.AsyncImage
import com.chen.doudoutv.MainActivity
import com.chen.doudoutv.data.emby.EmbyClient
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.data.model.splitCastNames
import com.chen.doudoutv.data.tvbox.HtmlText
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.detail.FocusColorCache
import com.chen.doudoutv.ui.detail.fallbackColorFromTitle
import com.chen.doudoutv.ui.detail.focusColorOf
import com.chen.doudoutv.ui.detail.loadPosterBitmap
import com.chen.doudoutv.ui.detail.pageTonesOf
import com.chen.doudoutv.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
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
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.ui.StandTarget
import com.chen.doudoutv.ui.components.episodeLabel
import com.chen.doudoutv.ui.components.matchEpisodeInSource
import com.chen.doudoutv.ui.components.usesSourceEpisodeName
import com.chen.doudoutv.ui.components.TextBadge
import com.chen.doudoutv.ui.components.TmdbCreditLine
import com.chen.doudoutv.ui.theme.TvText
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 电视端简介页（独立实现，不复用手机端 DetailScreen）。
 *
 * 交互模型：**单页滚动 + 焦点分区**，参考 Netflix 的电视端详情页。
 *   · 信息不再按层级显隐 —— 标题、简介、导演、主演、选集从进页起就在组合树里，
 *     方向键只负责在焦点锚点之间移动，视口跟着焦点滚动；
 *   · 因此不存在「按下键解锁剧情」「按上键把选集收掉」这两种模态行为：
 *     上键永远只回上一个区域，内容不会消失；
 *   · 只读区块（简介、导演、署名）不接收焦点，下键会直接跳过它们；
 *     主演行是例外：Emby / TMDB 带演员照片，头像本身是可聚焦的一站 ——
 *     电视上没有滚轮，焦点走不到的内容就等于看不到，尤其电影没有分集可落；
 *   · 主演排在选集之后（整页最后一层）：进详情先看剧情与选集，
 *     选集末行按下即落到主演头像行；导演与 TMDB 署名仍留在选集之前，
 *     署名必须在焦点滚得到的区域才有效；
 *   · 首屏仍保留「大图优先」：55% 顶部占位把标题与操作区压到屏幕下半部，
 *     下键进入第一集时，简介与选集首行同屏可见。
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
 *   收藏 ⇅ 播放/从头开始 ⇅（简介展开）⇅ 选集控制条（源 · 季 · 翻页）⇅ 选集网格 ⇅ 主演头像
 *   · 下键逐层走，一层都没有才跳过只读区块；
 *   · 有分集时仍按「控制条优先」，头像行不插进这条快捷路径，只能左右几键走到；
 *   · 在控制条上完成的切换（换源、换季、翻页）焦点留在控制条，主动按下才进网格；
 *   · 主演在选集网格之后，是整页最后一层，末行按下才落得到；
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

    /**
     * 从「继续观看」进来、页面改绑到剧集壳之后，记住用户真正在看的那条分集。
     * 剧集壳自己没有可播地址也不带进度，主按钮要续播的仍是这条分集。
     * 其他入口进来时恒为 null，按钮行为不变。
     */
    var resumeEpisode by remember(mediaId) { mutableStateOf<MediaEntity?>(null) }
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
    var tmdb by remember { mutableStateOf<com.chen.doudoutv.data.tmdb.TmdbClient.ScrapeResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    /** 分季 + 分页：几百集的剧直接一屏铺满很难找，先按季过滤再按页翻 */
    var selectedSeason by remember(mediaId) { mutableStateOf(0) }
    var epPage by remember(mediaId) { mutableStateOf(0) }
    /** 整页重建后要把焦点交回的那一集（0 = 没有），由 [LaunchedEffect] 的接址逻辑填 */
    var recallEpisodeId by remember(mediaId) { mutableStateOf(0L) }
    /**
     * 这一页是不是「播放器把宿主 Activity 打断后重建」进来的（true 才需要接回原来那一集）。
     *
     * 只有上面那条接址分支会把它翻真。[recallEpisodeId] 单独不够：换播放源（rebindSource）
     * 也写它，进程级记下的分集 id 还可能是上一次看别的片子留下的 —— 于是第一次正常进这一页
     * 也会被「接回老落点」带走，焦点落在选集格里，bring-into-view 顺势把简介摊出来，
     * 首屏那张大图构图就没有了。
     */
    var restoredStand by remember(mediaId) { mutableStateOf(false) }

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
    // 滚动状态提到抢焦之前建：入口抢焦要在焦点落定后把首屏构图压回顶部
    val scrollState = rememberScrollState()

    /**
     * 离开时焦点站在哪个元件上（播放 / 收藏 / 控制条 / 某一集），回来就还站在它上面。
     * 详情页的子树在进播放器时不销毁，所以这份记录只要活过一次组合就够了，
     * 不用像首页那样按页面 key 持久化。
     */
    val stand = remember { StandTarget() }
    /**
     * 播放键是不是真的拿到过焦点（首屏那一次落点是不是站上了）。
     * 只由播放键自己的焦点上报翻真，见 [publishStand]。
     */
    var playLanded by remember(mediaId) { mutableStateOf(false) }
    /** 日志用：stand 里那条落点记录是谁 */
    fun standLabel(): String = when (stand.requester) {
        null -> "none"
        playFocusRequester -> "play"
        favoriteFocusRequester -> "favorite"
        controlsFocusRequester -> "controls"
        firstEpFocusRequester -> "ep-first"
        else -> "cell"
    }
    /** 谁拿到焦点谁就把自己交给 [stand]；一个节点只交它自己已经挂着的那个 requester */
    fun publishStand(target: FocusRequester, name: String): Modifier =
        Modifier.onFocusChanged {
            if (it.hasFocus) {
                stand.requester = target
                if (target === playFocusRequester) playLanded = true
                Log.i(FOCUS_TAG, "stand <- $name (scroll=${scrollState.value})")
            }
        }

    /** 界面每次回到前台（进出播放器）都要重新站好焦点，见 [tvResumeTick] */
    val resumeTick = tvResumeTick()

    val scope = rememberCoroutineScope()

    // 首帧就用「上次算好的主色 / 内存缓存 / 标题哈希色」，避免进入瞬间一片黑
    // 同手机端：mediaOf() 是 suspend，组合期不能调。这里单条订阅这一条媒体，
    // 不订阅整库（整表 Flow 每次失效都要全表重读进内存，而详情页只要这颗种子色）；
    // 库数据晚到时 flow 补发，remember 的 key 挂到标题上，初值自动补正确颜色。
    val seedMedia by remember(mediaId) { vm.seedMediaFlow(mediaId) }
        .collectAsStateWithLifecycle(initialValue = null)
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

    /**
     * 已经刮过的片子先把图抢上屏。
     *
     * 完整那次刮削挂在下面加载链的最末端（要等分集、详情那几段跑完），而背景图的取值是
     * 「刮削图优先、条目自有图兜底」，于是第二次及以后进同一部片，仍然会先上一帧源站老图 ——
     * 数据其实早就躺在 scraped_meta 表里，一次网都没联。这里只读那张表，命中就直接用，
     * 没命中什么也不做，仍由下面那条链负责（首次联网刮的行为一个字没改）。
     * 分集行是「继续观看」进来那条：标题和类型都挂在剧集壳上，键算出来对不上，不去猜。
     */
    LaunchedEffect(mediaId, seedMedia?.title, seedMedia?.year, seedMedia?.kind) {
        val seed = seedMedia ?: return@LaunchedEffect
        if (seed.kind == MediaKind.EPISODE) return@LaunchedEffect
        if (tmdb == null) tmdb = vm.tmdbCached(seed.title, seed.year, seed.kind)
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
                // 记下真实 id：这一页要是被爬虫壳的 finish 打断后重建，导航栈里存的还是那个负 id，
                // 换成这里记的真实 id 才还能原样打开（负 id 靠的是内存里的浏览结果，重建后已没了）。
                TvNavHistory.resolvedDetailId = realId
            }
        }
        if (m == null) media = null
        if (m != null) {
            // 「继续观看」进来的是 EPISODE 行：海报/年份/简介/选集全都挂在剧集壳上，
            // 绑分集行整页几乎是空的。seriesKey 里已经带 sourceId + siteKey + vodId，
            // 查到的壳就是这条记录所属那个数据源的整部剧，不存在跨源歧义，直接换绑、不用问。
            // 壳不在库里（换源重入库、老数据）时保持分集行渲染。
            if (m.kind == MediaKind.EPISODE) {
                val shell = vm.shellOf(m.seriesKey)
                if (shell != null) {
                    resumeEpisode = m.takeIf { it.positionMs > 5_000L }
                    id = shell.id
                    m = shell
                }
            }
            media = m
            val src = vm.sourceOf(m.sourceId)
            if (src?.kind == SourceKind.EMBY) {
                scraped = runCatching { vm.embyDetailOf(m.sourceId, m.remoteId) }.getOrNull()
                if (m.kind == MediaKind.SERIES) {
                    runCatching { vm.loadEmbyEpisodes(m.sourceId, m.remoteId) }
                }
            } else if (src?.kind == SourceKind.TVBOX && m.kind == MediaKind.SERIES &&
                vm.episodesOf(m.seriesKey).isEmpty()
            ) {
                // 与手机端同一口径：只在库里还没有分集时才拉详情。loadTvBoxDetail 会把该剧
                // 分集全删重插（新行、新 id），无条件调用会顺手清掉「继续观看」的进度，
                // 并让主按钮正要播的那条分集行失效。
                runCatching { vm.loadTvBoxDetail(m.sourceId, m.remoteId) }
            }
            val latest = vm.mediaOf(id) ?: m
            media = latest

            // 演员/更新备注是这次才入库的字段：老条目里是空串，而上面的分集守卫又让它们
            // 没机会重拉详情。这里补一次「只写壳」的详情请求——不动分集行，
            // 「继续观看」进度与正在展示的选集都不受影响；每个 remoteId 一个进程只试一次。
            if (src?.kind == SourceKind.TVBOX && latest.kind == MediaKind.SERIES &&
                latest.actors.isBlank()
            ) {
                if (vm.refreshTvBoxMeta(m.sourceId, latest.remoteId)) {
                    media = vm.mediaOf(id) ?: latest
                }
            }

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

            // 「看完视频回来还站在那一集」：爬虫壳在播放期间 finish 宿主 → MainActivity recreate()，
            // 这一页连同季 / 页码 / 焦点全是一份新组合的默认值。用进程级记下的分集 id 把线路、季、页码接回去，
            // 并把该格的 requester 交给 [stand]，下面的 tvClaimFocusOnEnter 就以它为第一顺位锚点。
            // 正常进出播放器时子树没销毁，这个 LaunchedEffect 不会再跑，落点仍由 stand 原路接住。
            val playedId = TvNavHistory.playedEpisodeId
            if (playedId != 0L) {
                val all = runCatching { vm.episodesOf(latest.seriesKey) }.getOrDefault(emptyList())
                val played = all.firstOrNull { it.id == playedId }
                if (played != null) {
                    if (sources.isNotEmpty() && played.playSource.isNotBlank() &&
                        played.playSource in sources && played.playSource != selectedPlaySource
                    ) {
                        selectedPlaySource = played.playSource
                        episodes = runCatching {
                            vm.episodesBySource(latest.seriesKey, played.playSource)
                        }.getOrDefault(episodes)
                    }
                    val seasons = episodes.map { it.season }.distinct()
                    if (seasons.size > 1) selectedSeason = played.season
                    val inSeason =
                        if (seasons.size <= 1) episodes else episodes.filter { it.season == played.season }
                    val pos = inSeason.indexOfFirst { it.id == playedId }
                    if (pos >= 0) epPage = pos / TV_EPS_PAGE
                    recallEpisodeId = playedId
                    restoredStand = true
                }
            }

            // 刮削结果一律问仓储层：它先读 scraped_meta 表，表里没有才轮到「联网刮削」开关
            // 和凭据决定要不要发请求。这里再拦一道开关，会把已经刮到、躺在表里的数据一起藏掉。
            // 只在刮到东西时覆盖：上面那个只读表的抢跑已经摆好了图，这里失败不能把它擦回老封面。
            runCatching {
                vm.tmdbScrape(latest.title, latest.year, latest.kind, id)
            }.getOrNull()?.let { tmdb = it }
            // 刮削会把海报/Logo 回填进库，取回最新的再渲染
            vm.mediaOf(id)?.let { media = it }
        }
        } catch (e: Throwable) {
            e.printStackTrace()
        } finally {
            loading = false
        }
    }

    /**
     * 加载态两段：骨架晚 250ms 才出现，内容到位后用 200ms 淡入。
     *
     * 为什么要晚这 250ms：刮削表命中的片子整条链路只有几十毫秒，骨架立刻挂上就是
     * 「白框架闪一下」，比原来干等更晃眼；真正慢的是 TVBox 拉分集和 TMDB 联网那两类，
     * 它们超过 250ms 才值得给一个「正在加载」的样子。
     * 淡入只在加载结束这一刻走一次，不参与滚动，所以和当初为滚动性能关掉的淡入动画不冲突。
     */
    var skeletonArmed by remember(mediaId) { mutableStateOf(false) }
    val reveal = remember(mediaId) { Animatable(1f) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(250L)
            skeletonArmed = true
        } else {
            skeletonArmed = false
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(200))
        }
    }

    // 取色：与手机端一致，用 Coil ImageLoader 直接解码 bitmap，失败回退标题哈希色
    val tvContext = LocalContext.current
    // 依赖里带上 tmdb 的两个图：刮削完成后海报换了，主色要跟着重算
    LaunchedEffect(media?.posterUrl, media?.backdropUrl, tmdb?.posterUrl, tmdb?.backdropUrl) {
        val m = media ?: return@LaunchedEffect
        // 取色要跟页面上真正显示的那张图一致，不能停留在源站那张豆瓣图上
        val scrapedArt = tmdb?.posterUrl.orEmpty().ifBlank { tmdb?.backdropUrl.orEmpty() }
        val url = scrapedArt.ifBlank { m.posterUrl.ifBlank { m.backdropUrl } }
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
     * 进页与「界面每次回到前台」都要把焦点站好（规范 R7）：
     * 离开时站在某一集 / 控制条上，从播放器回来就还站在那里；这一页第一次进来（没有落点记录）
     * 才落到播放键，进页即可直接按 OK 播放。
     * key 只带「数据到位与否 + resume 次数」：刮削回填会换掉 media 实例，
     * 但 id 不变，所以不会把用户已经站好的焦点从选集抢走（等价于原来的 initialFocusDone 只落一次）。
     */
    // 第一次进这一页：焦点站播放键（首屏大图构图），不读上次离开时的落点 ——
    // 读了会把焦点直接交到下面的选集/控制条，bring-into-view 顺势把详情滚出来，首屏大图就没了。
    // stand 是本组合私有的：第一次进来还没人拿过焦点，它必然是空的，天然就落到播放键；
    // 从播放器回来（子树重建）时 recallEpisodeId 会在组合期把那一格交给 stand，于是还站在那一集。
    // 不再另立 entered 标记：LaunchedEffect(Unit) 和抢焦效果同一帧启动，抢焦还在 delay 里
    // 标记就已经翻真，读出来的永远是「已进过」，等于没有这道闸。
    //
    // tick 用「内容有没有真的组合出来」当信号，而不是「loading 结束了没有」：
    // loading=false 那一刻内容才刚进组合，抢焦效果同一帧启动时播放键可能还没挂上，
    // requestFocus 静默失败、之后再没有第二次机会（于是焦点干脆没落点）。
    // 内容组合出来了（骨架已撤、条目已到）才发一次 tick，抢焦必然命中。
    val contentReady = media != null && !(loading && skeletonArmed)
    // 第一落点 vs 之后：播放键真正拿到过焦点（[playLanded]）之前，每一轮抢焦都只认播放键。
    // 不能只判「这一页跑过第一轮没有」：tick 每变一次，上一轮就被取消重开，
    // 真机日志里进一次页跑了三轮，前两轮还在骨架阶段（ready=false），播放键根本没挂上，
    // 标记却被第二轮消费掉了 —— 第三轮改按 stand 里那个先拿到焦点的元件跑，
    // bring-into-view 一滚，简介就摊在眼前（stand 会被抢焦以外的焦点上报填上）。
    // 这一轮的落点判据（抢焦和「压回顶部」共用一份，两边不能各算各的）
    fun claimTarget(): FocusRequester = when {
        // 播放器把宿主 Activity 打断后重建回来的：接回刚才那一集，stand 说了算
        restoredStand -> stand.requester ?: playFocusRequester
        // 首屏那一次还没站上播放键：换源重绑、进程级老记录、别人先抢到的焦点都不认
        !playLanded -> playFocusRequester
        else -> stand.requester ?: playFocusRequester
    }
    tvClaimFocusOnEnter(
        tick = listOf(resumeTick, if (contentReady) media?.id else null),
        anchorsProvider = {
            val target = claimTarget()
            Log.i(
                FOCUS_TAG,
                "claim: ready=$contentReady loading=$loading stand=${standLabel()} " +
                    "restored=$restoredStand landed=$playLanded target=" +
                    (if (target === playFocusRequester) "play" else "stand") +
                    " scroll=${scrollState.value}"
            )
            listOf(target)
        },
        settleMillis = 120,
        // 播放键排在首屏 55% 占位之下，1.9 的 requestFocus 会把它「滚到可见」，顺势把简介摊出来。
        // 只有这一轮确实往播放键上落才把页面压回顶部；从播放器回来落在某一集上时不能滚，
        // 否则那一集又被滚出视口。
        keepTop = true,
        scrollToTop = {
            val t = claimTarget()
            if (t === playFocusRequester) scrollState.scrollTo(0)
        }
    )

    // 取证：进页头 4 秒里滚动位置每次变化都记一笔 ——
    // 「第一次进页就摊开简介」到底是抢焦滚的、还是 bring-into-view 补滚的，看这条日志就知道。
    LaunchedEffect(mediaId) {
        val until = android.os.SystemClock.uptimeMillis() + 4000L
        snapshotFlow { scrollState.value }.collect { v ->
            if (android.os.SystemClock.uptimeMillis() < until) Log.i(FOCUS_TAG, "scroll=$v")
        }
    }

    val item = media

    // 主演名单：Emby 刮削优先、TMDB 补齐，最后才回落到源自己给的 vod_actor。
    // 取前 5 位（电视一行放得下的量）。
    val sourceActorNames = splitCastNames(item?.actors.orEmpty())
    val actorNames = remember(scraped, tmdb, sourceActorNames) {
        val fromEmby = scraped?.actors?.map { it.name }?.takeIf { it.isNotEmpty() }
        val fromTmdb = tmdb?.actors?.map { it.name }?.takeIf { it.isNotEmpty() }
        val fromSource = sourceActorNames.takeIf { it.isNotEmpty() }
        (fromEmby ?: fromTmdb ?: fromSource).orEmpty().distinct().take(5)
    }
    /**
     * 带头像的主演行：只有 Emby / TMDB 这两路数据给演员照片（CatVod 协议本来就只有姓名）。
     * 有照片就铺一排头像，没有才退回主演文字行 —— 两条路径互斥，不会同屏重复列一遍名字。
     */
    val tvCast = remember(scraped, tmdb) {
        val fromEmby = scraped?.actors?.takeIf { it.isNotEmpty() }
            ?.map { TvCast(it.name, it.role, it.imageUrl) }
        val fromTmdb = tmdb?.actors?.takeIf { it.isNotEmpty() }
            ?.map { TvCast(it.name, it.role, it.imageUrl) }
        (fromEmby ?: fromTmdb).orEmpty()
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .take(6)
    }
    val hasCast = tvCast.isNotEmpty()

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

    /**
     * 换播放源（网盘 / 线路）之后，把这一页接回用户原来那集。
     *
     * 新线路的分集是另一批行、另一批 id：旧行要是继续留在 resumeEpisode 里，
     * 主按钮「继续观看」就会播回上一条网盘 —— 换源后进度看着还在，按下去却来自另一个源。
     * 找不到同一集时宁可清掉续播锚点、回到第一页，也不能拿着旧源的行往下走。
     */
    fun rebindSource(next: List<MediaEntity>, anchor: MediaEntity?) {
        val same = matchEpisodeInSource(anchor, next)
        episodes = next
        // 续播锚点只在「这页本来就是靠它开的」时跟着换源重绑；
        // 新线路没有同一集就清掉，不能让主按钮拿着旧网盘的行去播。
        if (resumeEpisode != null) resumeEpisode = same
        val seasons = next.map { it.season }.distinct()
        if (seasons.size > 1) {
            selectedSeason = when {
                same != null -> same.season
                selectedSeason in seasons -> selectedSeason
                else -> seasons.first()
            }
        }
        val inSeason =
            if (seasons.size <= 1) next else next.filter { it.season == selectedSeason }
        val pos = same?.let { s -> inSeason.indexOfFirst { it.id == s.id } } ?: -1
        epPage = if (pos >= 0) pos / TV_EPS_PAGE else 0
        recallEpisodeId = same?.id ?: 0L
    }

    val tones = remember(dominant) {
        if (dominant == Color.Unspecified) {
            AppColors.Bg to lerp(AppColors.Bg, Color.Black, 0.55f)
        } else {
            pageTonesOf(dominant)
        }
    }
    val top by animateColorAsState(tones.first, tween(Motion.SLOW), label = "tv_top")
    val bottom by animateColorAsState(tones.second, tween(Motion.SLOW), label = "tv_bottom")

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

    // 图一律「刮来的优先」，与手机端同一口径：源站那张常常是豆瓣缩略图，
    // Emby 公共片库的图也一样糙；刮不到时（字段为空）自然回落到条目自己的图。
    val backdrop = tmdb?.backdropUrl.orEmpty().ifBlank {
        item?.let { it.backdropUrl.ifBlank { it.posterUrl } }.orEmpty()
    }
    val logo = tmdb?.logoUrl.orEmpty().ifBlank { item?.logoUrl.orEmpty() }

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
        val contentAnchor = remember { BringIntoViewRequester() }

        /**
         * 一次下键的完整转场：内容块顶部对齐 → 焦点交给 [target]。
         *
         * 只依赖焦点的自动 brings-into-view，视口会「最小滚动」，落点虽然露出来了，
         * 简介顶部却常常被切掉；先把内容锚点滚到顶部再聚焦，才能稳定做到
         * 「一屏看到简介 + 选集首行」，这也是「一次下键看全」的关键。
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
         * 简介文本：优先 TMDB，其次刮削到的 tagline，最后源自己给的 overview。
         * 数据源（TVBox / Emby）常带 `<span>`、`<br>` 之类的 HTML 片段，统一清成纯文本。
         *
         * 定义在焦点链之前，是因为「有没有这颗展开按钮」要参与决定下键的落点。
         */
        val overview = (
            item?.let { media ->
                tmdb?.overview?.takeIf { it.isNotBlank() }
                    ?: scraped?.tagline?.takeIf { it.isNotBlank() }
                    ?: media.overview
            }.orEmpty()
            ).let { HtmlText.strip(it) }
        var descOverflow by remember(item?.id, overview) { mutableStateOf(false) }
        val descFocusRequester = remember { FocusRequester() }
        var descExpanded by remember(item?.id) { mutableStateOf(false) }
        val descTruncated = descExpanded || descOverflow
        // 每颗头像一个焦点站：requester 由本页先建好再交给头像行，
        // 「播放键 ↓ 落到谁」要在这里就能算出来，不能让组件自己造。
        val castFocusRequesters = remember(tvCast) { List(tvCast.size) { FocusRequester() } }
        /**
         * 网格末行最后一格的焦点器：主演挪到选集下面之后，它的上键要落回这一格。
         * 用状态存，网格换季/翻页换了节点时头像行会跟着重组，不会攥着旧节点不放。
         */
        val lastCellRequester = remember { mutableStateOf<FocusRequester?>(null) }

        /**
         * 播放区按下 ↓ 的落点。展开按钮排在简介正下方，所以有长简介时它是第一站；
         * 之后才是选集链（控制条 > 首格），电影没有分集就落到主演头像行。
         *
         * 电影 / Emby 单条内容没有分集：这一层原来一个可聚焦元件都没有，下键只能 Cancel ——
         * 焦点走不动，滚动也就不发生，完整简介和主演永远看不到（规范 R1/R4：一层一层走，不许跨层抢）。
         */
        val belowPlay: FocusRequester = when {
            descTruncated -> descFocusRequester
            hasEpisodes && hasControlAnchor -> controlsFocusRequester
            hasEpisodes -> firstEpFocusRequester
            hasCast -> castFocusRequesters.first()
            else -> FocusRequester.Cancel
        }
        /**
         * 展开按钮的下一站：往下是选集链（控制条 > 网格），再往下才是主演头像行；
         * 电影没有分集时直接落到主演，这就是整页最后一站。
         */
        val belowDesc: FocusRequester = when {
            hasEpisodes && hasControlAnchor -> controlsFocusRequester
            hasEpisodes -> firstEpFocusRequester
            hasCast -> castFocusRequesters.first()
            else -> FocusRequester.Cancel
        }
        /**
         * 头像行的上一站：它现在排在选集网格下面，正上方是网格末行最后一格。
         * 网格还没组合出来时（首帧）退回控制条，不能指向没挂载的节点。
         */
        val castUpTarget: FocusRequester =
            lastCellRequester.value?.takeIf { hasEpisodes }
                ?: if (hasControlAnchor) controlsFocusRequester
                else if (descTruncated) descFocusRequester
                else playFocusRequester
        /** 头像行的下一站：主演是整页最后一层，下面没有内容了 */
        val castDownTarget: FocusRequester = FocusRequester.Cancel
        /** 展开按钮的上一站：它紧贴简介，往上就是播放键 */
        val descUpTarget: FocusRequester = playFocusRequester

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
         *     简介与选集首行因此能同屏出现；
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

            if (loading && skeletonArmed) {
                TvDetailSkeleton()
            } else if (item == null) {
                // 还没到 250ms 的加载窗口里什么都不画：背景与暗层已经在下面铺着，
                // 先弹一行「加载中…」再换成骨架是两次跳变
                if (!loading) {
                    TvEmptyState(
                        "找不到该内容",
                        modifier = Modifier.fillMaxWidth().height(420.dp)
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = reveal.value }
                ) {
                    // ========== 首屏骨架：标题 / 收藏 / 元信息 / 徽章 / 操作 ==========
                    // 只占左 72%：电视是 16:9 横屏，首屏只有标题、元信息、
                    // 徽章和一个主按钮，铺满整宽会变成「左边一列字、右边一大片空」。
                    Column(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .padding(start = TvPagePadding, end = 12.dp)
                    ) {
                        // 标题位：Logo 与文字标题共用同一块标题槽，两套布局同一尺度（[TvMediaTitle]）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TvMediaTitle(
                                title = item.title,
                                logoUrl = logo,
                                modifier = Modifier.weight(1f, fill = false)
                            )
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
                                    .then(publishStand(favoriteFocusRequester, "favorite"))
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
                                onClick = {
                                    val next = !item.favorite
                                    vm.toggleFavorite(item.id, next)
                                    // media 是进页时一次性读出的快照，库 Flow 的回填不会流到这里，
                                    // 不本地回写就是「按了没反应」（同手机端的处理）
                                    media = media?.copy(favorite = next)
                                }
                            )
                        }

                        // 元信息放在标题与徽章之间，形成层级节奏：
                        // 大标题 → 元信息 → 徽章 → 按钮，各档字号都由 TvTypography 决定
                        Spacer(Modifier.height(8.dp))
                        TvMetaRow(item)
                        Spacer(Modifier.height(10.dp))
                        // TVBox 源的画质来自详情入库时的文件名推测 + 播放期真实轨道回写；
                        // 源自己给的更新状态/集数/线路数照旧保留，两类信息一起排。
                        val sourceBadges = buildList {
                            val remarks = item.remarks.trim()
                            if (remarks.isNotBlank()) add(remarks)
                            if (item.kind == MediaKind.SERIES && episodes.isNotEmpty()) {
                                add("共 ${episodes.size} 集")
                            }
                            if (playSources.size > 1) add("${playSources.size} 条线路")
                        }
                        FormatBadgesRow(
                            tmdbEnabled = false,   // 评分胶囊已删，徽章行不再显示 TMDB 字样
                            resolution = scraped?.videoResolution.orEmpty().ifBlank { item.videoResolution },
                            hdr = scraped?.hdrType.orEmpty().ifBlank { item.hdrType },
                            codec = scraped?.videoCodec.orEmpty().ifBlank { item.videoCodec },
                            audio = item.audioType,
                            sourceInfo = sourceBadges
                        )

                        // 操作按钮行：只出一个主按钮，三种内容状态各自对应一个明确动词。
                        // 没看过的剧先选集，看过的直接续播。
                        // resumeEp 只从「继续观看」进来时有值：那类入口给的是分集行，
                        // 页面换成剧集壳后壳自身不带进度，主按钮仍要落回那集才能一键接上。
                        val resumeEp = resumeEpisode
                        val seriesUnwatched = item.kind == MediaKind.SERIES &&
                            item.positionMs <= 5_000L && resumeEp == null
                        // 操作按钮的下键进选集区 / 展开按钮 / 主演头像行：
                        // 只读的简介、导演、署名不接收焦点，所以不会出现「按几次才滚到剧集」。
                        // 下键：先把内容块滚到顶部再交给落点（默认控制条首颗）；预览阶段消费，
                        // 避免默认焦点搜索先一步把视口滚到「只露出卡片」的位置。
                        // 一站都没有（短简介 + 无演员照片 + 无分集）时退化成「把这一屏滚到底」，
                        // 至少简介全文和主演文字看得见，而不是按下方向键毫无反应。
                        val episodeDownModifier = Modifier.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionDown) {
                                false
                            } else if (hasEpisodes || descTruncated || hasCast) {
                                enterEpisodeSection(belowPlay)
                                true
                            } else {
                                scope.launch {
                                    runCatching { scrollState.animateScrollTo(scrollState.maxValue) }
                                }
                                true
                            }
                        }
                        // 焦点走到操作区 = 用户已经按回顶部，构图同步送回「海报 + 大标题」态
                        val restoreHeaderOnFocus = Modifier.onFocusChanged {
                            if (it.hasFocus) restoreHeaderComposition()
                        }
                        val playFocusModifier = Modifier
                            .focusRequester(playFocusRequester)
                            .then(restoreHeaderOnFocus)
                            .then(publishStand(playFocusRequester, "play"))
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
                                        when {
                                            resumeEp != null ->
                                                if (resumeEp.usesSourceEpisodeName) "继续观看"
                                                else "继续观看 S${resumeEp.season}E${resumeEp.episode}"
                                            item.kind == MediaKind.SERIES && item.episode > 0 ->
                                                if (item.usesSourceEpisodeName) "继续观看"
                                                else "继续观看 S${item.season}E${item.episode}"
                                            else ->
                                                if (item.positionMs > 5_000L) "继续播放" else "立即播放"
                                        }
                                    },
                                    size = TvButtonSize.Lg,
                                    modifier = playFocusModifier,
                                    onClick = {
                                        val target = resumeEp?.id ?: item.id
                                        TvNavHistory.playedEpisodeId = target
                                        onPlay(target)
                                    }
                                )
                                if (item.positionMs > 5_000L || resumeEp != null) {
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
                                            // 「从头开始」归零的必须是真正要播的那条：
                                            // 从「继续观看」进来时 item 是剧集壳，进度写在分集行上
                                            val target = resumeEp ?: item
                                            vm.resetPosition(target.id)
                                            TvNavHistory.playedEpisodeId = target.id
                                            onPlay(target.id)
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
                        // ---------- 简介：只读，收起态下键会跳过它（展开入口紧贴其下方） ----------
                        // overview 已在焦点链之前算好：有没有那颗按钮要参与决定下键落点。
                        if (overview.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = overview,
                                color = TvText.Body,
                                style = MaterialTheme.typography.bodyLarge,
                                // 4 行以内：保证简介 + 展开按钮 + 导演 + 主演能挤进同一屏
                                maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                                overflow = if (descExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 620.dp),
                                onTextLayout = { layout ->
                                    if (!descExpanded) descOverflow = layout.hasVisualOverflow
                                }
                            )

                            // ---------- 展开 / 收起简介：只读区里唯一的焦点站 ----------
                            // 聚焦它时把内容块顶部对齐视口：电视上没有滚轮，只读的导演 / 主演一旦被
                            // 顶到视口外就再也滚不到（Emby 电影没有分集，这里往下就是末站）。
                            // 展开态不做这个对齐：那时按钮自己得留在视口里，才按得到「收起」。
                            if (descTruncated) {
                                Spacer(Modifier.height(12.dp))
                                TvPillButton(
                                    text = if (descExpanded) "收起" else "展开更多",
                                    primary = false,
                                    size = TvButtonSize.Md,
                                    modifier = Modifier
                                        .focusRequester(descFocusRequester)
                                        .onFocusChanged {
                                            if (it.isFocused && !descExpanded) {
                                                scope.launch { runCatching { contentAnchor.bringIntoView() } }
                                            }
                                        }
                                        .focusProperties {
                                            up = descUpTarget
                                            down = belowDesc
                                        }
                                        .then(publishStand(descFocusRequester, "desc")),
                                    onClick = {
                                        descExpanded = !descExpanded
                                        // 展开和收起都从简介第一行起读，而不是停在贴着按钮的那一屏
                                        scope.launch { runCatching { contentAnchor.bringIntoView() } }
                                    }
                                )
                            }
                        }

                        // ---------- 导演：紧接简介，只读、不接收焦点 ----------
                        val directors = (tmdb?.directors?.takeIf { it.isNotEmpty() }?.map { it.name }
                            ?: scraped?.directors?.map { it.name }
                            ?: splitCastNames(item.directors).take(3))
                            ?.joinToString(" / ").orEmpty()
                        if (directors.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            TvInfoLine("导演", directors)
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
                                        // ↑ 按版面顺序往回走：展开按钮 > 播放键
                                        // （主演已下移到选集之后，不再是控制条的上一站）
                                        up = if (descTruncated) descFocusRequester else playFocusRequester
                                        down = if (hasEpisodes) firstEpFocusRequester else FocusRequester.Cancel
                                    }.then(publishStand(controlsFocusRequester, "controls"))
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
                                                    // 换源前先记住「用户站在哪一集」：
                                                    // 续播的那集 > 回来后要落回的那格 > 当前页第一集
                                                    val anchor = resumeEpisode
                                                        ?: episodes.firstOrNull { it.id == recallEpisodeId }
                                                        ?: pagedEpisodes.firstOrNull()
                                                    selectedPlaySource = src
                                                    scope.launch {
                                                        val item2 = media
                                                        if (item2 != null) {
                                                            rebindSource(
                                                                vm.episodesBySource(item2.seriesKey, src),
                                                                anchor
                                                            )
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
                                            text = "上一页",
                                            icon = androidx.compose.material.icons.Icons.Filled.ChevronLeft,
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
                                            text = "下一页",
                                            trailingIcon = androidx.compose.material.icons.Icons.Filled.ChevronRight,
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
                                        // 每格自己的 requester：焦点停在哪一格，从播放器回来就还落回那一格。
                                        // 首行首格已经把 controlsFocusRequester 那个位置让给了
                                        // firstEpFocusRequester（换季/翻页的固定落点），不能两个节点挂同一个。
                                        val cellFocus = remember(ep.id) { FocusRequester() }
                                        val cellTarget =
                                            if (rowIdx == 0 && idx == 0) firstEpFocusRequester else cellFocus
                                        // 末行最后一格 = 主演头像行正上方那一格，主演的上键落在它
                                        if (rowIdx == epRows.lastIndex && idx == rowEps.lastIndex) {
                                            lastCellRequester.value = cellTarget
                                        }
                                        if (ep.id == recallEpisodeId) {
                                            // 重建后的落点：组合期就把这一格交给 stand（同 ReturnSpot.cardStand 的交法），
                                            // 抢焦效果在本帧组合之后才跑，取到的就是它
                                            remember(cellTarget) {
                                                stand.requester = cellTarget
                                                Log.i(FOCUS_TAG, "stand <- recall ep${ep.id}（组合期交入）")
                                            }
                                        }
                                        TvEpisodeCard(
                                            episode = ep,
                                            modifier = Modifier.weight(1f).then(publishStand(cellTarget, "ep${ep.id}")),
                                            // 首行第一格挂焦点器，「选择剧集」与换季/翻页后直接落这里
                                            focusRequester = cellTarget,
                                            // 首行再按上：回控制条（没有就回展开按钮，再没有才回播放）
                                            focusUp = if (rowIdx == 0) {
                                                if (hasControlAnchor) controlsFocusRequester
                                                else if (descTruncated) descFocusRequester
                                                else playFocusRequester
                                            } else null,
                                            // 末行再按下：落到选集下面的主演头像行；没有主演就留在原地
                                            focusDown = if (rowIdx == epRows.lastIndex) {
                                                if (hasCast) castFocusRequesters.first() else FocusRequester.Cancel
                                            } else null,
                                            onClick = {
                                                TvNavHistory.playedEpisodeId = ep.id
                                                onPlay(ep.id)
                                            }
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

                        // ---------- 主演：排在选集之后 ----------
                        // 原来夹在简介与选集之间，主页选集前总要先路过一排头像；
                        // 挪到选集下面后它是整页最后一层：选集末行按下即落到这里。
                        if (hasCast) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "主演",
                                color = TvText.Muted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            TvCastRow(
                                cast = tvCast,
                                requesters = castFocusRequesters,
                                focusUp = castUpTarget,
                                focusDown = castDownTarget
                            )
                        } else if (actorNames.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            TvInfoLine("主演", actorNames.joinToString(" / "))
                        }
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

/** 电视端主演行用的轻量数据结构，避免直接依赖 Emby / TMDB 两套 Person 类型 */
private data class TvCast(val name: String, val role: String, val imageUrl: String)

/**
 * 加载期的整页骨架：形状对着真实排版抄（首屏是左 72% 的标题块，内容区是整宽 + 40dp 左轴），
 * 所有格子共用同一条横扫整屏的光带。
 *
 * 为什么这里可以动、首页骨架却必须是静态：首页是几十张卡各挂一条无限动画，
 * 每帧重算几十个渐变还要和滚动抢填充率；这里全页只有下面这一条 transition，
 * 每块在 drawBehind 里填一个矩形渐变，而且首屏真正在场的只有 5 组块
 * （简介往后都在 0.55 屏高之下，屏幕外不参与绘制），加载一结束整个骨架就摘掉。
 */
@Composable
private fun TvDetailSkeleton() {
    val density = LocalDensity.current
    val pageW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val bandW = pageW * 0.34f
    val transition = rememberInfiniteTransition(label = "tv_detail_skeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart),
        label = "tv_detail_band"
    )
    // 光带左端在「整屏坐标系」里的位置：从屏幕外左侧进、屏幕外右侧出，一轮 1.25s
    val bandX = -bandW + phase * (pageW + bandW)
    // 底色取 SurfaceHi → SurfaceRaise 的中段，高光在其上再叠 8.5% 白
    val base = lerp(AppColors.SurfaceHi, AppColors.SurfaceRaise, 0.45f)
    val hi = lerp(base, Color.White, 0.085f)

    Column(Modifier.fillMaxWidth()) {
        // ---- 首屏：标题 / 收藏 / 元信息 / 徽章 / 按钮（间距与真实代码同档）----
        Column(
            Modifier
                .fillMaxWidth(0.72f)
                .padding(start = TvPagePadding, end = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Skel(Modifier.width(360.dp).height(72.dp), bandX, bandW, base, hi, Radius.Sm)
                Spacer(Modifier.width(14.dp))
                Skel(Modifier.size(40.dp), bandX, bandW, base, hi, CircleShape)
            }
            Spacer(Modifier.height(8.dp))
            Skel(Modifier.width(220.dp).height(18.dp), bandX, bandW, base, hi, Radius.Pill)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Skel(Modifier.width(64.dp).height(26.dp), bandX, bandW, base, hi, Radius.Pill)
                Skel(Modifier.width(52.dp).height(26.dp), bandX, bandW, base, hi, Radius.Pill)
                Skel(Modifier.width(74.dp).height(26.dp), bandX, bandW, base, hi, Radius.Pill)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Skel(Modifier.width(176.dp).height(58.dp), bandX, bandW, base, hi, Radius.Pill)
                Skel(Modifier.width(132.dp).height(52.dp), bandX, bandW, base, hi, Radius.Pill)
            }
        }

        // ---- 屏幕外那一半：简介 / 主演 / 选集，尺度照真实内容排 ----
        Column(Modifier.padding(start = TvPagePadding, end = TvPagePadding)) {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(620, 600, 560, 430).forEach { w ->
                    Skel(Modifier.width(w.dp).height(16.dp), bandX, bandW, base, hi, Radius.Sm)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(6) { Skel(Modifier.size(56.dp), bandX, bandW, base, hi, CircleShape) }
            }
            Spacer(Modifier.height(16.dp))
            Skel(Modifier.width(220.dp).height(18.dp), bandX, bandW, base, hi, Radius.Pill)
            Spacer(Modifier.height(10.dp))
            repeat(2) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    repeat(TV_GRID_COLS) {
                        Skel(Modifier.weight(1f).height(64.dp), bandX, bandW, base, hi, Radius.Md)
                    }
                }
            }
        }
    }
}

/**
 * 骨架上的一块。
 *
 * 光带是按「整屏 x」算的，而 drawBehind 用的是块自己的局部坐标，所以每块要把光带位置
 * 平移回自己这儿 —— 这一步就是「一道光横扫整屏」和「每块各自亮」的全部区别。
 * 左边界在 onGloballyPositioned 里取一次、存进普通数组（不是 State）：每帧绘制时读它，
 * 不需要为位置变化触发重组。
 */
@Composable
private fun Skel(
    modifier: Modifier,
    bandX: Float,
    bandW: Float,
    base: Color,
    hi: Color,
    shape: Shape
) {
    val rootX = remember { FloatArray(1) }
    Box(
        modifier
            .onGloballyPositioned { rootX[0] = it.positionInRoot().x }
            .clip(shape)
            .drawBehind {
                val start = bandX - rootX[0]
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(base, hi, base),
                        start = Offset(start, 0f),
                        end = Offset(start + bandW, 0f)
                    )
                )
            }
    )
}

/**
 * 主演头像行：圆形头像 + 姓名 + 角色，每颗头像是一个焦点站。
 *
 * 为什么必须能聚焦：电视上没有滚轮，焦点走不到的内容就等于看不到。Emby / TMDB 是唯一
 * 带演员照片的两路数据（CatVod 协议只有姓名），所以这一行只在有照片时出现，
 * 同时充当「电影详情页」下键链的落点。
 *
 * 头像 56dp、一行 6 个：和原来「简介 → 导演 → 主演 → 选集首行同屏」的量级保持一致，
 * 不会把选集挤出屏幕；聚焦时只放大 8% 并描一圈白边，不改变格子宽度，
 * 所以整行不会因为聚焦而移位（规范 R2）。
 */
@Composable
private fun TvCastRow(
    cast: List<TvCast>,
    requesters: List<FocusRequester>,
    focusUp: FocusRequester,
    focusDown: FocusRequester
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        cast.forEachIndexed { index, person ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val avatarShape = remember { CircleShape }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .zIndex(if (focused) 1f else 0f)
                    .then(
                        requesters.getOrNull(index)?.let { Modifier.focusRequester(it) } ?: Modifier
                    )
                    .focusable(interactionSource = interaction)
                    .focusProperties { up = focusUp; down = focusDown }
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(avatarShape)
                        .background(AppColors.SurfaceHi)
                        .then(if (focused) Modifier.border(2.dp, Color.White, avatarShape) else Modifier),
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
                        // 个别演员没有 PrimaryImageTag：用姓名首字顶上，避免出现一串空灰圆
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

/** 每页显示的分集数：40 集一页，超出用翻页按钮切换 */
private const val TV_EPS_PAGE = 40

/** 取证日志的 tag：进页抢焦与滚动位置都记在这里 */
private const val FOCUS_TAG = "TvDetailFocus"

/** 分集网格列数：分集名是源给的原名（如 "[5.4GB]E01.mkv【…】"），
 *  10 列时一格不到 90dp，名字全被省略号吃掉；4 列后单格够宽，集名能看清。 */
private const val TV_GRID_COLS = 4

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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val label = episode.episodeLabel()
    val watched = episode.positionMs > 5_000L
    val progress = if (episode.durationMs > 0L) {
        (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier
            .zIndex(if (focused) 1f else 0f)
            // 焦点路由必须排在焦点目标之前，才挂得到这个焦点目标上
            .focusProperties {
                if (focusUp != null) up = focusUp
                if (focusDown != null) down = focusDown
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // clickable 必须排在 focusable 外面，两者共用一个 interactionSource。
            // 反过来写（focusable().clickable()）焦点会落在外面那颗裸 focusable 上，
            // 它没有点击/按键处理，OK 键沿父链往上冒泡、永远到不了里层 clickable ——
            // 表现就是「选中了剧集按 OK 没反应，播放器 Activity 压根没被启动」。
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interaction)
            // 详情页不放大获焦的卡片：放大一改焦点矩形，verticalScroll 的
            // bring-into-view 每次都补一点滚动，左右切集时整屏跟着上下抖。
            // 焦点只用边框表示（品牌色 2dp），尺寸一动都不动。
            .clip(Radius.Md)
            .background(if (watched) AppColors.Brand.copy(alpha = 0.18f) else AppColors.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) AppColors.Brand else AppColors.SurfaceHi,
                shape = Radius.Md
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
                .clip(Radius.Pill)
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
 *
 * 分辨率 / HDR / 编码 / 音轨三条路都能给：Emby 来自 MediaSources，
 * TVBox 来自详情入库时的文件名推测 + 播放期真实轨道回写。
 * 源自己给的信息（[sourceInfo]：更新状态、集数、线路数）与画质徽章并存，
 * 画质在前、源信息在后；两边都没有就整行不出现。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatBadgesRow(
    tmdbEnabled: Boolean,
    resolution: String,
    hdr: String,
    codec: String,
    audio: String,
    sourceInfo: List<String>
) {
    val badges = mutableListOf<Pair<String, Color>>()
    if (tmdbEnabled) badges.add("TMDB" to AppColors.Brand)
    if (resolution.isNotBlank()) badges.add(resolution to Color(0xFF01B4E4))
    if (hdr.isNotBlank()) {
        val bg = when (hdr) {
            "Dolby Vision" -> Color(0xFF6D4C41)
            "HDR10+" -> Color(0xFFE50914)
            "HDR10", "HDR" -> Color(0xFF2E7D32)
            "HLG" -> Color(0xFFF57C00)
            else -> Color(0xFF455A64)
        }
        badges.add(hdr to bg)
    }
    if (codec.isNotBlank()) badges.add(codec.uppercase() to Color(0xFF455A64))
    if (audio.isNotBlank() && audio != "Dolby Digital") badges.add(audio to Color(0xFF6D4C41))
    sourceInfo.forEach { badges.add(it to Color(0xFF455A64)) }
    if (badges.isEmpty()) return

    // 徽章是电视上最小的一类字，11sp 在三米外只剩一个形状；抬到 14sp 并允许换行，
    // 免得「共 xx 集」「x 条线路」这类右侧信息被裁掉。手机端仍用原来的小档。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        badges.forEach { (label, bg) ->
            TextBadge(label, bg, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp)
        }
    }
}

