package com.tabbit.myvidhub.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 海报卡（VidHub 风格）。
 *
 * 三个细节决定"像不像"：
 * 1) 2:3 竖版比例，圆角 10dp
 * 2) 聚焦时轻微放大（1.05x）+ 品牌色描边 —— TV 遥控器可用性核心
 * 3) 底部渐变叠层压住标题，不单独占一块，保持海报主体完整
 */
@Composable
fun PosterCard(
    item: MediaEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // TV 遥控器聚焦：带过冲的弹性放大，弹一下让用户明确"选中了"
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.09f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 420f
        ),
        label = "poster_scale"
    )

    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .scale(scale)
            .clip(shape)
            .background(AppColors.Surface)
            .then(
                if (focused) Modifier.border(2.5.dp, AppColors.Brand, shape) else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .focusable()
    ) {
        // 无图、或者图是 404 死链时露出按片名哈希的兜底封面，不再是一块空灰矩形。
        // 这张卡底部渐变上已经有片名了，所以占位只排首字水印，避免同屏两处重复。
        PosterArt(model = item.posterUrl, contentDescription = item.title) {
            PosterFallback(title = item.title, showTitle = false, titleFontSize = 30.sp)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.92f)
                    )
                )
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (showProgress && item.positionMs > 0 && item.durationMs > 0) {
                    ProgressLine(item.positionMs.toFloat() / item.durationMs.toFloat())
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.28f))
            .padding(top = 2.5.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(AppColors.Brand)
        )
    }
}

/** 剧集显示 "S02E05"，电影显示年份 */
fun MediaEntity.subtitle(): String = when {
    kind == MediaKind.EPISODE && episode > 0 -> "S%02dE%02d".format(season, episode)
    year > 0 -> year.toString()
    else -> ""
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.Brand)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        trailing?.invoke()
    }
}
