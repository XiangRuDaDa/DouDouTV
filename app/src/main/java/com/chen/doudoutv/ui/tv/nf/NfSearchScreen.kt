package com.chen.doudoutv.ui.tv.nf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.ReturnSpot
import com.chen.doudoutv.ui.StandTarget
import com.chen.doudoutv.ui.PrecomposeWindow
import com.chen.doudoutv.ui.cardStand
import com.chen.doudoutv.ui.listState
import com.chen.doudoutv.ui.tv.TvPagePadding
import com.chen.doudoutv.ui.tv.TvSearchUiState
import com.chen.doudoutv.ui.tv.tvClaimFocusOnEnter
import com.chen.doudoutv.ui.tv.tvPosterColumns
import com.chen.doudoutv.ui.tv.tvFocusRing
import com.chen.doudoutv.ui.tv.tvMoveFocus
import com.chen.doudoutv.ui.tv.tvResumeTick
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Edge
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.TvText
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
    // 搜索历史：和手机端同一份 Prefs 记录，提交一次搜索或清空历史时 historyEpoch 自增触发重读
    val history = remember(state.historyEpoch) { Prefs.getSearchHistory() }
    val histFirstFocus = remember(history.firstOrNull()) { FocusRequester() }

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
    // 历史只在「还没提交、也还没打字」的空态铺开：一开始打字就让位给联想
    val showHistory =
        state.submittedKeyword.isEmpty() && state.keyword.isBlank() && history.isNotEmpty()
    // 双层分组：先按来源（一个来源一组：TVBox 一整份配置、Emby 一台服务器），
    // 组内再按线路（站点）。Emby 没有线路概念，那一组就只有来源这一层。
    val sources by vm.enabledSources.collectAsStateWithLifecycle()
    val sourceGroups = remember(state.results, sources) { nfSearchGroups(state.results, sources) }
    val sourceCount = sourceGroups.size
    // 真的按线路铺开成多组的来源数
    val routeGroupCount = sourceGroups.count { it.isTvBox && it.routes.size > 1 }

    // 每个来源的折叠状态 —— 默认第一个来源展开，其余折叠
    val expandedSources = remember(sourceGroups.size) {
        mutableStateMapOf<String, Boolean>().apply {
            sourceGroups.firstOrNull()?.let { this[it.key] = true }
        }
    }
    // 线路（站点）默认全部折叠：状态表初始为空即「没展开过」，OK 一次才铺那一行海报
    val expandedRoutes = remember(sourceGroups.size) { mutableStateMapOf<String, Boolean>() }
    // 全折叠时结果区没有任何卡片可聚焦，↑↓ 与进页抢焦改落到第一颗来源表头（规范 R6 不留焦点死区）
    val firstHeaderFocus = remember(sourceGroups.firstOrNull()?.key) { FocusRequester() }
    val resultsCardsVisible = sourceGroups.any { group ->
        (expandedSources[group.key] ?: false) &&
            group.routes.any { (routeName, _) -> routeName.isBlank() || (expandedRoutes["${group.key}/$routeName"] ?: false) }
    }
    val resultsAnchor = if (resultsCardsVisible) gridFirstFocus else firstHeaderFocus
    // 输入框 ↓ / 导航条 ↓ 的落点：联想列表 > 结果首卡（全折叠时是第一颗来源表头）> 历史首行 > 输入框自身
    // （空态且没有历史时页面只有输入框可聚焦，不能留焦点死区 —— 规范 R6）。
    val belowField = when {
        showSuggestions -> sugFirstFocus
        state.results.isNotEmpty() -> resultsAnchor
        showHistory -> histFirstFocus
        else -> fieldFocus
    }

    val resumeTick = tvResumeTick()
    // tick 里刻意不放 showSuggestions：联想是「打出第一个字 200ms 后」冒出来的，
    // 一旦进 tick，抢焦就会把焦点从 BasicTextField 挪到输入框外层那颗 focusable 节点上 ——
    // 输入法面板跟着关闭，用户才打了一个字就得重新按 OK 弹框。
    // 联想出现时焦点该留在原处：往下走由输入框自己的 ↓（nfFocusRoute）显式交点。
    // 从别的 tab 点「搜索」切过来：焦点留在标签上，不交给输入框（NfTabSwitch）
    val viaTab = remember { NfTabSwitch.consume(NfTab.Search) }
    tvClaimFocusOnEnter(
        tick = listOf(resumeTick, state.results.size),
        // 输入 / 联想阶段这一串锚点只留输入框自己：
        // 焦点往下走只有两条正当路径：用户自己按 ↓（nfFocusRoute 显式交点），或提交搜索出结果。
        // 提交之后（submittedKeyword 非空）恢复原来的抢焦：还站在离开时那张卡，没有记录才落首卡。
        anchorsProvider = {
            when {
                viaTab -> listOf(chrome.tabOf(NfTab.Search))
                state.submittedKeyword.isEmpty() -> listOf(fieldFocus)
                else -> listOfNotNull(stand.requester, belowField, fieldFocus)
            }
        }
    )

    // 返回键分层的依据：焦点此刻在不在「输入框 + 两颗按钮」这一行
    val jumpTo = rememberNfLayerJump()
    var fieldRowFocused by remember { mutableStateOf(false) }

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
        Column(
            Modifier
                .fillMaxSize()
                // 返回键的层级：联想 / 结果 / 历史 → 输入框 → tab 栏（tab 栏那一级由 TvApp 根部接）
                .nfBackKey {
                    if (fieldRowFocused) jumpTo(chrome.tabOf(NfTab.Search)) else jumpTo(fieldFocus)
                }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = TvPagePadding, end = TvPagePadding, bottom = 14.dp)
                    .onFocusEvent { fieldRowFocused = it.hasFocus },
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
                NfActionButton(text = "搜索", onClick = { state.submit() }, primary = true)
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
                                "搜索结果 ${state.results.size} 条 · ${sourceCount} 个来源" +
                                    (if (routeGroupCount > 0) " · $routeGroupCount 个来源按线路分组" else "") +
                                    if (state.remoteSearching) "，远程站点搜索中…" else "",
                                color = TvText.Meta,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        NfSearchGroupedGrid(
                            groups = sourceGroups,
                            expandedSources = expandedSources,
                            expandedRoutes = expandedRoutes,
                            firstHeaderFocus = firstHeaderFocus,
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

                    else -> if (showHistory) {
                        NfSearchHistory(
                            history = history,
                            firstFocus = histFirstFocus,
                            upFocusRequester = fieldFocus,
                            onSelect = { state.submit(it) },
                            onClear = { state.clearHistory() }
                        )
                    } else NfInlineEmpty(
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
            .tvFocusRing(focused)
            .then(if (focused) Modifier else Modifier.border(1.dp, Edge.HairlineStrong, Radius.Md))
            .nfFocusRoute(up = upFocusRequester, down = downFocusRequester)
            .focusRequester(focusRequester)
            .onFocusEvent { focused = it.hasFocus }
            .focusable()
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = TvText.Meta,
                modifier = Modifier.size(22.dp)
            )
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
            .tvFocusRing(focused)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = if (focused) Color.Black else TvText.Meta,
            modifier = Modifier.size(22.dp)
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

/**
 * 空态的「最近搜索」：横向流式铺开，放不下自动换下一行，最多 10 条（与手机端同一口径，
 * 超出 10 条的旧词在 [Prefs.addSearchHistory] 写入时就已经丢掉）。
 * 首颗的 ↑ 显式还给输入框，其余方向走系统几何搜索；「清空历史」排在最后一颗，是这一段的最后一站。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NfSearchHistory(
    history: List<String>,
    firstFocus: FocusRequester,
    upFocusRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TvPagePadding, end = TvPagePadding, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            history.take(10).forEachIndexed { index, kw ->
                NfHistoryChip(
                    title = kw,
                    onClick = { onSelect(kw) },
                    modifier = if (index == 0)
                        Modifier.nfFocusRoute(up = upFocusRequester).focusRequester(firstFocus)
                    else Modifier
                )
            }
            NfActionButton(text = "清空历史", onClick = onClear)
        }
    }
}

/** 历史记录的一颗词章：整颗可聚焦，OK 直接以该词搜索 */
@Composable
private fun NfHistoryChip(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier
            .widthIn(max = 420.dp)
            .clip(Radius.Md)
            .background(if (focused) Color.White else AppColors.Surface)
            .tvFocusRing(focused)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = if (focused) Color.Black else TvText.Meta,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = if (focused) Color.Black else TvText.Title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
 * 搜索结果的分组：来源 → 线路（站点）。
 *
 * Emby 没有线路概念，[routes] 只有一项且线路名为空 —— 界面上就只有来源那一层。
 */
private class NfSearchGroup(
    val key: String,
    val label: String,
    val isTvBox: Boolean,
    val routes: List<Pair<String, List<MediaEntity>>>,
)

private fun nfSearchGroups(results: List<MediaEntity>, sources: List<SourceEntity>): List<NfSearchGroup> =
    nfGroupBySource(results, sources).map { group ->
        if (!group.isTvBox) return@map NfSearchGroup(group.key, group.label, false, listOf("" to group.items))
        // 线路名来自入库/远程命中时带的站点名；老库里还没记站点名的条目汇到最后一组，
        // 不然会给它们铺出一堆空标题行
        val byRoute = group.items.groupBy { it.playSource }
        val named = byRoute.filterKeys { it.isNotBlank() }
        val unlabeled = byRoute[""] ?: emptyList()
        val routes = named.toList() + if (unlabeled.isEmpty()) emptyList() else listOf("未标注线路" to unlabeled)
        NfSearchGroup(group.key, group.label, true, routes)
    }

/**
 * 搜索结果分组：一个来源一组（TVBox 一整份配置、Emby 一台服务器），组内按线路（站点）铺开。
 * 外层 [LazyColumn]，每个来源组一个 section header（可聚焦，OK 键切换展开/折叠）。
 * 展开后：TVBox 每条线路一个小标题（同样可聚焦切换），OK 才铺那一行竖版海报；Emby 没有线路这一层，来源展开即出海报。
 * 默认第一个来源展开、其下线路全部折叠 —— 一次搜索几十个站点全铺开，滚到哪儿都找不到片名。
 */
@Composable
private fun NfSearchGroupedGrid(
    groups: List<NfSearchGroup>,
    expandedSources: MutableMap<String, Boolean>,
    expandedRoutes: MutableMap<String, Boolean>,
    firstHeaderFocus: FocusRequester,
    stand: StandTarget,
    firstFocus: FocusRequester,
    upFocusRequester: FocusRequester,
    onDetail: (Long) -> Unit
) {
    val listState = ReturnSpot.listState("$NfSearchKey/results", precompose = PrecomposeWindow.Tv)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 竖版海报：一屏约 6-7 列，按 5:7 比例算卡片宽
    val posterWidth: Dp = (screenWidth / 7f).coerceAtLeast(110.dp).coerceAtMost(160.dp)
    val firstId = groups.firstOrNull()?.routes?.firstOrNull()?.second?.firstOrNull()?.id

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TvPagePadding, end = TvPagePadding,
            top = 4.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.forEachIndexed { sourceIdx, group ->
            val isFirstSource = sourceIdx == 0
            val isExpanded = expandedSources[group.key] ?: false
            val total = group.routes.sumOf { it.second.size }

            // 来源组标题：可聚焦，OK 键切换展开/折叠
            item(key = "nf_src_header_${group.key}") {
                NfSourceHeader(
                    label = group.label,
                    meta = if (group.isTvBox) "$total 条 · ${group.routes.size} 条线路" else "$total 条",
                    expanded = isExpanded,
                    // 第一颗来源表头交回页面持有的那颗锚点：全折叠时结果区只有表头可聚焦，
                    // 输入框的 ↓ 和进页抢焦都落在这儿，不能留焦点死区（规范 R6）
                    focusRequester = if (isFirstSource) firstHeaderFocus else remember(group.key) { FocusRequester() },
                    upFocusRequester = if (isFirstSource) upFocusRequester else null,
                    onToggle = {
                        expandedSources[group.key] = !(expandedSources[group.key] ?: false)
                    }
                )
            }

            // 展开后渲染各线路
            if (isExpanded) {
                group.routes.forEachIndexed { routeIdx, (routeName, routeItems) ->
                    val isFirstCardEver = isFirstSource && routeIdx == 0
                    val siteRowKey = "${group.key}/$routeName"
                    // Emby 那一组只有一个空线路名的桶：没有线路这一层，来源展开就直接铺海报
                    val routeExpanded = routeName.isBlank() || (expandedRoutes[siteRowKey] ?: false)

                    if (routeName.isNotBlank()) {
                        item(key = "nf_site_header_$siteRowKey") {
                            NfSourceHeader(
                                label = routeName,
                                meta = "${routeItems.size} 条",
                                expanded = routeExpanded,
                                focusRequester = remember(siteRowKey) { FocusRequester() },
                                upFocusRequester = null,
                                indent = true,
                                onToggle = {
                                    expandedRoutes[siteRowKey] = !(expandedRoutes[siteRowKey] ?: false)
                                }
                            )
                        }
                    }

                    // 该线路的海报横排
                    if (routeExpanded) {
                        item(key = "nf_site_row_$siteRowKey") {
                            val rowState = ReturnSpot.listState("$NfSearchKey/$siteRowKey", precompose = PrecomposeWindow.Tv)
                            LazyRow(
                                state = rowState,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(NfCardGap)
                            ) {
                                items(routeItems, key = { "nf_s_${it.id}" }) { mediaItem ->
                                    val cardFocus = remember(mediaItem.id) { FocusRequester() }
                                    NfPosterCard(
                                        item = mediaItem,
                                        onClick = { onDetail(mediaItem.id) },
                                        width = posterWidth,
                                        modifier = Modifier
                                            .then(
                                                if (isFirstCardEver && mediaItem.id == firstId)
                                                    Modifier.nfFocusRoute(up = upFocusRequester)
                                                else Modifier
                                            )
                                            .then(
                                                ReturnSpot.cardStand(
                                                    pageKey = NfSearchKey,
                                                    rowKey = siteRowKey,
                                                    mediaId = mediaItem.id,
                                                    target = stand,
                                                    requester = if (mediaItem.id == firstId) firstFocus else cardFocus
                                                )
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分组表头行：可聚焦 + OK 键切换展开/折叠，来源层和线路层共用。
 * 来源层是「来源名 · 条数（TVBox 再带线路数）」，线路层是「站点名 · 条数」且整体右移一档表示层级。
 */
@Composable
private fun NfSourceHeader(
    label: String,
    meta: String,
    expanded: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    indent: Boolean = false,
    onToggle: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.Md)
            .background(if (focused) AppColors.SurfaceHi else Color.Transparent)
            .tvFocusRing(focused)
            .then(upFocusRequester?.let { Modifier.nfFocusRoute(up = it) } ?: Modifier)
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle
            )
            .focusable(interactionSource = interaction)
            .padding(start = if (indent) 28.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (expanded) "▾" else "▸",
            color = if (focused) Color.White else TvText.Meta,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            label,
            color = if (focused) Color.White else if (indent) TvText.Section else TvText.Title,
            style = if (indent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            meta,
            color = if (focused) Color.White.copy(alpha = 0.7f) else TvText.Meta,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
