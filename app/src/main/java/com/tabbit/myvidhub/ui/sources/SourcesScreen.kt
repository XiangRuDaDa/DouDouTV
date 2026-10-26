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
                            busy = vm.scanning,
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
                            busy = vm.scanning,
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
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

            // ---- 第一行：状态点 + 名称 + 使用中 + 开关 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(statusColor(source), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isActiveHome && source.enabled) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .background(AppColors.Brand.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "使用中",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.BrandSoft
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
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

            Spacer(Modifier.height(7.dp))

            // ---- 第二行：类型徽章 + 地址 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(
                            if (source.kind == SourceKind.EMBY) AppColors.Brand.copy(alpha = 0.16f)
                            else AppColors.SurfaceHi,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (source.kind == SourceKind.EMBY) "EMBY" else "TVBOX",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.kind == SourceKind.EMBY) AppColors.Brand else AppColors.TextSecondary
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    source.location,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(9.dp))

            // ---- 第三行：统计信息 + 更多菜单 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildStats(source, count, lastSync),
                    style = MaterialTheme.typography.labelSmall,
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
                    Spacer(Modifier.width(8.dp))
                }

                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(19.dp)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = {
            Text(if (initial == null) "添加视频来源" else "编辑视频来源", color = AppColors.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == SourceKind.EMBY,
                        onClick = { kind = SourceKind.EMBY },
                        label = { Text("Emby") },
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
                        label = { Text("TVBox") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Brand,
                            selectedLabelColor = Color.White,
                            containerColor = AppColors.SurfaceHi,
                            labelColor = AppColors.TextSecondary
                        )
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text(if (kind == SourceKind.EMBY) "我的 NAS 影库" else "主线路") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (kind == SourceKind.EMBY) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://emby.example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "添加时会自动登录并保存会话，无需重复输入密码",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                } else {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("TVBox / CMS 接口地址") },
                        placeholder = { Text("https://example.com/api.php/provide/vod/") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "接口需支持 ?ac=videolist 返回 JSON（苹果CMS 通用格式）",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, kind, location, username, password) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Brand,
                    contentColor = Color.White
                )
            ) { Text(if (initial == null) "添加" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppColors.TextSecondary)
            }
        }
    )
}
