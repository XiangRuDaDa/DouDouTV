package com.tabbit.myvidhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabbit.myvidhub.R

/**
 * 格式徽章条：TMDB 图标（开启 TMDB 时）+ Emby 解析到的分辨率/HDR/Codec。
 * 统一放在详情页标题覆盖层底部（第三行位置）。
 */
@Composable
fun FormatBadgesRow(
    tmdbEnabled: Boolean,
    resolution: String,
    hdr: String,
    codec: String,
    audio: String = "",
    modifier: Modifier = Modifier
) {
    val items = buildList {
        if (tmdbEnabled) add(Badge.Tmdb)
        resolution.takeIf { it.isNotBlank() }?.let { add(Badge.Resolution(it)) }
        hdr.takeIf { it.isNotBlank() }?.let { add(Badge.Hdr(it)) }
        // Codec 只在有明显特色时展示（H.264 太通用可以隐藏）
        codec.takeIf { it.isNotBlank() && it != "H.264" }?.let { add(Badge.Codec(it)) }
        // 音轨同样只在有辨识度时展示（DD+ / TrueHD / Atmos 这类杜比音轨）
        audio.takeIf { it.isNotBlank() && it != "Dolby Digital" }?.let { add(Badge.Audio(it)) }
    }
    if (items.isEmpty()) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        items.forEach { badge ->
            when (badge) {
                Badge.Tmdb -> TmdbIconOnly()
                is Badge.Resolution -> TextBadge(badge.label, bg = Color(0xFF01B4E4))
                is Badge.Hdr -> when (badge.label) {
                    "Dolby Vision" -> DolbyVisionBadge()
                    "HDR10+" -> TextBadge("HDR10+", bg = Color(0xFFE50914))
                    "HDR10" -> TextBadge("HDR10", bg = Color(0xFF2E7D32))
                    "HDR" -> TextBadge("HDR", bg = Color(0xFF2E7D32))
                    "HLG" -> TextBadge("HLG", bg = Color(0xFFF57C00))
                    else -> TextBadge(badge.label, bg = Color(0xFF455A64))
                }
                is Badge.Codec -> TextBadge(badge.label, bg = Color(0xFF455A64))
                is Badge.Audio -> TextBadge(badge.label, bg = Color(0xFF6D4C41))
            }
        }
    }
}

sealed interface Badge {
    data object Tmdb : Badge
    data class Resolution(val label: String) : Badge
    data class Hdr(val label: String) : Badge
    data class Codec(val label: String) : Badge
    data class Audio(val label: String) : Badge
}

/** 纯文字徽章：圆角矩形 + 纯色背景 + 白色粗体字。尺寸统一 28.dp 高。 */
@Composable
fun TextBadge(label: String, bg: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg.copy(alpha = 0.88f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            lineHeight = 12.sp
        )
    }
}

/** 只保留 TMDB 官方图标（Simple Icons · 纯蓝 logo），无任何文字。 */
@Composable
fun TmdbIconOnly() {
    Icon(
        painter = painterResource(R.drawable.ic_tmdb_logo),
        contentDescription = "TMDB",
        tint = Color(0xFF01B4E4),
        modifier = Modifier.height(22.dp)
    )
}

/** Dolby Vision 徽章：白底黑色 Dolby 官方 D 字母图标 + 右上角 DV 小字 */
@Composable
fun DolbyVisionBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_dolby),
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.height(16.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                "VISION",
                color = Color.Black,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.3.sp
            )
        }
    }
}
