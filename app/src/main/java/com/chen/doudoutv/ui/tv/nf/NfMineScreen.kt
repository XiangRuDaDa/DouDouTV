package com.chen.doudoutv.ui.tv.nf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.ReturnSpot
import com.chen.doudoutv.ui.StandTarget
import com.chen.doudoutv.ui.cardStand
import com.chen.doudoutv.ui.gridState
import com.chen.doudoutv.ui.tv.TvPagePadding
import com.chen.doudoutv.ui.tv.tvClaimFocusOnEnter
import com.chen.doudoutv.ui.tv.tvGridColumns
import com.chen.doudoutv.ui.tv.tvMoveFocus
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Edge
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.TvText
import kotlinx.coroutines.launch

/** [ReturnSpot] 的页面标识：「我的」只有一张网格，滚动与落点都记在它下面 */
private const val NfMineKey = "nf_mine"

/**
 * 「我的」页：竖版收藏卡按来源分组铺开。
 *
 * 收藏是全库范围的（DAO 只按来源是否启用过滤），所以这里读 [LibraryViewModel.favorites]
 * 而不是首页那套「按当前来源/线路过滤」的数据。
 *
 * 分组顺序把当前顶栏来源排在最前：这一组的卡片可以直接进详情，
 * 其它来源的卡片点了会先弹框确认是否切换顶栏来源。
 */
@Composable
fun NfMineScreen(
    vm: LibraryViewModel,
    onTab: (NfTab) -> Unit,
    onDetail: (Long) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    // 用全部来源而不是「启用的那几条」来对名字：收藏行可能挂在一个刚被停用的来源上，
    // 这时候仍然要告诉用户这条收藏属于谁，而不是显示一个空名字
    val allSources by vm.sources.collectAsStateWithLifecycle()
    val currentSourceId = vm.effectiveHomeSourceId()

    val chrome = rememberNfChrome()
    // 网格首卡与空态 CTA 各用一个 requester 实例，随内容身份重建
    val firstFocus = remember(favorites.firstOrNull()?.id) { FocusRequester() }
    val emptyFocus = remember(favorites.isEmpty()) { FocusRequester() }
    val stand = remember { StandTarget() }
    val groups = remember(favorites, allSources, currentSourceId) {
        nfFavoriteGroups(favorites, allSources, currentSourceId)
    }

    var pendingSwitch by remember { mutableStateOf<Pair<SourceEntity, MediaEntity>?>(null) }
    // 切换确认后要把焦点留在原位：弹窗关掉时卡片 requester 由 pendingFocus 带着
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val scope = rememberCoroutineScope()

    tvClaimFocusOnEnter(
        tick = groups.sumOf { it.items.size },
        anchorsProvider = {
            listOfNotNull(stand.requester, if (favorites.isEmpty()) emptyFocus else firstFocus)
        }
    )

    NfScaffold(
        vm = vm,
        current = NfTab.Mine,
        chrome = chrome,
        // 导航条 ↓ 显式交回这一屏的接焦元件（首组首卡 / 空态那颗按钮），规范 R1
        navInFlow = true,
        belowFocusRequester = if (favorites.isEmpty()) emptyFocus else firstFocus,
        onTab = onTab,
        onSettings = onSettings,
        onManageSources = onSources
    ) {
        Column(Modifier.fillMaxSize()) {
            if (favorites.isEmpty()) {
                NfInlineEmpty(
                    text = "还没有收藏任何内容",
                    hint = "在详情页按 ★ 收藏，它们会按来源分组出现在这里",
                    actionLabel = "去分类",
                    onAction = { onTab(NfTab.Browse) },
                    focusRequester = emptyFocus
                )
            } else {
                NfFavoriteGrid(
                    groups = groups,
                    stand = stand,
                    firstFocus = firstFocus,
                    upFocusRequester = chrome.tabOf(NfTab.Mine),
                    onOpen = { group, item, cardFocus ->
                        if (group.isCurrent) {
                            onDetail(item.id)
                        } else {
                            val src = group.source
                            if (src == null) {
                                // 来源已经不在了：没有可切换的目标，仍然让用户看一眼这条记录
                                onDetail(item.id)
                            } else {
                                pendingSwitch = src to item
                                pendingFocus = cardFocus
                            }
                        }
                    }
                )
            }
        }
    }

    pendingSwitch?.let { (src, item) ->
        NfSwitchSourceDialog(
            source = src,
            item = item,
            onConfirm = {
                pendingSwitch = null
                // 切来源会连带清掉首页那套分类/线路状态；这一条收藏属于新来源，
                // 切完直接进详情就是用户想要的那一屏
                vm.selectHomeSource(src.id)
                onDetail(item.id)
            },
            onDismiss = {
                val back = pendingFocus
                pendingSwitch = null
                pendingFocus = null
                // 弹窗窗口要先卸掉，requester 才重新挂得上下层那张卡 —— 直接 requestFocus 会打空
                if (back != null) {
                    scope.launch {
                        withFrameNanos { }
                        runCatching { back.requestFocus() }
                    }
                }
            }
        )
    }
}

/**
 * 一个来源分组：来源名 + 条数 + （非当前来源时）一句「打开会先确认切换」。
 * 表头横跨整行，卡片跟着排在它下面，Netflix 的 "row header + rack" 结构在网格里同样成立。
 */
private data class NfFavoriteGroup(
    val key: String,
    val source: SourceEntity?,
    val title: String,
    val isCurrent: Boolean,
    val items: List<MediaEntity>
)

private fun nfFavoriteGroups(
    favorites: List<MediaEntity>,
    sources: List<SourceEntity>,
    currentSourceId: Long?
): List<NfFavoriteGroup> {
    if (favorites.isEmpty()) return emptyList()
    val bySource = favorites.groupBy { it.sourceId }
    return bySource.entries
        .map { (sourceId, items) ->
            val src = sources.firstOrNull { it.id == sourceId }
            NfFavoriteGroup(
                key = sourceId.toString(),
                source = src,
                title = src?.name?.takeIf { it.isNotBlank() } ?: "已移除的来源",
                isCurrent = sourceId == currentSourceId,
                items = items
            )
        }
        // 当前来源永远排第一，其余按条数从多到少
        .sortedWith(compareByDescending<NfFavoriteGroup> { it.isCurrent }.thenByDescending { it.items.size })
}

@Composable
private fun NfFavoriteGrid(
    groups: List<NfFavoriteGroup>,
    stand: StandTarget,
    firstFocus: FocusRequester,
    upFocusRequester: FocusRequester,
    onOpen: (NfFavoriteGroup, MediaEntity, FocusRequester) -> Unit
) {
    val gridState = ReturnSpot.gridState(NfMineKey)
    val columns = tvGridColumns()
    val stagger = nfFirstFillStagger(groups.sumOf { it.items.size }, NfMineKey)
    val firstItemId = groups.firstOrNull()?.items?.firstOrNull()?.id
    val firstGroupKey = groups.firstOrNull()?.key

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TvPagePadding, end = TvPagePadding,
            top = NfGridTopPadding, bottom = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(NfCardGap),
        verticalArrangement = Arrangement.spacedBy(NfRowVerticalPadding)
    ) {
        groups.forEach { group ->
            item(key = "nf_mine_head_${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                NfGroupHead(group, isFirstGroup = group === groups.first())
            }
            gridItemsIndexed(group.items, key = { _, it -> "nf_mine_${it.id}" }) { index, item ->
                val cardFocus = remember(item.id) { FocusRequester() }
                NfPosterCard(
                    item = item,
                    onClick = { onOpen(group, item, cardFocus) },
                    fillCell = true,
                    anchorStart = index % columns == 0,
                    entrance = nfEntrance(index, stagger),
                    // 首组首行的 ↑ 显式交回本页那颗 tab：往上只有一条常驻导航，
                    // 默认几何搜索会先撞到横跨整行的表头（它不可聚焦）
                    modifier = Modifier
                        .then(
                            if (group.key == firstGroupKey && index < columns)
                                Modifier.nfFocusRoute(up = upFocusRequester)
                            else Modifier
                        )
                        .then(
                            ReturnSpot.cardStand(
                                pageKey = NfMineKey,
                                rowKey = "grid_${group.key}",
                                mediaId = item.id,
                                target = stand,
                                requester = if (item.id == firstItemId) firstFocus else cardFocus
                            )
                        )
                )
            }
        }
    }
}

/**
 * 分组表头。它本身已是 `GridItemSpan(maxLineSpan)` 的整行 item，外层网格的 contentPadding
 * 把左右各内缩过一个 [TvPagePadding]，这里再补一次就会让来源名比卡片多缩一整格左边距。
 */
@Composable
private fun NfGroupHead(group: NfFavoriteGroup, isFirstGroup: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstGroup) 4.dp else 16.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.title,
                color = TvText.Title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (group.isCurrent) "当前来源 · ${group.items.size}" else "${group.items.size} 部",
                color = if (group.isCurrent) TvText.Section else TvText.Muted,
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (!group.isCurrent) {
            val kindLabel = when (group.source?.kind) {
                SourceKind.TVBOX -> "TVBox 资源站"
                SourceKind.EMBY -> "Emby 媒体库"
                null -> "这条来源已从应用里移除"
            }
            Text(
                "$kindLabel · 打开这里的片子会先确认是否切换来源",
                color = TvText.Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 跨来源确认弹窗：默认焦点在「取消」，避免一次误按把顶栏来源换掉 */
@Composable
private fun NfSwitchSourceDialog(
    source: SourceEntity,
    item: MediaEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        tvMoveFocus(cancelFocus)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(520.dp)
                .background(AppColors.SurfaceHi, Radius.Lg)
                .border(1.dp, Edge.HairlineStrong, Radius.Lg)
                .padding(horizontal = 30.dp, vertical = 28.dp)
        ) {
            Text(
                "切换来源查看？",
                color = TvText.Title,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "「${item.title}」属于来源「${source.name}」。切换后顶栏的来源会跟着变，" +
                    "分类和线路也按这条来源重新读取。",
                color = TvText.Body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 14.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NfActionButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus)
                )
                NfActionButton(text = "切换并打开", onClick = onConfirm)
            }
        }
    }
}
