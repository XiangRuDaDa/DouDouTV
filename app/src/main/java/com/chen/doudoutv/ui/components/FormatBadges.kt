package com.chen.doudoutv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chen.doudoutv.R
import com.chen.doudoutv.ui.theme.Radius

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

/**
 * 纯文字徽章：哑光材质 —— 底色只取传入色的 15% 做同色相染色，再勾一道同色相的描边，字用近白。
 *
 * 之前是六个高饱和实底色块（青/绿/红/橙/蓝灰/棕）并排，徽章本身比海报还抢眼，
 * 等于在画面上撒了一排彩色贴纸。传入的色值保留下来：它现在表示「这一类信息的色相」，
 * 不再等于色块本身。
 *
 * 尺寸与内边距一字未改，只换材质 —— 徽章行在详情页覆盖层的第三行，动内边距会挤动整行。
 */
@Composable
fun TextBadge(
    label: String,
    bg: Color,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 14.sp,
    letterSpacing: TextUnit = 0.6.sp
) {
    Box(
        Modifier
            .clip(Radius.Sm)
            .background(bg.copy(alpha = 0.15f))
            .border(1.dp, bg.copy(alpha = 0.38f), Radius.Sm)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight
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

/**
 * Dolby Vision 徽章：官方 D 字母图标 + VISION 小字。
 *
 * 原来是一整块纯白底 —— 一排哑光徽章里唯一发亮的色块，视线先被它抓走。
 * 现在跟着 [TextBadge] 的材质走：低亮度白底 + 一道亮边，Dolby 的标识本身保持原样。
 */
@Composable
fun DolbyVisionBadge() {
    Box(
        Modifier
            .clip(Radius.Sm)
            .background(Color.White.copy(alpha = 0.13f))
            .border(1.dp, Color.White.copy(alpha = 0.34f), Radius.Sm)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_dolby),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.height(16.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                "VISION",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.3.sp
            )
        }
    }
}
