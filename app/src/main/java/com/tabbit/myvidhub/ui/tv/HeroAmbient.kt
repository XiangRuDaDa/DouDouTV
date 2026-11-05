package com.tabbit.myvidhub.ui.tv

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.ui.detail.loadPosterBitmap
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 着陆色往下方淡出的深度（从 Hero 底边算起）。
 *
 * 收色带自己只占 10dp 的布局高度，淡出却要走这么深才化得开：这条带子和 Hero 底边之间的接缝
 * 必须隐形，能拉开的只有带子以下的空间，而那一屏里唯一的空间就是「继续观看」的标题与海报上沿。
 * 10dp 内从实色降到 0，观感就是「颜色戛然而止」——130dp 大约压住标题整行加海报上沿三成，
 * 曲线后半段只剩零点几的透明度，肉眼找不到终点。
 */
internal val SEAM_FADE_DEPTH: Dp = 130.dp

/** 取样色换页时的混色时长，与底图淡入（[TvHero] 里的 600ms）对齐 */
private const val AMBIENT_BLEND_MS = 600

/**
 * 没有取样色时用的中性哨兵：饱和度为 0，[heroLandingColor] 认得它，
 * 会退回改造前的纯 [AppColors.Bg] —— 取色失败时画面和以前一个像素都不差。
 */
internal val AmbientNone = Color(0xFF808080)

/**
 * 底图下缘的取样色。
 *
 * 改造前 Hero 的渐隐终点是纯 [AppColors.Bg]：无论底图什么色调，都是「画面切一刀然后掉进黑里」，
 * 海报下缘到下面第一行之间那条约 50dp 的带子因此是死黑一片。Netflix billboard 的观感来自另一件事：
 * 画面下缘的色相溢出来一片柔和的色雾，页面底色是从这片雾里长出来的。这里算的就是那个色相。
 */
internal object HeroAmbientCache {
    private const val MAX = 24

    private val map = object : LinkedHashMap<String, Color>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > MAX
    }

    @Synchronized
    fun has(url: String): Boolean = url.isNotBlank() && map.containsKey(url)

    @Synchronized
    fun get(url: String): Color? = if (url.isBlank()) null else map[url]

    @Synchronized
    fun put(url: String, color: Color) {
        if (url.isNotBlank()) map[url] = color
    }
}

/**
 * 取底图下缘那条带的平均色。
 *
 * 取样带按图的长短分两种：竖版海报在 16:9 的 Hero 里被 centerCrop 之后，
 * 真正露出来的只有中间约三成（电视上一屏大约对应原图 35%~65% 那一段），
 * 所以竖图取 50%~70%，也就是画面底边附近；横图整张都在画面里，取 65%~90%。
 */
private fun ambientColorOf(bitmap: Bitmap): Color {
    val portrait = bitmap.height > bitmap.width
    val yFrom = (bitmap.height * if (portrait) 0.50f else 0.65f).toInt()
    val yTo = (bitmap.height * if (portrait) 0.70f else 0.90f).toInt().coerceAtLeast(yFrom + 1)
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    val stepX = (bitmap.width / 24).coerceAtLeast(1)
    var y = yFrom
    while (y < yTo) {
        var x = 0
        while (x < bitmap.width) {
            val p = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(p)
            g += android.graphics.Color.green(p)
            b += android.graphics.Color.blue(p)
            n++
            x += stepX
        }
        y++
    }
    if (n == 0L) return AmbientNone
    return Color(android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
}

/**
 * 着陆色：保住取样色的色相，亮度只压到比页面底色亮一档。
 * Hero 渐隐的底边和下面那条收色带的顶边共用它，接缝仍然隐形，但沉进去的是这张图自己的颜色。
 *
 * 饱和度上限压到 0.62、明度压到 0.12~0.22：这条带子横贯整屏宽，再亮就从「氛围」变成「色块」，
 * 也会把压在上面的按钮对比度吃掉。黑白灰底图（饱和度 < 0.10）直接退回纯 [AppColors.Bg]。
 */
internal fun heroLandingColor(ambient: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(ambient.toArgb(), hsv)
    if (hsv[1] < 0.10f) return AppColors.Bg
    hsv[1] = hsv[1].coerceIn(0.30f, 0.62f)
    hsv[2] = hsv[2].coerceIn(0.12f, 0.22f)
    return lerp(AppColors.Bg, Color(android.graphics.Color.HSVToColor(hsv)), 0.78f)
}

/** 当前这张轮播底图的下缘取样色；没取到之前是 [AmbientNone]（= 改造前的纯 Bg） */
@Composable
internal fun rememberHeroAmbient(imageUrl: String): Color {
    var color by remember(imageUrl) { mutableStateOf(HeroAmbientCache.get(imageUrl) ?: AmbientNone) }
    val context: Context = LocalContext.current
    LaunchedEffect(imageUrl) {
        if (imageUrl.isBlank() || HeroAmbientCache.has(imageUrl)) return@LaunchedEffect
        val sampled = withContext(Dispatchers.IO) {
            runCatching { loadPosterBitmap(context, imageUrl, 160)?.let { ambientColorOf(it) } }.getOrNull()
        }
        if (sampled != null) {
            HeroAmbientCache.put(imageUrl, sampled)
            color = sampled
        }
    }
    return color
}

/**
 * 把异步到达的取样色包成一个「只在 draw 阶段读」的取值函数。
 *
 * 换页时从上一张的颜色混到这一张，和底图淡入同时走完 600ms；不做这层过渡，
 * 底图还在渐隐而着陆色已经硬切成新图的颜色 —— 表现就是那条带子「啪」地跳色一下。
 * 返回的 lambda 读的是 snapshot state，混色的 600ms 里只重画遮罩，不重组 Hero。
 */
@Composable
internal fun rememberAmbientBlend(ambient: Color): () -> Color {
    val from = remember { mutableStateOf(ambient) }
    val to = remember { mutableStateOf(ambient) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(ambient) {
        if (to.value != ambient) {
            from.value = lerp(from.value, to.value, mix.value)
            to.value = ambient
            mix.snapTo(0f)
            mix.animateTo(1f, tween(AMBIENT_BLEND_MS))
        }
    }
    return { lerp(from.value, to.value, mix.value) }
}
