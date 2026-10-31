@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tabbit.myvidhub.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 首页顶部大图轮播（VidHub 的「精选」区）。
 *
 * 视觉要点：
 * · 16:9 背景剧照铺满，渐变压暗后沉入页面底色，消除硬边界
 * · 标题优先用刮削到的艺术字（ClearLogo，透明 PNG）；没有才回落到文字
 * · 只保留「播放 / 详情」两个按钮，不再堆影片简介，画面更干净
 * · 遮罩透明度整体调低，让剧照本身的色彩透出来，营造艺术氛围
 *
 * 分层结构（本次调整的重点）：
 *   第 1 层 — HorizontalPager：只有背景图和压暗遮罩，跟着手指/自动播放一起滑动。
 *   第 2 层 — 文字层：标题、年份评分、两个按钮。挂在 Pager 外面，
 *            按 pagerState.currentPage 用 AnimatedContent 做淡入淡出，
 *            所以切换时是「旧页信息淡出 → 新页信息淡入」，不会整块横移。
 *   第 3 层 — 分页指示器：同样挂在 Pager 外面、固定在图片上层同一位置，
 *            圆点只切换“哪一个高亮”，本身不随轮播位移。
 */
@Composable
fun HeroBanner(
    items: List<MediaEntity>,
    isTv: Boolean,
    onOpen: (MediaEntity) -> Unit,
    onPlay: (MediaEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6_500)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }

    // 淡入淡出的时长；进出用同一时长，交替时才不会有一闪而过的空档
    val fadeMs = 460

    Box(
        modifier
            .fillMaxWidth()
            .height(if (isTv) 430.dp else 392.dp)
    ) {
        // ================= 第 1 层：图 + 遮罩（唯一参与滑动的内容） =================
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize()) {
                val art = item.backdropUrl.ifBlank { item.posterUrl }
                if (art.isNotBlank()) {
                    AsyncImage(
                        model = art,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        // 轮播数据来自源站首页，多数只给竖版海报：放大后铺满 + 模糊，
                        // 避免竖图硬拉伸的细节抢文字的注意力（Android 11 及以下自动忽略模糊）
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.12f
                                scaleY = 1.12f
                            }
                            .blur(22.dp)
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(AppColors.Surface))
                }

                // 纵向遮罩：顶部只轻微压暗保住白字，底部平滑融入页面底色。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.10f),
                                0.42f to Color.Black.copy(alpha = 0.28f),
                                0.78f to Color.Black.copy(alpha = 0.58f),
                                1f to AppColors.Bg
                            )
                        )
                )
                // 横向遮罩：只在左侧给标题留一层薄纱，右侧完全放开，避免压死画面
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to AppColors.Bg.copy(alpha = 0.62f),
                                0.45f to AppColors.Bg.copy(alpha = 0.14f),
                                1f to Color.Transparent
                            )
                        )
                )
            }
        }

        // ================= 第 2 层：文字 + 按钮（淡入淡出，不做横向位移） =================
        AnimatedContent(
            targetState = pagerState.currentPage,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = fadeMs)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = fadeMs))
            },
            label = "heroInfo",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) { page ->
            val item = items.getOrNull(page)
            if (item != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 底部留出 46dp，让下方的固定指示器有独立空间，两者不重叠
                        .padding(start = 22.dp, end = 22.dp, bottom = 46.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 标题：有刮削艺术字（ClearLogo）就用图，没有才用文字兜底
                    if (item.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.logoUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(if (isTv) 100.dp else 72.dp)
                                .widthIn(max = if (isTv) 420.dp else 300.dp)
                        )
                    } else {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    val meta = metaLine(item)
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            letterSpacing = 1.2.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 主按钮：品牌色实底 + 阴影，视觉最突出
                        Button(
                            onClick = { onPlay(item) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Brand,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("播放", fontWeight = FontWeight.SemiBold)
                        }
                        // 次按钮：半透明白底玻璃风，与主按钮形成层级
                        OutlinedButton(
                            onClick = { onOpen(item) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.14f),
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, Color.White.copy(alpha = 0.35f)
                            ),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp)
                        ) {
                            Text("详情", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ================= 第 3 层：分页指示器（固定位置，不随轮播滑动） =================
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(items.size) { i ->
                val active = pagerState.currentPage == i
                Box(
                    Modifier
                        .size(width = if (active) 16.dp else 6.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (active) AppColors.Brand else Color.White.copy(alpha = 0.35f)
                        )
                )
            }
        }
    }
}

private fun metaLine(item: MediaEntity): String {
    val parts = mutableListOf<String>()
    if (item.year > 0) parts += item.year.toString()
    if (item.rating > 0) parts += "★ %.1f".format(item.rating)
    if (item.genres.isNotBlank()) {
        parts += item.genres.split(",", "/", " ", "，").first().trim()
    }
    return parts.joinToString("  ·  ")
}
