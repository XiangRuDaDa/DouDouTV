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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.TvText

/**
 * 电视端专用组件（十英尺界面）。
 *
 * 与手机端组件完全分开实现，理由很实在：
 *   · 电视没有触摸，所有可交互元素必须是「可聚焦 + 有明显焦点态」，尺寸要按观看距离放大；
 *   · 手机端组件靠涟漪和阴影表达状态，这些在电视上隔着三米根本看不见，必须用白边和放大。
 *
 * 字号与文字颜色都不在这里写死：
 *   · 字号取 MaterialTheme.typography 的档位，电视端实际大小由 Theme 的 TvTypography 决定；
 *   · 文字颜色取 TvText 的层级（Title / Section / Body / Meta / Muted / Hairline），
 *     整屏只用白→灰一条轴拉开层级，品牌色只留给按钮和进度条这类「可操作 / 进行中」的元素。
 *
 * 聚焦的元件一律先 .zIndex(1f) 再 .graphicsLayer 放大：
 * 抬高绘制层级，放大后的白边才不会被同排的兄弟节点压住。
 */

/**
 * 电视端按钮的尺寸档位。
 *
 * 分档只动内边距，字号一律 titleMedium —— 这是 Netflix 的规矩：可操作元件靠
 * 「实心 / 幽灵 + 图标」分主次，不靠字号和尺寸分主次。一旦让次要按钮掉到 label 字号，
 * 就会出现「分区标题旁边一颗大胶囊、Hero 上一对小胶囊」的倒挂，两边都显得不对。
 *
 * 三档算上文字行高（23dp）大致是 35 / 43 / 49dp：
 *   · [Sm] 顶栏入口条（横向预算最紧）；
 *   · [Md] 页内次操作、分区「查看全部」、选集控制条；
 *   · [Lg] Hero 与详情页的主操作 —— 全页最大的可点元素。
 */
enum class TvButtonSize { Sm, Md, Lg }

private fun tvButtonPadding(size: TvButtonSize): PaddingValues = when (size) {
    TvButtonSize.Sm -> PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    TvButtonSize.Md -> PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    TvButtonSize.Lg -> PaddingValues(horizontal = 26.dp, vertical = 13.dp)
}

/** 电视上的主按钮：白底黑字（主操作）或半透明白底（次操作），聚焦时加粗白边 */
@Composable
fun TvPillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    size: TvButtonSize = TvButtonSize.Md,
    onClick: () -> Unit
) {
    // 焦点态统一由 interactionSource 驱动：clickable 与 focusable 共用同一个 source。
    // 之前用 Modifier.onFocusChanged 里的 isFocused 判断，在「遥控器跨子树跳焦点」
    // （Hero 播放键 → 顶栏同步键）时会出现逻辑焦点已移走、画面高亮却停在原按钮上的错位。
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "pill")

    Box(
        modifier
            // 放大后要把绘制层级抬起来，否则同排右侧的兄弟节点会压在白边上面
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    primary && focused -> Color.White
                    primary -> AppColors.Brand
                    focused -> Color.White.copy(alpha = 0.28f)
                    else -> Color.White.copy(alpha = 0.14f)
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interaction)
            .padding(tvButtonPadding(size))
    ) {
        Text(
            text,
            color = when {
                primary && focused -> Color.Black       // 主按钮 + 聚焦：白底黑字
                primary -> TvText.Title                 // 主按钮 + 正常：蓝底白字
                focused -> Color.Black                  // 次按钮 + 聚焦：白底黑字
                size == TvButtonSize.Sm -> TvText.Body   // 顶栏按钮：弱化，不跟主操作抢注意力
                else -> TvText.Section
            },
            // 三档共用同一个字号，层级交给填充与高度
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
    }
}

/**
 * 播放主按钮：在 TvPillButton 基础上左侧加一个播放图标，
 * 视觉上和截图的「▶ 开始观看 S05E01」一致。
 */
@Composable
fun TvPlayButton(
    text: String,
    modifier: Modifier = Modifier,
    size: TvButtonSize = TvButtonSize.Md,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "play_pill")

    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else AppColors.Brand)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(tvButtonPadding(size)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = if (focused) Color.Black else Color.White,
            // 图标跟按钮同档缩放，避免出现「小胶囊里塞个标准大的三角」
            modifier = Modifier.size(
                when (size) {
                    TvButtonSize.Sm -> 18.dp
                    TvButtonSize.Md -> 22.dp
                    TvButtonSize.Lg -> 24.dp
                }
            )
        )
        Text(
            text,
            color = if (focused) Color.Black else TvText.Title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
    }
}

/**
 * 圆形图标按钮：电视端的次操作（收藏等）用图标而不是长条文字胶囊。
 * 圆形视觉更轻、横向只占一个按钮宽度，把省下来的空间留给海报；
 * 聚焦时放大并加白边，符合十英尺界面的可辨识要求。
 */
@Composable
fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    contentDescription: String? = null,
    /**
     * 直径。放进标题行时贴着大标题站，46dp 会显得比字还抢眼，
     * 所以允许调用方收小一档；操作区里的默认值仍是 46dp。
     */
    sizeDp: Dp = 46.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "tv_icon")

    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(sizeDp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    active -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(50))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else TvText.Title,
            // 图标随按钮等比缩，收小后不会出现「大图标塞在小圆里」
            modifier = Modifier.size(sizeDp * 0.48f)
        )
    }
}

/**
 * 电视端海报卡：2:3 竖版，比手机大一圈，聚焦时放大并加白色描边。
 * 有播放进度的条目底部带一条进度线，方便一眼看出「这片看了一半」。
 */
@Composable
fun TvPosterCard(
    item: MediaEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Prefs.posterSize.tvDp.dp,
    /**
     * 网格模式：铺满格子宽度并按 2:3 自适应高度。
     * 固定 [width] 原本只适合首页横排，放进 LazyVerticalGrid 会溢出格子或留白，
     * 所以网格页（分类/查看全部/搜索）统一用这个模式。
     */
    fillCell: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.10f else 1f, tween(150), label = "card")

    val progress = if (item.durationMs > 0L) {
        (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier
            .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(width))
            // 放大 10% 后要抬高绘制层级，否则描边会被同行后面的卡片压住
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface)
            .then(if (focused) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(0.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fillCell) Modifier.aspectRatio(2f / 3f) else Modifier.height(width * 1.5f))
                .background(AppColors.SurfaceHi)
        ) {
            AsyncImage(
                model = item.posterUrl.ifBlank { item.backdropUrl },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(AppColors.Brand)
                    )
                }
            }
        }
        Text(
            item.title,
            // 卡片标题不靠粗体压海报，靠 88% 白 + Medium 保持和正文同族
            color = TvText.Section,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}

/** 分区标题：电视上字要大、留白要多，右侧可挂「更多」 */
@Composable
fun TvSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    // 默认走页面左轴；详情页等信息列更宽的区域由调用方覆写。
    contentPadding: PaddingValues = PaddingValues(
        start = TvPagePadding, end = TvPagePadding, top = 22.dp, bottom = 10.dp
    ),
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            // 分区标题比 Hero 低一档：titleLarge + Section 级白，靠字号分层而不是靠粗体
            color = TvText.Section,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 电视端的「更多」入口：和分区标题同排，取中档 —— 比它旁边的 20sp 标题略小，不抢字 */
@Composable
fun TvMoreButton(onClick: () -> Unit) {
    TvPillButton(text = "查看全部", primary = false, size = TvButtonSize.Md, onClick = onClick)
}

/** 空状态提示 —— 主文 headlineSmall，下面补一条更弱的 hint */
@Composable
fun TvEmptyState(text: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = TvText.Section,
            style = MaterialTheme.typography.headlineSmall
        )
        if (hint != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = hint,
                // hint 比主文低两档，别抢注意力
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * TV 端元信息行：年份 · 类型 · 时长，中间用圆点分隔。
 * 评分不在这里显示 —— 海报背景上已带 IMDb 分，重复出现会打架。
 * 权重介于标题与徽章之间：bodyMedium + Meta 级白。
 */
@Composable
fun TvMetaRow(item: MediaEntity, modifier: Modifier = Modifier) {
    val bits = buildList {
        if (item.year > 0) add(item.year.toString())
        if (item.genres.isNotBlank()) add(item.genres.split(",", "/", " ", "，").first().trim())
        if (item.durationMs > 0) add("${item.durationMs / 60_000} 分钟")
    }.filter { it.isNotBlank() }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        bits.forEachIndexed { index, s ->
            if (index > 0) {
                Text(
                    text = "  ·  ",
                    color = TvText.Hairline,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = s,
                color = TvText.Meta,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** 电视端返回按钮（左上角），可聚焦 */
@Composable
fun TvBackButton(onBack: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(start = TvPagePadding, top = 20.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.45f))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack
            )
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            "← 返回",
            // 返回按钮用 titleSmall + Body 级白：够用但不抢焦点
            color = if (focused) Color.Black else TvText.Body,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

/**
 * 页面左右边距：电视端所有页面共用这一条左轴。
 *
 * 取 40dp（1080p 电视上约等于屏宽的 4~5%），和 Netflix 电视端的页面留白同一量级：
 * 元素不贴屏边，屏幕不被塞满，聚焦描边也天然有处可放。
 * Hero 文案、顶栏、行标题、海报行、网格页都从这里取值，
 * 任何一处单独写死就会立刻出现第二条左轴。
 */
internal val TvPagePadding = 40.dp

/**
 * 电视端网格列数：跟随设置页「缩略图大小」。
 * 所有 TV 网格页（分类 / 查看全部 / 搜索 / 来源）都取这个值，避免各处各写一套。
 */
fun tvGridColumns(): Int = when (Prefs.posterSize) {
    Prefs.PosterSize.SMALL -> 8
    Prefs.PosterSize.MEDIUM -> 6
    Prefs.PosterSize.LARGE -> 4
}

/** 电视端可聚焦的小尺寸标签（题材筛选等） */
@Composable
fun TvFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AppColors.Brand
                    else -> AppColors.SurfaceHi
                }
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> TvText.Title
                else -> TvText.Body
            },
            // 徽章级：labelMedium，比正文轻一档
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}
