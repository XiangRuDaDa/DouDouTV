package com.tabbit.myvidhub.ui.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tabbit.myvidhub.ui.ReturnSpot
import com.tabbit.myvidhub.ui.StandTarget
import com.tabbit.myvidhub.ui.cardStand
import com.tabbit.myvidhub.ui.components.PosterArt
import com.tabbit.myvidhub.ui.components.PosterFallback
import com.tabbit.myvidhub.ui.components.ShimmerCard
import com.tabbit.myvidhub.ui.components.continueTitle
import com.tabbit.myvidhub.ui.components.episodeLabel
import com.tabbit.myvidhub.ui.listState
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.themeBackdrop
import com.tabbit.myvidhub.ui.theme.themeSeamFade
import com.tabbit.myvidhub.ui.theme.TvText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/** [ReturnSpot] 的页面标识：首页的落点记录、页面滚动、各行的滚动都挂在它下面 */
private const val TvHomeKey = "tv_home"

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
    // 收藏是全库范围，不跟首页来源/线路过滤走（与手机端同一份数据源）
    val favorites by vm.favorites.collectAsStateWithLifecycle()

    // 首页内容按选中的来源/站点（线路）过滤；未选择来源时原样返回
    val filteredLibrary = remember(library, vm.homeSourceId, vm.homeSiteKey) { vm.filterForHome(library) }

    // 轮播取当前线路 homeContent 的前 5 条（源站推荐顺序），来源与线路永远一致；
    // 还没拉到线路数据（或选的是「全部站点」）时才回退本地库。
    val heroItems = remember(filteredLibrary, vm.siteHomeItems) {
        val fromSite = vm.siteHomeItems
            .filter { it.posterUrl.isNotBlank() || it.backdropUrl.isNotBlank() }
            .distinctBy { it.id }
        if (fromSite.isNotEmpty()) {
            fromSite.take(5)
        } else {
            val primary = filteredLibrary.filter { it.backdropUrl.isNotBlank() }
            if (primary.size >= 3) primary.take(5)
            else (primary + filteredLibrary.filter { it.posterUrl.isNotBlank() })
                .distinctBy { it.id }
                .take(5)
        }
    }

    // 继续观看：记录取全库（vm.continueWatching 直接读 positionMs>5s 的 MOVIE/EPISODE 行，
    // observeLibrary() 只有 MOVIE/SERIES，进度却写在 EPISODE 行上、剧集壳永远不带进度），
    // 但只展示当前来源/线路那一份 —— 换来源就看到另一份追看记录。
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val continueItems = remember(continueWatching, vm.homeSourceId, vm.homeSiteKey) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L
        vm.filterForHome(continueWatching).filter { it.lastPlayedAt > cutoff }.take(8)
    }

    var heroIndex by remember { mutableStateOf(0) }
    /**
     * 当前这张已停留的进度（0→1）。用帧时钟而不是 delay：
     * 进度条和换页读同一个值，走到 1 才翻页，两者不会出现「线还差一截就跳下一张」。
     */
    val heroProgress = remember { mutableFloatStateOf(0f) }
    val hero = heroItems.getOrNull(heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0)))

    val listState = ReturnSpot.listState(TvHomeKey)
    val scope = rememberCoroutineScope()
    // 轮播进度按 50ms 推进而不是逐帧：进度条宽度 120dp，50ms 只走 0.6dp，看不出台阶；
    // 但静止时 GPU 的渲染频率从 60 次/秒降到 20 次/秒。列表滚动中不推进，
    // 否则滚到一半整屏换图，看起来就是「屏幕刷新了一下」。
    LaunchedEffect(heroItems.size) {
        if (heroItems.size > 1) {
            var elapsedMs = 0L
            while (true) {
                delay(50L)
                if (listState.isScrollInProgress) continue
                elapsedMs += 50L
                heroProgress.floatValue = (elapsedMs / (HERO_DWELL_SECONDS * 1000f)).coerceIn(0f, 1f)
                if (heroProgress.floatValue >= 1f) {
                    heroIndex = (heroIndex + 1) % heroItems.size
                    elapsedMs = 0L
                }
            }
        } else {
            heroProgress.floatValue = 0f
        }
    }
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
    // 顶栏是否还在（首屏 Hero 可见）。用 derivedStateOf 只观察 firstVisibleItemIndex 这个离散值：
    // 订阅落在计算块里，滚动时顶栏子树只在真正跨过首屏那一次才重组，不会像直接读连续量那样把整页拖进滚动。
    val topBarVisible = remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // Ken Burns 只在列表静止时跑：滚动帧本身就吃紧，再叠一层全屏缩放重绘必然掉帧。
    // isScrollInProgress 只在滚动开始/结束各变一次，读到它的只有 hero item 这一块。
    val heroZoomPaused by remember { derivedStateOf { listState.isScrollInProgress } }

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
    // 「继续观看」卡片的操作菜单：长按 OK / 按 Menu 唤出。
    // 连同卡片自己的 FocusRequester 一起存，菜单关掉后要把焦点还回那张卡。
    var continueMenu by remember { mutableStateOf<Pair<MediaEntity, FocusRequester>?>(null) }

    // 弹窗关闭后把焦点交还给触发它的卡片：否则整页焦点被清空，
    // 用户要重按一次方向键才能重新出现高亮。
    val restoreCardFocus: (FocusRequester) -> Unit = { requester ->
        scope.launch {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }

    // 焦点 requester 链：Hero 播放按钮 ↔ TopBar 来源按钮。
    // TopBar 在 Box 顶层悬浮，和 LazyColumn 不在同一焦点子树 → 落点必须显式给（规范 R1），
    // 不能再靠默认几何搜索：它只在「当前视口内的可聚焦节点」里找，而 Hero 空态整块不可聚焦，
    // 于是出现「顶栏按 ↓ 没反应」「详情键按 ↑ 没反应」。
    val syncButtonFocus = remember { FocusRequester() }
    val sourcePillFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }
    // Hero 空态（没有轮播数据）里的第一颗 CTA：顶栏的 ↓ 在没有播放按钮时落到它
    val heroEmptyFocus = remember { FocusRequester() }

    /**
     * 「离开时站在那张卡，回来还站在那张卡」的交接点：各行的卡片在拿到焦点、
     * 或重建后确认自己就是落点时，把自己的 requester 交进来。
     */
    val standTarget = remember { StandTarget() }

    /**
     * 焦点来到非卡片元件（顶栏、Hero 两颗按钮）时清掉卡片落点。
     * 卡片落点是唯一一个跨组合存活的记录，不清的话「站在 Hero 进详情再返回」
     * 会被上一次路过的那张卡抢走焦点，回到一个用户根本没站过的地方。
     */
    val forgetCardStand: () -> Unit = {
        ReturnSpot.saveStand(TvHomeKey, "")
        standTarget.requester = null
    }

    // 顶栏不再常驻：滚出首屏后会完全淡出。一旦焦点落到顶栏（遥控器按上键），
    // 把列表回滚到顶部，让顶栏边淡入边进入视野，避免「焦点在看不见的按钮上」。
    val revealTopBar: () -> Unit = {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    /**
     * Hero（含空态 CTA）按 ↑ 交棒给顶栏。
     *
     * 这里必须走按键拦截 + tvMoveFocus，而不是 focusProperties { up = sourcePillFocus }：
     * 顶栏滚出首屏就不在组合里了（对应规范 R8），requester 没挂载时 focusProperties 会把
     * 异常抛到焦点事务里。先 revealTopBar 让顶栏回到组合，等一帧再跳，跳不动就隔一小段重试一次 ——
     * 两次都指向同一个落点，不是「跳完再校验一遍状态位」那种重复跳转（规范 R3）。
     */
    val goTopBar: () -> Unit = goTopBar@{
        revealTopBar()
        scope.launch {
            withFrameNanos { }
            if (tvMoveFocus(sourcePillFocus, syncButtonFocus)) return@launch
            delay(180)
            tvMoveFocus(sourcePillFocus, syncButtonFocus)
        }
    }

    // 进页与「每次界面回到前台」都要把焦点站好：从播放器退回来时焦点是空的，
    // 用户看到的就是「没有选中框，按一下方向键才出现」。
    val resumeTick = tvResumeTick()
    // key 带上「Hero 有没有数据」：库数据晚到时抢焦目标从空态 CTA 换成播放按钮，
    // 否则首屏会停在没人抢焦的状态（原来只在首次组合抢一次，那时通常还没数据）。
    // 离开时站在某张卡片上 → 先抢那张卡（requester 由卡片交进 [standTarget]），卡片没挂上
    // （行不在恢复后的视口里）就按 R3 退回入口锚点 Hero，不会出现「没人持焦、按一下才亮」。
    tvClaimFocusOnEnter(
        tick = listOf(resumeTick, hero != null),
        anchorsProvider = {
            val heroAnchor = if (hero != null) heroPlayFocus else heroEmptyFocus
            listOfNotNull(standTarget.requester, heroAnchor)
        }
    )

    // 顶层不再拦 ↑/↓：交棒改由 Hero 按钮自己的按键拦截（goTopBar）和顶栏的显式 down 承担。
    // 原来这里用 heroFocused / topBarFocused 两个页面级布尔位判断「焦点在哪颗按钮上」，
    // 一位表达不了多颗按钮，结果就是播放键 ↑ 有效、旁边的详情键 ↑ 无效（规范 R2）。
    Box(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
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
            item(key = "hero") {
                if (hero != null) {
                    TvHero(
                        item = hero,
                        heroIndex = heroIndex,
                        heroTotal = heroItems.size,
                        progress = heroProgress,
                        paused = heroZoomPaused,
                        playFocusRequester = heroPlayFocus,
                        onPlay = { onPlay(hero) },
                        onDetail = { onDetail(hero.id) },
                        onNavigateUp = goTopBar,
                        onButtonFocus = forgetCardStand
                    )
                } else {
                    TvHeroEmpty(
                        libraryIsEmpty = library.isEmpty(),
                        hasCategories = vm.homeCategories.isNotEmpty(),
                        error = vm.homeSitesError,
                        scanning = vm.scanning,
                        firstFocusRequester = heroEmptyFocus,
                        onNavigateUp = goTopBar,
                        onAddSource = onSources,
                        onRefresh = { vm.refreshHome() },
                        onButtonFocus = forgetCardStand
                    )
                }
            }
            // 选了「只有分类没首页影片」的线路时不提示空：下面的分类入口本身就是内容入口
            if (hero != null) {
                // 收色带：接缝正好落在 Hero 底边上时，无论把渐隐终点配成什么色都会露出一条线，
                // 所以把接缝挪进这条渐变里 —— 顶边与 Hero 底边同色（看不出发界），底边完全透明（露出页面背景）
                item(key = "hero_seam") { TvHeroSeamFade() }
            }

            // ---------- 我的收藏 ----------
            // 收藏是用户主动留下的内容，优先级高于「继续观看」，双端都排在它上面
            if (favorites.isNotEmpty()) {
                item(key = "fav_title") { TvSectionTitle("★ 我的收藏") }
                item(key = "fav_row") {
                    TvPosterRow(items = favorites, onDetail = onDetail, rowKey = "fav", stand = standTarget)
                }
            }

            // ---------- 继续观看 ----------
            if (continueItems.isNotEmpty()) {
                item(key = "continue_title") { TvSectionTitle("▶ 继续观看") { } }
                item(key = "continue_row") {
                    TvContinueRow(
                        items = continueItems,
                        rowKey = "continue",
                        stand = standTarget,
                        onDetail = onDetail,
                        onPlay = onPlay,
                        onMenu = { media, cardFocus -> continueMenu = media to cardFocus }
                    )
                }
            }

            // ---------- TVBox 动态分类预览 sections ----------
            // 电视端去掉了「分类 chip 横条」（单独占一行、把海报往下挤），
            // 分类内容直接以海报行呈现，遥控器上下就是换分类。
            val homeCategories = vm.homeCategories
            homeCategories.take(7).forEachIndexed { index, cat ->
                // 首屏只预载前 2 个分类（视口内可见的那些），其余由 cat_row 在真正滚到可见时触发，
                // 避免一进首页就把 7 个分类的请求全打出去、和首屏渲染抢网络和 CPU。
                // 预载挂在标题行上而不是单开一个 0 高 item：item 越少，分类陆续插入时的索引抖动越小。
                item(key = "cat_title_${cat.typeId}") {
                    if (index < 2) {
                        // homeRefreshEpoch 变化时重新触发一次，否则刷新后分类行仍是旧缓存
                        LaunchedEffect(cat.typeId, vm.homeRefreshEpoch) { vm.ensureCategoryPreview(cat.typeId) }
                    }
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
                    TvCategoryPreview(
                        typeId = cat.typeId,
                        vm = vm,
                        stand = standTarget,
                        onDetail = onDetail
                    )
                }
            }

            // ---------- 动态分区：根据 filteredLibrary 实际存在的 kind 渲染 ----------
            gallerySections.forEach { section ->
                item(key = "sec_${section.title}") {
                    TvSectionTitle(section.title) { TvMoreButton { onGenre(section.kind, section.genre) } }
                }
                item(key = "sec_row_${section.title}") {
                    TvPosterRow(
                        items = section.items,
                        onDetail = onDetail,
                        rowKey = "sec_${section.title}",
                        stand = standTarget
                    )
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
            topBarVisible = topBarVisible,
            sourceName = displaySourceName,
            sourceKind = selectedSource?.kind,
            siteName = displaySiteName,
            scanning = vm.scanning,
            syncFocusRequester = syncButtonFocus,
            sourceFocusRequester = sourcePillFocus,
            // 顶栏的 ↓ 显式落到 Hero 的可聚焦元件上：有轮播是播放按钮，没轮播是空态第一颗 CTA。
            // 这两个 requester 在顶栏可见时（firstVisibleItemIndex == 0）一定挂得上。
            belowFocusRequester = if (hero != null) heroPlayFocus else heroEmptyFocus,
            // 焦点进顶栏就先把列表滚回顶部，保证高亮看得见（这是视觉补偿，不是跳转校验）
            onGainFocus = {
                forgetCardStand()
                revealTopBar()
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

        // ---------- 继续观看卡片的操作菜单（长按 OK / Menu 键唤出） ----------
        continueMenu?.let { (media, cardFocus) ->
            TvContinueActionDialog(
                item = media,
                onPlay = {
                    continueMenu = null
                    onPlay(media)
                },
                onDetail = {
                    continueMenu = null
                    onDetail(media.id)
                },
                onClear = {
                    // 只归零进度，不删媒体行：条目立刻离开「继续观看」，片子还在库里
                    vm.clearWatchProgress(media.id)
                    continueMenu = null
                },
                onDismiss = {
                    continueMenu = null
                    restoreCardFocus(cardFocus)
                }
            )
        }
    }
}

/** 顶部入口条：SpaceBetween 布局 — 左侧来源链 PillButton，右侧操作组。
 *  每颗可聚焦元件都显式声明落点（规范 R1）：↑ = Cancel（顶栏之上没有东西，别让焦点跳飞出屏），
 *  ↓ = belowFocusRequester（Hero 的播放按钮或空态 CTA）。
 *  gain focus 时通过 onGainFocus 通知顶层把列表滚回顶部，保证高亮一定看得见。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvTopBar(
    alphaProvider: () -> Float,
    topBarVisible: State<Boolean>,
    sourceName: String,
    sourceKind: SourceKind?,
    siteName: String?,
    syncFocusRequester: FocusRequester,
    sourceFocusRequester: FocusRequester,
    belowFocusRequester: FocusRequester,
    onGainFocus: () -> Unit,
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
    val markTopBarFocus: (FocusState) -> Unit = { if (it.isFocused) onGainFocus() }
    val topBarRoute = Modifier.tvFocusRoute(up = FocusRequester.Cancel, down = belowFocusRequester)

    // 滚过首屏后顶栏已经完全透明，但 Compose 的焦点搜索不看透明度，从下面某行按上键仍会把
    // 高亮交给这排看不见按钮（compose 1.6 也还没有 focusEnabled 可以关掉）。这里直接从组合里摘掉，
    // 上键就只会在列表内部走。顶栏一旦抢到焦点，顶层的 revealTopBar 会先把列表滚回顶部，
    // 于是本条件重新成立、顶栏再挂回来，Hero ↑ 交棒链路不受影响。
    if (!topBarVisible.value) return

    Row(
        Modifier
            .fillMaxWidth()
            // alpha 在 draw phase 从 listState 计算 —— 不触发 composition 重组，滚动流畅
            .graphicsLayer { this.alpha = alphaProvider() }
            .padding(start = TvPagePadding, end = TvPagePadding, top = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- 左：来源链 PillButton ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isEmpty) {
                TvPillButton(
                    text = "＋ 添加来源", primary = true, size = TvButtonSize.Sm, onClick = onSources,
                    modifier = topBarRoute
                        .focusRequester(sourceFocusRequester)
                        .onFocusChanged(markTopBarFocus)
                )
            } else {
                TvSourceChainPill(
                    label = if (isTvBox) "来源" else "Emby",
                    value = sourceName,
                    kind = sourceKind,
                    onClick = onHomeSource,
                    modifier = topBarRoute.focusRequester(sourceFocusRequester),
                    onFocusChanged = markTopBarFocus
                )
                if (onHomeSite != null && !siteName.isNullOrBlank()) {
                    Text("›", color = TvText.Muted, style = MaterialTheme.typography.titleMedium)
                    TvSourceChainPill(
                        label = "线路",
                        value = siteName,
                        kind = null,
                        onClick = onHomeSite,
                        modifier = topBarRoute,
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
                    "刷新", primary = false, size = TvButtonSize.Sm, onClick = onRefresh,
                    modifier = topBarRoute
                        .focusRequester(syncFocusRequester)
                        .onFocusChanged(markTopBarFocus)
                )
            }
            TvPillButton("搜索", primary = false, size = TvButtonSize.Sm, onClick = onSearch,
                modifier = topBarRoute.onFocusChanged(markTopBarFocus))
            TvPillButton("来源", primary = false, size = TvButtonSize.Sm, onClick = onSources,
                modifier = topBarRoute.onFocusChanged(markTopBarFocus))
            TvPillButton("设置", primary = false, size = TvButtonSize.Sm, onClick = onSettings,
                modifier = topBarRoute.onFocusChanged(markTopBarFocus))
        }
    }
}

/** 来源链 PillButton — label 用品牌色，value 用白色；来源名 ellipsis 到 ~16 字。
 *  落点由调用方通过 modifier 传进来（顶栏统一用 tvFocusRoute），焦点状态只用于视觉与「滚回顶部」。 */
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
            // 垂直内边距跟顶栏的 Sm 档对齐（6dp）：两颗按钮同高，一行才不会看着错位
            .padding(horizontal = 14.dp, vertical = 6.dp),
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

/** 一张精选在 Hero 上停留的秒数；右下进度条按这个时长走满，走满即换页。 */
private const val HERO_DWELL_SECONDS = 9f

/**
 * Hero 与页面背景之间的收色带：把两者共用的那条接缝挪进渐变中段。
 * 画法与手机端首页的轮播同款，见 [themeSeamFade]。
 *
 * 高度就是 Hero 与下面第一行之间的额外空白：这条带子本身是纯 Bg 淡出到透明，
 * 底下并没有内容，40dp 时它和标题行的上间距叠起来，把轮播和分类推得很开，收到 20dp。
 */
@Composable
private fun TvHeroSeamFade(modifier: Modifier = Modifier) {
    Box(modifier.themeSeamFade(20.dp))
}

/**
 * Hero 高度。
 *
 * Netflix 的 billboard 约占视口高的 55~60%，但那家的标题排在画面**下方**；
 * 这里标题、元信息、按钮全压在画面里（logo 就占 110dp），所以要留出内容高度。
 * 电视的 dp 视口往往只有 540dp 高（1080p 按 2x density 折算），纯按比例算出来只有
 * 310dp 左右，底部内容块会被压缩 —— 播放按钮会瘦成一条横线。
 * 因此按比例取，同时夹在 430~620dp 之间：小屏不至于挤内容，大屏也不会顶掉整个首屏。
 */
@Composable
private fun tvHeroHeight(): Dp {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    return (screenH * 0.72f).coerceIn(430.dp, 620.dp)
}

/** 主视觉：满宽横版图，左下压标题与操作，底部渐隐接入页面底色；轮播指示器在右下。
 *  Hero 从屏幕最顶端开始铺满，顶栏透明悬浮其上。
 *
 *  动效按 Netflix billboard 的量级来：
 *    · 背景换页用淡入覆盖（600ms），不是硬切；
 *    · 标题/简介随内容淡入淡出，但按钮行留在动画之外 —— 同一 FocusRequester
 *      若被两份实例同时 attach，焦点落点会变得不确定；
 *    · 背景持续做极慢的 Ken Burns 缩放（1.02→1.075 再倒回），
 *      缩放值只在 graphicsLayer 的 lambda 里读取，走 draw 阶段，画面在动但 Composable 不重组；
 *    · 缩放后的画面必须裁在 Hero 边界内，否则会溢出盖住下面一行和渐隐遮罩。 */
@Composable
private fun TvHero(
    item: MediaEntity,
    heroIndex: Int,
    heroTotal: Int,
    progress: State<Float>,
    /** 列表正在滚动：暂停 Ken Burns，别让全屏缩放和滚动抢同一帧的绘制时间 */
    paused: Boolean,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    /** ↑ 交棒给顶栏来源按钮；两颗按钮都要，之前只有播放键接了 ↑，详情键按上没反应 */
    onNavigateUp: () -> Unit,
    /** 两颗按钮任一颗拿到焦点：首页已经不站在卡片上了 */
    onButtonFocus: () -> Unit
) {
    val imageUrl = item.backdropUrl.ifBlank { item.posterUrl }

    // 背景换页：旧图留在底层不动，新图在上层淡入盖过去。
    // 没有直接用 Crossfade —— 它会把两张图同时压到半透明，中间那一瞬露出黑色页面底色，
    // 整块画面发灰，底部渐隐也跟着破相。
    val currentUrl = remember { mutableStateOf(imageUrl) }
    val outgoingUrl = remember { mutableStateOf<String?>(null) }
    val backdropAlpha = remember { Animatable(1f) }
    LaunchedEffect(imageUrl) {
        if (imageUrl != currentUrl.value) {
            outgoingUrl.value = currentUrl.value
            currentUrl.value = imageUrl
            backdropAlpha.snapTo(0f)
            backdropAlpha.animateTo(1f, tween(600))
            outgoingUrl.value = null
        }
    }

    // Ken Burns：只用 Animatable 而不用 rememberInfiniteTransition ——
    // 后者没法暂停。列表一滚动就停，滚动时不再每帧缩放整屏大图；
    // 缩放值只在 graphicsLayer 的 lambda 里读取，走 draw 阶段，Composable 不重组。
    val zoom = remember { Animatable(1.02f) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val legMs = (HERO_DWELL_SECONDS * 2600).toInt()
        while (true) {
            zoom.animateTo(1.075f, tween(legMs, easing = LinearEasing))
            zoom.animateTo(1.02f, tween(legMs, easing = LinearEasing))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(tvHeroHeight())
    ) {
        // 背景层：放大后的图要裁在 Hero 里，不能溢出到下方内容区
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            val old = outgoingUrl.value
            if (old != null) {
                AsyncImage(
                    model = old,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                        }
                )
            }
            AsyncImage(
                model = currentUrl.value,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = backdropAlpha.value
                        scaleX = zoom.value
                        scaleY = zoom.value
                    }
            )
        }

        // 渐变遮罩 —— remember 缓存避免每次重绘都重建 Brush
        val horizVignette = remember {
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.88f),
                0.45f to Color.Black.copy(alpha = 0.40f),
                1f to Color.Transparent
            )
        }
        val bottomVignette = remember {
            // 暗化的起点从 0.28 后移到 0.45：原来画面刚过中线就开始压黑，整块下半截糊成一片，
            // 看着不像渐隐而像被截断。中段（0.45~0.82）只铺到四成透明度，画面还认得出；
            // 左半边另有 horizVignette 的 0.88 黑压着，两片叠加后标题/按钮的对比度不受影响。
            // 0.96 起收到不透明纯色，底边留出一条横贯整宽的纯色着陆带 ——
            // 下面的收色带要跟它接，接的必须是一条确定的色，不能是还在渐变的段。
            // 收色仍然是纯 Bg：与下方背景的衔接交给 TvHeroSeamFade，不在这里配品牌色 ——
            // 页面背景横着是不均匀的（左浓右淡），配出来的色只在那一个点准，这就是上一版还留条黑线的原因。
            Brush.verticalGradient(
                0.45f to Color.Transparent,
                0.66f to AppColors.Bg.copy(alpha = 0.14f),
                0.82f to AppColors.Bg.copy(alpha = 0.42f),
                0.92f to AppColors.Bg.copy(alpha = 0.80f),
                0.96f to AppColors.Bg,
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
                .padding(start = TvPagePadding, end = 320.dp, bottom = 30.dp)
        ) {
            // 文字块换页时淡出旧、淡入新，底边对齐保证不会上下跳
            AnimatedContent(
                targetState = item,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(150))
                },
                contentAlignment = Alignment.BottomStart,
                label = "heroText"
            ) { current ->
                Column {
                    if (current.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = current.logoUrl,
                            contentDescription = current.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(110.dp).widthIn(max = 520.dp)
                        )
                    } else {
                        Text(
                            current.title,
                            color = TvText.Title,
                            style = MaterialTheme.typography.displayLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    TvMetaRow(current)
                }
            }
            Spacer(Modifier.height(18.dp))
            // Hero 的两个按钮走最大一档：它们旁边是 44sp 的片名，
            // 再用顶栏那一档会显得缩在画面角落里。
            // ↑ 交棒挂在按钮自己身上（规范 R2），两颗都接 ——
            // 之前是顶层看 heroFocused 这个页面级布尔位判断，只有播放键喂得动它，
            // 详情键按 ↑ 就什么都不发生。
            val heroUpKey: Modifier = Modifier
                .onFocusChanged { if (it.hasFocus) onButtonFocus() }
                .onPreviewKeyEvent { e ->
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    ) {
                        onNavigateUp()
                        true
                    } else false
                }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPillButton(
                    text = if (item.positionMs > 5_000L) "▶ 继续播放" else "▶ 播放",
                    modifier = heroUpKey.focusRequester(playFocusRequester),
                    primary = true,
                    size = TvButtonSize.Lg,
                    onClick = onPlay
                )
                TvPillButton(
                    text = "ⓘ 详情",
                    modifier = heroUpKey,
                    primary = false,
                    size = TvButtonSize.Lg,
                    onClick = onDetail
                )
            }
        }

        // 轮播进度（右下）：序号 + 一条细进度线，替代圆点指示器
        if (heroTotal > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = TvPagePadding, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${heroIndex + 1} / $heroTotal",
                    color = TvText.Muted,
                    style = MaterialTheme.typography.labelMedium
                )
                // 品牌色在组合阶段取好：drawBehind 不是组合式上下文，读不了 CompositionLocal
                val brandColor = AppColors.Brand
                Box(
                    Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TvText.Hairline)
                        // 在 draw 阶段读 State：进度每秒变几十次，只重画这条线，不重组整个 Hero
                        .drawBehind {
                            drawRect(
                                color = brandColor,
                                size = Size(size.width * progress.value, size.height)
                            )
                        }
                )
            }
        }
    }
}

/** 空态主视觉：轮播区常驻，但没有可推荐内容时占位。
 *  高度走同一个 [tvHeroHeight]，避免首页在「加载中 / 空库」之间上下跳版。 */
@Composable
private fun TvHeroEmpty(
    libraryIsEmpty: Boolean,
    hasCategories: Boolean,
    error: String?,
    scanning: Boolean,
    firstFocusRequester: FocusRequester,
    onNavigateUp: () -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    /** CTA 拿到焦点：首页已经不站在卡片上了 */
    onButtonFocus: () -> Unit
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
            hint = "可以重新同步，或换一个来源/线路"
        }
        libraryIsEmpty -> {
            text = "还没有内容"
            hint = "先添加 Emby 或 TVBox 来源，再同步媒体库"
        }
        hasCategories -> {
            text = "暂无推荐内容"
            hint = "可从下方分类入口浏览"
        }
        else -> {
            text = "当前来源/站点暂无内容"
            hint = "重新同步，或换一个来源/线路"
        }
    }
    val bg = remember {
        Brush.verticalGradient(0f to AppColors.Surface, 0.7f to AppColors.Bg, 1f to AppColors.Bg)
    }

    /**
     * 空态必须自带可聚焦入口（规范 R6）。
     * 这块 Hero 高 430~620dp、整屏的八成，原来里面一个字都点不动：
     * 顶栏按 ↓ 时焦点在视口内找不到任何落点，就卡在工具栏上出不去。
     * 按钮行的位置与有内容时一致（左下、bottom 44dp），顶栏的 ↓ 正好落在这第一颗上。
     */
    val actions: List<Triple<String, Boolean, () -> Unit>> = when {
        libraryIsEmpty -> listOf(Triple("＋ 添加来源", true, onAddSource))
        scanning -> listOf(Triple("＋ 添加来源", false, onAddSource))
        else -> listOf(
            Triple("↻ 立即同步", true, onRefresh),
            Triple("＋ 切换来源", false, onAddSource)
        )
    }
    val heroUpKey: Modifier = Modifier
        .onFocusChanged { if (it.hasFocus) onButtonFocus() }
        .onPreviewKeyEvent { e ->
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
            ) {
                onNavigateUp()
                true
            } else false
        }

    Box(
        Modifier
            .fillMaxWidth()
            .height(tvHeroHeight())
            .background(bg)
    ) {
        TvEmptyState(
            text = text,
            hint = hint,
            modifier = Modifier.align(Alignment.Center)
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = TvPagePadding, bottom = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            actions.forEachIndexed { index, (label, primary, onClick) ->
                val focusModifier = if (index == 0) heroUpKey.focusRequester(firstFocusRequester) else heroUpKey
                TvPillButton(
                    text = label,
                    modifier = focusModifier,
                    primary = primary,
                    size = TvButtonSize.Lg,
                    onClick = onClick
                )
            }
        }
    }
}

/** 单个 TVBox 分类的海报行（含加载态）。数据在这里读取，避免首页整体跟着重组。 */
@Composable
private fun TvCategoryPreview(
    typeId: String,
    vm: LibraryViewModel,
    stand: StandTarget,
    onDetail: (Long) -> Unit
) {
    // 行真正进入组合（滚到可见）时才拉这一行：首屏只预载前 2 个分类，其余靠这里按需触发
    LaunchedEffect(typeId, vm.homeRefreshEpoch) { vm.ensureCategoryPreview(typeId) }
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
            rowKey = "cat_$typeId",
            stand = stand,
            onNearEnd = { vm.loadMoreCategoryPreview(typeId) }
        )
        // 还没开始加载（等 activeSite）或加载失败：也必须占住行高。
        // 什么都不画会让整行塌成 0 高，7 个分类叠成一片空白，
        // 遥控器按 ↓ 时焦点搜索越过空白直接落到页面底部 —— 就是「一按就跑到最下面」。
        else -> TvShimmerRow()
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
        // 底部留出真实卡片标题条的高度：骨架换成真卡片时行高不跳，
        // LazyColumn 不会因此重新定位，遥控器的位置也不跟着晃。
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 34.dp),
        contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 7.dp),
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
    onNearEnd: (() -> Unit)? = null,
    /** 这一行在 [ReturnSpot] 里的标识，同时用作横向滚动记录和卡片落点的一部分 */
    rowKey: String,
    /** 落点卡片 requester 的交接点，由首页传入 */
    stand: StandTarget
) {
    val rowState = ReturnSpot.listState("$TvHomeKey/$rowKey")
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
        contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        itemsIndexed(items, key = { _, it -> "tv_${it.id}" }, contentType = { _, _ -> "poster" }) { index, item ->
            val cardFocus = remember(item.id) { FocusRequester() }
            TvPosterCard(
                item = item,
                onClick = { onDetail(item.id) },
                width = cardWidth,
                // 行首卡放大时锚住左缘，否则它会越过 40dp 左边距，跟下面各行错位
                anchorStart = index == 0,
                modifier = ReturnSpot.cardStand(TvHomeKey, rowKey, item.id, stand, cardFocus)
            )
        }
    }
}

/** 继续观看横排 —— 横向缩略卡，显示进度条 + 集数 */
@Composable
private fun TvContinueRow(
    items: List<MediaEntity>,
    onDetail: (Long) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    onMenu: (MediaEntity, FocusRequester) -> Unit,
    rowKey: String,
    stand: StandTarget
) {
    val rowState = ReturnSpot.listState("$TvHomeKey/$rowKey")
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(items, key = { _, it -> "continue_${it.id}" }, contentType = { _, _ -> "continue" }) { index, item ->
            val progress = if (item.durationMs > 0L) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            // 同 TvPosterCard：放大动画的值留在 State 里，在 draw phase 读，避免每帧重组卡片
            val scale = animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "continue")
            val cardShape = remember { RoundedCornerShape(10.dp) }
            val cardFocus = remember(item.id) { FocusRequester() }
            // OK 键按下的时间戳（elapsedRealtime，0 表示当前没有按下中）
            var centerDownAt by remember { mutableLongStateOf(0L) }
            // 必须排在 clickable 之前：按键点击发生在 clickable 自己的 preview 处理里，
            // 同一节点上外层的 preview 先拿到事件，长按才能把 KeyUp 吃掉、不让它起播。
            val longPressMenu: Modifier = ReturnSpot.cardStand(TvHomeKey, rowKey, item.id, stand, cardFocus)
                .onPreviewKeyEvent { e ->
                    val native = e.nativeKeyEvent
                    val isCenter = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == KeyEvent.KEYCODE_ENTER
                    when {
                        // 有 Menu 键的遥控器走这条捷径；没有的机型只能长按 OK
                        native.keyCode == KeyEvent.KEYCODE_MENU -> {
                            if (native.action == KeyEvent.ACTION_DOWN) onMenu(item, cardFocus)
                            true
                        }
                        isCenter && native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> {
                            centerDownAt = SystemClock.elapsedRealtime()
                            false
                        }
                        isCenter && native.action == KeyEvent.ACTION_UP -> {
                            val held = if (centerDownAt == 0L) 0L else SystemClock.elapsedRealtime() - centerDownAt
                            centerDownAt = 0L
                            held >= 500L
                        }
                        else -> false
                    }
                }
            Box(
                longPressMenu
                    // 放大后的白边要靠 z 轴抬起来，否则会被同行后面的卡片压住
                    .zIndex(if (focused) 1f else 0f)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        // 首张横卡放大 8% 会往左吃掉 12.8dp，跟上面各行首卡错位 ——
                        // 用平移把这一半增量补回去（和 TvPosterCard 同一套做法）
                        if (index == 0) {
                            translationX = (scale.value - 1f) * size.width / 2f
                        }
                        shape = cardShape
                        clip = true
                    }
                    .background(AppColors.SurfaceHi)
                    .then(if (focused) Modifier.border(2.dp, Color.White, cardShape) else Modifier)
                    .clickable(interactionSource = interaction, indication = null) {
                        if (item.positionMs > 5_000L) onPlay(item) else onDetail(item.id)
                    }
                    .focusable(interactionSource = interaction)
                    .width(320.dp)
                    .height(120.dp)
            ) {
                // 横卡没有 backdrop 也没有 poster、或图是死链时，露出渐变兜底而不是空灰块
                PosterArt(
                    model = item.backdropUrl.ifBlank { item.posterUrl },
                    contentDescription = item.title
                ) {
                    PosterFallback(title = item.title, showTitle = false, titleFontSize = 34.sp)
                }
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
                        item.continueTitle,
                        color = TvText.Title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val epLabel = item.episodeLabel().takeIf { it.isNotBlank() && it != item.continueTitle }
                    if (epLabel != null) {
                        Text(
                            epLabel,
                            color = TvText.Body,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
 * 「继续观看」卡片的操作菜单（长按 OK / Menu 键唤出）。
 *
 * 弹窗形态跟本页的来源/线路选择器一致（Compose Dialog + tvMoveFocus 抢焦，规范 R1/R5）：
 * 独立窗口天然把焦点关在菜单里，不会逃逸到下层海报行，Back 也由窗口自己处理。
 * 行样式复用 [TvMenuRow]，与来源页功能菜单同一套视觉。
 */
@Composable
private fun TvContinueActionDialog(
    item: MediaEntity,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 等一帧：Dialog 内容要先组合完成，requester 才挂得上
        withFrameNanos { }
        tvMoveFocus(firstFocus)
    }

    val progress = if (item.durationMs > 0L) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f

    Dialog(
        onDismissRequest = onDismiss,
        // 关掉平台默认窄宽度，宽度由弹窗自己按屏幕尺寸决定
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            val dialogWidth = (maxWidth * 0.34f).coerceIn(380.dp, 640.dp)
            Column(
                Modifier
                    .width(dialogWidth)
                    .background(AppColors.SurfaceHi, RoundedCornerShape(18.dp))
                    .padding(vertical = 26.dp)
            ) {
                Text(
                    text = item.continueTitle,
                    color = TvText.Title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        val epLabel = item.episodeLabel().takeIf { it.isNotBlank() && it != item.continueTitle }
                        if (epLabel != null) {
                            append(epLabel)
                            append(" · ")
                        }
                        append("看到 ")
                        append(tvClock(item.positionMs))
                        if (item.durationMs > 0L) {
                            append(" / ")
                            append(tvClock(item.durationMs))
                            append("（")
                            append((progress * 100).roundToInt())
                            append("%）")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TvText.Meta,
                    modifier = Modifier.padding(horizontal = 26.dp)
                )
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvMenuRow(
                        text = "继续播放",
                        hint = "从上次的位置接着看",
                        modifier = Modifier.focusRequester(firstFocus),
                        onClick = onPlay
                    )
                    TvMenuRow(text = "查看详情", onClick = onDetail)
                    TvMenuRow(
                        text = "清除观看记录",
                        hint = "片子还在库里，只是不再出现在这里",
                        danger = true,
                        onClick = onClear
                    )
                }
            }
        }
    }
}

/** 观看进度菜单用的简短时长：小时才带时位，分钟秒补足 */
private fun tvClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
    // 规范 R5：默认焦点 = 当前选中项，不是列表第 0 项。
    // 库里已有来源时用户进来是「换一个来源」，停在第 0 项就得先按几下才回到现选项。
    val selectedHit = sources.indexOfFirst { it.id == selectedId }
    val focusIndex = if (selectedHit >= 0) selectedHit else 0
    LaunchedEffect(Unit) {
        // 等一帧：Dialog 内容要先组合完成，requester 才挂得上
        withFrameNanos { }
        tvMoveFocus(firstFocus)
    }
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
                    Box(Modifier.then(if (index == focusIndex) Modifier.focusRequester(firstFocus) else Modifier)) {
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
    // 规范 R5：默认焦点落在当前选中的线路上，不是「全部站点」。
    // 选中的线路已经不在列表里（换过来源）时，effectiveKey 为 null，回退到第一格。
    val effectiveKey = selectedKey?.takeIf { key -> sites.any { it.key == key } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        tvMoveFocus(firstFocus)
    }
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
                        Box(
                            Modifier.then(
                                if (effectiveKey == null) Modifier.focusRequester(firstFocus) else Modifier
                            )
                        ) {
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
                            onClick = { onPick(site.key, site) },
                            modifier = if (site.key == effectiveKey) {
                                Modifier.focusRequester(firstFocus)
                            } else Modifier
                        )
                    }
                }
            }
        }
    }
}
