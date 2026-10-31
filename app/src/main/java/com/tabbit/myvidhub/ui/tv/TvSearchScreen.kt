package com.tabbit.myvidhub.ui.tv

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.ReturnSpot
import com.tabbit.myvidhub.ui.StandTarget
import com.tabbit.myvidhub.ui.cardStand
import com.tabbit.myvidhub.ui.gridState
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.themeBackdrop
import com.tabbit.myvidhub.ui.theme.TvText
import kotlinx.coroutines.delay

/**
 * 电视端搜索页状态：由导航层（TvApp）持有，
 * 进详情再返回时搜索词与结果保留，真正退出搜索页才清空。
 */
class TvSearchUiState {
    var keyword by mutableStateOf("")

    /** 输入过程中的联想片名：只展示，不触发真正的搜索 */
    var suggestions by mutableStateOf<List<String>>(emptyList())

    /** 用户确认要搜的关键字（选中联想 / 点搜索 / 回车才会赋值） */
    var submittedKeyword by mutableStateOf("")

    var results by mutableStateOf<List<MediaEntity>>(emptyList())
    var loading by mutableStateOf(false)
    var searched by mutableStateOf(false)

    /** 远程站点搜索进行中：本地库只有首页入库的少量条目，绝大多数片名要靠各站点远程命中 */
    var remoteSearching by mutableStateOf(false)

    /** 已完成搜索的关键字，从详情返回时据此跳过重复搜索 */
    var searchedKeyword by mutableStateOf("")

    /** 热门榜缓存版本，刷新后自增以触发联想重查 */
    var hotEpoch by mutableStateOf(0)

    fun clear() {
        keyword = ""
        suggestions = emptyList()
        submittedKeyword = ""
        results = emptyList()
        loading = false
        searched = false
        searchedKeyword = ""
        remoteSearching = false
    }

    /**
     * 真正发起一次搜索。
     * @param title 选中联想片名时传入，会回填到输入框
     */
    fun submit(title: String? = null) {
        val kw = (title ?: keyword).trim()
        if (kw.isEmpty()) return
        if (title != null) keyword = title
        suggestions = emptyList()
        submittedKeyword = kw
    }
}

/**
 * 电视端搜索页。
 *
 * 交互对齐手机端（但按遥控器重排布局）：
 *   · 输入框输入 → 下方展示联想到的影片名称，此时**不联网/不查库出结果**；
 *   · 遥控器选中某条联想、或聚焦「搜索」按钮按 OK、或键盘回车，才真正查询影片；
 *   · 结果用海报网格，聚焦第一张卡方便直接进详情。
 */
@Composable
fun TvSearchScreen(
    vm: LibraryViewModel,
    state: TvSearchUiState,
    onBack: () -> Unit,
    onDetail: (Long) -> Unit
) {
    val fieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }

    // 首次进搜索页后台拉一次豆瓣热门榜，补齐联想（24h 内走本地缓存，不联网）
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

    // 实际搜索：只有 submittedKeyword 变化（选中联想 / 点搜索 / 回车）才执行；
    // 从详情返回时关键字没变，直接复用已有结果，不重复查询。
    // 与手机端一致：本地库先上屏，随后并发搜 TVBox 各站点，谁先搜完先追加谁的结果。
    // 只查本地库时，TV 端会因为库里只有首页那批条目而「搜不到」，远程这一步是主要补齐来源。
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

    // 从详情返回时结果子树被重建：落点卡在组合时把新生成的 requester 交回 stand，
    // 网格滚动位置由 resultsGridState 按同一份记录起滚。
    val stand = remember { StandTarget() }
    val resultsGridState = ReturnSpot.gridState("tv_search/results")
    val firstResultId = state.results.firstOrNull()?.id

    // 进页自动聚焦输入框；从详情返回（已有搜索结果）时把焦点交给结果卡而不是输入框
    LaunchedEffect(Unit) {
        if (state.submittedKeyword.isEmpty()) runCatching { fieldFocus.requestFocus() }
    }
    LaunchedEffect(state.submittedKeyword, state.results.isNotEmpty()) {
        if (state.submittedKeyword.isEmpty()) return@LaunchedEffect
        if (state.results.isNotEmpty()) {
            // 离开时站在哪张结果卡就还给那张，没有记录才落第一张
            tvMoveFocus(*listOfNotNull(stand.requester, firstResultFocus).toTypedArray())
        }
    }

    val showSuggestions =
        state.submittedKeyword.isEmpty() && state.keyword.isNotBlank() && state.suggestions.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .themeBackdrop()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = TvPagePadding, end = TvPagePadding, top = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 遥控器 Back 键统一由 TvApp.BackHandler 处理，不再放页面内返回按钮
            TvSearchField(
                value = state.keyword,
                focusRequester = fieldFocus,
                suggestionsVisible = showSuggestions,
                modifier = Modifier.weight(1f),
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
            TvPillButton(
                text = "搜索",
                primary = true,
                onClick = { state.submit() }
            )
        }

        when {
            showSuggestions -> {
                Text(
                    "猜你想看 · 按 OK 直接搜索",
                    color = TvText.Meta,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = TvPagePadding, bottom = 8.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.suggestions.take(8), key = { "tv_sug_$it" }) { title ->
                        TvSuggestionRow(
                            title = title,
                            onClick = { state.submit(title) }
                        )
                    }
                }
            }

            state.loading -> TvEmptyState("搜索中…")

            // 本地已查完但远程站点还在并发返回：先上屏本地结果，顶部提示仍在补
            state.results.isNotEmpty() -> {
                // 与手机端一致：按来源（sourceName）分组，组标题占整行，组内仍是海报网格。
                // groupBy 保留插入顺序，所以本地库的组在前、远程陆续命中的组往后追加。
                val grouped = remember(state.results) {
                    state.results.groupBy { it.sourceName.ifBlank { "未知来源" } }
                }
                Text(
                    if (state.remoteSearching) {
                        "搜索结果 ${state.results.size} 条 · ${grouped.size} 个来源，远程站点搜索中…"
                    } else {
                        "搜索结果 ${state.results.size} 条 · ${grouped.size} 个来源"
                    },
                    color = TvText.Meta,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = TvPagePadding, bottom = 10.dp)
                )
                LazyVerticalGrid(
                    state = resultsGridState,
                    columns = GridCells.Fixed(tvGridColumns()),
                    contentPadding = PaddingValues(start = TvPagePadding, end = TvPagePadding, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    grouped.forEach { (sourceName, itemsOfSource) ->
                        item(
                            key = "tv_s_grp_$sourceName",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            TvSectionTitle(
                                sourceName,
                                contentPadding = PaddingValues(start = 0.dp, end = 0.dp, top = 8.dp, bottom = 0.dp),
                                trailing = {
                                    Text(
                                        "${itemsOfSource.size} 条",
                                        color = TvText.Meta,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            )
                        }
                        items(itemsOfSource, key = { "tv_s_${it.id}" }) { item ->
                            val ownFocus = remember(item.id) { FocusRequester() }
                            TvPosterCard(
                                item = item,
                                onClick = { onDetail(item.id) },
                                fillCell = true,
                                // 首张卡同时是这一页的入口锚点，落点记账复用它的 requester
                                modifier = ReturnSpot.cardStand(
                                    pageKey = "tv_search",
                                    rowKey = "results",
                                    mediaId = item.id,
                                    target = stand,
                                    requester = if (item.id == firstResultId) firstResultFocus else ownFocus
                                )
                            )
                        }
                    }
                }
            }

            // 远程还没回来时不要急着判「没找到」，否则用户看到的是搜索中的空屏
            state.remoteSearching -> TvEmptyState("正在搜索各站点…", "本地库没有命中，正在向 TVBox 各站点查询")

            state.searched -> TvEmptyState("没有找到「${state.submittedKeyword}」", "换个关键词试试")
            else -> TvEmptyState("搜索媒体库", "输入片名、演员或拼音首字母，选中联想或按「搜索」确认")
        }
    }
}

/**
 * 联想片名行：整行可聚焦，聚焦时翻白底黑字，
 * 遥控器按 OK 直接以该片名搜索（与手机端点联想 chip 的语义对齐）。
 */
@Composable
private fun TvSuggestionRow(
    title: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else AppColors.Surface)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
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

/** 电视端搜索输入框：获得焦点时显示品牌色描边，未聚焦时保持低调 */
@Composable
private fun TvSearchField(
    value: String,
    focusRequester: FocusRequester,
    suggestionsVisible: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface)
            .then(
                if (focused) Modifier.border(2.dp, AppColors.Brand, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, AppColors.SurfaceHi, RoundedCornerShape(10.dp))
            )
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focusRequester)
            .focusable()
            // 输入框内部会把方向键当光标移动吃掉，这里截获「下」把焦点交给联想列表
            .onPreviewKeyEvent { event ->
                if (suggestionsVisible && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                } else {
                    false
                }
            }
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
