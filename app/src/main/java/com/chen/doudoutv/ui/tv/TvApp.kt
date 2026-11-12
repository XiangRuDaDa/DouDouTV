package com.chen.doudoutv.ui.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.components.SyncProgressBar
import com.chen.doudoutv.ui.player.tv.TvPlayerActivity
import com.chen.doudoutv.ui.sources.TvSourcesScreen
import com.chen.doudoutv.ui.tv.nf.NfBrowseScreen
import com.chen.doudoutv.ui.tv.nf.NfHomeScreen
import com.chen.doudoutv.ui.tv.nf.NfMineScreen
import com.chen.doudoutv.ui.tv.nf.NfSearchScreen
import com.chen.doudoutv.ui.tv.nf.NfShellNav
import com.chen.doudoutv.ui.tv.nf.NfTabSwitch
import com.chen.doudoutv.ui.tv.nf.LocalNfShell
import com.chen.doudoutv.ui.tv.nf.rememberNfShell
import com.chen.doudoutv.ui.tv.nf.NfTab
import com.chen.doudoutv.ui.theme.TvText
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * 电视端外壳（完全独立于手机端 PhoneApp）。
 *
 * 导航用栈（navStack）：
 *   - 进详情、分类、搜索 = 入栈
 *   - Back 键 = 出栈，回到进入当前页之前的页面（而不是强制跳首页）
 *   - Sources/Settings 这类"根级 Tab"也用栈维护，但首次进入时可以 reset
 *   - 遥控器 Back 键会触发 BackHandler；栈只有 Home 时走「双返回」退出（见 exitHintAt）
 */
internal sealed interface TvRoute {
    data object Home : TvRoute

    /** 以下三颗只在 Netflix 版布局的常驻导航条上出现（旧布局没有一级 tab） */
    data object Browse : TvRoute
    data object Mine : TvRoute
    data object Search : TvRoute
    data class Detail(val mediaId: Long) : TvRoute
    data class Genre(val kind: String?, val genre: String?) : TvRoute
    data class Category(val typeId: String, val typeName: String) : TvRoute
    data object Sources : TvRoute
    data object Settings : TvRoute
}

/**
 * 一级 tab 之间互切的栈规则（Netflix 版布局的导航条用）。
 *
 * 首页是根，tab 之间有层级：首页 → 分类 → 我的 走了一趟，返回键就该一级一级退回来
 * （我的 → 分类 → 首页），而不是不管从哪来都直接回首页 —— 直接回首页等于把
 * 「从哪来」这一层信息丢了，用户按返回时看到的却是另一颗 tab。
 *
 * 规则：
 *   · 按「首页」tab = 回根，历史清空（首页是根，不是历史里的一环）；
 *   · 切到历史里已有的 tab = 截断到那一层（浏览器的前进/后退语义）；
 *   · 历史最多 [TAB_HISTORY_MAX] 层：tab 只有四颗，再多也退不出去，
 *     退到首页就该走「双返回」退出了。
 * 旧布局不用这条，仍然是 push/出栈。
 */
private const val TAB_HISTORY_MAX = 4

/** 这条路由是不是一级 tab（详情 / 分类 / 来源 / 设置都不是） */
private fun TvRoute.isTabRoute(): Boolean = when (this) {
    is TvRoute.Home, is TvRoute.Browse, is TvRoute.Mine, is TvRoute.Search -> true
    else -> false
}

private fun tabStack(current: List<TvRoute>, route: TvRoute): List<TvRoute> {
    if (route is TvRoute.Home) return listOf(TvRoute.Home)
    // 历史段：栈里出现过的那些 tab 页（首页本身不算，它永远是根）
    val history = current.filter { it.isTabRoute() && it !is TvRoute.Home }
    val at = history.indexOf(route)
    val next = if (at >= 0) history.take(at + 1) else history + route
    return listOf(TvRoute.Home) + next.takeLast(TAB_HISTORY_MAX - 1)
}

/**
 * 进程级的导航栈。
 *
 * 内嵌的爬虫壳在播放期间也会顺手 finish 宿主 MainActivity，MainActivity 于是 recreate()；
 * 栈要是只放在 remember 里，重建后组合树全新一份，用户看到的就是「看完视频回来落到首页」。
 * 放进程级和 [com.chen.doudoutv.ui.ReturnSpot] 一个道理：重建能穿过去，真正的新进程自然从首页开始。
 */
internal object TvNavHistory {
    var stack: List<TvRoute> = listOf(TvRoute.Home)

    /**
     * 详情页最近一次发起播放的分集 id（0 = 没有）。
     * 重建后详情页要靠它把线路 / 季 / 页码和焦点接回「刚才看的那一集」。
     * 离开详情页（pop）时清掉，避免下次正常进这一页也照着老记录跳页。
     */
    var playedEpisodeId: Long = 0L

    /**
     * 临时负 id 的详情页路由落库后的真实 id（0 = 没有）。
     * 负 id 只是浏览结果在内存里的编号，重建后那份内存没了、按负 id 查不到条目；
     * 记下真实 id，恢复导航栈时把路由换成它，这一页就还能原样打开。
     */
    var resolvedDetailId: Long = 0L

    fun forgetEpisode() {
        playedEpisodeId = 0L
        resolvedDetailId = 0L
    }
}

@Composable
fun TvApp(vm: LibraryViewModel) {
    val context = LocalContext.current
    var navStack by remember { mutableStateOf(restorableStack(TvNavHistory.stack)) }
    LaunchedEffect(navStack) { TvNavHistory.stack = navStack }
    // 搜索页状态放在导航层：进详情再返回时结果不丢，真正退出搜索页才清空
    val searchState = remember { TvSearchUiState() }
    val push: (TvRoute) -> Unit = {
        navStack = navStack + it
    }
    val pop: () -> Unit = {
        if (navStack.size > 1) {
            if (navStack.last() is TvRoute.Search) searchState.clear()
            if (navStack.last() is TvRoute.Detail) TvNavHistory.forgetEpisode()
            navStack = navStack.dropLast(1)
        }
    }
    // Netflix 版导航条：四颗 tab 互切走 tabStack 规则，并带上当前布局风格（切风格要重建栈底）
    val switchTab: (TvRoute) -> Unit = { route ->
        // 从详情页切走（导航条常驻时不会发生，抽屉/其他入口会）就把「刚才播的那一集」清掉：
        // 留着它，下次正常进同一部剧的详情页会照着老记录跳季 / 跳页，首屏大图构图就没了
        if (navStack.last() is TvRoute.Detail) TvNavHistory.forgetEpisode()
        navStack = tabStack(navStack, route)
    }

    BackHandler(enabled = navStack.size > 1) { pop() }

    // 首页（栈底）的 Back 走「双返回」：第一下只在底部亮一条提示，窗口内再按一下才真退出。
    // 这里不用弹窗而用浮层：弹窗要抢焦点，会把高亮从首页带走，再按返回时焦点还得找回来。
    // 单一时间戳同时当「提示是否显示」和「窗口是否还没过」用，超时归零后两者一起失效。
    var exitHintAt by remember { mutableLongStateOf(-1L) }
    BackHandler(enabled = navStack.size == 1) {
        val now = SystemClock.elapsedRealtime()
        if (exitHintAt >= 0 && now - exitHintAt <= EXIT_CONFIRM_WINDOW_MS) {
            exitHintAt = -1L
            // 真退出：只 finish() 在电视盒子上常常是「回到桌面、进程还在」，爬虫壳和代理线程
            // 仍挂在后台；这里结束整个任务并杀掉进程，下一次启动是干净的冷启动
            context.findActivity()?.finishAffinity()
            Process.killProcess(Process.myPid())
        } else {
            exitHintAt = now
        }
    }

    LaunchedEffect(exitHintAt) {
        if (exitHintAt < 0) return@LaunchedEffect
        delay((EXIT_CONFIRM_WINDOW_MS - (SystemClock.elapsedRealtime() - exitHintAt)).coerceAtLeast(0L))
        exitHintAt = -1L
    }

    // Netflix 口径：退出播放器落详情页，而不是回到起播那张卡。
    // 轮播 / 继续观看是直接起播的，起播前先把这一条目的详情页压进栈；已经在详情页就不重复压。
    // 首页的滚动与落点由 ReturnSpot 记着，从详情页再返回时还站在原来那张卡上。
    // 两套布局（旧首页 / Netflix 版首页）共用这一条起播路径，行为完全一致。
    val playFromRoot: (MediaEntity) -> Unit = { item ->
        if (navStack.last() !is TvRoute.Detail) push(TvRoute.Detail(item.id))
        context.startActivity(TvPlayerActivity.intent(context, item.id))
    }

    val nfLayout = Prefs.tvLayout == Prefs.TvLayout.NETFLIX
    val nfShell = rememberNfShell()
    val nfTab = nfTabOf(navStack.last())

    val baseTypography = MaterialTheme.typography
    // 14sp 是 TV 端小字下限，三米外更小只剩色块。
    // 但下限不等于「都压成 14sp」：原来 bodySmall / labelMedium / labelSmall
    // 三个 role 被写成同一个 14sp，徽章、计数、副标题看上去一样重，层级全平了。
    // 现在按用途拆出可见台阶（label 家族 16 / 15 / 14，body 家族 17 / 15 / 14）：
    //   labelLarge  16sp —— 按钮与强调文字
    //   labelMedium 15sp —— 徽章、标签，比小正文醒目一档
    //   bodySmall   14sp —— 小正文，行高 20sp（和徽章同字号但更松）
    //   labelSmall  14sp —— 计数与极弱提示，行高 18sp + 字距 0.5sp，视觉更紧更小
    // 字号覆盖挂在转场之外：常驻顶栏和页面内容必须用同一套 typography，
    // 否则顶栏量出来的高度和页面让位的高度对不上。
    val typography = remember(baseTypography) {
        baseTypography.copy(
            labelLarge = baseTypography.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
            bodySmall = baseTypography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = baseTypography.labelMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
            labelSmall = baseTypography.labelSmall.copy(fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.5.sp)
        )
    }

    MaterialTheme(typography = typography) {
    CompositionLocalProvider(LocalNfShell provides (if (nfLayout) nfShell else null)) {
    Box(
        Modifier
            .fillMaxSize()
            // 返回键层级的最高一级：焦点在常驻导航条上时按返回，退回「首页」这一 tab，
            // 焦点留在首页那颗标签上（NfTabSwitch 让新页面不把焦点抢进轮播播放键）。
            // 导航条画在转场之外，各页自己的按键链收不到它这一层，只能在根上接。
            // 已经在首页时原样交回：由下面的 BackHandler 走退栈 / 双返回退出。
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.Back) {
                    return@onPreviewKeyEvent false
                }
                val tab = if (nfLayout) nfTab else null
                // 设置页是 push 进来的覆盖页（不是 tab 互切）：焦点在顶栏按返回时原样交回，
                // 由下面的 BackHandler 出栈回到「从哪来」那一页，而不是切到首页。
                if (tab == null || tab == NfTab.Home || tab == NfTab.Settings || !nfShell.chrome.navFocused) {
                    return@onPreviewKeyEvent false
                }
                NfTabSwitch.arm(NfTab.Home)
                switchTab(TvRoute.Home)
                true
            }
    ) {
        // 切页直切无过渡（选型台组合 D 拍板）：任何时长/淡化在电视上都嫌拖，0ms 换内容最干净。
        AnimatedContent(
            targetState = navStack.last(),
            transitionSpec = {
                (EnterTransition.None togetherWith ExitTransition.None) using null
            },
            label = "tv_route",
            modifier = Modifier.fillMaxSize().clipToBounds()
        ) { target ->
                when (target) {
                    is TvRoute.Home -> if (nfLayout) {
                        NfHomeScreen(
                            vm = vm,
                            onTab = { tab -> switchTab(nfRouteOf(tab)) },
                            onDetail = { id -> push(TvRoute.Detail(id)) },
                            onPlay = playFromRoot,
                            onSources = { push(TvRoute.Sources) },
                            onSettings = { push(TvRoute.Settings) }
                        )
                    } else TvHomeScreen(
                        vm = vm,
                        onSearch = { push(TvRoute.Search) },
                        onDetail = { id -> push(TvRoute.Detail(id)) },
                        onGenre = { kind, genre -> push(TvRoute.Genre(kind, genre)) },
                        onCategory = { typeId, typeName -> push(TvRoute.Category(typeId, typeName)) },
                        onPlay = playFromRoot,
                        onSources = { push(TvRoute.Sources) },
                        onSettings = { push(TvRoute.Settings) }
                    )

                    is TvRoute.Browse -> NfBrowseScreen(
                        vm = vm,
                        onTab = { tab -> switchTab(nfRouteOf(tab)) },
                        onDetail = { id -> push(TvRoute.Detail(id)) },
                        onSources = { push(TvRoute.Sources) },
                        onSettings = { push(TvRoute.Settings) }
                    )

                    is TvRoute.Mine -> NfMineScreen(
                        vm = vm,
                        onTab = { tab -> switchTab(nfRouteOf(tab)) },
                        onDetail = { id -> push(TvRoute.Detail(id)) },
                        onSources = { push(TvRoute.Sources) },
                        onSettings = { push(TvRoute.Settings) }
                    )

                    is TvRoute.Search -> if (nfLayout) {
                        // Netflix 版：搜索是导航条上的第四颗 tab，外壳和其他三页完全一样
                        NfSearchScreen(
                            vm = vm,
                            state = searchState,
                            onTab = { tab -> switchTab(nfRouteOf(tab)) },
                            onDetail = { id -> push(TvRoute.Detail(id)) },
                            onSources = { push(TvRoute.Sources) },
                            onSettings = { push(TvRoute.Settings) }
                        )
                    } else TvSearchScreen(
                        vm = vm,
                        state = searchState,
                        onBack = pop,
                        onDetail = { id -> push(TvRoute.Detail(id)) }
                    )

                    is TvRoute.Genre -> TvGenreScreen(
                        vm = vm,
                        initialKind = target.kind,
                        initialGenre = target.genre,
                        onBack = pop,
                        onDetail = { id -> push(TvRoute.Detail(id)) }
                    )

                    is TvRoute.Category -> TvCategoryScreen(
                        vm = vm,
                        typeId = target.typeId,
                        typeName = target.typeName,
                        onBack = pop,
                        onDetail = { id -> push(TvRoute.Detail(id)) }
                    )

                    is TvRoute.Detail -> TvDetailScreen(
                        vm = vm,
                        mediaId = target.mediaId,
                        onBack = pop,
                        onPlay = { id ->
                            context.startActivity(TvPlayerActivity.intent(context, id))
                        }
                    )

                    is TvRoute.Sources -> TvSourcesScreen(vm = vm)
                    is TvRoute.Settings -> TvSettingsScreen(
                        vm = vm,
                        onTab = { tab -> switchTab(nfRouteOf(tab)) },
                        // 已在设置页：齿轮是当前页指示，点了不再重复入栈
                        onSettings = { },
                        onSources = { push(TvRoute.Sources) }
                    )
                }
            }

            // 常驻导航条挂在转场之外：切 tab 只让下面的内容滑动，顶栏不动、不重建、不闪。
            // 每颗 tab 页把自己的顶栏配置交进 shell 的槽位，这里按当前路由取一份来画。
            if (nfLayout && nfTab != null) {
                NfShellNav(
                    vm = vm,
                    shell = nfShell,
                    current = nfTab,
                    onTab = { tab -> switchTab(nfRouteOf(tab)) },
                    onSettings = { push(TvRoute.Settings) },
                    onManageSources = { push(TvRoute.Sources) },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            // 同步进度线：贴在屏幕最上沿，画在常驻导航条之上。
            // 同步可以从任何页面触发（顶栏刷新、来源页那张卡的「刷新同步」、冷启动增量），
            // 所以这条线挂在 TV 外壳这一层，不跟页面走；线比手机粗一档，十英尺外才看得见在推进。
            if (vm.scanning) {
                SyncProgressBar(
                    fetched = vm.scanFetched,
                    total = vm.scanTotal,
                    thickness = 4.dp,
                    glow = 16.dp,
                    head = 44.dp,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

        // 退出提示压在页面上层：不可聚焦、不拦按键，纯绘制，所以不会影响下面任何页面的焦点走位。
        AnimatedVisibility(
            visible = exitHintAt >= 0,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.SLOW)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                Modifier
                    .padding(bottom = 32.dp)
                    .clip(Radius.Pill)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 22.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "再按一次「返回」键退出",
                    color = TvText.Title,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    }
    }
}

/** 双返回退出的确认窗口：比 Android 惯例的 2 秒稍长一点，电视上按键到屏幕回显有延迟 */
private const val EXIT_CONFIRM_WINDOW_MS = 2500L

/** 导航条的四颗 tab → 路由（Settings 是伪 tab，只供设置页注册槽位，tab 行不会点到它） */
private fun nfRouteOf(tab: NfTab): TvRoute = when (tab) {
    NfTab.Home -> TvRoute.Home
    NfTab.Browse -> TvRoute.Browse
    NfTab.Mine -> TvRoute.Mine
    NfTab.Search -> TvRoute.Search
    NfTab.Settings -> TvRoute.Settings
}

/** 路由 → 导航条 tab；设置页借 Settings 伪 tab 露出常驻顶栏，详情 / 分类 / 来源没有 tab */
private fun nfTabOf(route: TvRoute): NfTab? = when (route) {
    is TvRoute.Home -> NfTab.Home
    is TvRoute.Browse -> NfTab.Browse
    is TvRoute.Mine -> NfTab.Mine
    is TvRoute.Search -> NfTab.Search
    is TvRoute.Settings -> NfTab.Settings
    else -> null
}

/**
 * 恢复导航栈时把不能原样加载的路由换掉或丢弃。
 *
 * 首页分类 / 搜索是内存里的临时条目（负 id），落库靠 ViewModel 里那份浏览结果；
 * 重建后那份内存没了，按负 id 只能渲染成「条目不存在」。
 * 所以记下过真实 id 的就换成真实 id 恢复；没记到的（含真 id 已不在库里）退回首页。
 * 其余页面都按 id / 类型重新取数，栈照原样接回去。
 */
private fun restorableStack(stored: List<TvRoute>): List<TvRoute> {
    val top = stored.lastOrNull() as? TvRoute.Detail ?: return stored
    if (top.mediaId >= 0) return stored
    val real = TvNavHistory.resolvedDetailId
    return if (real > 0) stored.dropLast(1) + TvRoute.Detail(real) else listOf(TvRoute.Home)
}

/** LocalContext 可能被 ContextThemeWrapper 包一层，直接 cast 拿不到 Activity 就退不出去 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}
