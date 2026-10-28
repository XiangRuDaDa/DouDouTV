package com.tabbit.myvidhub.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 电视端搜索页状态：由导航层（TvApp）持有，
 * 进详情再返回时搜索词与结果保留，真正退出搜索页才清空。
 */
class TvSearchUiState {
    var keyword by mutableStateOf("")
    var results by mutableStateOf<List<MediaEntity>>(emptyList())
    var loading by mutableStateOf(false)
    var searched by mutableStateOf(false)

    /** 已完成搜索的关键字，从详情返回时据此跳过重复搜索 */
    var searchedKeyword by mutableStateOf("")

    fun clear() {
        keyword = ""
        results = emptyList()
        loading = false
        searched = false
        searchedKeyword = ""
    }
}

/**
 * 电视端搜索页。
 *
 * 遥控器没有键盘，实际输入靠系统弹出软键盘或在搜索框上直接键入拼音/英文，
 * 因此界面重点不是「输入框多漂亮」，而是：
 *   · 搜索框默认获得焦点，一进来就能打字；
 *   · 结果用大号海报网格，聚焦即放大，方便沙发上挑选；
 *   · 空结果给出下一步提示，而不是一片空白。
 */
@Composable
fun TvSearchScreen(
    vm: LibraryViewModel,
    state: TvSearchUiState,
    onBack: () -> Unit,
    onDetail: (Long) -> Unit
) {
    val fieldFocus = remember { FocusRequester() }

    suspend fun runSearch() {
        val kw = state.keyword.trim()
        if (kw.isEmpty()) {
            state.results = emptyList()
            state.searched = false
            return
        }
        state.loading = true
        state.results = runCatching { vm.searchMedia(kw) }.getOrDefault(emptyList())
        state.searchedKeyword = kw
        state.loading = false
        state.searched = true
    }

    // 输入停顿 350ms 后自动搜索；从详情返回时关键字没变就不重复搜
    LaunchedEffect(state.keyword) {
        val kw = state.keyword.trim()
        if (kw.isEmpty()) {
            state.results = emptyList()
            state.searched = false
            state.searchedKeyword = ""
            return@LaunchedEffect
        }
        if (kw == state.searchedKeyword) return@LaunchedEffect
        delay(350L)
        runSearch()
    }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 遥控器 Back 键统一由 TvApp.BackHandler 处理，不再放页面内返回按钮
            TvSearchField(
                value = state.keyword,
                focusRequester = fieldFocus,
                onValueChange = { state.keyword = it },
                onSubmit = {
                    // 回车立即出结果，不等防抖
                }
            )
        }

        when {
            state.loading -> TvEmptyState("搜索中…")
            state.results.isNotEmpty() -> {
                Text(
                    "找到 ${state.results.size} 个结果",
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.results, key = { "tv_s_${it.id}" }) { item ->
                        TvPosterCard(
                            item = item,
                            onClick = { onDetail(item.id) }
                        )
                    }
                }
            }

            state.searched -> TvEmptyState("没有找到「${state.keyword}」", "换个关键词，或先在来源里同步媒体库")
            else -> TvEmptyState("搜索媒体库", "输入片名、演员或题材，按 OK 键确认")
        }
    }
}

/** 电视端搜索输入框：获得焦点时显示白色描边，未聚焦时保持低调 */
@Composable
private fun TvSearchField(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        Modifier
            .width(520.dp)
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
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", style = MaterialTheme.typography.titleMedium, color = AppColors.TextTertiary)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        "搜索片名 / 演员 / 题材",
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AppColors.TextPrimary,
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
