package com.chen.doudoutv.ui.tv.nf

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.data.tvbox.TvBoxClient
import com.chen.doudoutv.ui.tv.tvMoveFocus
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Edge
import com.chen.doudoutv.ui.theme.Motion
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.TvText
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/** 抽屉左列的一行：分组小标题 / 一颗来源 / 一句提示 */
private sealed interface ScopeRow {
    data class Header(val title: String, val count: Int) : ScopeRow
    data class Note(val text: String) : ScopeRow
    data class Src(val source: SourceEntity) : ScopeRow
}

/**
 * 作用域抽屉：一屏里同时换「来源」和「线路」。
 *
 * 左列 = 全部来源，按 Emby / TVBox 分组；右列 = 光标停在那一行来源的线路。
 *   · Emby → 右列只留一句说明（它没有线路概念），OK 选中即生效并关闭；
 *   · TVBox → OK 先提交这个来源（顶栏和页面内容跟着换），再把焦点送进右列继续挑线路；
 *     抽屉不关，选了一条线路才连线路一起换掉，不想换直接按返回；
 *     先提交再跳是因为右列 requester 可能恰好没挂上，只跳转会让整页看起来无响应。
 *   · 这条 TVBox 来源没有/还没拉到线路 → 右列给一颗「只用这个来源」，按它收口并关闭。
 *
 * 焦点：默认停在「当前生效的来源」那一行，且先把该行滚进视野再抢焦 —— requester 只挂在
 * 已组合的列表项上，滚不到就抢不到；左右跨列用按键拦截 + [tvMoveFocus] 而不是 focusProperties，
 * 因为右列随光标换来源而挂载/摘除，requester 没挂上时 focusProperties 会把异常抛进焦点事务。
 */
@Composable
fun NfScopeDrawer(
    sources: List<SourceEntity>,
    selectedSourceId: Long?,
    selectedSiteKey: String?,
    sitesOf: (Long) -> List<TvBoxClient.TvBoxSite>,
    loadingSites: Boolean,
    onEnsureSites: (Long) -> Unit,
    onPickSource: (Long) -> Unit,
    onPickSite: (String?, TvBoxClient.TvBoxSite?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    // 光标所在来源：决定右列显示谁家的线路。开抽屉时先站在当前生效的来源上
    val initialId = selectedSourceId ?: sources.firstOrNull()?.id
    var cursorId by remember { mutableStateOf(initialId) }
    // 左列当前那行的 requester（右列按 ← 要跳回它），以及右列第一格的 requester（左列按 → 跳过去）
    var cursorFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val firstSiteFocus = remember { FocusRequester() }
    val onlySourceFocus = remember { FocusRequester() }
    val entryFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val siteListState = rememberLazyGridState()

    val cursorSource = sources.firstOrNull { it.id == cursorId }
    val cursorIsTvBox = cursorSource?.kind == SourceKind.TVBOX
    val sites = cursorId?.let { sitesOf(it) }.orEmpty().filter { it.key.isNotBlank() }
    val sitesVisible = cursorIsTvBox && sites.isNotEmpty()
    // 右列的高亮只描述「当前生效的那一条」，光标停在别的来源时不能拿上一个个来源的线路 key 来比
    val showSiteSelection = cursorId != null && cursorId == selectedSourceId

    val rows: List<ScopeRow> = remember(sources) {
        buildList {
            val emby = sources.filter { it.kind == SourceKind.EMBY }
            val tvbox = sources.filter { it.kind == SourceKind.TVBOX }
            if (emby.isNotEmpty()) {
                add(ScopeRow.Header("Emby 媒体库", emby.size))
                emby.forEach { add(ScopeRow.Src(it)) }
            }
            if (tvbox.isNotEmpty()) {
                add(ScopeRow.Header("TVBox 资源站", tvbox.size))
                tvbox.forEach { add(ScopeRow.Src(it)) }
            }
            if (sources.isEmpty()) add(ScopeRow.Note("还没有配置任何来源"))
        }
    }

    LaunchedEffect(cursorId) { cursorId?.let { onEnsureSites(it) } }

    // 右列打开时要停在当前生效的那条线路上，而不是从「全部站点」往下找。
    // 「全部站点」占第 0 格，真实站点的下标要 +1；光标不在当前来源上（没有高亮）就回到顶部。
    LaunchedEffect(cursorId, sites, selectedSiteKey) {
        val at = if (showSiteSelection) 1 + sites.indexOfFirst { it.key == selectedSiteKey } else 0
        if (at > 0) siteListState.scrollToItem(at)
    }

    LaunchedEffect(Unit) {
        val at = rows.indexOfFirst { it is ScopeRow.Src && it.source.id == initialId }
        if (at >= 0) listState.scrollToItem(at)
        withFrameNanos { }
        // 抽屉窗口和列表项的挂载顺序没有保证，第一次可能还是空的 —— 补一次，别让抽屉开起来没人持焦
        if (!tvMoveFocus(entryFocus)) {
            delay(140)
            tvMoveFocus(entryFocus)
        }
    }

    /** 只换来源（Emby 与「这条 TVBox 源没有线路」两条路径） */
    fun pickSourceOnly(src: SourceEntity) {
        if (src.id != selectedSourceId) onPickSource(src.id)
        onDismiss()
    }

    /** 来源 + 线路一次换完：先切来源（会把它自己记忆过的线路读出来），再按用户这次选的覆盖 */
    fun pick(src: SourceEntity, siteKey: String?, site: TvBoxClient.TvBoxSite?) {
        if (src.id != selectedSourceId) onPickSource(src.id)
        onPickSite(siteKey, site)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val panelWidth = (maxWidth * 0.78f).coerceIn(680.dp, 1400.dp)
            val panelHeight = (maxHeight * 0.82f).coerceAtMost(760.dp)
            Column(
                Modifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .background(AppColors.SurfaceHi, Radius.Lg)
                    .border(1.dp, Edge.HairlineStrong, Radius.Lg)
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "切换来源 / 线路",
                        color = TvText.Title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // 原来左下角的「管理来源…」行挪到这里；一个来源都没有时这里就是抽屉唯一的落点
                    NfActionButton(
                        text = "添加来源",
                        onClick = onManage,
                        modifier = if (sources.isEmpty()) Modifier.focusRequester(entryFocus) else Modifier
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // ---------- 左列：来源，按类型分组 ----------
                    LazyColumn(
                        modifier = Modifier.width(340.dp).fillMaxHeight(),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(rows, key = { _, row ->
                            when (row) {
                                is ScopeRow.Src -> "src_${row.source.id}"
                                is ScopeRow.Header -> "head_${row.title}"
                                is ScopeRow.Note -> "note"
                            }
                        }) { _, row ->
                            when (row) {
                                is ScopeRow.Header -> NfGroupHeader(row.title, row.count)
                                is ScopeRow.Note -> Text(
                                    row.text,
                                    color = TvText.Meta,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp)
                                )
                                is ScopeRow.Src -> {
                                    val src = row.source
                                    NfSourceRow(
                                        source = src,
                                        label = src.name,
                                        active = src.id == selectedSourceId,
                                        isCursor = src.id == cursorId,
                                        focusedEntry = src.id == initialId,
                                        entryFocus = entryFocus,
                                        onFocusHere = { requester ->
                                            cursorId = src.id
                                            cursorFocus = requester
                                        },
                                        canRight = cursorIsTvBox,
                                        onRight = {
                                            // 右列 requester 没挂上时跳不动，交回默认几何搜索；此时来源已经换掉，右列会开始读取进度
                                            if (src.kind == SourceKind.TVBOX)
                                                tvMoveFocus(firstSiteFocus, onlySourceFocus)
                                            else false
                                        },
                                        onClick = {
                                            if (src.kind == SourceKind.TVBOX) {
                                                // OK 先落定这个来源（右列 requester 万一没挂上，按下去也不会整页无响应），
                                                // 再把焦点送进右列继续挑线路；抽屉保持打开，选线路或按返回才关闭
                                                cursorId = src.id
                                                if (src.id != selectedSourceId) onPickSource(src.id)
                                                tvMoveFocus(firstSiteFocus, onlySourceFocus)
                                            } else {
                                                pickSourceOnly(src)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ---------- 右列：线路（TVBox 来源在光标停住时就占位，Emby 只留一句说明）----------
                    if (cursorIsTvBox) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    cursorSource?.name?.takeIf { it.isNotBlank() } ?: "线路",
                                    color = TvText.Title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    when {
                                        loadingSites && sites.isEmpty() -> "读取中…"
                                        sites.isEmpty() -> "没有可用站点"
                                        else -> "${sites.size} 条"
                                    },
                                    color = TvText.Muted,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            if (sitesVisible) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 150.dp),
                                    state = siteListState,
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    item(key = "site_all") {
                                        NfSiteCell(
                                            text = "全部站点",
                                            selected = showSiteSelection && selectedSiteKey.isNullOrEmpty(),
                                            anchor = firstSiteFocus,
                                            onLeft = { cursorFocus?.let { tvMoveFocus(it) } ?: false },
                                            onClick = { cursorSource?.let { pick(it, null, null) } }
                                        )
                                    }
                                    items(sites, key = { "site_${it.key}" }) { site ->
                                        NfSiteCell(
                                            text = site.name,
                                            selected = showSiteSelection && selectedSiteKey == site.key,
                                            anchor = null,
                                            onLeft = { cursorFocus?.let { tvMoveFocus(it) } ?: false },
                                            onClick = { cursorSource?.let { pick(it, site.key, site) } }
                                        )
                                    }
                                }
                            } else {
                                // 线路还没到（或这条源就没有）：右列仍然可聚焦，← 才回得去；
                                // 想只换来源就按下面这颗，不必等线路拉完
                                Column(
                                    Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (loadingSites) {
                                        CircularProgressIndicator(
                                            color = AppColors.Brand,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(26.dp)
                                        )
                                        Text(
                                            "正在读取这条来源的线路…",
                                            color = TvText.Meta,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(top = 12.dp)
                                        )
                                    } else {
                                        Text(
                                            "这条 TVBox 来源没有可切换的站点",
                                            color = TvText.Meta,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    cursorSource?.let { src ->
                                        NfActionButton(
                                            text = if (src.id == selectedSourceId) "关闭" else "只用这个来源",
                                            onClick = {
                                                if (src.id == selectedSourceId) onDismiss() else pickSourceOnly(src)
                                            },
                                            modifier = Modifier.padding(top = 18.dp)
                                                .focusRequester(onlySourceFocus)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (cursorSource?.kind == SourceKind.EMBY) "Emby 来源没有线路，左边选中即生效"
                                else "左边选中一条 TVBox 来源后，这里列出它的线路",
                                color = TvText.Meta,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分组小标题：Netflix 式的小字距标签，不靠颜色分层 */
@Composable
private fun NfGroupHeader(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text.uppercase(),
            color = TvText.Muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text("$count", color = TvText.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 左列的一行来源。
 * 拿到焦点时通过 [onFocusHere] 把 requester 交出去，右列才知道 ← 该跳回哪。
 */
@Composable
private fun NfSourceRow(
    source: SourceEntity,
    label: String,
    active: Boolean,
    isCursor: Boolean,
    focusedEntry: Boolean,
    entryFocus: FocusRequester,
    onFocusHere: (FocusRequester) -> Unit,
    onRight: () -> Boolean,
    canRight: Boolean,
    onClick: () -> Unit
) {
    val requester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (focused) 1.03f else 1f, tween(Motion.FAST, easing = Motion.Ease), label = "srcRow")
    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .then(if (focusedEntry) Modifier.focusRequester(entryFocus) else Modifier)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocusHere(requester) }
            .onPreviewKeyEvent { e ->
                // → 跨到右列。右列可能刚被摘掉，tvMoveFocus 内部兜异常，跳不动就交回默认几何搜索
                if (canRight && e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    onRight()
                } else false
            }
            .fillMaxWidth()
            .clip(Radius.Md)
            .background(
                when {
                    focused -> AppColors.Brand.copy(alpha = 0.5f)
                    active -> Color.White.copy(alpha = 0.12f)
                    else -> AppColors.Surface.copy(alpha = 0.6f)
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, Radius.Md) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        if (active) AppColors.Brand else TvText.Muted,
                        Radius.Pill
                    )
            )
            Text(
                label,
                color = if (focused || active) TvText.Title else TvText.Section,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 光标停在这行 = 右列正在列它家的线路；TVBox 行还要提示「再按 → 才去挑线路」
            if (source.kind == SourceKind.TVBOX) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "线路",
                        color = if (isCursor) AppColors.Brand else TvText.Muted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    androidx.compose.material3.Icon(
                        imageVector = if (isCursor) {
                            Icons.Filled.Circle
                        } else {
                            Icons.Filled.ChevronRight
                        },
                        contentDescription = null,
                        tint = if (isCursor) AppColors.Brand else TvText.Muted,
                        modifier = Modifier.size(if (isCursor) 8.dp else 16.dp)
                    )
                }
            } else if (active) {
                Text("使用中", color = AppColors.Brand, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 右列的一格线路。[anchor] 非空时它是右列第一格，左列按 → 就落在这里 */
@Composable
private fun NfSiteCell(
    text: String,
    selected: Boolean,
    anchor: FocusRequester?,
    onLeft: () -> Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .then(if (anchor != null) Modifier.focusRequester(anchor) else Modifier)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    onLeft()
                } else false
            }
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(Radius.Md)
            .background(
                when {
                    focused -> AppColors.Brand.copy(alpha = 0.5f)
                    selected -> AppColors.Brand.copy(alpha = 0.22f)
                    else -> AppColors.Surface.copy(alpha = 0.6f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, Radius.Md)
                else if (selected) Modifier.border(1.dp, AppColors.Brand, Radius.Md)
                else Modifier.border(1.dp, Edge.Hairline, Radius.Md)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            color = if (focused || selected) TvText.Title else TvText.Section,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
