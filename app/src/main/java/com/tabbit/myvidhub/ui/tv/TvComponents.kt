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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.components.subtitle
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 电视端专用组件（十英尺界面）。
 *
 * 与手机端组件完全分开实现，理由很实在：
 *   · 电视没有触摸，所有可交互元素必须是「可聚焦 + 有明显焦点态」，尺寸要按观看距离放大；
 *   · 手机端组件靠涟漪和阴影表达状态，这些在电视上隔着三米根本看不见，必须用白边和放大。
 */

/** 电视上的主按钮：白底黑字（主操作）或半透明白底（次操作），聚焦时加粗白边 */
@Composable
fun TvPillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    compact: Boolean = false,
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
            .padding(
                horizontal = if (compact) 18.dp else 26.dp,
                vertical = if (compact) 8.dp else 12.dp
            )
    ) {
        Text(
            text,
            color = when {
                primary && focused -> Color.Black       // 主按钮 + 聚焦：白底黑字
                primary -> Color.White                 // 主按钮 + 正常：蓝底白字
                focused -> Color.Black                 // 次按钮 + 聚焦：白底黑字
                compact -> Color.White.copy(alpha = 0.72f)  // 紧凑顶栏按钮：72% opacity 轻量化
                else -> Color.White.copy(alpha = 0.90f)     // 次按钮：90% opacity
            },
            // 非紧凑（主操作）→ Level 3: titleMedium 16sp Bold 100%
            // 紧凑（顶栏/工具栏）→ labelLarge 14sp Medium 72%
            style = if (compact) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.titleMedium,
            fontWeight = if (compact) FontWeight.Medium else FontWeight.Bold,
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
    compact: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "play_pill")

    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else AppColors.Brand)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .focusable()
            .padding(
                horizontal = if (compact) 20.dp else 26.dp,
                vertical = if (compact) 8.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = if (focused) Color.Black else Color.White
        )
        Text(
            text,
            color = if (focused) Color.Black else Color.White,
            // Level 3: titleMedium 16sp Bold 100%（主操作语气要肯定）
            style = if (compact) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
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
    sizeDp: Dp = 46.dp,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "tv_icon")

    Box(
        modifier
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
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else Color.White,
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface)
            .then(if (focused) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable()
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
            // 卡片标题：100% 白 + Bold —— 和海报视觉竞争不能输
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
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
    // 内边距可配：详情页铺满整屏时要把左边界推到 56dp 与信息列对齐，
    // 其它页面沿用默认值，保持原有观感。
    contentPadding: PaddingValues = PaddingValues(
        start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp
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
            // Level 2 分区标题：titleLarge 22sp Bold · 90% White
            // headlineSmall(24sp) 太大太粗，分区标题比 Hero 标题低一档
            color = Color.White.copy(alpha = 0.90f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 电视端的「更多」入口，可用遥控器聚焦点击 */
@Composable
fun TvMoreButton(onClick: () -> Unit) {
    TvPillButton(text = "查看全部", primary = false, onClick = onClick)
}

/** 空状态提示 —— 主文 headlineSmall 24sp 粗 + hint bodyMedium 14sp 55% */
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
            color = Color.White.copy(alpha = 0.90f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (hint != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = hint,
                // Level 7 信息标签级：14sp · 55% White —— 别和主文抢注意力
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 元信息条：年份 · 评分 · 类型，中间用圆点分隔 */
/** TV 端元信息行：年份 · 类型 · 时长（已移除评分 —— 避免与海报背景 IMDb 分重复）。
 *  字号 14sp / Medium / rgba(255,255,255,.72) —— 介于标题与徽章之间的视觉权重。 */
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
                    color = Color.White.copy(alpha = 0.30f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal
                )
            }
            Text(
                text = s,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
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
            .padding(start = 20.dp, top = 20.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.45f))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack
            )
            .focusable()
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            "← 返回",
            // 返回按钮：titleSmall 14sp Medium · 72% White —— 不要抢焦点
            color = if (focused) Color.Black else Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 电视端网格列数：跟随设置页「缩略图大小」。
 * 之前网格写死 6 列，设置项只作用于首页横排，切到「查看全部」海报又变回默认大小。
 */
fun tvGridColumns(): Int = when (Prefs.posterSize) {
    Prefs.PosterSize.SMALL -> 8
    Prefs.PosterSize.MEDIUM -> 6
    Prefs.PosterSize.LARGE -> 4
}

/** 竖版海报行之间的统一间距 */
internal val TvRowSpacing = 18.dp

/** 行首尾留白，保证聚焦放大时不被裁切 */
internal val TvRowPadding = 20.dp

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
            .focusable()
            .padding(horizontal = 20.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                // 未选中 chip ：85% White —— 徽章级视觉权重
                else -> Color.White.copy(alpha = 0.85f)
            },
            // Level 6 徽章：labelMedium 11sp SemiBold
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
