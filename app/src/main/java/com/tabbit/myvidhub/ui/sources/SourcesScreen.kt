package com.tabbit.myvidhub.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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

/**
 * 手机端来源管理。
 *
 * 与电视端 [TvSourcesScreen] 分离：手机屏幕窄、操作靠手指，
 * 所以卡片做成三行分层（状态/地址/统计），高危的删除收进 ⋯ 菜单并二次确认，
 * 添加入口放到底部通栏按钮，避免 FAB 挡住最后一张卡片。
 *
 * 数据只用 [SourceEntity] 现有字段 + 本地时间戳，不新增数据库列。
 */
@Composable
fun SourcesScreen(vm: LibraryViewModel, isTv: Boolean = false) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    var deleting by remember { mutableStateOf<SourceEntity?>(null) }

    val activeHomeId = vm.effectiveHomeSourceId()
    val embys = remember(sources) { sources.filter { it.kind == SourceKind.EMBY } }
    val tvboxes = remember(sources) { sources.filter { it.kind == SourceKind.TVBOX } }

    Scaffold(
        containerColor = AppColors.Bg,
        bottomBar = {
            Surface(color = AppColors.Bg) {
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Brand,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(50.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加来源", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Header(
                    syncing = vm.scanning,
                    onSyncAll = { vm.syncAll() }
                )
            }

            if (sources.isEmpty()) {
                item { EmptySourceHint() }
            } else {
                if (embys.isNotEmpty()) {
                    item { GroupLabel("Emby 媒体服务器", embys.size) }
                    items(embys, key = { "emby-${it.id}" }) { src ->
                        SourceCard(
                            vm = vm,
                            source = src,
                            isActiveHome = src.id == activeHomeId,
                            busy = vm.isSourceSyncing(src.id),
                            onSync = { vm.scanOne(src) },
                            onRelogin = { vm.reloginEmby(src) },
                            onEdit = { editing = src },
                            onToggle = { vm.toggleSourceEnabled(src) },
                            onDelete = { deleting = src }
                        )
                    }
                }
                if (tvboxes.isNotEmpty()) {
                    item { GroupLabel("TVBox 接口", tvboxes.size) }
                    items(tvboxes, key = { "tvbox-${it.id}" }) { src ->
                        SourceCard(
                            vm = vm,
                            source = src,
                            isActiveHome = src.id == activeHomeId,
                            busy = vm.isSourceSyncing(src.id),
                            onSync = { vm.scanOne(src) },
                            onRelogin = { vm.reloginEmby(src) },
                            onEdit = { editing = src },
                            onToggle = { vm.toggleSourceEnabled(src) },
                            onDelete = { deleting = src }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        SourceDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { name, kind, location, user, pass ->
                vm.addSource(name, kind, location, user, pass)
                showAdd = false
            }
        )
    }

    editing?.let { src ->
        SourceDialog(
            initial = src,
            onDismiss = { editing = null },
            onConfirm = { name, kind, location, user, pass ->
                vm.updateSource(src.id, name, kind, location, user, pass)
                editing = null
            }
        )
    }

    deleting?.let { src ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = AppColors.Surface,
            title = { Text("删除来源？", color = AppColors.TextPrimary) },
            text = {
                Text(
                    "将移除「${src.name}」以及它已同步的全部内容，此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeSource(src)
                        deleting = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5484D),
                        contentColor = Color.White
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("取消", color = AppColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun Header(syncing: Boolean, onSyncAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "视频来源",
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary
            )
            Text(
                "支持 Emby 媒体服务器与 TVBox 接口",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }
        if (syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AppColors.Brand
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "同步中…",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary
            )
        } else {
            TextButton(
                onClick = onSyncAll,
                colors = ButtonDefaults.textButtonColors(contentColor = AppColors.BrandSoft)
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("全部同步", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GroupLabel(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count 个",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextTertiary
        )
    }
}

/** 状态圆点：停用=灰，Emby token 失效=橙，其余=绿 */
private fun statusColor(source: SourceEntity): Color = when {
    !source.enabled -> AppColors.TextTertiary
    source.kind == SourceKind.EMBY && source.token.isBlank() -> Color(0xFFFF8A65)
    else -> Color(0xFF34C759)
}

@Composable
private fun SourceCard(
    vm: LibraryViewModel,
    source: SourceEntity,
    isActiveHome: Boolean,
    busy: Boolean,
    onSync: () -> Unit,
    onRelogin: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = vm.sourceCounts[source.id]
    val lastSync = vm.lastSyncOf(source.id)

    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (source.enabled) 1f else 0.55f)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {

            // ───── 第一行：左侧 [状态点 + 名称]  右侧 ["使用中"徽章 + Switch] ─────
            // 左侧 weight(1f) 撑满剩余空间；右侧自然宽度锚定最右
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左侧组：状态圆点 + 名称（整体 fill，名称 ellipsis）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = true)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(statusColor(source), CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(10.dp))

                // 右侧组：徽章（可选）+ Switch —— 位置固定不变
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActiveHome && source.enabled) {
                        Box(
                            Modifier
                                .background(AppColors.Brand.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "使用中",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.BrandSoft,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppColors.Brand,
                            uncheckedTrackColor = AppColors.SurfaceHi
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ───── 第二行：类型徽章 + 地址 ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(
                            if (source.kind == SourceKind.EMBY) AppColors.Brand.copy(alpha = 0.16f)
                            else AppColors.SurfaceHi,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (source.kind == SourceKind.EMBY) "EMBY" else "TVBOX",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.kind == SourceKind.EMBY) AppColors.Brand else AppColors.TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    source.location,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ───── 第三行：统计信息 + 同步状态 + 更多菜单 ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildStats(source, count, lastSync),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.Brand
                    )
                    Spacer(Modifier.width(6.dp))
                }

                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑配置", color = AppColors.TextPrimary) },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null, tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (source.kind == SourceKind.EMBY && source.token.isBlank()) "重新登录"
                                    else "立即同步",
                                    color = AppColors.TextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (source.kind == SourceKind.EMBY && source.token.isBlank()) Icons.Filled.Refresh else Icons.Filled.Sync,
                                    contentDescription = null,
                                    tint = AppColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                if (source.kind == SourceKind.EMBY && source.token.isBlank()) onRelogin()
                                else onSync()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除来源", color = Color(0xFFE5484D)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFE5484D), modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 「128 部影片 · 12 分钟前同步」；数量未知时省略前半段 */
private fun buildStats(source: SourceEntity, count: Int?, lastSync: Long): String {
    val parts = mutableListOf<String>()
    if (count != null && count > 0) parts += "$count 个条目"
    if (source.kind == SourceKind.EMBY) {
        parts += if (source.token.isBlank()) "登录失效" else "已登录 · ${source.username}"
    }
    parts += if (lastSync <= 0L) "未同步" else "${relativeTime(lastSync)}同步"
    return parts.joinToString(" · ")
}

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    if (diff < 0) return "刚刚"
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 30)} 个月前"
    }
}

@Composable
private fun EmptySourceHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "还没有添加来源",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary
        )
        Text(
            "· Emby：填服务器地址（如 https://emby.abc.com）与账号密码，登录后自动同步影库\n" +
                "· TVBox：填写饭太硬、牛小二等TVBox接口",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextTertiary
        )
    }
}

@Composable
private fun SourceDialog(
    initial: SourceEntity?,
    onDismiss: () -> Unit,
    onConfirm: (String, SourceKind, String, String, String) -> Unit
) {
    var kind by remember { mutableStateOf(initial?.kind ?: SourceKind.EMBY) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }

    val trimmedName = name.trim()
    val trimmedLoc = location.trim()
    val canSubmit = trimmedName.isNotBlank() && trimmedLoc.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                if (initial == null) "添加视频来源" else "编辑视频来源",
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // 类型选择器
                Text(
                    "来源类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = kind == SourceKind.EMBY,
                        onClick = { kind = SourceKind.EMBY },
                        label = { Text("Emby 媒体服务器", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Brand,
                            selectedLabelColor = Color.White,
                            containerColor = AppColors.SurfaceHi,
                            labelColor = AppColors.TextSecondary
                        )
                    )
                    FilterChip(
                        selected = kind == SourceKind.TVBOX,
                        onClick = { kind = SourceKind.TVBOX },
                        label = { Text("TVBox 接口", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Brand,
                            selectedLabelColor = Color.White,
                            containerColor = AppColors.SurfaceHi,
                            labelColor = AppColors.TextSecondary
                        )
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 名称（两种类型都需要）
                MobileFormField(
                    label = "来源名称",
                    value = name,
                    placeholder = if (kind == SourceKind.EMBY) "例如：我的 NAS 影库" else "例如：主线路",
                    onValueChange = { name = it },
                    imeAction = ImeAction.Next
                )

                when (kind) {
                    SourceKind.EMBY -> {
                        MobileFormField(
                            label = "服务器地址",
                            value = location,
                            placeholder = "https://emby.example.com",
                            onValueChange = { location = it },
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                            hint = "支持 HTTPS 反向代理；局域网可用 http://192.168.x.x:8920"
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                MobileFormField(
                                    label = "用户名",
                                    value = username,
                                    placeholder = "admin",
                                    onValueChange = { username = it },
                                    imeAction = ImeAction.Next
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                MobileFormField(
                                    label = "密码",
                                    value = password,
                                    placeholder = "••••••••",
                                    isPassword = true,
                                    onValueChange = { password = it },
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                )
                            }
                        }
                        Text(
                            "添加时会自动登录并保存会话，下次启动无需重新输入密码",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary
                        )
                    }

                    SourceKind.TVBOX -> {
                        MobileFormField(
                            label = "TVBox / CMS 接口地址",
                            value = location,
                            placeholder = "https://example.com/api.php/provide/vod/",
                            onValueChange = { location = it },
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                            hint = "接口需支持 ?ac=videolist 返回 JSON（苹果CMS 通用格式）"
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSubmit) {
                        onConfirm(
                            trimmedName, kind, trimmedLoc,
                            if (kind == SourceKind.EMBY) username.trim() else "",
                            if (kind == SourceKind.EMBY) password else ""
                        )
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Brand,
                    contentColor = Color.White,
                    disabledContainerColor = AppColors.SurfaceHi,
                    disabledContentColor = AppColors.TextTertiary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    if (initial == null) "添加来源" else "保存",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppColors.TextSecondary)
            }
        }
    )
}

/** 手机端表单输入字段：OutlinedTextField 包装，统一高度 + 品牌色聚焦 + 可选 hint */
@Composable
private fun MobileFormField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    hint: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextSecondary
                )
            },
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextTertiary
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 15.sp,
                color = AppColors.TextPrimary
            ),
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = AppColors.Bg,
                focusedContainerColor = AppColors.Bg,
                unfocusedBorderColor = AppColors.SurfaceHi,
                focusedBorderColor = AppColors.Brand,
                cursorColor = AppColors.Brand,
                focusedTextColor = AppColors.TextPrimary,
                unfocusedTextColor = AppColors.TextPrimary
            )
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextTertiary
            )
        }
    }
}
