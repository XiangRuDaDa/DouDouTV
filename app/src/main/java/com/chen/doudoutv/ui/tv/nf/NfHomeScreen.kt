package com.chen.doudoutv.ui.tv.nf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.ReturnSpot
import com.chen.doudoutv.ui.StandTarget
import com.chen.doudoutv.ui.PrecomposeWindow
import com.chen.doudoutv.ui.cardStand
import com.chen.doudoutv.ui.components.ShimmerCard
import com.chen.doudoutv.ui.listState
import com.chen.doudoutv.ui.tv.HERO_DWELL_SECONDS
import com.chen.doudoutv.ui.tv.rememberHeroAmbient
import com.chen.doudoutv.ui.tv.TvContinueActionDialog
import com.chen.doudoutv.ui.tv.TvHero
import com.chen.doudoutv.ui.tv.TvHeroEmpty
import com.chen.doudoutv.ui.tv.TvHeroSeamFade
import com.chen.doudoutv.ui.tv.TvMediaTitle
import com.chen.doudoutv.ui.tv.tvClaimFocusOnEnter
import com.chen.doudoutv.ui.tv.tvMoveFocus
import com.chen.doudoutv.ui.tv.tvResumeTick
import com.chen.doudoutv.ui.theme.TvText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [ReturnSpot] 的页面标识：Netflix 版首页的滚动与落点都挂在它下面 */
private const val NfHomeKey = "nf_home"

/**
 * 轮播停在第几帧。挂在进程级而不是 `remember`：进详情会把这些页面整块摘出组合，
 * 返回时轮播若从第一条重放，读起来就像「页面被重新加载了一遍」。
 */
private val NfHomeHeroIndex = mutableIntStateOf(0)

/**
 * 本进程是否已经进过首页。冷启动第一次进首页要把焦点站在顶栏第一颗 tab 并回到首屏；
 * 之后从详情/播放器返回则沿用「站在离开时那张卡」的恢复逻辑，不被这次抢焦拽回顶栏。
 */
private val NfHomeOpened = mutableStateOf(false)

/**
 * Netflix 基准布局的首页：整屏轮播 + 一行「继续观看」，其余分类内容全部搬到「分类」页。
 *
 * 轮播直接复用旧首页的 [TvHero] / [TvHeroEmpty] / [TvHeroSeamFade]（元件本身一行没改，
 * 只是从 private 放成 internal）—— 不另造第二套 Hero，免得两套轮播以后各自漂移。
 *
 * 顶栏可见性与 ↑/↓ 交棒时序分别见 [NfScaffold] 和 [nfFocusRoute]。
 */
@Composable
fun NfHomeScreen(
    vm: LibraryViewModel,
    onTab: (NfTab) -> Unit,
    onDetail: (Long) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()

    val filteredLibrary = remember(library, vm.homeSourceId, vm.homeSiteKey) { vm.filterForHome(library) }
    // 轮播取当前线路 homeContent 的前 5 条（源站推荐顺序），来源与线路永远一致；
    // 还没拉到线路数据（或选的是「全部站点」）时才回退本地库
    val heroItems = remember(filteredLibrary, vm.siteHomeItems) {
        nfHeroCandidates(filteredLibrary, vm.siteHomeItems)
    }
    // 继续观看：记录取全库，但只展示当前来源/线路那一份 —— 换来源就看到另一份追看记录
    val continueItems = remember(continueWatching, vm.homeSourceId, vm.homeSiteKey) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L
        vm.filterForHome(continueWatching).filter { it.lastPlayedAt > cutoff }.take(8)
    }

    var heroIndex by remember { NfHomeHeroIndex }
    val heroProgress = remember { mutableFloatStateOf(0f) }
    val hero = heroItems.getOrNull(heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0)))
    // 底图下缘取样色：渐隐遮罩和下面的收色带共用一个值，见旧首页 TvHomeScreen 的同名变量
    val heroAmbient = rememberHeroAmbient(hero?.let { it.backdropUrl.ifBlank { it.posterUrl } }.orEmpty())

    val listState = ReturnSpot.listState(NfHomeKey, precompose = PrecomposeWindow.Tv)
    val scope = rememberCoroutineScope()
    // 轮播进度按 50ms 推进而不是逐帧：列表滚动中不推进，否则滚到一半整屏换图，
    // 看起来就是「屏幕刷新了一下」（与旧首页同一套时间账）
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

    val heroZoomPaused by remember { derivedStateOf { listState.isScrollInProgress } }

    val chrome = rememberNfChrome()
    val heroPlayFocus = remember { FocusRequester() }
    val heroEmptyFocus = remember { FocusRequester() }
    val standTarget = remember { StandTarget() }
    val continueFirstFocus = remember { FocusRequester() }
    // 空态那颗 CTA 单独一个 requester：和首卡共用会在「数据到达」那一刻
    // 留下一个指向已销毁节点的旧 attach，requestFocus 不报错却什么都不做
    val continueEmptyFocus = remember { FocusRequester() }
    /** 「继续观看」这一屏真正能接住焦点的那个元件 */
    val continueAnchor = if (continueItems.isNotEmpty()) continueFirstFocus else continueEmptyFocus

    // 顶栏按滚动距离连续淡出，淡到位才摘出组合。
    // 不能只看 firstVisibleItemIndex > 0：电视 dp 视口约 540、轮播按 70% 有 378 高，
    // 往下滚一整段仍停在首屏那一格，index 永远是 0 —— 读起来就是「顶栏甩不掉地常驻」。
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val navCollapsePx = with(density) {
        (configuration.screenHeightDp * 0.16f).dp.roundToPx()
    }.coerceAtLeast(1)
    // 在 draw phase 读滚动状态：组合里读连续量会让每一次滚动都把顶栏拖进重组
    val navAlphaProvider: () -> Float = {
        if (listState.firstVisibleItemIndex > 0) 0f
        else (1f - listState.firstVisibleItemScrollOffset.toFloat() / navCollapsePx).coerceIn(0f, 1f)
    }
    val navFaded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset >= navCollapsePx
        }
    }
    // 焦点还在顶栏上时不撤 —— 撤了等于把焦点丢进无人区（规范 R6）
    val navVisible = !navFaded || chrome.navFocused

    /** 焦点来到顶栏：先把列表滚回顶部，保证高亮落在看得见的按钮上 */
    val revealNav: () -> Unit = {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    /** 焦点来到轮播的两颗按钮：首页已经不站在卡片上了 */
    val forgetCardStand: () -> Unit = {
        ReturnSpot.saveStand(NfHomeKey, "")
        standTarget.requester = null
    }

    /**
     * Hero 的 ↑ 交棒给导航条上「首页」那颗 tab。
     * 顶栏淡到位就不在组合里了，所以先 reveal 让它回到组合、等一帧再抢，抢不动隔一段补抢一次。
     */
    val goNavBar: () -> Unit = {
        revealNav()
        scope.launch {
            withFrameNanos { }
            if (!tvMoveFocus(chrome.tabOf(NfTab.Home))) {
                delay(180)
                tvMoveFocus(chrome.tabOf(NfTab.Home))
            }
        }
    }

    /**
     * Hero 按 ↓：轮播 378dp 高（540dp 视口），折痕下只剩 162dp，只够露「继续观看」的标题和卡片上半（整行约 212dp），
     * 默认几何搜索找不到没完整进视口的卡片。
     * 所以先直接落焦点（拿到焦点本身会触发 bringIntoView，能把这一行带进视野），
     * 落不上再把列表滚到「继续观看」标题行、等一帧补抢。
     */
    val goToContinueRow: suspend () -> Unit = {
        if (!tvMoveFocus(continueAnchor)) {
            listState.scrollToItem(if (hero != null) 2 else 1)
            withFrameNanos { }
            if (!tvMoveFocus(continueAnchor)) {
                delay(140)
                tvMoveFocus(continueAnchor)
            }
        }
    }

    val resumeTick = tvResumeTick()
    // 冷启动第一次进首页：焦点站顶栏第一颗 tab、列表回首屏（顶栏才看得见）；
    // 之后的返回（详情/播放器）走原来的落点恢复，不被这次抢焦拽走
    val freshOpen = remember {
        val first = !NfHomeOpened.value
        NfHomeOpened.value = true
        first
    }
    // 从别的 tab 点「首页」切回来：焦点同样留在标签上，内容不抢；
    // 列表滚回顶部保证悬浮顶栏在组合里（焦点落上去时 onNavFocus 也会兜底滚一次）
    val viaTab = remember { NfTabSwitch.consume(NfTab.Home) }
    tvClaimFocusOnEnter(
        tick = listOf(resumeTick, hero != null),
        anchorsProvider = {
            if (freshOpen || viaTab) {
                listOf(chrome.tabOf(NfTab.Home))
            } else {
                val heroAnchor = if (hero != null) heroPlayFocus else heroEmptyFocus
                listOfNotNull(standTarget.requester, heroAnchor)
            }
        },
        scrollToTop = { if (freshOpen || viaTab) listState.scrollToItem(0) }
    )

    // 「继续观看」卡片的操作菜单：长按 OK / Menu 键唤出，连同卡片自己的 requester 一起存
    var continueMenu by remember { mutableStateOf<Pair<MediaEntity, FocusRequester>?>(null) }

    NfScaffold(
        vm = vm,
        current = NfTab.Home,
        chrome = chrome,
        belowFocusRequester = if (hero != null) heroPlayFocus else heroEmptyFocus,
        navVisible = navVisible,
        navAlpha = navAlphaProvider,
        onNavFocus = revealNav,
        onTab = onTab,
        onSettings = onSettings,
        onManageSources = onSources
    ) {
        LazyColumn(
            state = listState,
            // 返回键的层级：轮播按钮 / 卡片 → tab 栏（tab 栏那一层的返回由 TvApp 根部接）
            modifier = Modifier.fillMaxSize().nfBackKey { goNavBar(); true },
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "hero") {
                Box(
                    Modifier.nfKeyDown(
                        enabled = continueItems.isNotEmpty() || !vm.scanning
                    ) { goToContinueRow() }
                ) {
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
                            onNavigateUp = goNavBar,
                            onButtonFocus = forgetCardStand,
                            ambient = heroAmbient,
                            titleContent = { current -> TvMediaTitle(current.title, current.logoUrl) }
                        )
                    } else {
                        TvHeroEmpty(
                            libraryIsEmpty = library.isEmpty(),
                            hasCategories = vm.homeCategories.isNotEmpty(),
                            error = vm.homeSitesError,
                            scanning = vm.scanning,
                            firstFocusRequester = heroEmptyFocus,
                            onNavigateUp = goNavBar,
                            onAddSource = onSources,
                            onRefresh = { vm.refreshHome() },
                            onButtonFocus = forgetCardStand
                        )
                    }
                }
            }
            if (hero != null) {
                item(key = "hero_seam") { TvHeroSeamFade(heroAmbient) }
            }

            item(key = "continue_title") {
                NfRowTitle(
                    text = "继续观看",
                    trailing = {
                        if (continueItems.isNotEmpty()) {
                            Text(
                                "${continueItems.size} 部",
                                color = TvText.Muted,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                )
            }
            item(key = "continue_row") {
                when {
                    continueItems.isNotEmpty() -> NfContinueRow(
                        items = continueItems,
                        stand = standTarget,
                        firstFocus = continueFirstFocus,
                        upFocus = if (hero != null) heroPlayFocus else heroEmptyFocus,
                        onDetail = onDetail,
                        onPlay = onPlay,
                        onMenu = { media, cardFocus -> continueMenu = media to cardFocus }
                    )
                    vm.scanning -> NfContinueSkeleton()
                    else -> NfInlineEmpty(
                        text = "还没有看到一半的内容",
                        hint = "从轮播或「分类」里挑一部开始播放，这里就会接上进度",
                        actionLabel = "去分类",
                        onAction = { onTab(NfTab.Browse) },
                        focusRequester = continueEmptyFocus
                    )
                }
            }
        }
    }

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
                scope.launch {
                    withFrameNanos { }
                    runCatching { cardFocus.requestFocus() }
                }
            }
        )
    }
}

/** 轮播候选：线路首页内容优先，回落到本地库里带横图/竖图的条目 */
private fun nfHeroCandidates(library: List<MediaEntity>, siteHomeItems: List<MediaEntity>): List<MediaEntity> {
    val fromSite = siteHomeItems
        .filter { it.posterUrl.isNotBlank() || it.backdropUrl.isNotBlank() }
        .distinctBy { it.id }
    if (fromSite.isNotEmpty()) return fromSite.take(5)
    val primary = library.filter { it.backdropUrl.isNotBlank() }
    if (primary.size >= 3) return primary.take(5)
    return (primary + library.filter { it.posterUrl.isNotBlank() })
        .distinctBy { it.id }
        .take(5)
}

/**
 * 继续观看一行：16:9 横版卡，图按 Crop 裁满 —— TVBox 只有竖版海报时取中间一条带，
 * Emby/TMDB 有真横图时正好铺满。卡片宽度按屏幕宽度的三成算，一屏可见 3 张半。
 */
@Composable
private fun NfContinueRow(
    items: List<MediaEntity>,
    stand: StandTarget,
    firstFocus: FocusRequester,
    /** 卡片按 ↑ 的落点：Hero 的播放键。不显式给的话，默认几何搜索会因为播放键偏在画面左下、
     *  卡片在右侧而把它判成「不在正上方」，直接跳过它落到顶栏 */
    upFocus: FocusRequester,
    onDetail: (Long) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    onMenu: (MediaEntity, FocusRequester) -> Unit
) {
    val rowState = ReturnSpot.listState("$NfHomeKey/continue", precompose = PrecomposeWindow.Tv)
    val stagger = nfFirstFillStagger(items.size, "$NfHomeKey/continue")
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = (screenWidth * 0.30f).coerceIn(240.dp, 380.dp)
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        // 纵向余量必须大于卡片的焦点抬起量（CardLift=8dp），否则抬起的那几像素被 Lazy 容器裁掉
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = NfRowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(NfCardGap)
    ) {
        itemsIndexed(items, key = { _, it -> "nf_continue_${it.id}" }, contentType = { _, _ -> "continue" }) { index, item ->
            val ownFocus = remember(item.id) { FocusRequester() }
            // 首卡不再另外挂第二个 requester：同一焦点节点挂两个在 1.6.8 上不可靠
            // （见 ReturnSpot.cardStand 的注释），首卡的记账直接用页面级那一个
            val cardFocus = if (index == 0) firstFocus else ownFocus
            NfContinueCard(
                item = item,
                cardWidth = cardWidth,
                modifier = Modifier
                    .nfFocusRoute(up = upFocus)
                    .then(ReturnSpot.cardStand(NfHomeKey, "continue", item.id, stand, cardFocus)),
                anchorStart = index == 0,
                entrance = nfEntrance(index, stagger),
                onClick = { if (item.positionMs > 5_000L) onPlay(item) else onDetail(item.id) },
                onMenu = { onMenu(item, cardFocus) }
            )
        }
    }
}

/** 同步进行中：一行与真实横版卡等高的骨架，避免标题下面空一截 */
@Composable
private fun NfContinueSkeleton() {
    val cardWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.30f).coerceIn(240.dp, 380.dp)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = NfRowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(NfCardGap)
    ) {
        items(count = 4) {
            Box(Modifier.width(cardWidth).height(cardWidth * 9f / 16f)) {
                ShimmerCard()
            }
        }
    }
}
