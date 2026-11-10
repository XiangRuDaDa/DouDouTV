package com.chen.doudoutv.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.chen.doudoutv.ui.theme.AppColors

/** 玻璃底栏的一格：图标 + 文字 */
data class GlassTabItem(val label: String, val icon: ImageVector)

/**
 * 玻璃底栏压在内容上的高度（条高 50dp + 离底留白 16dp）。
 *
 * 底栏是浮在内容之上的，所以页面自己的滚动容器必须留出这么一段底部内边距，
 * 最后一行才能滚到栏顶上边去，而不是永远压在玻璃条底下。
 */
val GlassBarClearance: Dp = 66.dp

/** 记一个坐标：绘制时现读，不进重组 */
private class MutableOffset {
    var value: Offset = Offset.Zero
}

/**
 * 页面背景抓取器：底栏靠它拿到「自己脚下那块内容」去做真模糊。
 *
 * Android 没有系统级的 backdrop blur —— Modifier.blur 模糊的是节点自己，看不见下层。
 * 要做到「透过底栏看到下面被模糊的内容」，只能先把页面内容离屏录一份，
 * 再从这份里取底栏脚下那一条去做高斯。
 *
 * 录的不是整屏而是页面底部一条（[stripHeight]）：整屏离屏纹理在 1440×3200 上
 * 一次就是十几 MB，而且每帧都要重新光栅化；只录底栏脚下够用的一条约 2MB。
 */
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    /** 抓取节点在根坐标里的位置 */
    internal var originInRoot: Offset = Offset.Zero

    /** 抓取条顶边在抓取节点内的 y（px） */
    internal var stripTop: Float = 0f
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) {
        GlassBackdrop(layer).also { it.layer.clip = true }
    }
}

/**
 * 抓取：把节点内容录进 [backdrop] 的离屏图层，只留页面底部 [stripHeight] 那一条。
 *
 * 录的时候把内容整体上移一条的高度，于是这一层的原点正好落在页面底部那条的顶边上；
 * 底栏再按根坐标差值平移画它，就正好取到自己脚下那截内容。
 */
fun Modifier.glassBackdropCapture(
    backdrop: GlassBackdrop,
    stripHeight: Dp = 160.dp
): Modifier = drawWithContent {
    val pageH = size.height
    val stripPx = stripHeight.toPx().coerceAtMost(pageH)
    if (stripPx >= 1f && size.width >= 1f) {
        val stripTop = pageH - stripPx
        backdrop.stripTop = stripTop
        backdrop.layer.record(IntSize(size.width.toInt(), stripPx.toInt())) {
            translate(top = -stripTop) {
                this@drawWithContent.drawContent()
            }
        }
    }
    drawContent()
}

/**
 * 手机端底部标签栏：悬浮在屏幕上方的一条液态玻璃横条。
 *
 * 原来是 Material 的 [androidx.compose.material3.NavigationBar]：通栏、实色、贴死底边，
 * 52dp 实打实切掉屏幕底部一条，页面看着像被盖了块玻璃砖。
 * 现在改成「浮起来 + 真透出下面的内容」：
 *   · 高度 50dp、离底 16dp（再让开系统手势条），整条只有原来通栏导航条的一半高；
 *   · 底色不是一块黑玻璃，而是把页面底部那条离屏录下来、模糊后垫在栏底下 ——
 *     透过栏能看见下面滚动的内容（糊的），不是一片死黑；
 *   · 磨砂是往上调的：暗底只留 20%，再叠一层磨砂白提亮，
 *     图标用白色（选中用主题色并放大），不再是一块黑底压着灰字；
 *   · 只留图标、不带文字：标签压在玻璃上会被底下滚动的内容衬得发虚，
 *     而且挤在 50dp 的条里又小又挤，一眼看不出是哪个页；
 *   · 顶边一道 1px 高光描边 + 一道斜向镜面扫光，这是玻璃感的主要来源。
 *
 * 模糊是怎么来的：Android 没有系统级 backdrop blur，Modifier.blur 模糊的是节点自己。
 * 所以这里把页面内容录进一层离屏图层（[GlassBackdrop]），底栏再从这层里取自己脚下那一条，
 * 套一层 BlurEffect 画出来。整层只有底部一条高，不是整屏离屏纹理。
 *
 * 高度是这一版刻意压过的：玻璃条再高一点就会把首页最后一行海报挡住，
 * 而底部那条系统手势区又必须让开，所以可用高度本来就只有 40dp 出头。
 */
@Composable
fun GlassTabBar(
    items: List<GlassTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    /** 页面背景抓取器；传 null 就退回半透明底色（没有模糊） */
    backdrop: GlassBackdrop? = null,
    modifier: Modifier = Modifier
) {
    val brand = AppColors.Brand
    val brandSoft = AppColors.BrandSoft
    val barShape = RoundedCornerShape(17.dp)
    val cellShape = RoundedCornerShape(13.dp)

    // Modifier.blur 只在 Android 12（API 31）以上真正生效，低版本上它是个空操作：
    // 那时会把底下的内容原样（不模糊）切一块出来贴在栏里，比不透明底还难看，
    // 所以低版本直接退回半透明底色。
    val blurUsable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 底栏自己在根坐标里的位置：抓取层按这个差值平移，才对得上栏脚下的内容
    val selfRoot = remember { MutableOffset() }

    Box(
        modifier
            .height(50.dp)
            .onGloballyPositioned { selfRoot.value = it.positionInRoot() }
            .clip(barShape)
    ) {
        // ① 栏脚下的内容：抓来模糊后垫底。抓不到（或系统不支持模糊）就退回半透明底色
        if (backdrop != null && blurUsable) {
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(10.dp)
                    .drawBehind {
                        val dx = backdrop.originInRoot.x - selfRoot.value.x
                        val dy = backdrop.originInRoot.y + backdrop.stripTop - selfRoot.value.y
                        translate(dx, dy) { drawLayer(backdrop.layer) }
                    }
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF161618).copy(alpha = 0.62f)))
        }

        // ② 玻璃底色：暗底只留薄薄一层（原来 34% 黑，压得下面什么都看不见，
        // 整条读起来还是一块黑玻璃），再叠一层磨砂白把玻璃「提亮」——
        // 磨砂玻璃是往上调的，不是往下压的。
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D).copy(alpha = 0.20f)))
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.09f)))

        // ③ 斜向扫光 + ④ 顶边高光
        Box(Modifier.fillMaxSize().glassSweep())
        Box(
            Modifier.fillMaxSize().border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))
                ),
                barShape
            )
        )

        // ⑤ 三格
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(cellShape)
                        // 选中格带一点主题色：底衬是主题色压出来的柔光，不换图标形状
                        // 只有图标之后没有文字做区分，选中态全靠这一层底色，所以别调太重
                        .background(if (selected) brand.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        // 未选中用白（半透），不用主题里的 TextTertiary ——
                        // 那是给纯色页面上的正文用的，压在玻璃上几乎看不见
                        tint = if (selected) brandSoft else Color.White.copy(alpha = 0.78f),
                        // 选中格放大一点：没有文字做区分，只能靠图标本身
                        modifier = Modifier.size(if (selected) 22.dp else 19.dp)
                    )
                }
            }
        }
    }
}

/**
 * 斜向镜面扫光：一道从左下往右上的白色渐变带，横过整条玻璃。
 *
 * 只画一条很淡的带（峰值 9% 白），位置固定不动 —— 动起来要每帧重绘，
 * 底栏又是常驻元素，为一点「流光」换常驻重绘不值当。
 */
private fun Modifier.glassSweep(): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val sweep = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.26f to Color.White.copy(alpha = 0.09f),
            0.44f to Color.Transparent,
            1f to Color.Transparent
        ),
        start = Offset(0f, h),
        end = Offset(w * 0.78f, 0f)
    )
    onDrawBehind { drawRect(sweep) }
}
