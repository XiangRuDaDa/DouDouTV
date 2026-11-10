package com.chen.doudoutv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Edge
import com.chen.doudoutv.ui.theme.Motion
import com.chen.doudoutv.ui.theme.Radius

/**
 * 全局按钮材质。
 *
 * 电视端和手机端共用这一份：分开写就会出现「同一个操作两种样子」，
 * 而按钮恰恰是最容易被一眼看出不统一的地方。
 *
 * 四档：
 *   · [AppButtonVariant.Primary] —— 品牌渐变实底 + 顶部内高光 + 品牌光晕。全局唯一允许发光的档位，
 *     一屏最多一颗，多了光晕互相干扰反而显脏。
 *   · [AppButtonVariant.Secondary] —— 磨砂玻璃：半透明白底 + 发丝描边 + 内高光，配白字。
 *   · [AppButtonVariant.Neutral] —— 哑光面，不发光，三级操作用。
 *   · [AppButtonVariant.Danger] —— 危险色实底。语义色，不跟品牌色板走。
 */
object ButtonMaterial {

    /** 主按钮色阶：顶端受光、底端沉一档 */
    fun primaryStops(brand: Color, brandSoft: Color): List<Color> =
        listOf(brandSoft, brand, lerp(brand, Color.Black, 0.16f))

    /** 危险动作色阶：和主按钮同一套受光逻辑，只换颜色 */
    fun dangerStops(color: Color): List<Color> =
        listOf(lerp(color, Color.White, 0.22f), color, lerp(color, Color.Black, 0.10f))

    /** 玻璃底：上厚下薄，像一片有厚度的磨砂片 */
    fun glassStops(): List<Color> =
        listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.07f))

    /** 中性哑光面 */
    fun neutralStops(): List<Color> =
        listOf(AppColors.SurfaceRaise, AppColors.SurfaceHi)

    /**
     * 内高光描边：上缘亮、下缘几乎透明。
     *
     * 平面色块加一圈均匀亮边反而更像塑料；高光只压在上缘，
     * 眼睛才会读成「这块料被顶光照过」——材质感真正的来源就在这一条。
     */
    val highlightEdge: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.04f))
    )

    /** 玻璃档的发丝描边：上缘略亮，底边压到几乎看不见 */
    val glassEdge: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.13f))
    )

    /** 主按钮的品牌光晕高度 */
    val PrimaryGlow = 8.dp
}

/**
 * 手机端按钮。
 *
 * 材质取自 [ButtonMaterial]，与电视端同一套；交互上按手机习惯给按压反馈，
 * 而不是电视那套白边 + 放大。
 *
 * 尺寸默认值跟 Material3 的 Button 对齐（最小 80×40dp、水平 24 / 垂直 10 的内容留白），
 * 换过去之后原来那些按钮占位不会跳。
 */
enum class AppButtonVariant { Primary, Secondary, Neutral, Danger }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    shape: Shape = Radius.Md,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    fontWeight: FontWeight? = null,
    iconSize: Dp = 18.dp,
    minWidth: Dp = 80.dp,
    minHeight: Dp = 40.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(Motion.FAST, easing = Motion.Ease),
        label = "appButton"
    )

    val brand = AppColors.Brand
    val brandSoft = AppColors.BrandSoft

    val stops: List<Color> = when {
        // 禁用态退回哑光面：实底按钮变灰后不该还带着品牌渐变和光晕
        !enabled -> ButtonMaterial.neutralStops()
        variant == AppButtonVariant.Primary -> ButtonMaterial.primaryStops(brand, brandSoft)
        variant == AppButtonVariant.Secondary -> ButtonMaterial.glassStops()
        variant == AppButtonVariant.Neutral -> ButtonMaterial.neutralStops()
        else -> ButtonMaterial.dangerStops(AppColors.Danger)
    }
    val content = when {
        !enabled -> AppColors.TextTertiary
        // 实底档一律黑字。品牌色明度都在中档，黑字对比度比白字高一倍
        // （石墨蓝上黑字 6.6:1、白字只有 3.22:1），深字压亮底也是当下高端 UI 的通行做法。
        variant == AppButtonVariant.Primary || variant == AppButtonVariant.Danger -> Color.Black
        variant == AppButtonVariant.Secondary -> Color.White
        else -> AppColors.TextPrimary
    }

    Box(
        modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (enabled && variant == AppButtonVariant.Primary) {
                    Modifier.shadow(
                        ButtonMaterial.PrimaryGlow,
                        shape,
                        clip = false,
                        ambientColor = brand.copy(alpha = 0.35f),
                        spotColor = brand.copy(alpha = 0.45f)
                    )
                } else Modifier
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (pressed && enabled) stops.map { lerp(it, Color.Black, 0.12f) } else stops
                )
            )
            .then(
                when {
                    !enabled -> Modifier.border(1.dp, Edge.HairlineStrong, shape)
                    variant == AppButtonVariant.Primary || variant == AppButtonVariant.Danger ->
                        Modifier.border(1.dp, ButtonMaterial.highlightEdge, shape)
                    variant == AppButtonVariant.Secondary ->
                        Modifier.border(1.dp, ButtonMaterial.glassEdge, shape)
                    else -> Modifier.border(1.dp, Edge.HairlineStrong, shape)
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(iconSize)
                )
            }
            Text(
                text = text,
                color = content,
                style = textStyle,
                fontWeight = fontWeight ?: textStyle.fontWeight,
                maxLines = 1
            )
        }
    }
}
