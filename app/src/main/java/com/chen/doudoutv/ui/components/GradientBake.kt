package com.chen.doudoutv.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** 烘焙降采样倍率：这些层全是平滑渐变，1/4 分辨率双线性放大后肉眼分不出 */
private const val BAKE_SCALE = 4f

/**
 * 把「每帧逐像素求值的渐变 shader」换成「烘一次、之后每帧只采一张纹理」。
 *
 * 电视是 Mali-G52：多 stop 渐变求值 + 读回帧缓冲做混合，比纹理采样 + 混合贵一个量级。
 * 实测首页 Hero 那两层满幅渐变一帧吃 21ms，烘成 1/4 分辨率的图再放大铺满后只剩约 2ms，
 * 画面一个像素看不出差别 —— 因为渐变本身没有高频细节，双线性插值就是它的精确解。
 *
 * 用法：在 drawWithCache 的缓存块里调 [image]，把着色内容画进回调给的 Canvas
 * （回调里的坐标仍是全尺寸，Canvas 已按 [BAKE_SCALE] 缩好）。[key] 不变且尺寸不变时
 * 直接复用上一张，稳态下一帧也不重烘；换色那几百毫秒里逐帧重烘也便宜（九万像素级）。
 */
internal class GradientBake {

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var cached: ImageBitmap? = null
    private var bakedW = 0
    private var bakedH = 0
    private var bakedKey: Any? = null

    fun image(width: Float, height: Float, key: Any, draw: (Canvas) -> Unit): ImageBitmap {
        val bw = (width / BAKE_SCALE).toInt().coerceAtLeast(1)
        val bh = (height / BAKE_SCALE).toInt().coerceAtLeast(1)
        var bmp = bitmap
        var cv = canvas
        if (bmp == null || cv == null || bakedW != bw || bakedH != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            cv = Canvas(bmp)
            bitmap = bmp
            canvas = cv
            cached = bmp.asImageBitmap()
            bakedW = bw
            bakedH = bh
            bakedKey = null
        }
        if (bakedKey != key) {
            cv.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            cv.save()
            cv.scale(1f / BAKE_SCALE, 1f / BAKE_SCALE)
            draw(cv)
            cv.restore()
            bakedKey = key
        }
        return cached!!
    }
}

/** 保持 RGB 只换 alpha：和 Compose 的 Color.copy(alpha=…).toArgb() 同一套量化 */
internal fun argbWithAlpha(argb: Int, alpha: Float): Int =
    (argb and 0x00FFFFFF) or ((alpha * 255f + 0.5f).toInt() shl 24)