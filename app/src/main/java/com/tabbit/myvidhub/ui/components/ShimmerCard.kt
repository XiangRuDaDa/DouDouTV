package com.tabbit.myvidhub.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 海报卡骨架屏：2:3 竖版 + 底部标题条，带 shimmer 光扫效果。
 * 用于分类 section 加载中和 TvBoxCategoryScreen 首屏加载。
 */
@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val shimmerBrush = shimmerBrush()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(AppColors.Surface)
    ) {
        // 海报区域 shimmer
        Box(
            Modifier
                .fillMaxSize()
                .background(shimmerBrush)
        )
        // 底部标题条
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(shimmerBrush)
                    .align(Alignment.CenterStart)
            )
        }
    }
}

/**
 * 纯 shimmer 盒子（横向 section 行、全页网格等复用）。
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(AppColors.Surface)
    ) {
        Box(Modifier.fillMaxSize().background(shimmerBrush()))
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -400f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    return Brush.linearGradient(
        colors = listOf(
            AppColors.Surface,
            AppColors.SurfaceHi.copy(alpha = 0.55f),
            AppColors.Surface
        ),
        start = Offset(offset, 0f),
        end = Offset(offset + 120f, 0f)
    )
}
