package com.tabbit.myvidhub.ui.detail

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import com.tabbit.myvidhub.ui.theme.AppColors

/**
 * 简介页配色工具。
 *
 * 手机端与电视端各自有独立的简介页实现，布局、控件、交互全部不共用；
 * 但「从封面取聚焦色」「把聚焦色压成整页填充色」属于与设备无关的纯计算，
 * 抽在这里共用，避免两端各写一份导致取色效果不一致。
 */

/**
 * 直接用 Coil ImageLoader 把封面解码成小尺寸 Bitmap。
 *
 * 不用 AsyncImage 的 onSuccess 回调里 drawable.toBitmap，因为某些 drawable
 * （LayerDrawable / 矢量图 / 已被回收的 bitmap）会让 toBitmap 抛异常并被 runCatching
 * 吞掉，结果就是取色永远失败、页面背景退回纯黑。这里走一次独立的 ImageRequest，
 * allowHardware(false) 保证拿到的是软件位图，可直接读像素。
 */
internal suspend fun loadPosterBitmap(context: Context, url: String, size: Int = 160): Bitmap? {
    if (url.isBlank()) return null
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(size)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        val drawable = result.drawable ?: return@runCatching null
        (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: drawable.toBitmap(size, size)
    }.getOrNull()
}

/**
 * 取色彻底失败时的兜底色：根据标题文字生成一个稳定的有色相的颜色，
 * 保证页面背景永远不会是纯黑。基于标题字符的 hashCode 选取色相。
 */
internal fun fallbackColorFromTitle(title: String): Color {
    val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
    val hsv = floatArrayOf(hue, 0.55f, 0.32f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 从封面提取"聚焦色"。
 *
 * 首选策略：用 Canvas 像素采样取海报**中心 60% 区域**的平均色。
 * 中心区域通常是海报主角/主视觉所在，平均色最能代表这张图的主色调。
 * 若中心色饱和度过低（接近黑白灰），再回落 Palette 的 vibrant/muted 色块，
 * 最后兜底整图平均色。全程给饱和度、亮度设下限，避免暗色海报取出来还是黑。
 */
internal fun focusColorOf(bitmap: Bitmap): Color {
    // 1) 中心区域平均色
    val centerArgb = centerAverageColorOf(bitmap)
    val centerHsv = FloatArray(3)
    android.graphics.Color.colorToHSV(centerArgb, centerHsv)
    if (centerHsv[1] >= 0.12f) {
        centerHsv[1] = centerHsv[1].coerceIn(0.40f, 0.90f)
        centerHsv[2] = centerHsv[2].coerceIn(0.45f, 0.82f)
        return Color(android.graphics.Color.HSVToColor(centerHsv))
    }

    // 2) Palette 色块（饱和度通常更足）
    val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull()
    val swatch = listOfNotNull(
        palette?.vibrantSwatch,
        palette?.lightVibrantSwatch,
        palette?.mutedSwatch,
        palette?.darkVibrantSwatch,
        palette?.dominantSwatch,
        palette?.darkMutedSwatch
    ).firstOrNull { it.population > 0 }

    val argb = swatch?.rgb ?: averageColorOf(bitmap)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    if (hsv[1] < 0.06f) return AppColors.BrandBase

    hsv[1] = hsv[1].coerceIn(0.40f, 0.90f)
    hsv[2] = hsv[2].coerceIn(0.45f, 0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 取海报中心 60% 区域的像素平均色。
 * 用 Bitmap.getPixel 逐点采样（等价于 Canvas draw 后读像素），按 12x12 步长采样，
 * 避免对大图做昂贵的全像素遍历。
 */
private fun centerAverageColorOf(bitmap: Bitmap): Int {
    val cx = bitmap.width / 2
    val cy = bitmap.height / 2
    val halfW = (bitmap.width * 0.30f).toInt().coerceAtLeast(1)
    val halfH = (bitmap.height * 0.30f).toInt().coerceAtLeast(1)
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    val stepX = (halfW / 12).coerceAtLeast(1)
    val stepY = (halfH / 12).coerceAtLeast(1)
    val xStart = (cx - halfW).coerceAtLeast(0)
    val xEnd = (cx + halfW).coerceAtMost(bitmap.width)
    val yStart = (cy - halfH).coerceAtLeast(0)
    val yEnd = (cy + halfH).coerceAtMost(bitmap.height)
    var y = yStart
    while (y < yEnd) {
        var x = xStart
        while (x < xEnd) {
            val p = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(p)
            g += android.graphics.Color.green(p)
            b += android.graphics.Color.blue(p)
            n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0L) return AppColors.BrandBase.toArgb()
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/** Palette 失败时的兜底：整图按步长采样求平均色 */
private fun averageColorOf(bitmap: Bitmap): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    val stepX = (bitmap.width / 24).coerceAtLeast(1)
    val stepY = (bitmap.height / 24).coerceAtLeast(1)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val p = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(p)
            g += android.graphics.Color.green(p)
            b += android.graphics.Color.blue(p)
            n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0L) return AppColors.BrandBase.toArgb()
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * 整页填充的两个端点色，都保留聚焦色的色相：
 *   first  = 顶部，色相最浓、略亮，承接头图
 *   second = 底部，同色相压得更暗，收住长列表
 * 两端同色系，中间用插值平滑过渡，天然没有色带断层。
 */
internal fun pageTonesOf(focus: Color): Pair<Color, Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(focus.toArgb(), hsv)

    val topHsv = hsv.copyOf()
    topHsv[1] = (topHsv[1] * 0.96f).coerceIn(0.34f, 0.80f)
    topHsv[2] = 0.34f

    val bottomHsv = hsv.copyOf()
    bottomHsv[1] = (bottomHsv[1] * 0.90f).coerceIn(0.30f, 0.72f)
    bottomHsv[2] = 0.14f

    return Color(android.graphics.Color.HSVToColor(topHsv)) to
        Color(android.graphics.Color.HSVToColor(bottomHsv))
}

/**
 * 封面聚焦色缓存。
 *
 * 同一张封面会在首页、继续观看、简介页反复出现，每次进入都重新解码取色是浪费。
 * 以图片地址为 key 做小容量 LRU，命中即返回。
 */
internal object FocusColorCache {
    private const val MAX = 64

    private val map = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > MAX
    }

    @Synchronized
    fun get(url: String): Color? = if (url.isBlank()) null else map[url]

    @Synchronized
    fun put(url: String, color: Color) {
        if (url.isNotBlank()) map[url] = color
    }
}
