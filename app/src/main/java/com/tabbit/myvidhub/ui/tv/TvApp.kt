package com.tabbit.myvidhub.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.player.tv.TvPlayerActivity
import com.tabbit.myvidhub.ui.settings.SettingsScreen
import com.tabbit.myvidhub.ui.sources.TvSourcesScreen

/**
 * 电视端外壳（完全独立于手机端 PhoneApp）。
 *
 * 导航用栈（navStack）：
 *   - 进详情、分类、搜索 = 入栈
 *   - Back 键 = 出栈，回到进入当前页之前的页面（而不是强制跳首页）
 *   - Sources/Settings 这类"根级 Tab"也用栈维护，但首次进入时可以 reset
 *   - 遥控器 Back 键会触发 BackHandler；若栈只有 Home 则交给系统默认（退出）
 */
internal sealed interface TvRoute {
    data object Home : TvRoute
    data object Search : TvRoute
    data class Detail(val mediaId: Long) : TvRoute
    data class Genre(val kind: String?, val genre: String?) : TvRoute
    data object Sources : TvRoute
    data object Settings : TvRoute
}

@Composable
fun TvApp(vm: LibraryViewModel) {
    val context = LocalContext.current
    var navStack by remember { mutableStateOf<List<TvRoute>>(listOf(TvRoute.Home)) }
    // 搜索页状态放在导航层：进详情再返回时结果不丢，真正退出搜索页才清空
    val searchState = remember { TvSearchUiState() }
    val push: (TvRoute) -> Unit = { navStack = navStack + it }
    val pop: () -> Unit = {
        if (navStack.size > 1) {
            if (navStack.last() is TvRoute.Search) searchState.clear()
            navStack = navStack.dropLast(1)
        }
    }

    BackHandler(enabled = navStack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = navStack.last(),
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
            label = "tv_route",
            modifier = Modifier.fillMaxSize()
        ) { target ->
            when (target) {
                is TvRoute.Home -> TvHomeScreen(
                    vm = vm,
                    onSearch = { push(TvRoute.Search) },
                    onDetail = { id -> push(TvRoute.Detail(id)) },
                    onGenre = { kind, genre -> push(TvRoute.Genre(kind, genre)) },
                    onPlay = { item: MediaEntity ->
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

                is TvRoute.Detail -> TvDetailScreen(
                    vm = vm,
                    mediaId = target.mediaId,
                    onBack = pop,
                    onPlay = { id ->
                        context.startActivity(TvPlayerActivity.intent(context, id))
                    }
                )

                is TvRoute.Sources -> TvSourcesScreen(vm = vm)
                is TvRoute.Settings -> SettingsScreen(vm = vm)
            }
        }
    }
}
