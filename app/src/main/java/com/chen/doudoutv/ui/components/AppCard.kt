package com.chen.doudoutv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Radius

/**
 * 全局卡片材质（②微抬 + ③顶光 混搭）。
 *
 * 卡片原来是一整块 Surface(#131313) 实色、零描边，落在 Bg(#080808) 上只差 11/255，
 * 近黑里这点色差读不出来，于是糊成「贴着底的黑块」。这里不换色板、不动 Bg/Surface 基线，
 * 只把卡片这一层抬起来并勾边，全部由既有 L1/L2/L3 令牌推导：
 *   · 底：一道竖向渐层，上缘抬到 L2/L3 之间、下缘沉回 L1 附近 ——
 *     平均明度≈SurfaceHi（②的「微抬」），上亮下暗模拟顶光（③的「质感」）；
 *     下缘刻意压回 L1 附近，卡内原本用 SurfaceHi 的统计行/徽章仍比卡面亮，不会糊在一起。
 *   · 边：1dp 发丝描边，上缘略亮、下缘压暗，和 [ButtonMaterial] 内高光同一套
 *     「这块料被顶光照过」的逻辑 —— 材质感真正的来源就在这一条。
 *
 * 静止卡只给描边不给投影：一屏多卡各挂一层投影就是多个 graphicsLayer，
 * 弱盒子滚动帧时间吃不消（和 Lift 注释里海报卡不给投影是同一类取舍）。
 */
object CardMaterial {
    /** 卡面渐层：上缘受光抬一档（L2→L3 之间），下缘沉回 L1 附近 */
    val stops: List<Color> = listOf(
        lerp(AppColors.SurfaceHi, AppColors.SurfaceRaise, 0.40f),
        lerp(AppColors.Surface, AppColors.SurfaceHi, 0.12f)
    )

    /** 发丝描边：上缘略亮、下缘压到接近 Edge.Hairline，读成顶光勾边 */
    val edge: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f))
    )
}

/**
 * 给容器卡套上全局卡片材质：渐层底 + 顶光发丝边。
 * 形状由调用方传（设置卡 [Radius.Md] / 来源卡 [Radius.Lg]），先 clip 再铺底勾边，
 * 内容画在材质之上。配 Material3 Card 用时把 containerColor 设成透明，让这层渐层透出来。
 */
fun Modifier.appCardSurface(shape: Shape = Radius.Md): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(CardMaterial.stops))
    .border(1.dp, CardMaterial.edge, shape)

/**
 * TV 容器卡材质（①抬亮 slab + 亮描边，叠 ②品牌侧条）。
 *
 * 不照搬手机 [CardMaterial]：手机那套 8% 发丝边 + 十几级渐变是给手臂距离看的，
 * 电视隔三米低于可辨阈值，搬过去等于没改。TV 要的是「三米外读得出这是一张卡」：
 *   · 底：竖向渐层抬得比手机更亮、更平（下缘也抬到 L1/L2 之间），整块 slab 明显浮于 Bg；
 *   · 边：白 22%→14%，两端都压在可辨阈值之上（手机只 16%→6%），仍保留顶光勾边那套逻辑；
 *   · 侧条：左缘 4dp 品牌色，把分组标题那套品牌识别延伸进卡片，换色板整组跟着换。
 * 焦点态白边 + 放大是 TV 遥控器落点语言，不动；侧条是恒定识别元素、不是焦点指示。
 * 静止卡只给描边不给投影（同手机取舍，弱盒子滚动帧时间吃不消）。
 */
object TvCardMaterial {
    /** 卡面渐层：比手机更亮更平，下缘抬到 L1/L2 之间，三米外整块读得出 */
    val stops: List<Color> = listOf(
        lerp(AppColors.SurfaceHi, AppColors.SurfaceRaise, 0.40f),
        lerp(AppColors.Surface, AppColors.SurfaceHi, 0.25f)
    )

    /** 亮描边：两端都高于可辨阈值，上缘略亮保留顶光感 */
    val edge: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.14f))
    )

    /** 左缘品牌侧条宽度 */
    val rail = 4.dp
}

/**
 * 给 TV 容器卡套材质：抬亮渐层底 + 亮描边 + 左缘品牌侧条。
 *
 * [focused] 时描边换纯白（焦点落点）；宽度由 [borderWidth] 定死，来源卡传 2dp，
 * 让聚焦/失焦内容区不跳（原来用透明 2dp 占位是同一目的）。
 * 侧条画在内容之下、clip 之内，跟着圆角收左缘；卡内留白 ≥12dp，不会被 4dp 侧条压到。
 */
@Composable
fun Modifier.tvCardSurface(
    shape: Shape = Radius.Md,
    focused: Boolean = false,
    borderWidth: Dp = 1.5.dp
): Modifier {
    val brand = AppColors.Brand
    return this
        .clip(shape)
        .background(Brush.verticalGradient(TvCardMaterial.stops))
        .then(
            if (focused) Modifier.border(borderWidth, Color.White, shape)
            else Modifier.border(borderWidth, TvCardMaterial.edge, shape)
        )
        .drawWithContent {
            val rail = TvCardMaterial.rail.toPx()
            drawRect(brand, topLeft = Offset.Zero, size = Size(rail, size.height))
            drawContent()
        }
}