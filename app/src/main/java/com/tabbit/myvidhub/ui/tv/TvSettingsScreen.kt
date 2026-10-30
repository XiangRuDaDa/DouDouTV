package com.tabbit.myvidhub.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.scrape.ScrapeEngine
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.player.PlayerFactory
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.components.TmdbDisclaimer
import com.tabbit.myvidhub.ui.components.ThemeSwatch
import com.tabbit.myvidhub.ui.settings.APP_VERSION_LABEL
import com.tabbit.myvidhub.ui.settings.forceTvMode
import com.tabbit.myvidhub.ui.settings.setForceTvMode
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.AppPalette
import com.tabbit.myvidhub.ui.theme.ThemePalettes
import com.tabbit.myvidhub.ui.theme.TvText
import com.tabbit.myvidhub.ui.theme.themeBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电视端设置页 —— 手机端 SettingsScreen 的十英尺版本。
 *
 * 分组和内容与手机端一一对应（状态 / 界面 / 刮削 / 数据来源 / 播放缓存 / 关于），
 * 但控件全部换掉：手机端的 Switch、AssistChip、AlertDialog 是靠手指点的，
 * 隔着三米用遥控器操作，可聚焦区域至少要 40dp 高、焦点态必须有白边，
 * 所以这里统一用「整行可聚焦 + 右侧回显当前值」的十英尺设置列表。
 *
 * 纵向走位交给默认几何搜索：这一页是一条单列的可聚焦序列，
 * 上下移动属于同一功能区（规范 R1 禁的是跨功能区依赖几何搜索）；
 * 用 Column + verticalScroll 而不是 LazyColumn，是为了让所有行从一开始就全部组合，
 * 几何搜索不会因为目标行还没被组合而找不到落点（R8）。
 * 入口抢焦与「回到本页重抢」走 [tvClaimFocusOnEnter]（R7）。
 */
@Composable
fun TvSettingsScreen(vm: LibraryViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var forceTv by remember { mutableStateOf(ctx.forceTvMode()) }
    // 缩略图档位单独留一份状态：Prefs 背后是 SharedPreferences，读它不会触发重组，
    // 只写不读状态的话选中圆点永远停在按下前那一格（手机端设置页同样是这么处理的）
    var posterSize by remember { mutableStateOf(Prefs.posterSize) }
    var tmdbOn by remember { mutableStateOf(Prefs.tmdbEnabled) }
    var tmdbToken by remember { mutableStateOf(Prefs.tmdbReadToken) }
    var engineTick by remember { mutableStateOf(0) }
    // 只在记忆化里做纯计算（读凭据推导），写全局状态放到副作用里
    val activeEngine = remember(engineTick) { ScrapeStrategy.resolve() }
    LaunchedEffect(engineTick) { ScrapeStrategy.refresh() }

    var cacheCount by remember { mutableStateOf(vm.scrapeCacheSize()) }
    var clearing by remember { mutableStateOf(false) }
    var playerCache by remember { mutableStateOf(0L) }

    val scrollState = rememberScrollState()
    val entryFocus = remember { FocusRequester() }
    val resumeTick = tvResumeTick()
    tvClaimFocusOnEnter(
        resumeTick,
        entryFocus,
        scrollToTop = { scrollState.scrollTo(0) }
    )
    // 播放缓存是播放器写出来的，只在进页读一次会停在旧值上：回到这一页（resume tick）再读一遍。
    // 整棵缓存目录树的遍历放 IO 线程，不在主线程上按文件数卡帧。
    LaunchedEffect(resumeTick) {
        playerCache = withContext(Dispatchers.IO) { PlayerFactory.cacheSizeBytes(ctx) }
        cacheCount = vm.scrapeCacheSize()
    }

    // 光晕画在不滚动的外层：挂在 verticalScroll 节点上会跟内容一起滚走，
    // 滚到底就只剩纯黑，而且 Brush 尺寸要按整页内容高算
    Box(Modifier.fillMaxSize().themeBackdrop()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = TvPagePadding, end = TvPagePadding, top = 20.dp, bottom = 48.dp)
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = TvText.Title,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))

            // 内容列限宽：电视上一整行拉到 1800dp，三米外读到行尾就失焦了
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1080.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                TvSettingsGroup("状态") {
                    TvStatRow("来源数量", "${sources.size}")
                    TvStatRow("媒体条目", "${library.size}")
                    TvStatRow(
                        "Emby 已登录",
                        "${sources.count { it.kind == SourceKind.EMBY && it.token.isNotBlank() }}"
                    )
                }

                TvSettingsGroup("界面") {
                    TvSettingRow(
                        title = "强制 TV 模式",
                        hint = "手机上开启可模拟 TV 界面布局，重启后生效",
                        focusRequester = entryFocus,
                        onClick = {
                            forceTv = !forceTv
                            ctx.setForceTvMode(forceTv)
                        },
                        value = if (forceTv) "已开启" else "已关闭",
                        accent = forceTv
                    )

                    TvSettingRow(title = "缩略图大小", hint = null) {
                        Prefs.PosterSize.values().forEach { s ->
                            Spacer(Modifier.width(10.dp))
                            TvFilterChip(
                                text = when (s) {
                                    Prefs.PosterSize.SMALL -> "小"
                                    Prefs.PosterSize.MEDIUM -> "中"
                                    Prefs.PosterSize.LARGE -> "大"
                                },
                                selected = s == posterSize,
                                onClick = { Prefs.posterSize = s; posterSize = s }
                            )
                        }
                    }

                    TvSettingRow(title = "主题色", hint = null) {
                        ThemePalettes.forEach { palette ->
                            ThemeSwatch(
                                palette = palette,
                                selected = palette.id == AppPalette.current.id,
                                onClick = { AppPalette.select(palette.id) }
                            )
                        }
                    }
                    Text(
                        "主题色只换强调色与背景氛围，文字层级和中性底色保持不变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvText.Meta
                    )
                }

                TvSettingsGroup("刮削") {
                    TvSettingRow(
                        title = "使用 TMDB 刮削",
                        hint = "开启后自动补全海报、评分、简介与演职员",
                        onClick = {
                            tmdbOn = !tmdbOn
                            Prefs.tmdbEnabled = tmdbOn
                            engineTick++
                        },
                        value = if (tmdbOn) "已开启" else "已关闭",
                        accent = tmdbOn
                    )

                    if (tmdbOn) {
                        TvSettingField(
                            label = "Read Access Token（v4）",
                            value = tmdbToken,
                            placeholder = "粘贴以 eyJ 开头的令牌",
                            onValueChange = {
                                tmdbToken = it
                                Prefs.tmdbReadToken = it
                                // 填了令牌就自动打开总开关，避免「密钥填了但刮削没生效」的困惑
                                if (it.isNotBlank() && !tmdbOn) {
                                    tmdbOn = true
                                    Prefs.tmdbEnabled = true
                                }
                                engineTick++
                            }
                        )
                        Text(
                            "TMDB 账号 → Settings → API → Read Access Token，整串复制。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvText.Meta
                        )

                        val tmdbActive = activeEngine == ScrapeEngine.TMDB
                        Text(
                            when {
                                tmdbActive -> "当前方案：TMDB 刮削"
                                Prefs.tmdbReadToken.isBlank() -> "当前方案：本地元数据（尚未填写令牌）"
                                else -> "当前方案：本地元数据"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (tmdbActive) AppColors.Brand else TvText.Body
                        )

                        TvSettingAction(
                            title = "刮削缓存",
                            hint = "已缓存 $cacheCount 条，同一影片 7 天内不重复请求",
                            actionLabel = if (clearing) "清除中…" else "清除缓存",
                            onClick = {
                                if (clearing) return@TvSettingAction
                                clearing = true
                                scope.launch {
                                    vm.clearScrapeCache()
                                    cacheCount = vm.scrapeCacheSize()
                                    clearing = false
                                }
                            }
                        )
                    }
                }

                if (tmdbOn) {
                    TvSettingsGroup("数据来源与声明") {
                        TmdbDisclaimer()
                    }
                }

                TvSettingsGroup("播放缓存") {
                    TvSettingAction(
                        title = "当前占用：${PlayerFactory.formatBytes(playerCache)}",
                        hint = "播放产生的临时缓存，关闭播放器时自动清理，也可手动释放存储",
                        actionLabel = "清除缓存",
                        onClick = {
                            // 删目录是整棵缓存树的磁盘遍历，读大小同理：都不该压在按钮点击里跑主线程
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    PlayerFactory.clearCache(ctx)
                                    playerCache = PlayerFactory.cacheSizeBytes(ctx)
                                }
                            }
                        }
                    )
                }

                TvSettingsGroup("关于") {
                    Text(
                        APP_VERSION_LABEL,
                        style = MaterialTheme.typography.titleMedium,
                        color = TvText.Title
                    )
                    Text(
                        "自用安卓媒体中心，手机与电视同一安装包。\n" +
                            "Media3 播放内核 · Emby 直连 · TVBox 兼容 · 断点续播 · 海报墙",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvText.Body
                    )
                }

                Text(
                    "播放器为自研实现，请仅播放你拥有合法权利的内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvText.Muted
                )
            }
        }
    }
}

/** 分组：品牌色小标题 + 一张卡片，卡内按顺序放行 */
@Composable
private fun TvSettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.Brand)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.Surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/** 纯信息行：不可聚焦，遥控器上下移动时会被自然跳过 */
@Composable
private fun TvStatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppColors.SurfaceHi, RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TvText.Body,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TvText.Title)
    }
}

/**
 * 可聚焦的设置行：整行是焦点单位，右侧回显当前值，OK 键直接切换。
 *
 * 焦点态用「白边 + 轻微放大 + 抬 zIndex」，与 TvPillButton / TvPosterCard 一致：
 * 电视上涟漪和阴影看不见，只有描边和尺寸变化能在三米外读出来。
 */
@Composable
private fun TvSettingRow(
    title: String,
    hint: String?,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
    value: String? = null,
    accent: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.015f else 1f, tween(140), label = "tv_setting_row")

    Row(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) AppColors.SurfaceHi else AppColors.Bg.copy(alpha = 0.35f))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onClick != null,
                onClick = { onClick?.invoke() }
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TvText.Title)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvText.Meta
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = if (accent) AppColors.Brand else TvText.Body
            )
        }
        trailing?.invoke(this)
    }
}

/** 带操作按钮的行：标题在左、主操作在右，焦点落在按钮上 */
@Composable
private fun TvSettingAction(
    title: String,
    hint: String?,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TvText.Title
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvText.Meta,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        TvPillButton(text = actionLabel, primary = false, size = TvButtonSize.Md, onClick = onClick)
    }
}

/**
 * 令牌输入行。
 *
 * 电视上没有物理键盘可用时，聚焦输入框会拉起系统软键盘（与来源页的接口地址输入同一条路径）；
 * 60dp 高的框是为遥控器光标准备的，矮了看不清插入点。
 */
@Composable
private fun TvSettingField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = TvText.Title)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = TvText.Meta) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvText.Title),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = AppColors.Bg,
                focusedContainerColor = AppColors.Bg,
                unfocusedBorderColor = AppColors.SurfaceHi,
                focusedBorderColor = AppColors.Brand,
                cursorColor = AppColors.Brand,
                focusedTextColor = TvText.Title,
                unfocusedTextColor = TvText.Title
            )
        )
    }
}
