package com.tabbit.myvidhub.ui.sources

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.TvText
import com.tabbit.myvidhub.ui.tv.TvFilterChip
import com.tabbit.myvidhub.ui.tv.TvIconButton
import com.tabbit.myvidhub.ui.tv.TvPillButton

/**
 * TV 端来源管理 —— 10 英尺界面。
 *
 * 与手机端 SourcesScreen 完全分离：
 *   · 单列 → 三列大卡片网格（响应式，小屏 2 列 / 1080p 3 列 / 4K 4+ 列）
 *   · AlertDialog → 全屏遮罩面板表单（900dp 宽）
 *   · 无 TV 焦点态 → 对齐 TvPosterCard / TvPillButton 组件库焦点模式
 *   · 无删除确认 → 新增红色危险对话框，默认聚焦"取消"防误触
 *
 * 核心组件：TvSourceCard / TvSourceFormPanel / TvDeleteConfirmDialog
 *
 * TV 组件复用：TvPillButton / TvIconButton / TvBackButton（TvComponents.kt）
 */
@Composable
fun TvSourcesScreen(vm: LibraryViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    var deleting by remember { mutableStateOf<SourceEntity?>(null) }
    // 卡片 OK 键打开的功能菜单（启停/刷新/编辑/删除）
    var menuSource by remember { mutableStateOf<SourceEntity?>(null) }

    val activeHomeId = vm.effectiveHomeSourceId()
    val embys = remember(sources) { sources.filter { it.kind == SourceKind.EMBY } }
    val tvboxes = remember(sources) { sources.filter { it.kind == SourceKind.TVBOX } }

    BoxWithConstraints {
        val screenWidth = maxWidth
        // 响应式列数：**290dp 基准**（75 寸 1080p ≈ 5 列，1080p ≈ 5-6 列，4K ≈ 12+ 列）；最小 3 列
        val columns = maxOf(3, (screenWidth / 290.dp).toInt())
        val cardGap = 16.dp
        val usableWidth = screenWidth - 72.dp // 左右各 36dp
        val cardWidth = (usableWidth - cardGap * (columns - 1)) / columns
        val cardHeight = (cardWidth * 0.72f).coerceIn(210.dp, 280.dp)

        Box(Modifier.fillMaxSize().background(AppColors.Bg)) {

            // ========== 顶部栏 ==========
            Row(
                Modifier.fillMaxWidth().padding(top = 0.dp, start = 20.dp, end = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text(
                            text = "视频来源",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TvText.Title,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "支持 Emby 媒体服务器与 TVBox 接口 · 共 ${sources.size} 个来源",
                            style = MaterialTheme.typography.labelSmall,
                            color = TvText.Meta
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vm.scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.Brand
                        )
                        Text(
                            text = "同步中…",
                            style = MaterialTheme.typography.labelMedium,
                            color = TvText.Body
                        )
                    } else {
                        TvPillButton(
                            text = "全部同步",
                            primary = false,
                            compact = true,
                            onClick = { vm.syncAll() }
                        )
                    }
                    TvPillButton(
                        text = "＋ 添加来源",
                        primary = true,
                        compact = true,
                        onClick = { showAdd = true }
                    )
                }
            }

            // ========== 内容区 ==========
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().padding(top = 110.dp, start = 36.dp, end = 36.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(cardGap),
                verticalArrangement = Arrangement.spacedBy(cardGap),
                contentPadding = PaddingValues(top = 4.dp)
            ) {
                // ---- Emby 分组标题 ----
                if (embys.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(this.maxLineSpan) }) {
                        TvGroupHeader(title = "Emby 媒体服务器", count = embys.size)
                    }
                    items(embys, key = { "emby-${it.id}" }) { src ->
                        TvSourceCard(
                            source = src,
                            width = cardWidth,
                            height = cardHeight,
                            isActiveHome = src.id == activeHomeId,
                            busy = vm.isSourceSyncing(src.id),
                            count = vm.sourceCounts[src.id],
                            lastSync = vm.lastSyncOf(src.id),
                            onMenu = { menuSource = src }
                        )
                    }
                }

                // ---- TVBox 分组标题 ----
                if (tvboxes.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(this.maxLineSpan) }) {
                        TvGroupHeader(title = "TVBox 接口", count = tvboxes.size)
                    }
                    items(tvboxes, key = { "tvbox-${it.id}" }) { src ->
                        TvSourceCard(
                            source = src,
                            width = cardWidth,
                            height = cardHeight,
                            isActiveHome = src.id == activeHomeId,
                            busy = vm.isSourceSyncing(src.id),
                            count = vm.sourceCounts[src.id],
                            lastSync = vm.lastSyncOf(src.id),
                            onMenu = { menuSource = src }
                        )
                    }
                }

                // ---- 空态提示 ----
                if (sources.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(this.maxLineSpan) }) {
                        TvEmptySourcesHint()
                    }
                }
            }
        }
    }

    // ========== 添加 / 编辑表单 ==========
    if (showAdd) {
        TvSourceFormPanel(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { name, kind, location, user, pass ->
                vm.addSource(name, kind, location, user, pass)
                showAdd = false
            }
        )
    }
    editing?.let { src ->
        TvSourceFormPanel(
            initial = src,
            onDismiss = { editing = null },
            onConfirm = { name, kind, location, user, pass ->
                vm.updateSource(src.id, name, kind, location, user, pass)
                editing = null
            }
        )
    }

    // ========== 删除二次确认 ==========
    deleting?.let { src ->
        TvDeleteConfirmDialog(
            source = src,
            itemCount = vm.sourceCounts[src.id] ?: 0,
            onDismiss = { deleting = null },
            onConfirm = {
                vm.removeSource(src)
                deleting = null
            }
        )
    }

    // ========== 卡片功能菜单（OK 键弹出） ==========
    menuSource?.let { src ->
        TvSourceActionDialog(
            source = src,
            busy = vm.isSourceSyncing(src.id),
            isActiveHome = src.id == activeHomeId,
            onToggle = {
                vm.toggleSourceEnabled(src)
                menuSource = null
            },
            onRefresh = {
                // Emby 未登录时「刷新」实际是重新登录
                if (src.kind == SourceKind.EMBY && src.token.isBlank()) vm.reloginEmby(src)
                else vm.scanOne(src)
                menuSource = null
            },
            onEdit = {
                editing = src
                menuSource = null
            },
            onDelete = {
                deleting = src
                menuSource = null
            },
            onDismiss = { menuSource = null }
        )
    }
}

/* ========================================================
   分组标题
   ======================================================== */
@Composable
private fun TvGroupHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = TvText.Title,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$count 个已配置",
            style = MaterialTheme.typography.labelSmall,
            color = TvText.Meta
        )
    }
}

/* ========================================================
   空态提示
   ======================================================== */
@Composable
private fun TvEmptySourcesHint() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "还没有添加来源",
            style = MaterialTheme.typography.headlineSmall,
            color = TvText.Title,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "· Emby：填服务器地址与账号密码，自动登录换 Token\n" +
                "· TVBox：填 TVBox 配置 JSON 地址（需包含 spider 与 type=3 站点）",
            style = MaterialTheme.typography.bodyMedium,
            color = TvText.Meta
        )
    }
}

/* ========================================================
   来源状态圆点颜色 —— 与手机端保持一致
   ======================================================== */
private fun tvStatusColor(source: SourceEntity): Color = when {
    !source.enabled -> Color(0xFF6B7482)
    source.kind == SourceKind.EMBY && source.token.isBlank() -> Color(0xFFFF8A65)
    else -> Color(0xFF34C759)
}

/* ========================================================
   TvSourceCard —— 响应式卡片
   焦点模式完全对齐 TvPosterCard
   ======================================================== */
@Composable
private fun TvSourceCard(
    source: SourceEntity,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    isActiveHome: Boolean,
    busy: Boolean,
    count: Int?,
    lastSync: Long,
    onMenu: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(140),
        label = "source-card"
    )

    val disabledAlpha = if (!source.enabled) 0.5f else 1f

    Box(
        Modifier
            .width(width)
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = disabledAlpha }
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp))
                else Modifier.border(2.dp, Color.Transparent, RoundedCornerShape(14.dp))
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onMenu
            )
            .focusable(interactionSource = interaction)
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 上半部分：图标 + 名称 + 徽章 + URL
            Column(modifier = Modifier.fillMaxWidth()) {
                // 第一行：图标 + 状态圆点
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvSourceIcon(kind = source.kind)
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(tvStatusColor(source))
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 名称
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = TvText.Title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                // 徽章行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvKindBadge(kind = source.kind)
                    if (isActiveHome && source.enabled) {
                        Box(
                            Modifier
                                .background(AppColors.Brand.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "使用中",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.BrandSoft,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (!source.enabled) {
                        Box(
                            Modifier
                                .background(AppColors.SurfaceHi, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "已停用",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvText.Meta,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // URL
                Text(
                    text = source.location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvText.Meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 底部：分割线 + 统计信息
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp).background(AppColors.SurfaceHi)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        count?.let {
                            Text(
                                text = "🎬 $it 部",
                                style = MaterialTheme.typography.labelMedium,
                                color = TvText.Body
                            )
                        }
                        if (source.kind == SourceKind.EMBY) {
                            if (source.token.isBlank()) {
                                Text(
                                    text = "⚠ 登录失效",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFFF8A65)
                                )
                            } else {
                                Text(
                                    text = "👤 ${source.username.ifBlank { "—" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TvText.Body
                                )
                            }
                        }
                    }
                    Text(
                        text = tvRelativeTime(lastSync),
                        style = MaterialTheme.typography.labelSmall,
                        color = TvText.Meta
                    )
                }
            }
        }

        // 同步中：右上角显示一个不可聚焦的进度环，仅作状态提示
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 18.dp)
                    .size(26.dp),
                strokeWidth = 2.dp,
                color = AppColors.Brand
            )
        }
    }
}

/* ========================================================
   TvSourceIcon
   ======================================================== */
@Composable
private fun TvSourceIcon(kind: SourceKind) {
    val bg = if (kind == SourceKind.EMBY) AppColors.Brand.copy(alpha = 0.2f) else AppColors.Rating.copy(alpha = 0.14f)
    val fg = if (kind == SourceKind.EMBY) AppColors.Brand else AppColors.Rating
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (kind == SourceKind.EMBY) "E" else "📺",
            color = fg,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/* ========================================================
   TvKindBadge
   ======================================================== */
@Composable
private fun TvKindBadge(kind: SourceKind) {
    val bg = if (kind == SourceKind.EMBY) AppColors.Brand.copy(alpha = 0.16f) else AppColors.SurfaceHi
    val fg = if (kind == SourceKind.EMBY) AppColors.Brand else TvText.Body
    Box(
        Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (kind == SourceKind.EMBY) "EMBY" else "TVBOX",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/* ========================================================
   卡片功能菜单（OK 键弹出）
   ======================================================== */
@Composable
private fun TvSourceActionDialog(
    source: SourceEntity,
    busy: Boolean,
    isActiveHome: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val rootFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
        kotlinx.coroutines.delay(140)
        runCatching { firstFocus.requestFocus() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // 焦点根容器：阻止焦点从菜单逃逸到下层网格
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Back) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        // 宽度按设备自适应：屏幕宽度的 20% 就够（只有 4 个短菜单项），
        // 同时给一个最小宽度兜底，避免小密度设备上文字被压得放不下。
        val dialogWidth = (maxWidth * 0.2f).coerceIn(280.dp, 460.dp)
        Surface(
            modifier = Modifier.width(dialogWidth),
            shape = RoundedCornerShape(20.dp),
            color = AppColors.Surface,
            shadowElevation = 24.dp
        ) {
            Column(Modifier.padding(vertical = 24.dp)) {
                Column(Modifier.padding(horizontal = 26.dp)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = TvText.Title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append(if (source.kind == SourceKind.EMBY) "Emby 媒体服务器" else "TVBox 接口")
                            append(" · ")
                            append(if (source.enabled) "已启用" else "已停用")
                            if (isActiveHome) append(" · 使用中")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TvText.Meta
                    )
                }
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 启用/停用共用一个条目，按当前状态切换文案
                    TvMenuRow(
                        text = if (source.enabled) "停用" else "启用",
                        hint = if (isActiveHome && source.enabled) "当前首页使用中" else null,
                        modifier = Modifier.focusRequester(firstFocus),
                        onClick = onToggle
                    )
                    TvMenuRow(
                        text = if (source.kind == SourceKind.EMBY && source.token.isBlank()) "重新登录" else "刷新同步",
                        hint = if (busy) "同步中…" else null,
                        onClick = onRefresh
                    )
                    TvMenuRow(text = "编辑来源", onClick = onEdit)
                    TvMenuRow(text = "删除来源", danger = true, onClick = onDelete)
                }
            }
        }
    }
}

/** 功能菜单里的单行选项：可聚焦，聚焦时反白高亮 */
@Composable
private fun TvMenuRow(
    text: String,
    hint: String? = null,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    danger -> Color(0xFFE5484D).copy(alpha = 0.10f)
                    else -> AppColors.SurfaceHi.copy(alpha = 0.35f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp))
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .focusable()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = when {
                    focused && danger -> Color(0xFFE5484D)
                    focused -> Color.Black
                    danger -> Color(0xFFFF8A65)
                    else -> TvText.Title
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color.Black.copy(alpha = 0.6f) else TvText.Meta
                )
            }
        }
    }
}

/* ========================================================
   相对时间（与手机端保持一致）
   ======================================================== */
private fun tvRelativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    if (diff < 0) return "刚刚"
    val minutes = diff / 60_000
    return when {
        minutes <= 0L -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 30)} 个月前"
    }
}

/* ========================================================
   TvSourceFormPanel —— 全屏遮罩表单面板
   不用 AlertDialog —— Leanback 上焦点导航困难
   ======================================================== */
@Composable
private fun TvSourceFormPanel(
    initial: SourceEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, SourceKind, String, String, String) -> Unit
) {
    var kind by remember { mutableStateOf(initial?.kind ?: SourceKind.EMBY) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }

    // Dialog 焦点根容器 + 第一个输入框焦点
    val dialogRootFocus = remember { FocusRequester() }
    val nameFieldFocus = remember { FocusRequester() }
    // Dialog 首次显示时，先让 Box 成为焦点根，再延迟 requestFocus 到「来源名称」输入框
    LaunchedEffect(Unit) {
        dialogRootFocus.requestFocus()
        kotlinx.coroutines.delay(160)
        runCatching { nameFieldFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // focusRequester + focusable 让 Box 成为 Dialog 的焦点子树根容器，
            // 阻止焦点从 Dialog 内部逃逸到上层 LazyVerticalGrid 的卡片
            .focusRequester(dialogRootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                // Back 键关闭 Dialog
                if (e.type == KeyEventType.KeyDown && e.key == Key.Back) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.62f).fillMaxSize(0.90f),
            shape = RoundedCornerShape(20.dp),
            color = AppColors.Surface,
            shadowElevation = 24.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                // 标题区
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(AppColors.Brand.copy(alpha = 0.10f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 36.dp, vertical = 22.dp)
                ) {
                    Column {
                        Text(
                            text = if (initial == null) "添加视频来源" else "编辑视频来源",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TvText.Title,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 表单区：必须用 weight(1f)，否则 fillMaxSize 的 LazyColumn 会吃掉全部高度，
                // 底部「取消/保存」按钮被挤到 0 高度而看不见
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 类型选择器：一行两个紧凑 chip，不再用大卡片占掉半个屏幕
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "来源类型",
                                style = MaterialTheme.typography.labelMedium,
                                color = TvText.Body
                            )
                            Spacer(Modifier.width(4.dp))
                            TvFilterChip(
                                text = "Emby 媒体服务器",
                                selected = kind == SourceKind.EMBY,
                                onClick = { kind = SourceKind.EMBY }
                            )
                            TvFilterChip(
                                text = "TVBox 接口",
                                selected = kind == SourceKind.TVBOX,
                                onClick = { kind = SourceKind.TVBOX }
                            )
                        }
                    }

                    // 名称（**第一个可聚焦字段 —— Dialog 显示时自动 requestFocus 这里**）
                    item {
                        TvFormField(
                            label = "来源名称",
                            required = true,
                            value = name,
                            focusRequester = nameFieldFocus,
                            placeholder = if (kind == SourceKind.EMBY) "例如：我的 NAS 影库" else "例如：主线路",
                            onValueChange = { name = it }
                        )
                    }

                    // 服务器地址 / TVBox 配置
                    item {
                        TvFormField(
                            label = if (kind == SourceKind.EMBY) "服务器地址" else "TVBox 配置地址",
                            required = true,
                            value = location,
                            placeholder = if (kind == SourceKind.EMBY) "https://emby.example.com" else "https://example.com/tvbox.json",
                            keyboardType = KeyboardType.Uri,
                            onValueChange = { location = it }
                        )
                        if (kind == SourceKind.EMBY) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "支持 HTTPS 反向代理；局域网可用 http://192.168.x.x:8920",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvText.Meta
                            )
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "需填 TVBox 配置 JSON 地址（含 spider 与 type=3 站点）",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvText.Meta
                            )
                        }
                    }

                    // Emby 账号 + 密码
                    if (kind == SourceKind.EMBY) {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(Modifier.weight(1f)) {
                                    TvFormField(
                                        label = "用户名",
                                        required = true,
                                        value = username,
                                        placeholder = "admin",
                                        onValueChange = { username = it }
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    TvFormField(
                                        label = "密码",
                                        required = true,
                                        value = password,
                                        placeholder = "••••••••",
                                        isPassword = true,
                                        onValueChange = { password = it }
                                    )
                                }
                            }
                        }
                    }

                    // 启用说明
                    if (kind == SourceKind.EMBY) {
                        item {
                            Text(
                                text = "添加时会自动登录并保存会话，下次启动无需重新输入密码",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvText.Meta
                            )
                        }
                    }
                }

                // 按钮区
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 28.dp)
                        .background(AppColors.Surface)
                        .border(1.dp, AppColors.SurfaceHi, RoundedCornerShape(0.dp))
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvPillButton(
                        text = "取消",
                        primary = false,
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(16.dp))
                    TvPillButton(
                        text = if (initial == null) "添加来源" else "保存",
                        primary = true,
                        onClick = {
                            val trimmedName = name.trim()
                            val trimmedLoc = location.trim()
                            if (trimmedName.isBlank() || trimmedLoc.isBlank()) return@TvPillButton
                            onConfirm(
                                trimmedName, kind, trimmedLoc,
                                if (kind == SourceKind.EMBY) username.trim() else "",
                                if (kind == SourceKind.EMBY) password else ""
                            )
                        }
                    )
                }
            }
        }
    }
}

/* ========================================================
   TvFormField —— 60dp 大号输入框
   ======================================================== */
@Composable
private fun TvFormField(
    label: String,
    required: Boolean = false,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TvText.Body
            )
            if (required) {
                Box(Modifier.size(6.dp).background(Color(0xFFE5484D), CircleShape))
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(text = placeholder, color = TvText.Meta)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Next
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TvText.Title),
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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

/* ========================================================
   TvDeleteConfirmDialog —— 删除二次确认对话框
   红色危险视觉 + 默认聚焦"取消"防误触
   ======================================================== */
@Composable
private fun TvDeleteConfirmDialog(
    source: SourceEntity,
    itemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val cancelRequester = remember { FocusRequester() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(640.dp),
            shape = RoundedCornerShape(16.dp),
            color = AppColors.Surface,
            shadowElevation = 24.dp
        ) {
            Column {
                // 标题行
                Row(
                    Modifier.fillMaxWidth().padding(28.dp, 24.dp, 28.dp, 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // 警告图标
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5484D).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚠",
                            fontSize = 28.sp,
                            color = Color(0xFFFF8A65)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "删除来源？",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TvText.Title,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "将移除「${source.name}」以及它已同步的全部内容。",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TvText.Body
                        )
                    }
                }

                // 危险提示条
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp).background(
                        Color(0xFFE5484D).copy(alpha = 0.08f),
                        RoundedCornerShape(10.dp)
                    ).border(1.dp, Color(0xFFE5484D).copy(alpha = 0.25f), RoundedCornerShape(10.dp)).padding(16.dp, 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "• 已同步的 ${if (itemCount > 0) "$itemCount 部影片" else "内容"} 将被永久删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF8A65)
                        )
                        Text(
                            text = "• 此操作不可撤销 — 服务器本身不受影响",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF8A65)
                        )
                    }
                }

                // 按钮区
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                        .border(1.dp, AppColors.SurfaceHi, RoundedCornerShape(0.dp))
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvPillButton(
                        text = "取消",
                        primary = false,
                        modifier = Modifier.focusRequester(cancelRequester),
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(16.dp))
                    // 危险按钮：红色 PillButton 变体
                    val delRequester = remember { FocusRequester() }
                    var delFocused by remember { mutableStateOf(false) }
                    val delScale by animateFloatAsState(
                        targetValue = if (delFocused) 1.06f else 1f,
                        animationSpec = tween(140),
                        label = "del-scale"
                    )
                    Box(
                        Modifier
                            .graphicsLayer { scaleX = delScale; scaleY = delScale }
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (delFocused) Color.White else Color(0xFFE5484D)
                            )
                            .then(
                                if (delFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .onFocusChanged { delFocused = it.isFocused }
                            .focusRequester(delRequester)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onConfirm
                            )
                            .focusable()
                            .padding(horizontal = 32.dp, vertical = 14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                tint = if (delFocused) Color(0xFFE5484D) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "确认删除",
                                color = if (delFocused) Color(0xFFE5484D) else Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    // 默认焦点落到"取消"按钮 —— 防止遥控器默认聚焦红色按钮导致误触
    LaunchedEffect(Unit) {
        cancelRequester.requestFocus()
    }
}
