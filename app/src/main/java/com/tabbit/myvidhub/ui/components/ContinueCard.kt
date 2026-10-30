package com.tabbit.myvidhub.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 「继续观看」横向大卡：16:9 剧照 + 进度条。
 *
 * 与海报卡区分开是有意的 —— VidHub 首页把「继续观看」做成横幅卡片而非竖版海报，
 * 因为用户此刻要的是"接着看"，缩略画面比海报更能唤起记忆。
 *
 * 单击直接接着播，长按唤出卡片菜单（查看详情 / 清除观看记录）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ContinueCard(
    item: MediaEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(180),
        label = "continue_scale"
    )

    val shape = RoundedCornerShape(12.dp)
    val image = item.backdropUrl.ifBlank { item.posterUrl }

    Box(
        modifier = modifier
            .width(248.dp)
            .height(140.dp)
            .scale(scale)
            .clip(shape)
            .background(AppColors.Surface)
            .then(if (focused) Modifier.border(2.5.dp, AppColors.Brand, shape) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .focusable()
    ) {
        // 无图或死链时露出渐变兜底，不再是一块纯色矩形；图上已经压了片名，占位只放首字
        PosterArt(model = image, contentDescription = item.title) {
            PosterFallback(title = item.title, showTitle = false, titleFontSize = 30.sp)
        }

        // 左侧压暗，保证文字可读
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.15f))
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = progressLabel(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            // 进度条
            if (item.durationMs > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (item.positionMs.toFloat() / item.durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            )
                            .height(3.dp)
                            .background(AppColors.Brand)
                    )
                }
            }
        }
    }
}

private fun progressLabel(item: MediaEntity): String {
    val pos = item.positionMs
    val dur = item.durationMs
    val remaining = if (dur > pos) dur - pos else 0L
    return "剩余 ${formatMin(remaining)}"
}

private fun formatMin(ms: Long): String {
    val totalMin = ms / 60_000L
    return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
}
