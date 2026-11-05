package com.tabbit.myvidhub.ui.tv.nf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.ReturnSpot
import com.tabbit.myvidhub.ui.StandTarget
import com.tabbit.myvidhub.ui.cardStand
import com.tabbit.myvidhub.ui.gridState
import com.tabbit.myvidhub.ui.tv.TvPagePadding
import com.tabbit.myvidhub.ui.tv.TvSearchUiState
import com.tabbit.myvidhub.ui.tv.tvClaimFocusOnEnter
import com.tabbit.myvidhub.ui.tv.tvGridColumns
import com.tabbit.myvidhub.ui.tv.tvMoveFocus
import com.tabbit.myvidhub.ui.tv.tvResumeTick
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.Edge
import com.tabbit.myvidhub.ui.theme.Radius
import com.tabbit.myvidhub.ui.theme.TvText
import kotlinx.coroutines.delay

/** [ReturnSpot] 的页面标识：搜索结果网格的滚动与落点记在它下面 */
private const val NfSearchKey = "nf_search"

/**
 * Netflix 版布局的搜索页 —— 导航条里的第四颗 tab，不是从首页跳出去的独立页面。
 *
 * 数据和查询链路整条复用旧搜索页（[TvSearchUiState] 由导航层持有）：
 *   · 输入只出联想，选中联想 / 点「搜索」/ 键盘回车才真正查询；
 *   · 本地库先上屏，随后并发搜 TVBox 各站点，谁先返回先追加谁；
 *   · `submittedKeyword` 没变就不重查 —— 从详情页按返回回来时结果原样留着。
 * 换掉的只有版式：导航条 + 竖版海报网格 + 十英尺输入行，外壳由 [NfScaffold] 统一给。
 */
@Composable
fun NfSearchScreen(
    vm: LibraryViewModel,
    state: TvSearchUiState,
    onTab: (NfTab) -> Unit,
    onDetail: (Long) -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit
) {
    val chrome = rememberNfChrome()
    val fieldFocus = remember { FocusRequester() }
    // 首行/首卡的锚点随「那一格换成谁了」重建：同一实例先后挂两个节点会留下指向废弃节点的记录
    val sugFirstFocus = remember(state.suggestions.firstOrNull()) { FocusRequester() }
    val gridFirstFocus = remember(state.results.firstOrNull()?.id) { FocusRequester() }
    val stand = remember { StandTarget() }

    // 首次进搜索页后台拉一次热门榜补齐联想（24h 内走本地缓存，不联网）
    LaunchedEffect(Unit) {
        if (runCatching { vm.refreshHotSuggestions() }.getOrDefault(false)) state.hotEpoch++
    }

    // 联想：输入停顿 200ms 后查库。中文按标题模糊匹配，字母按拼音首字母前缀匹配
    LaunchedEffect(state.keyword, state.hotEpoch) {
        val q = state.keyword.trim()
        if (q.isEmpty() || state.submittedKeyword.isNotEmpty()) {
            state.suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        state.suggestions = runCatching { vm.pinyinSuggest(q.lowercase()) }
            .getOrDefault(emptyList())
    }

    // 实际搜索：只有 submittedKeyword 变化才执行；从详情返回时关键字没变，直接复用已有结果。
    LaunchedEffect(state.submittedKeyword) {
        val q = state.submittedKeyword.trim()
        if (q.isEmpty() || q == state.searchedKeyword) return@LaunchedEffect
        state.loading = true
        state.searchedKeyword = q

        val local = runCatching { vm.searchMedia(q) }.getOrDefault(emptyList())
        if (state.submittedKeyword == q) {
            state.results = local
            state.loading = false
            state.searched = true
        }

        /** 把一批远程结果并进当前列表（按 remoteId 去重） */
        fun appendRemote(batch: List<MediaEntity>) {
            val seen = state.results.map { it.remoteId }.toSet()
            state.results = state.results + batch.filter { it.remoteId !in seen }
        }

        state.remoteSearching = true
        val remote = vm.searchRemote(q) { partial ->
            // 远程返回前用户可能已经改词重新搜索，旧结果不能覆盖当前列表
            if (state.submittedKeyword != q) return@searchRemote
            appendRemote(partial)
        }
        if (state.submittedKeyword == q) {
            appendRemote(remote)
            state.remoteSearching = false
        }
    }

    val showSuggestions =
        state.submittedKeyword.isEmpty() && state.keyword.isNotBlank() && state.suggestions.isNotEmpty()
    val grouped = remember(state.results) {
        state.results.groupBy { it.sourceName.ifBlank { "未知来源" } }
    }

    // 输入框 ↓ / 导航条 ↓ 的落点：联想列表 > 结果首卡 > 输入框自身（空态时页面只有输入框可聚焦，
    // 不能留焦点死区 —— 规范 R6）。
    val belowField = when {
        showSuggestions -> sugFirstFocus
        state.results.isNotEmpty() -> gridFirstFocus
        else -> fieldFocus
    }

    val resumeTick = tvResumeTick()
    tvClaimFocusOnEnter(
        tick = listOf(resumeTick, showSuggestions, state.results.size),
        // 输入 / 联想阶段这一串锚点只留输入框自己：打第一个字之后联想列表冒出来，
        // tick 跟着变，抢焦会把焦点从正在打字的输入框拽到联想首行 —— 用户还没动手，光标就跑了。
        // 焦点往下走只有两条正当路径：用户自己按 ↓（nfFocusRoute 显式交点），或提交搜索出结果。
        // 提交之后（submittedKeyword 非空）恢复原来的抢焦：还站在离开时那张卡，没有记录才落首卡。
        anchorsProvider = {
            if (state.submittedKeyword.isEmpty()) listOf(fieldFocus)
            else listOfNotNull(stand.requester, belowField, fieldFocus)
        }
    )

    NfScaffold(
        vm = vm,
        current = NfTab.Search,
        chrome = chrome,
        // 导航条 ↓ 显式交回输入行那一层（联想首行 > 结果首卡 > 输入框），规范 R1
        navInFlow = true,
        belowFocusRequester = belowField,
        onTab = onTab,
        onSettings = onSettings,
        onManageSources = onSources
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = TvPagePadding, end = TvPagePadding, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NfSearchField(
                    value = state.keyword,
                    focusRequester = fieldFocus,
                    modifier = Modifier.weight(1f),
                    upFocusRequester = chrome.tabOf(NfTab.Search),
                    downFocusRequester = belowField,
                    onValueChange = {
                        state.keyword = it
                        // 改词就清掉上一次的结果，回到「输入 + 联想」状态
                        if (state.submittedKeyword.isNotEmpty()) {
                            state.submittedKeyword = ""
                            state.searchedKeyword = ""
                            state.results = emptyList()
                            state.searched = false
                            state.loading = false
                            state.remoteSearching = false
                        }
                    },
                    onSubmit = { state.submit() }
                )
                Spacer(Modifier.width(16.dp))
                NfActionButton(text = "搜索", onClick = { state.submit() })
                Spacer(Modifier.width(12.dp))
                // 清空：词、联想、结果和各进行中标记一次全清，回到「还没输入」的初始态。
                // 清完把焦点交回输入框 —— 接着打下一个词不用再按一次 ↑。
                NfActionButton(
                    text = "清空",
                    onClick = {
                        state.clear()
                        tvMoveFocus(fieldFocus)
                    }
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    showSuggestions -> {
                        Column(Modifier.fillMaxSize()) {
                            Text(
                                "猜你想看 · 按 OK 直接搜索",
                                color = TvText.Meta,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = TvPagePadding, bottom = 8.dp)
                            )
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = TvPagePadding, end = TvPagePadding, bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.suggestions.take(8), key = { "nf_sug_$it" }) { title ->
                                    NfSuggestionRow(
                                        title = title,
                                        onClick = { state.submit(title) },
                                        modifier = if (title == state.suggestions.firstOrNull())
                                            Modifier.nfFocusRoute(up = fieldFocus).focusRequester(sugFirstFocus)
                                        else Modifier
                                    )
                                }
                            }
                        }
                    }

                    state.loading -> NfSearchBusy("搜索中…")

                    state.results.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = TvPagePadding, end = TvPagePadding, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "搜索结果 ${state.results.size} 条 · ${grouped.size} 个来源" +
                                    if (state.remoteSearching) "，远程站点搜索中…" else "",
                                color = TvText.Meta,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        NfSearchGrid(
                            groups = grouped,
                            stand = stand,
                            firstFocus = gridFirstFocus,
                            upFocusRequester = fieldFocus,
                            onDetail = onDetail
                        )
                    }

                    // 远程还没回来时不要急着判「没找到」，否则用户看到的是搜索中的空屏
                    state.remoteSearching -> NfSearchBusy("正在搜索各站点…", "本地库没有命中，正在向 TVBox 各站点查询")

                    state.searched -> NfInlineEmpty(
                        text = "没有找到「${state.submittedKeyword}」",
                        hint = "换个关键词试试，结果按来源分组铺开"
                    )

                    else -> NfInlineEmpty(
                        text = "搜索媒体库",
                        hint = "输入片名、演员或拼音首字母，选中联想或按「搜索」确认"
                    )
                }
            }
        }
    }
}

/**
 * 搜索输入框。方向键显式交回：↑ 给本 tab 在导航条上的那颗按钮，↓ 给联想列表 / 结果首卡。
 * 文本框自己会把方向键读成光标移动，所以走按键拦截（[nfFocusRoute]）而不是 focusProperties。
 */
@Composable
private fun NfSearchField(
    value: String,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier
            .height(56.dp)
            .zIndex(1f)
            .clip(Radius.Md)
            .background(if (focused) AppColors.SurfaceHi else AppColors.Surface)
            .then(
                if (focused) Modifier.border(2.dp, Color.White, Radius.Md)
                else Modifier.border(1.dp, Edge.HairlineStrong, Radius.Md)
            )
            .nfFocusRoute(up = upFocusRequester, down = downFocusRequester)
            .focusRequester(focusRequester)
            .onFocusEvent { focused = it.hasFocus }
            .focusable()
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", style = MaterialTheme.typography.titleMedium, color = TvText.Meta)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        "搜索片名 / 演员 / 拼音首字母",
                        color = TvText.Meta,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TvText.Title,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(AppColors.Brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 联想片名行：整行可聚焦，OK 直接以该片名搜索；首行的 ↑ 显式还给输入框 */
@Composable
private fun NfSuggestionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier
            .fillMaxWidth()
            .clip(Radius.Md)
            .background(if (focused) Color.White else AppColors.Surface)
            .then(if (focused) Modifier.border(2.dp, Color.White, Radius.Md) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "🔍",
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Color.Black else TvText.Meta
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            color = if (focused) Color.Black else TvText.Title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 查询进行中的整块状态：转圈 + 一句话，不可聚焦（此时焦点仍留在输入框上） */
@Composable
private fun NfSearchBusy(text: String, hint: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = AppColors.Brand,
            strokeWidth = 2.dp,
            modifier = Modifier.width(26.dp).height(26.dp)
        )
        Text(
            text,
            color = TvText.Section,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp)
        )
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * 结果网格：按来源分组，组标题横跨整行，组内是竖版海报。
 * `groupBy` 保留插入顺序，所以本地库的组在前、远程陆续命中的组往后追加（与旧页一致）。
 */
@Composable
private fun NfSearchGrid(
    groups: Map<String, List<MediaEntity>>,
    stand: StandTarget,
    firstFocus: FocusRequester,
    upFocusRequester: FocusRequester,
    onDetail: (Long) -> Unit
) {
    val gridState = ReturnSpot.gridState("$NfSearchKey/results")
    val columns = tvGridColumns()
    val total = groups.values.sumOf { it.size }
    val stagger = nfFirstFillStagger(total, NfSearchKey)
    val firstId = groups.values.firstOrNull()?.firstOrNull()?.id
    val firstGroup = groups.keys.firstOrNull()

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TvPagePadding, end = TvPagePadding,
            top = NfRowVerticalPadding, bottom = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(NfCardGap),
        verticalArrangement = Arrangement.spacedBy(NfRowVerticalPadding)
    ) {
        groups.forEach { (sourceName, itemsOfSource) ->
            item(key = "nf_s_grp_$sourceName", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (sourceName == firstGroup) 4.dp else 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        sourceName,
                        color = TvText.Title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${itemsOfSource.size} 条",
                        color = TvText.Meta,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            gridItemsIndexed(itemsOfSource, key = { _, it -> "nf_s_${it.id}" }) { index, item ->
                val cardFocus = remember(item.id) { FocusRequester() }
                NfPosterCard(
                    item = item,
                    onClick = { onDetail(item.id) },
                    fillCell = true,
                    anchorStart = index % columns == 0,
                    entrance = nfEntrance(index, stagger),
                    modifier = Modifier
                        .then(
                            if (sourceName == firstGroup && index < columns)
                                Modifier.nfFocusRoute(up = upFocusRequester)
                            else Modifier
                        )
                        .then(
                            ReturnSpot.cardStand(
                                pageKey = NfSearchKey,
                                rowKey = "results",
                                mediaId = item.id,
                                target = stand,
                                requester = if (item.id == firstId) firstFocus else cardFocus
                            )
                        )
                )
            }
        }
    }
}
