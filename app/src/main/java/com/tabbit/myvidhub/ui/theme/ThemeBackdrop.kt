package com.tabbit.myvidhub.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

/**
 * 页面根背景：近黑底色之上，从左侧边缘渗进三团品牌色光晕。
 *
 * 为什么不是直接把背景换成品牌色：主题色是「强调色」而不是「墙面色」，
 * 整屏糊上饱和度，中性文字的第一印象就从「深色影音」变成「彩色塑料」。
 * 品牌色只以两成上下的量从边缘渗进来，读者感知到的是氛围而不是颜色本身；
 * 光晕全部贴左轴布置也和 TV 端的左对齐栅格一致，中心区始终留给内容。
 *
 * 光晕必须落在「真的看得见」的那几条带子上，这是这版重排的全部原因：
 *   · TV 首页的 Hero 占满 0.72 屏高（见 tvHeroHeight），且自带一条淡到不透明
 *     AppColors.Bg 的底部渐隐，所以顶部光晕在首页是被整块盖住的 —— 真机上
 *     表现为「换了主题色背景毫无变化」。左上那团留给标题裸露的页面
 *     （来源 / 设置 / 分类），左下和右下两团负责首页折痕以下那条可视带。
 *   · 原来叠在最上面的一层「竖向压暗」是 AppColors.Bg 画在 AppColors.Bg 上，
 *     自己完全不可见，只会把底下的光晕洗掉，所以去掉。底色本来就是近黑，
 *     长列表的文字对比度不需要它操心。
 *
 * 走 drawWithCache 而不是 drawBehind：Brush 的圆心要按实际绘制尺寸算，
 * 而尺寸在一次布局里不会变，缓下来就只在尺寸变化时重建，不用跟着重绘逐帧分配。
 */
@Composable
fun Modifier.themeBackdrop(): Modifier {
    val brand = AppColors.Brand
    val brandSoft = AppColors.BrandSoft
    val isTv = LocalTvMode.current
    // 电视观看距离远、又是整屏大面积，同样的 α 挂在 65 寸上比手机更耐看，不必刻意压低；
    // 手机端屏幕小、光晕占比高，反而要收一档，否则贴脸的色块会抢文字。
    val edgeAlpha = if (isTv) 0.26f else 0.20f
    val cornerAlpha = if (isTv) 0.16f else 0.12f
    return this
        .background(AppColors.Bg)
        .drawWithCache {
            val w = size.width
            val h = size.height
            // 左上主光晕：标题裸露页（来源/设置/分类/TV 设置）的氛围来源
            val topGlow = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to brand.copy(alpha = edgeAlpha),
                    0.5f to brand.copy(alpha = edgeAlpha * 0.30f),
                    1f to Color.Transparent
                ),
                center = Offset(w * 0.18f, h * 0.02f),
                radius = maxOf(w, h) * 0.75f
            )
            // 左下光晕：TV 首页 Hero 折痕以下那条唯一可视的带子，手机上也压住左轴。
            // 圆心放在 0.9h 而不是贴在屏幕角上 —— 首页那条约 0.28h 高的带子里，
            // 贴角等于最亮的一圈压在最后一行海报背后，观感上仍然是「没有换主题」。
            val bottomLeftGlow = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to brand.copy(alpha = cornerAlpha),
                    0.55f to brand.copy(alpha = cornerAlpha * 0.28f),
                    1f to Color.Transparent
                ),
                center = Offset(w * 0.08f, h * 0.90f),
                radius = h * 0.55f
            )
            // 右下点缀：用浅色把对角拉平衡，避免整屏只往一边偏色
            val bottomRightGlow = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to brandSoft.copy(alpha = cornerAlpha * 0.75f),
                    1f to Color.Transparent
                ),
                center = Offset(w * 1.0f, h * 0.88f),
                radius = h * 0.5f
            )
            onDrawBehind {
                drawRect(topGlow)
                drawRect(bottomLeftGlow)
                drawRect(bottomRightGlow)
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
