package com.tabbit.myvidhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.LocalTvMode
import com.tabbit.myvidhub.ui.theme.TvText
import com.tabbit.myvidhub.ui.theme.ThemePalette

/**
 * 主题色色卡：一颗色点 + 下面一行名字。
 *
 * 手机端和电视端共用这一颗：电视端的焦点态由 interactionSource 驱动
 * （clickable 与遥控器聚焦共用同一个 source），手机上永远拿不到焦点，
 * 所以不需要为十英尺再复制一份。
 *
 * 选中态用「同色相的晕」而不是描边：色点本身就等于品牌色，在它外面描一圈同色等于什么都没画；
 * 让外圈铺一层低透明度的同色，选中与未选中在三米外也分得开。
 */
@Composable
fun ThemeSwatch(
    palette: ThemePalette,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val isTv = LocalTvMode.current
    // 十英尺下把可聚焦面积放大到 44dp：色点本身只要 30dp，剩下的留给焦点晕圈
    val haloSize = if (isTv) 44.dp else 34.dp
    val dotSize = if (isTv) 30.dp else 22.dp

    Column(
        modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(haloSize)
                .clip(CircleShape)
                .background(
                    when {
                        focused -> Color.White
                        selected -> palette.brand.copy(alpha = 0.22f)
                        else -> Color.Transparent
                    }
                )
                .then(
                    if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(palette.brand)
            )
        }
        Text(
            palette.label,
            // 字号统一交给主题的 labelSmall：电视端自动走 TvTypography 那一档，不在这里按端各写一套
            style = MaterialTheme.typography.labelSmall,
            color = when {
                focused -> if (isTv) Color.Black else AppColors.TextPrimary
                isTv -> if (selected) TvText.Title else TvText.Meta
                selected -> palette.brand
                else -> AppColors.TextSecondary
            },
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}
