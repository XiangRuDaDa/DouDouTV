package com.chen.doudoutv.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.data.scrape.ScrapeStrategy
import com.chen.doudoutv.core.AppCache
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.core.VideoCache
import com.chen.doudoutv.player.PlayerFactory
import com.chen.doudoutv.ui.components.TmdbDisclaimer
import com.chen.doudoutv.ui.components.ThemeSwatch
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.AppPalette
import com.chen.doudoutv.ui.theme.ThemePalettes
import com.chen.doudoutv.ui.theme.themeBackdrop
import com.chen.doudoutv.ui.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "app_prefs"
private const val KEY_FORCE_TV = "force_tv_mode"

/** 关于页的版本号：与 build.gradle.kts 的 versionName、CHANGELOG.md 一起改（发版纪律见 README「发版纪律」） */
internal const val APP_VERSION_LABEL = "DouDouTV 1.0.0"

/** 强制 TV 模式开关（测试用）。true 时覆盖系统判断 */
fun Context.forceTvMode(): Boolean =
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_TV, false)

fun Context.setForceTvMode(enabled: Boolean) {
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_FORCE_TV, enabled).apply()
}

/** 设置页上「点开才展开选项」的那几组，一次只开一个弹窗 */
internal enum class SettingChoice { PosterSize, Theme, VideoTier, CardMotion }

internal fun Prefs.PosterSize.label(): String = when (this) {
    Prefs.PosterSize.SMALL -> "小"
    Prefs.PosterSize.MEDIUM -> "中"
    Prefs.PosterSize.LARGE -> "大"
}

/** 焦点移动效果的两档文案（只在 TV 设置页出现，只有新版布局的海报卡读这个值） */
internal fun Prefs.CardMotion.label(): String = when (this) {
    Prefs.CardMotion.ART_ZOOM -> "放大"
    Prefs.CardMotion.LIFT -> "上抬"
}

@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val libraryCount by vm.libraryCount.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var forceTv by remember { mutableStateOf(ctx.forceTvMode()) }
    // 总开关：默认关闭。关闭时不渲染任何 TMDB 设置、数据来源与声明
    var tmdbOn by remember { mutableStateOf(Prefs.tmdbEnabled) }
    // 只保留 v4 持续访问令牌（官方推荐，令牌走请求头不进 URL）
    var tmdbToken by remember { mutableStateOf(Prefs.tmdbReadToken) }

    // 凭据变更计数：自增即触发重组，并重新判定刮削引擎
    var engineTick by remember { mutableStateOf(0) }
    // 只在记忆化里做纯计算（读凭据推导），不写全局状态
    val activeEngine = remember(engineTick) { ScrapeStrategy.resolve() }

    // 写操作放到副作用里：凭据一变就同步给全局，供其他页面读取
    LaunchedEffect(engineTick) { ScrapeStrategy.refresh() }

    // 界面与缓存的可选项收进弹窗：页面上只留「分组 + 当前值」，点哪一行才展开那一组的档位
    var choice by remember { mutableStateOf<SettingChoice?>(null) }
    // Prefs 背后是 SharedPreferences，写它不会触发重组；行上的当前值得自己留一份状态
    var posterSize by remember { mutableStateOf(Prefs.posterSize) }
    var videoTier by remember { mutableStateOf(Prefs.videoCacheTier) }
    var videoCacheBytes by remember { mutableStateOf(0L) }
    var imageCacheBytes by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    // 体积要读 SQLite 索引（缓存没建起来时是遍历目录），不能压在主线程；
    // 换档与清空都在 IO 线程就地重读，这里只在进页时读一次
    LaunchedEffect(Unit) {
        videoCacheBytes = withContext(Dispatchers.IO) { VideoCache.usedBytes() }
        imageCacheBytes = withContext(Dispatchers.IO) { AppCache.sizeBytes(ctx) }
    }

    // 背景光晕画在不滚动的外层：挂在 verticalScroll 节点上，光晕会跟着内容一起滚走，
    // 滚到底就只剩一片纯黑，尺寸也要按整页内容高算。
    Box(Modifier.fillMaxSize().themeBackdrop()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp, 20.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // ---------- 状态 ----------
            SettingCard(title = "状态") {
                StatRow("来源数量", "${sources.size}")
                StatRow("媒体条目", "$libraryCount")
                StatRow(
                    "Emby 已登录",
                    sources.count { it.kind == com.chen.doudoutv.data.model.SourceKind.EMBY && it.token.isNotBlank() }
                        .toString()
                )
            }

            // ---------- 界面 ----------
            SettingCard(title = "界面") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(AppColors.SurfaceHi, Radius.Sm)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "强制 TV 模式",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary
                        )
                        Text(
                            "手机上开启可模拟 TV 界面布局，重启后生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )
                    }
                    Switch(
                        checked = forceTv,
                        onCheckedChange = { forceTv = it; ctx.setForceTvMode(it) }
                    )
                }

                ValueRow("缩略图大小", posterSize.label()) { choice = SettingChoice.PosterSize }
                ValueRow("主题色", AppPalette.current.label) { choice = SettingChoice.Theme }
            }

            // ---------- 刮削 ----------
            // 设计原则：默认关闭。开关关掉时，这里只有一行开关，
            // 令牌、镜像、状态、缓存、声明全部不渲染，界面上看不到任何 TMDB 痕迹。
            SettingCard(title = "刮削") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "使用 TMDB 刮削",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextPrimary
                            )
                            Text(
                                "开启后自动补全海报、评分、简介与演职员；关闭则只用本地元数据。",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                        }
                        Switch(
                            checked = tmdbOn,
                            onCheckedChange = {
                                tmdbOn = it
                                Prefs.tmdbEnabled = it
                                engineTick++
                            }
                        )
                    }

                    // ---- 以下仅在开关打开时出现 ----
                    if (tmdbOn) {
                        Text(
                            "Read Access Token（v4）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary
                        )
                        OutlinedTextField(
                            value = tmdbToken,
                            onValueChange = {
                                tmdbToken = it
                                Prefs.tmdbReadToken = it
                                // 填了令牌就自动打开总开关，避免「密钥填了但刮削没生效」的困惑
                                if (it.isNotBlank() && !tmdbOn) {
                                    tmdbOn = true
                                    Prefs.tmdbEnabled = true
                                }
                                engineTick++
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("粘贴以 eyJ 开头的令牌") },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AppColors.TextPrimary,
                                unfocusedTextColor = AppColors.TextSecondary,
                                focusedBorderColor = AppColors.Brand,
                                unfocusedBorderColor = AppColors.SurfaceHi
                            )
                        )
                        Text(
                            "TMDB 账号 → Settings → API → Read Access Token，整串复制。",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )

                        // ---- 生效状态 ----
                        val tmdbActive = activeEngine == com.chen.doudoutv.data.scrape.ScrapeEngine.TMDB
                        Text(
                            when {
                                tmdbActive -> "当前方案：TMDB 刮削"
                                Prefs.tmdbReadToken.isBlank() -> "当前方案：本地元数据（尚未填写令牌）"
                                else -> "当前方案：本地元数据"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tmdbActive) AppColors.Brand else AppColors.TextSecondary
                        )
                    }

                    // ---- 清除刮削缓存：始终可见 ----
                    // 关掉 tmdbOn 后 scraped_meta 表里的旧条目还在，用户照样可能想清掉；
                    // 没开过 TMDB 也可能有残留条目需要清。
                    // 条数来自 scraped_meta 表，要查库，不能在组合期同步读；进这一段时异步填一次
                    var cacheCount by remember { mutableStateOf(0) }
                    var clearing by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { cacheCount = vm.scrapeCacheSize() }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "刮削缓存",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextPrimary
                            )
                            Text(
                                "已记住 $cacheCount 部影片，同名同年不再联网重刮。",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                        }
                        Button(
                            onClick = {
                                if (clearing) return@Button
                                clearing = true
                                scope.launch {
                                    vm.clearScrapeCache()
                                    cacheCount = vm.scrapeCacheSize()
                                    clearing = false
                                }
                            },
                            shape = Radius.Md,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.SurfaceHi,
                                contentColor = AppColors.TextPrimary
                            )
                        ) {
                            Text(
                                if (clearing) "清除中…" else "清除缓存",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // ---------- 数据来源与声明：仅开启 TMDB 时出现 ----------
            if (tmdbOn) {
                SettingCard(title = "数据来源与声明") {
                    TmdbDisclaimer()
                }
            }

            // ---------- 缓存 ----------
            SettingCard(title = "缓存") {
                ValueRow("视频缓存容量上限", videoTier.label) { choice = SettingChoice.VideoTier }
                ActionRow(
                    label = "视频缓存占用",
                    value = PlayerFactory.formatBytes(videoCacheBytes),
                    hint = "看过的内容在本机留一份：往回拖进度、重看同一集不再重复下载；看完的自动删。",
                    actionLabel = "清空",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VideoCache.clearAll()
                                videoCacheBytes = VideoCache.usedBytes()
                            }
                        }
                    }
                )
                ActionRow(
                    label = "图片缓存占用",
                    value = PlayerFactory.formatBytes(imageCacheBytes),
                    hint = "封面与海报的本地副本，清除后浏览时重新下载。",
                    actionLabel = "清除",
                    onClick = {
                        // 清缓存同样是磁盘操作，不能压在点击回调里同步跑
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppCache.clear(ctx)
                                imageCacheBytes = AppCache.sizeBytes(ctx)
                            }
                        }
                    }
                )
            }

            // ---------- 关于（精简） ----------
            SettingCard(title = "关于") {
                Text(
                    APP_VERSION_LABEL,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary
                )
                Text(
                    "自用安卓媒体中心，手机与电视同一安装包。\n" +
                        "Media3 播放内核 · Emby 直连 · TVBox 兼容 · 断点续播 · 海报墙",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )
            }

            Text(
                "播放器为自研实现，请仅播放你拥有合法权利的内容。",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary.copy(alpha = 0.7f)
            )
        }

        // ---------- 选项弹窗：一次只开一个 ----------
        when (choice) {
            SettingChoice.PosterSize -> ChoiceDialog(
                title = "缩略图大小",
                options = Prefs.PosterSize.values().toList(),
                label = { it.label() },
                selected = posterSize,
                hint = null,
                onPick = { Prefs.posterSize = it; posterSize = it },
                onDismiss = { choice = null }
            )

            SettingChoice.Theme -> ChoiceDialog(
                title = "主题色",
                options = ThemePalettes,
                label = { it.label },
                selected = AppPalette.current,
                swatch = { palette ->
                    ThemeSwatch(palette, selected = palette.id == AppPalette.current.id, onClick = { })
                },
                hint = "只换强调色与背景氛围，文字层级与中性底色保持不变。",
                onPick = { AppPalette.select(it.id) },
                onDismiss = { choice = null }
            )

            SettingChoice.VideoTier -> ChoiceDialog(
                title = "视频缓存容量上限",
                options = Prefs.VideoCacheTier.values().toList(),
                label = { it.label },
                selected = videoTier,
                hint = "只给看过的网盘内容留一份，看完的自动删，超出上限先清最久没看的。",
                onPick = { t ->
                    val previous = Prefs.videoCacheTier
                    if (previous != t) {
                        Prefs.videoCacheTier = t
                        videoTier = t
                        // 降档（含关闭）要清空目录，占用数字跟着重读；都在 IO 线程做
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VideoCache.applyTier(t.maxBytes, previous.maxBytes)
                                videoCacheBytes = VideoCache.usedBytes()
                            }
                        }
                    }
                },
                onDismiss = { choice = null }
            )

            // 这一组只挂在 TV 设置页：手机端没有对应开关，落到这里就是空转
            SettingChoice.CardMotion, null -> Unit
        }
    }
}

/**
 * 通用选项弹窗：标题 + 一行一个档位 + 底部一句说明。
 *
 * 选中即生效但弹窗不关（多档设置常连着调），退出走返回键或点弹窗外。
 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    selected: T,
    swatch: (@Composable (T) -> Unit)? = null,
    hint: String?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .background(AppColors.Surface, Radius.Lg)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Brand
                )
                options.forEach { option ->
                    val isCurrent = option == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) AppColors.Brand.copy(alpha = 0.16f) else AppColors.SurfaceHi,
                                Radius.Sm
                            )
                            .clickable { onPick(option) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        swatch?.invoke(option)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            label(option),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) AppColors.Brand else AppColors.TextPrimary
                        )
                        if (isCurrent) {
                            Text("当前", style = MaterialTheme.typography.labelSmall, color = AppColors.Brand)
                        }
                    }
                }
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }
        }
    }
}

/** 点开弹窗的条目行：左边标题、右边当前值，整行可点 */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceHi, Radius.Sm)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Brand
        )
    }
}

/** 当前值 + 一个直接生效的操作按钮（清空缓存这类，不弹确认） */
@Composable
private fun ActionRow(
    label: String,
    value: String,
    hint: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(AppColors.SurfaceHi, Radius.Sm)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label：$value",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onClick,
                shape = Radius.Sm,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Brand,
                    contentColor = Color.White
                )
            ) { Text(actionLabel, style = MaterialTheme.typography.labelMedium) }
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = Radius.Md,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Brand
            )
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceHi, Radius.Sm)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary
        )
    }
}
