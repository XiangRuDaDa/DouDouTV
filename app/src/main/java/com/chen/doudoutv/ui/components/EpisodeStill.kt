package com.chen.doudoutv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Radius

/**
 * Emby 分集的 16:9 剧照缩略图内容（只画画面，不含外层卡片的点击 / 焦点壳）。
 *
 * 剧照来自 Emby 分集的 Primary 图（入库时已写进 [MediaEntity.posterUrl]），
 * 没有图 / 加载中 / 加载失败都先铺 [AppColors.SurfaceHi] 兜底块，图成功后盖在上面，
 * 所以任何网络状态下都不会出现空洞或破图图标。
 *
 * 字号一律走 [MaterialTheme.typography]：电视端在根上覆盖过小字四档，
 * 同一个组件在两端会自动落到各自的字阶，不需要按端传尺寸。
 */
@Composable
fun EpisodeStill(episode: MediaEntity, modifier: Modifier = Modifier) {
    val playing = episode.positionMs > 0
    val progress = if (episode.durationMs > 0L) {
        (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
    } else 0f

    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        // 兜底层：无剧照 / 加载中 / 加载失败都先看到这块
        Box(
            Modifier.matchParentSize().background(AppColors.SurfaceHi),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(30.dp)
            )
        }
        if (episode.posterUrl.isNotBlank()) {
            AsyncImage(
                model = episode.posterUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }

        // 左上角集号角标：在看的那一集用品牌色提示
        Text(
            text = episode.episodeLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = if (playing) AppColors.BrandSoft else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(Radius.Sm)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        // 底部渐变 + 标题 + 时长
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (episode.durationMs > 0) {
                Text(
                    text = "${episode.durationMs / 60_000} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }

        // 观看进度条贴最底沿
        if (playing && episode.durationMs > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(AppColors.Brand)
                )
            }
        }
    }
}