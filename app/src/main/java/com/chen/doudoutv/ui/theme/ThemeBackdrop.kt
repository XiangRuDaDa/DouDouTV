package com.chen.doudoutv.ui.theme

import android.graphics.Matrix
import android.graphics.Paint
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.chen.doudoutv.ui.components.GradientBake

/**
 * 页面根背景：近黑底色之上，③ 顶部天光。
 *
 * ③ 天光是一团从屏幕上方洒下来的品牌光，亮心压在顶边之外（cy 为负），
 * 所以屏幕顶边拿到的是接近峰值的颜色，往下渐变到透明、铺到屏中。
 * 它跟着屏幕走、不跟着内容走：没有 Hero 的页面（设置 / 来源 / 搜索）顶部一定看得见。
 * 用椭圆而不是正圆：电视是宽屏，光要横向铺开、纵向收住，正圆在电视上会糊成一团。
 * Compose 自带的 radialGradient 只能画正圆，这里用 Android 的 RadialGradient
 * 配一张 X 向缩放矩阵把正圆拉成椭圆（rx=1.25W、ry=0.72H），和效果图同一形状。
 *
 * 强度：手机 α0.46、电视 α0.30。电视一条光铺满 1920 宽、感知更强，且首页 Hero 会盖住顶部，
 * 所以收一档；手机屏幕小、要多给一点才看得见。换色板时天光跟着换色。
 *
 * 曾经还叠过一层 ⑥ 胶片颗粒（256×256 噪点图 BitmapShader REPEAT 铺满全屏），已摘掉：
 * 它在 Mali-G52 上每像素要做一次依赖纹理寻址，1920×1080 一遍就是 2M 像素，
 * 而 display list 每帧重放 —— 实测电视端 GPU 中位 23ms（预算 16.67ms），同机 OK影视 只有 6ms。
 * 观感收益也不值这个价。要再加质感层，先量 GPU 中位，别只看静态效果图。
 *
 * 红线：只画在 Bg 之上，不动 Bg/Surface/文字对比度基线；色板 id 不改（色值可按拍板换）；详情页不接这一层。
 *
 * 走 [GradientBake]：天光烘成 1/4 分辨率的一张图，每帧只采纹理、不再逐像素求径向渐变。
 * 只跟尺寸 / 色板走，缓下来就不必重烘；绘制范围也只到 alpha 归零那条线（0.40H），下面不白画。
 */
@Composable
fun Modifier.themeBackdrop(): Modifier {
    val brand = AppColors.Brand
    val isTv = LocalTvMode.current
    val glowAlpha = if (isTv) 0.30f else 0.46f
    val bake = remember { GradientBake() }
    return this
        .background(AppColors.Bg)
        .drawWithCache {
            val w = size.width
            val h = size.height
            // 只画到 alpha 归零的那条线为止。最后一个 stop 在 0.70×ry，
            // 换算成屏幕坐标是 -0.12h + 0.70×0.72h = 0.384h，再往下 CLAMP 恒为全透明。
            // 原来整屏 drawRect，下面六成像素是纯白干：电视端一帧 2.07M 像素里有 1.24M 是这么烧掉的。
            val glowBottom = h * 0.40f
            val cx = w * 0.5f
            val cy = -h * 0.12f
            val ry = h * 0.72f
            val rx = w * 1.25f
            val img = bake.image(w, glowBottom, brand to glowAlpha) { cv ->
                // 外圈收到「品牌色减 alpha」而不是 Color.Transparent：后者把 RGB 一起拖向纯黑，
                // 淡出末尾会压出一条暗边；保持 RGB 不变只降 alpha，淡出才干净。
                val shader = RadialGradient(
                    cx, cy, ry,
                    intArrayOf(
                        brand.copy(alpha = glowAlpha).toArgb(),
                        brand.copy(alpha = glowAlpha * 0.39f).toArgb(),
                        brand.copy(alpha = 0f).toArgb()
                    ),
                    floatArrayOf(0f, 0.34f, 0.70f),
                    Shader.TileMode.CLAMP
                ).apply { setLocalMatrix(Matrix().apply { setScale(rx / ry, 1f, cx, cy) }) }
                cv.drawRect(0f, 0f, w, glowBottom, Paint().apply { this.shader = shader })
            }
            val dst = IntSize(w.toInt(), glowBottom.toInt())
            onDrawBehind {
                drawImage(img, dstSize = dst, filterQuality = FilterQuality.Low)
            }
        }
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
