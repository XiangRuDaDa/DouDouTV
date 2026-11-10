package com.chen.doudoutv.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.components.GlassTabBar
import com.chen.doudoutv.ui.components.GlassTabItem
import com.chen.doudoutv.ui.components.SyncProgressBar
import com.chen.doudoutv.ui.components.glassBackdropCapture
import com.chen.doudoutv.ui.components.rememberGlassBackdrop
import com.chen.doudoutv.ui.detail.DetailScreen
import com.chen.doudoutv.ui.home.GenreScreen
import com.chen.doudoutv.ui.home.HomeScreen
import com.chen.doudoutv.ui.home.TvBoxCategoryScreen
import com.chen.doudoutv.ui.home.SearchScreen
import com.chen.doudoutv.ui.home.SearchUiState
import com.chen.doudoutv.ui.player.phone.PhonePlayerActivity
import com.chen.doudoutv.ui.settings.SettingsScreen
import com.chen.doudoutv.ui.sources.SourcesScreen
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Motion

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
    data class Genre(val kind: String?, val genre: String?, val library: String? = null) : PhoneRoute
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
    // 页面背景抓取器：玻璃底栏靠它做真模糊（录页面底部一条，离屏高斯后垫在栏底）
    val backdrop = rememberGlassBackdrop()

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
        snackbarHost = {
            // 玻璃底栏浮在内容之上，提示条默认贴在内容区底部会被它压住，额外让开一段
            SnackbarHost(snackbarHost, Modifier.padding(bottom = 84.dp))
        },
        containerColor = AppColors.Bg
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // 内容层：整屏离屏录一份到 backdrop，底栏靠它取「自己脚下那一条」做真模糊。
            // 录的是这一层而不是页面本身，所以底栏那条玻璃不会把自己也录进去。
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { backdrop.originInRoot = it.positionInRoot() }
                    .glassBackdropCapture(backdrop)
            ) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AnimatedContent(
                        targetState = navStack.last(),
                        transitionSpec = {
                            when {
                                targetState is PhoneRoute.Detail || targetState is PhoneRoute.Genre ->
                                    (slideInHorizontally(animationSpec = tween(Motion.SLOW)) { it / 6 } +
                                        fadeIn(tween(Motion.SLOW)))
                                        .togetherWith(fadeOut(tween(Motion.FAST)))

                                (initialState is PhoneRoute.Detail || initialState is PhoneRoute.Genre) &&
                                    targetState is PhoneRoute.Home ->
                                    (fadeIn(tween(Motion.SLOW)) +
                                        slideInHorizontally(animationSpec = tween(Motion.SLOW)) { -it / 12 })
                                        .togetherWith(
                                            slideOutHorizontally(animationSpec = tween(Motion.SLOW)) { it / 4 } +
                                                fadeOut(tween(Motion.BASE))
                                        )

                                else -> fadeIn(tween(Motion.BASE)).togetherWith(fadeOut(tween(Motion.FAST)))
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
                                onOpenGenre = { kind, genre, library -> push(PhoneRoute.Genre(kind, genre, library)) },
                                onOpenCategory = { tid, tname -> push(PhoneRoute.Category(tid, tname)) },
                                onPlay = { item: MediaEntity ->
                                    // Netflix 口径：退出播放器一律落在详情页，而不是回到起播那张卡。
                                    // 首页轮播 / 继续观看是直接起播的，这里在起播前把它的详情页压进栈；
                                    // 已经在详情页起播的不重复压，免得栈里叠两层同一部片。
                                    if (navStack.last() !is PhoneRoute.Detail) {
                                        push(PhoneRoute.Detail(item.id))
                                    }
                                    context.startActivity(PhonePlayerActivity.intent(context, item.id))
                                }
                            )

                            is PhoneRoute.Genre -> GenreScreen(
                                vm = vm,
                                isTv = false,
                                initialKind = target.kind,
                                initialGenre = target.genre,
                                initialLibrary = target.library,
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
                                onPlay = { id ->
                                    context.startActivity(PhonePlayerActivity.intent(context, id))
                                }
                            )

                            is PhoneRoute.Search -> SearchScreen(
                                vm = vm,
                                isTv = false,
                                state = searchState,
                                onOpenDetail = { id -> push(PhoneRoute.Detail(id)) }
                            )

                            is PhoneRoute.Sources -> SourcesScreen(vm = vm, isTv = false)
                            is PhoneRoute.Settings -> SettingsScreen(vm = vm)
                        }
                    }

                    // 同步进度线覆盖在内容区最上沿：不占布局高度，出现/消失都不推挤页面
                    if (vm.scanning) {
                        SyncProgressBar(
                            fetched = vm.scanFetched,
                            total = vm.scanTotal,
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                    }
                }
            }

            // 悬浮玻璃条：离底 16dp（再让开系统手势条），宽度收到 300dp 以内让左右流出空间。
            // 内容是从它底下穿过去的（页面自己留了 GlassBarClearance 的底部内边距），
            // 所以这条玻璃能看到下面滚过去的内容。
            GlassTabBar(
                items = PhoneTab.entries.map { GlassTabItem(it.label, it.icon) },
                selectedIndex = PhoneTab.entries.indexOf(currentTab),
                onSelect = { resetTo(PhoneTab.entries[it].route()) },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(vertical = 16.dp)
                    .widthIn(max = 300.dp)
                    .padding(horizontal = 12.dp)
            )
        }
    }
}

private fun PhoneTab.route(): PhoneRoute = when (this) {
    PhoneTab.HOME -> PhoneRoute.Home
    PhoneTab.SOURCES -> PhoneRoute.Sources
    PhoneTab.SETTINGS -> PhoneRoute.Settings
}
