package com.chen.doudoutv.ui.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.player.tv.TvPlayerActivity
import com.chen.doudoutv.ui.sources.TvSourcesScreen
import com.chen.doudoutv.ui.tv.nf.NfBrowseScreen
import com.chen.doudoutv.ui.tv.nf.NfHomeScreen
import com.chen.doudoutv.ui.tv.nf.NfMineScreen
import com.chen.doudoutv.ui.tv.nf.NfSearchScreen
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
 * 栈底永远是首页，tab 页最多一颗压在它上面：
 * 首页 → 分类 → 我的 只留 [Home, Mine]，返回键一下回首页、两下走「双返回」退出，
 * 不会像逐个 push 那样要按四次才出去。旧布局不用这条，仍然是 push/出栈。
 */
private fun tabStack(current: List<TvRoute>, route: TvRoute): List<TvRoute> {
    val root = if (current.firstOrNull() is TvRoute.Home) current.take(1) else listOf(TvRoute.Home)
    return if (route is TvRoute.Home) root else root + route
}

/**
 * 一级 tab 在导航条上的左右次序，只用来判断切页动画往哪边推。
 * 非 tab 页（详情 / 分类 / 来源 / 设置）统一算「更深一层」，压在四颗 tab 右边。
 */
private fun tabOrderOf(route: TvRoute): Int = when (route) {
    is TvRoute.Home -> 0
    is TvRoute.Browse -> 1
    is TvRoute.Mine -> 2
    is TvRoute.Search -> 3
    else -> 4
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
    // 切页动画的推进方向：入栈 / 往右的 tab 向前推，出栈 / 往左的 tab 往回拉
    var navForward by remember { mutableStateOf(true) }
    val push: (TvRoute) -> Unit = {
        navForward = true
        navStack = navStack + it
    }
    val pop: () -> Unit = {
        if (navStack.size > 1) {
            if (navStack.last() is TvRoute.Search) searchState.clear()
            if (navStack.last() is TvRoute.Detail) TvNavHistory.forgetEpisode()
            navForward = false
            navStack = navStack.dropLast(1)
        }
    }
    // Netflix 版导航条：四颗 tab 互切走 tabStack 规则，并带上当前布局风格（切风格要重建栈底）
    val switchTab: (TvRoute) -> Unit = { route ->
        navForward = tabOrderOf(route) >= tabOrderOf(navStack.last())
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
            context.findActivity()?.finish()
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
    Box(Modifier.fillMaxSize()) {
        // 主题底色铺在转场之外：切页时两页之间不会露出窗口底，顶栏也有稳定的衬底
        if (nfLayout) Box(Modifier.fillMaxSize().themeBackdrop())
        AnimatedContent(
            targetState = navStack.last(),
            // 切页不能再走淡入淡出：旧页淡出 120ms 比新页淡入 200ms 先结束，
            // 中间那 80ms 屏幕上只剩一张半透明的新页压在近黑底上，亮度骤降一下 —— 用户看到的「闪」就是这个。
            // 而且任何交叉淡化在中点都会漏底：in*t + out*(1-t)² + B*t(1-t)，两页都不满 alpha 时底色透上来约 25%。
            // 改成等时长的纯位移（alpha 恒为 1），两页各自不透明、行进距离只有 1/8 屏宽，
            // 新页盖在旧页上推进，全程满屏有内容，数学上不可能漏底；位移小到不会甩动整屏海报。
            transitionSpec = {
                val sign = if (navForward) 1 else -1
                val spec = tween<IntOffset>(Motion.BASE, easing = Motion.Ease)
                (slideInHorizontally(spec) { sign * it / 8 } togetherWith
                    slideOutHorizontally(spec) { -sign * it / 8 })
                    .apply { targetContentZIndex = 1f }
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
                    is TvRoute.Settings -> TvSettingsScreen(vm = vm)
                }
            }
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

/** 双返回退出的确认窗口：比 Android 惯例的 2 秒稍长一点，电视上按键到屏幕回显有延迟 */
private const val EXIT_CONFIRM_WINDOW_MS = 2500L

/** 导航条的四颗 tab → 路由 */
private fun nfRouteOf(tab: NfTab): TvRoute = when (tab) {
    NfTab.Home -> TvRoute.Home
    NfTab.Browse -> TvRoute.Browse
    NfTab.Mine -> TvRoute.Mine
    NfTab.Search -> TvRoute.Search
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
