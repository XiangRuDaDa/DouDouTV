package com.chen.doudoutv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chen.doudoutv.ui.theme.AppColors

/**
 * 同步进度线（贴在软件内容区最上沿，覆盖绘制、不占布局高度）。
 *
 * 观感口径：不做 Material 那条 4dp 粗实心条 —— 页面顶部压一条粗蓝带会把沉浸式头图切断。
 * 这里是 2.5dp 发丝线 + 品牌渐变 + 往下渗的光晕，读起来像页面自己在发光而不是贴了个控件。
 *
 * 两种形态：
 *   · 知道总数（Emby 分页给了 TotalRecordCount）→ 定长推进，领先端带一小段高光；
 *     进度按页跳（每 500 条一次），所以用补间把台阶抹平成连续推进。
 *   · 不知道总数（TVBox 聚合、拉取前的准备阶段）→ 一段彗尾扫过轨道，不假装有百分比。
 *
 * 手机端贴在内容区最上沿；电视端也贴在屏幕最上沿，但线更粗、光晕更高 ——
 * 电视是十英尺外看，2.5dp 的发丝线在那儿只是一闪，读不出「在推进」。
 */
@Composable
fun SyncProgressBar(
    fetched: Int,
    total: Int,
    modifier: Modifier = Modifier,
    /**
     * 线本身的厚度。电视端要粗一档：十英尺外 2.5dp 的发丝线基本看不见，
     * 细线在电视上读起来只剩「屏幕闪了一下」，看不出在推进。
     */
    thickness: Dp = 2.5.dp,
    /** 线往下渗的光晕高度 */
    glow: Dp = 10.5.dp,
    /** 领先端高光的长度 */
    head: Dp = 28.dp
) {
    val brand = AppColors.Brand
    val soft = AppColors.BrandSoft
    val determinate = total > 0

    val target = if (determinate) (fetched.toFloat() / total).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "sync_fraction"
    )

    val sweep by rememberInfiniteTransition(label = "sync_sweep").animateFloat(
        initialValue = -0.34f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_sweep_pos"
    )

    val barPx = with(LocalDensity.current) { thickness.toPx() }
    val headPx = with(LocalDensity.current) { head.toPx() }

    Canvas(modifier.fillMaxWidth().height(thickness + glow)) {
        val w = size.width
        val glowH = size.height - barPx

        drawRect(
            color = Color.White.copy(alpha = 0.05f),
            topLeft = Offset.Zero,
            size = Size(w, barPx)
        )

        val len: Float
        val left: Float
        if (determinate) {
            left = 0f
            len = fraction * w
        } else {
            val seg = w * 0.34f
            left = (sweep * w).coerceAtLeast(0f)
            len = (seg - (0f - sweep * w).coerceAtLeast(0f)).coerceAtMost(w - left)
        }
        if (len <= 0.5f) return@Canvas

        // 定长时整段渐变；扫描时彗尾两头透明，避免一条硬边方块在轨道上平移
        val fill = if (determinate) {
            Brush.horizontalGradient(
                colors = listOf(brand, soft),
                startX = left,
                endX = (left + len).coerceAtLeast(left + 1f)
            )
        } else {
            Brush.horizontalGradient(
                colors = listOf(Color.Transparent, brand, soft, Color.Transparent),
                startX = left,
                endX = left + len
            )
        }
        drawRoundRect(
            brush = fill,
            topLeft = Offset(left, 0f),
            size = Size(len, barPx),
            cornerRadius = CornerRadius(barPx / 2f)
        )

        // 光晕：只在已推进的那段下方渗色，右侧跟着进度一起收，不是整屏铺一层
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(soft.copy(alpha = if (determinate) 0.30f else 0.22f), Color.Transparent),
                startY = barPx,
                endY = size.height
            ),
            topLeft = Offset(left, barPx),
            size = Size(len, glowH)
        )

        // 领先端高光：定长推进时给出「正在往前爬」的方向感
        if (determinate && len > headPx) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f)),
                    startX = left + len - headPx,
                    endX = left + len
                ),
                topLeft = Offset(left + len - headPx, 0f),
                size = Size(headPx, barPx)
            )
        }
    }
}