package com.chen.doudoutv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

/**
 * 页面根背景：近黑底色之上，铺上下两条品牌色线性渐层（方案②「影院渐层」）。
 *
 * 为什么是两条线性渐层而不是三团径向光斑：光斑的亮心是「某一个点」，
 * 在 TV 上会被 Hero 大图整块盖住、在手机上又只糊在屏幕一角，
 * 于是同一个主题色在不同页面出现频率完全不同（用户原话：有的地方能看到有的地方看不到）。
 * 改成两条贯穿屏幕的横向渐层后，它跟着屏幕走、不跟着内容走：
 * 任何一页都至少有一段看得见，而且边界是渐变、不会出现硬切。
 *
 * 两条渐层都在屏幕上下边缘收到全透明，不是「边缘最亮」：
 * 内容区上下都可能被系统栏 / 底栏切掉一圈，边缘亮、正中暗的话，
 * 那条切口就成了一条横线。收到透明之后，切口两边都是同一个底色，接不上缝。
 *
 * 渐层用的是品牌色，所以换色板时整屏氛围跟着换；
 * 默认色板现在是苹果灰，这一层就是灰雾而不是蓝雾。
 *
 * 走 drawWithCache 而不是 drawBehind：Brush 在一次布局里尺寸不变，
 * 缓下来就只在尺寸变化时重建，不用跟着重绘逐帧分配。
 * 电视端不再走「四分之一位图放大」那条路：原来那是为了省三层径向渐变的逐像素开销，
 * 现在只有两条纵向渐变，直接画就是两次插值，比放大位图更省，也不会有放大带来的色阶。
 */
@Composable
fun Modifier.themeBackdrop(): Modifier {
    val brand = AppColors.Brand
    val brandSoft = AppColors.BrandSoft
    val isTv = LocalTvMode.current
    // 电视上一条渐层铺满 1920 宽的整屏，感知强度比手机强，α 反而要收一档；
    // 手机屏幕小，同样一档 α 反而弱一点，可以多给一点。
    val topAlpha = if (isTv) 0.15f else 0.19f
    val bottomAlpha = if (isTv) 0.09f else 0.12f
    val top = remember(brand, topAlpha) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.16f to brand.copy(alpha = topAlpha),
                0.46f to Color.Transparent
            )
        )
    }
    val bottom = remember(brandSoft, bottomAlpha) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.60f to Color.Transparent,
                0.88f to brandSoft.copy(alpha = bottomAlpha),
                1f to Color.Transparent
            )
        )
    }
    return this
        .background(AppColors.Bg)
        .drawWithCache {
            onDrawBehind {
                drawRect(top)
                drawRect(bottom)
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
