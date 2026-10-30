package com.tabbit.myvidhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

/**
 * 无海报时的兜底封面。
 *
 * TVBox 源的 vod_pic 经常是空串，或者填了却是个 404 死链；这两种情况都不能让海报位
 * 只剩一块纯灰矩形。这里按片名哈希出一对稳定的深色做斜向渐变，同一部片永远是同一组颜色，
 * 一排卡片肉眼能分开，也不会随刷新变色。
 *
 * 底色亮度刻意压得很低（value 0.13~0.28），保证白字可读、也不会在一排海报里发光。
 */
@Composable
fun PosterFallback(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    /**
     * true：占位区中央排整片名 —— 竖版海报卡没有别的文字可看，标题就是封面内容。
     * false：只排一个超大首字做水印 —— 调用方自己已经在图上压了标题（手机卡底部渐变、继续观看卡）。
     */
    showTitle: Boolean = true,
    titleFontSize: TextUnit = 14.sp,
    subtitleFontSize: TextUnit = 11.sp,
) {
    val brush = remember(title) { posterFallbackBrush(title) }
    Box(
        modifier
            .fillMaxSize()
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        if (showTitle) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    title,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = titleFontSize,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.48f),
                        fontSize = subtitleFontSize,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            Text(
                title.take(1),
                color = Color.White.copy(alpha = 0.16f),
                fontSize = titleFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * 海报位：有图就取图，图为空串或加载失败就画 [fallback]。
 *
 * 两个分支互斥、不把兜底垫在图下面，是有取舍的：一屏最多七十张卡，垫着等于多排七十次文字，
 * 弱盒子上白白吃帧；代价是加载中会先露一会儿卡片底色，跟改动前一样。
 * 只在失败时换兜底，也顺带省掉了空 URL 那次注定失败的图片请求。
 */
@Composable
fun PosterArt(
    model: String,
    contentDescription: String?,
    fallback: @Composable () -> Unit
) {
    var failed by remember(model) { mutableStateOf(false) }
    if (model.isBlank() || failed) {
        fallback()
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onState = { if (it is AsyncImagePainter.State.Error) failed = true }
        )
    }
}

private fun posterFallbackBrush(seed: String): Brush {
    val hue = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()
    val from = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.44f, 0.28f)))
    val to = Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf((hue + 38f) % 360f, 0.58f, 0.13f)
        )
    )
    return Brush.linearGradient(listOf(from, to))
}
