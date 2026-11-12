package com.chen.doudoutv.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlin.random.Random

/**
 * 页面根背景：近黑底色之上，③ 顶部天光 + ⑥ 胶片颗粒。
 *
 * ③ 天光是一团从屏幕上方洒下来的品牌光，亮心压在顶边之外（cy 为负），
 * 所以屏幕顶边拿到的是接近峰值的颜色，往下渐变到透明、铺到屏中。
 * 它跟着屏幕走、不跟着内容走：没有 Hero 的页面（设置 / 来源 / 搜索）顶部一定看得见。
 * 用椭圆而不是正圆：电视是宽屏，光要横向铺开、纵向收住，正圆在电视上会糊成一团。
 * Compose 自带的 radialGradient 只能画正圆，这里用 Android 的 RadialGradient
 * 配一张 X 向缩放矩阵把正圆拉成椭圆（rx=1.25W、ry=0.72H），和效果图同一形状。
 *
 * ⑥ 颗粒是一张固定种子一次性生成的平铺噪点图，BitmapShader REPEAT 铺满全屏。
 * 它给近黑底一点「料」：默认苹果灰是无彩色，天光只是明度雾，颗粒让这层雾读成刻意的质感而不是脏。
 * 静态、不逐帧变化（不会闪），整屏一次 drawRect，弱盒子也安全。
 *
 * 强度：手机 α0.46、电视 α0.30。电视一条光铺满 1920 宽、感知更强，且首页 Hero 会盖住顶部，
 * 所以收一档；手机屏幕小、要多给一点才看得见。换色板时天光跟着换色，颗粒始终是中性灰。
 *
 * 红线：只画在 Bg 之上，不动 Bg/Surface/文字对比度基线；默认色板 id 与色值不改；详情页不接这一层。
 *
 * 走 drawWithCache：两个 shader 只跟尺寸 / 色板走，缓下来就不必逐帧分配；
 * 颗粒图是进程级单例，所有页面共用同一张、纹理一致。
 */
@Composable
fun Modifier.themeBackdrop(): Modifier {
    val brand = AppColors.Brand
    val isTv = LocalTvMode.current
    val glowAlpha = if (isTv) 0.30f else 0.46f
    return this
        .background(AppColors.Bg)
        .drawWithCache {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = -h * 0.12f
            val ry = h * 0.72f
            val rx = w * 1.25f
            // 外圈收到「品牌色减 alpha」而不是 Color.Transparent：后者把 RGB 一起拖向纯黑，
            // 淡出末尾会压出一条暗边；保持 RGB 不变只降 alpha，淡出才干净。
            val glowShader = RadialGradient(
                cx, cy, ry,
                intArrayOf(
                    brand.copy(alpha = glowAlpha).toArgb(),
                    brand.copy(alpha = glowAlpha * 0.39f).toArgb(),
                    brand.copy(alpha = 0f).toArgb()
                ),
                floatArrayOf(0f, 0.34f, 0.70f),
                Shader.TileMode.CLAMP
            ).apply { setLocalMatrix(Matrix().apply { setScale(rx / ry, 1f, cx, cy) }) }
            val glowBrush = ShaderBrush(glowShader)
            val grainBrush = ShaderBrush(
                BitmapShader(NoiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            )
            onDrawBehind {
                drawRect(glowBrush)
                drawRect(grainBrush, alpha = GrainAlpha)
            }
        }
}

/** 颗粒强度：噪点是 0~255 的灰，乘这一档 alpha 叠在近黑底上，只加一点纹理、不抬黑位 */
private const val GrainAlpha = 11f / 255f

/** 固定种子噪点图：进程级共用一张，256×256 平铺，约 256KB，不随页面重复生成 */
private val NoiseTile: Bitmap by lazy {
    val px = 256
    val rnd = Random(7)
    val pixels = IntArray(px * px) {
        val v = rnd.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}

/**
 * 顶部满幅大图与页面背景之间的收色带：从 [seam] 实色淡到完全透明，把背景接出来。
 *
 * 大图的渐隐无论配成什么色，接缝都正好压在它自己的底边上：配纯黑是一条黑线，
 * 配猜出来的品牌色混合值又只在那一个点准，横着走两步就不对。
 * 所以让大图只管收到纯 [seam]，这条带的顶边取同一个 [seam]（发界因此隐形），
 * 底边 alpha 归零露出 [themeBackdrop] 画出来的真实背景 —— 接缝被挪进渐变中段，不再靠配色去猜。
 *
 * 收尾用 [seam] 自己减 alpha 而不是 Color.Transparent：后者会把 RGB 一起拖向纯黑，
 * 淡出末尾反而压出一条暗边。
 */
@Composable
fun Modifier.themeSeamFade(height: Dp, seam: Color = AppColors.Bg): Modifier {
    val brush = remember(seam) {
        Brush.verticalGradient(0f to seam, 1f to seam.copy(alpha = 0f))
    }
    return this
        .fillMaxWidth()
        .height(height)
        .background(brush)
}
