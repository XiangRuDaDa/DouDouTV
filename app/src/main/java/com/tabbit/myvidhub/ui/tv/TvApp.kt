package com.tabbit.myvidhub.ui.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.player.tv.TvPlayerActivity
import com.tabbit.myvidhub.ui.sources.TvSourcesScreen
import com.tabbit.myvidhub.ui.theme.TvText
import com.tabbit.myvidhub.ui.theme.Radius
import com.tabbit.myvidhub.ui.theme.Motion
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
    data object Search : TvRoute
    data class Detail(val mediaId: Long) : TvRoute
    data class Genre(val kind: String?, val genre: String?) : TvRoute
    data class Category(val typeId: String, val typeName: String) : TvRoute
    data object Sources : TvRoute
    data object Settings : TvRoute
}

/**
 * 进程级的导航栈。
 *
 * 内嵌的爬虫壳在播放期间也会顺手 finish 宿主 MainActivity，MainActivity 于是 recreate()；
 * 栈要是只放在 remember 里，重建后组合树全新一份，用户看到的就是「看完视频回来落到首页」。
 * 放进程级和 [com.tabbit.myvidhub.ui.ReturnSpot] 一个道理：重建能穿过去，真正的新进程自然从首页开始。
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
    // 取证用：每次本 Activity 装配都打一行，能直接分清
    // 「没重建但栈里只有 Home」（压根没进详情，如从继续观看直接播放）和「重建后恢复失败」。
    // 放在 remember 里：这一列是 Composable 体，写在外面每次重组都会打一行。
    remember {
        Log.w("MyVidHubNav", "TvApp 装配 pid=${Process.myPid()} 存档栈=${TvNavHistory.stack} 本次栈=$navStack")
        Unit
    }
    // 搜索页状态放在导航层：进详情再返回时结果不丢，真正退出搜索页才清空
    val searchState = remember { TvSearchUiState() }
    val push: (TvRoute) -> Unit = {
        Log.w("MyVidHubNav", "导航入栈 $it")
        navStack = navStack + it
    }
    val pop: () -> Unit = {
        if (navStack.size > 1) {
            Log.w("MyVidHubNav", "导航出栈 ${navStack.last()}")
            if (navStack.last() is TvRoute.Search) searchState.clear()
            if (navStack.last() is TvRoute.Detail) TvNavHistory.forgetEpisode()
            navStack = navStack.dropLast(1)
        }
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

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = navStack.last(),
            transitionSpec = { fadeIn(tween(Motion.BASE)).togetherWith(fadeOut(tween(Motion.FAST))) },
            label = "tv_route",
            modifier = Modifier.fillMaxSize()
        ) { target ->
            when (target) {
                is TvRoute.Home -> TvHomeScreen(
                    vm = vm,
                    onSearch = { push(TvRoute.Search) },
                    onDetail = { id -> push(TvRoute.Detail(id)) },
                    onGenre = { kind, genre -> push(TvRoute.Genre(kind, genre)) },
                    onCategory = { typeId, typeName -> push(TvRoute.Category(typeId, typeName)) },
                    onPlay = { item: MediaEntity ->
                        // Netflix 口径：退出播放器落详情页，而不是回到起播那张卡。
                        // 轮播 / 继续观看是直接起播的，起播前先把这一条目的详情页压进栈；
                        // 已经在详情页就不重复压。首页的滚动与落点由 ReturnSpot 记着，
                        // 从详情页再返回时还站在原来那张卡上。
                        if (navStack.last() !is TvRoute.Detail) push(TvRoute.Detail(item.id))
                        context.startActivity(TvPlayerActivity.intent(context, item.id))
                    },
                    onSources = { push(TvRoute.Sources) },
                    onSettings = { push(TvRoute.Settings) }
                )

                is TvRoute.Search -> TvSearchScreen(
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
