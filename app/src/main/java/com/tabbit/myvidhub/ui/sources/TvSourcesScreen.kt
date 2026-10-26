package com.tabbit.myvidhub.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 电视端来源管理（遥控器方向键导航，卡片保持单行紧凑布局）。
 *
 * 与手机端 SourcesScreen 完全分离：TV 端不适合多行卡片 + 弹出菜单。
 *
 * 来源管理：只保留 Emby 与 TVBox 两类。
 *
 * Emby 是「服务器 + 账号」模型，添加时即尝试登录换 token；
 * TVBox 是「JSON 接口」模型，只需一个 CMS 接口地址。
 */
@Composable
fun TvSourcesScreen(vm: LibraryViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceEntity?>(null) }

    Scaffold(
        containerColor = AppColors.Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                containerColor = AppColors.Brand,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加来源") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(
                        "视频来源",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        "支持 Emby 媒体服务器与 TVBox 接口，最多可添加多个来源",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }

            if (sources.isEmpty()) {
                item { TvEmptySourceHint() }
            } else {
                items(sources, key = { it.id }) { src ->
                    SourceRow(
                        source = src,
                        busy = vm.scanning,
                        onSync = { vm.scanOne(src) },
                        onRelogin = { vm.reloginEmby(src) },
                        onEdit = { editing = src },
                        onToggle = { vm.toggleSourceEnabled(src) },
                        onDelete = { vm.removeSource(src) }
                    )
                }
            }
        }
    }

    if (showAdd) {
        TvSourceDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { name, kind, location, user, pass ->
                vm.addSource(name, kind, location, user, pass)
                showAdd = false
            }
        )
    }

    editing?.let { src ->
        TvSourceDialog(
            initial = src,
            onDismiss = { editing = null },
            onConfirm = { name, kind, location, user, pass ->
                vm.updateSource(src.id, name, kind, location, user, pass)
                editing = null
            }
        )
    }
}

@Composable
private fun TvEmptySourceHint() {
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
private fun SourceRow(
    source: SourceEntity,
    busy: Boolean,
    onSync: () -> Unit,
    onRelogin: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary
                    )
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
                    if (!source.enabled) {
                        Box(
                            Modifier
                                .background(AppColors.SurfaceHi, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "已停用",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.TextTertiary
                            )
                        }
                    }
                }
                Text(
                    source.location,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )
                if (source.kind == SourceKind.EMBY) {
                    Text(
                        if (source.token.isBlank()) "未登录 · 请点击右侧刷新重新登录"
                        else "已登录 · ${source.username}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.token.isBlank()) Color(0xFFFF8A65) else AppColors.TextTertiary
                    )
                }
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

            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(12.dp).height(18.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.Brand
                )
            } else {
                IconButton(onClick = {
                    if (source.kind == SourceKind.EMBY && source.token.isBlank()) onRelogin()
                    else onSync()
                }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "同步",
                        tint = AppColors.TextSecondary
                    )
                }
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑",
                    tint = AppColors.TextSecondary
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = AppColors.TextTertiary
                )
            }
        }
    }
}

@Composable
private fun TvSourceDialog(
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
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
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
