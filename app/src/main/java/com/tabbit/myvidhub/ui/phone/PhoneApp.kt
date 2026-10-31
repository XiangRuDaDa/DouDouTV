package com.tabbit.myvidhub.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.detail.DetailScreen
import com.tabbit.myvidhub.ui.home.GenreScreen
import com.tabbit.myvidhub.ui.home.HomeScreen
import com.tabbit.myvidhub.ui.home.TvBoxCategoryScreen
import com.tabbit.myvidhub.ui.home.SearchScreen
import com.tabbit.myvidhub.ui.home.SearchUiState
import com.tabbit.myvidhub.ui.player.phone.PhonePlayerActivity
import com.tabbit.myvidhub.ui.settings.SettingsScreen
import com.tabbit.myvidhub.ui.sources.SourcesScreen
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 手机端外壳（与电视端完全独立的一套代码）。
 *
 * 交互前提：触摸 + 竖屏为主，底部标签栏切换，页面之间左右推入。
 * 电视端不共用这里的任何组件，见 ui/tv/TvApp.kt。
 *
 * 导航用栈（[navStack]）：
 * - 跳转详情、分类等子页面 = 入栈
 * - 返回 = 出栈（自动回到进入详情页之前的那个页面）
 * - 切底栏 Tab = 重置为该 Tab 的根页面（不保留旧栈）
 */

internal sealed interface PhoneRoute {
    data object Home : PhoneRoute
    data object Sources : PhoneRoute
    data object Settings : PhoneRoute
    data object Search : PhoneRoute
    data class Detail(val mediaId: Long) : PhoneRoute
    data class Genre(val kind: String?, val genre: String?) : PhoneRoute
    data class Category(val typeId: String, val typeName: String) : PhoneRoute
}

internal enum class PhoneTab(val label: String, val icon: ImageVector) {
    HOME("媒体库", Icons.Filled.Home),
    SOURCES("来源", Icons.Filled.Storage),
    SETTINGS("设置", Icons.Filled.Settings)
}

@Composable
fun PhoneApp(vm: LibraryViewModel) {
    val context = LocalContext.current
    // 导航栈：初始在 Home；底栏切换会重置栈，详情/搜索/分类会入栈
    var navStack by remember { mutableStateOf<List<PhoneRoute>>(listOf(PhoneRoute.Home)) }
    // 搜索页状态放在导航层：进详情再返回时结果不丢，真正退出搜索页才清空
    val searchState = remember { SearchUiState() }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        val msg = vm.message
        if (!msg.isNullOrBlank()) {
            snackbarHost.showSnackbar(msg)
            vm.message = null
        }
    }

    // 弹出栈顶 = 返回上一个页面；已经在根时系统会自己拦截
    val goBack: () -> Unit = {
        if (navStack.size > 1) {
            // 真正离开搜索页才清空搜索状态（详情 → 搜索属于栈内返回，不清）
            if (navStack.last() is PhoneRoute.Search) searchState.clear()
            navStack = navStack.dropLast(1)
        }
    }
    val push: (PhoneRoute) -> Unit = { r ->
        navStack = navStack + r
    }
    val resetTo: (PhoneRoute) -> Unit = { r ->
        // 切底栏 = 离开当前页面栈，搜索状态一并清空
        searchState.clear()
        navStack = listOf(r)
    }

    BackHandler(enabled = navStack.size > 1) {
        goBack()
    }

    val currentTab = when (navStack.last()) {
        is PhoneRoute.Home, is PhoneRoute.Detail, is PhoneRoute.Search, is PhoneRoute.Genre, is PhoneRoute.Category -> PhoneTab.HOME
        is PhoneRoute.Sources -> PhoneTab.SOURCES
        is PhoneRoute.Settings -> PhoneTab.SETTINGS
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = AppColors.Bg,
        bottomBar = {
            NavigationBar(
                containerColor = AppColors.Surface,
                tonalElevation = 0.dp,
                modifier = Modifier.height(52.dp)
            ) {
                PhoneTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = currentTab == t,
                        onClick = { resetTo(t.route()) },
                        icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(20.dp)) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.Brand,
                            selectedTextColor = AppColors.Brand,
                            unselectedIconColor = AppColors.TextTertiary,
                            unselectedTextColor = AppColors.TextTertiary,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = navStack.last(),
                transitionSpec = {
                    when {
                        targetState is PhoneRoute.Detail || targetState is PhoneRoute.Genre ->
                            (slideInHorizontally(animationSpec = tween(300)) { it / 6 } +
                                fadeIn(tween(260)))
                                .togetherWith(fadeOut(tween(160)))

                        (initialState is PhoneRoute.Detail || initialState is PhoneRoute.Genre) &&
                            targetState is PhoneRoute.Home ->
                            (fadeIn(tween(260)) +
                                slideInHorizontally(animationSpec = tween(300)) { -it / 12 })
                                .togetherWith(
                                    slideOutHorizontally(animationSpec = tween(260)) { it / 4 } +
                                        fadeOut(tween(200))
                                )

                        else -> fadeIn(tween(220)).togetherWith(fadeOut(tween(160)))
                    }
                },
                label = "phone_route",
                modifier = Modifier.fillMaxSize()
            ) { target ->
                when (target) {
                    is PhoneRoute.Home -> HomeScreen(
                        vm = vm,
                        isTv = false,
                        onOpenSearch = { push(PhoneRoute.Search) },
                        onOpenDetail = { id -> push(PhoneRoute.Detail(id)) },
                        onOpenGenre = { kind, genre -> push(PhoneRoute.Genre(kind, genre)) },
                        onOpenCategory = { tid, tname -> push(PhoneRoute.Category(tid, tname)) },
                        onPlay = { item: MediaEntity ->
                            context.startActivity(PhonePlayerActivity.intent(context, item.id))
                        }
                    )

                    is PhoneRoute.Genre -> GenreScreen(
                        vm = vm,
                        isTv = false,
                        initialKind = target.kind,
                        initialGenre = target.genre,
                        onBack = goBack,
                        onOpenDetail = { id -> push(PhoneRoute.Detail(id)) }
                    )

                    is PhoneRoute.Category -> TvBoxCategoryScreen(
                        vm = vm,
                        typeId = target.typeId,
                        typeName = target.typeName,
                        onBack = goBack,
                        onOpenDetail = { id -> push(PhoneRoute.Detail(id)) }
                    )

                    is PhoneRoute.Detail -> DetailScreen(
                        vm = vm,
                        mediaId = target.mediaId,
                        isTv = false,
                        onBack = goBack,
                        onPlay = { id ->
                            context.startActivity(PhonePlayerActivity.intent(context, id))
                        }
                    )

                    is PhoneRoute.Search -> SearchScreen(
                        vm = vm,
                        isTv = false,
                        state = searchState,
                        onBack = goBack,
                        onOpenDetail = { id -> push(PhoneRoute.Detail(id)) }
                    )

                    is PhoneRoute.Sources -> SourcesScreen(vm = vm, isTv = false)
                    is PhoneRoute.Settings -> SettingsScreen(vm = vm)
                }
            }
        }
    }
}

private fun PhoneTab.route(): PhoneRoute = when (this) {
    PhoneTab.HOME -> PhoneRoute.Home
    PhoneTab.SOURCES -> PhoneRoute.Sources
    PhoneTab.SETTINGS -> PhoneRoute.Settings
}
