package com.tabbit.myvidhub.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tabbit.myvidhub.R
import com.tabbit.myvidhub.ui.theme.AppColors
import com.tabbit.myvidhub.ui.theme.Radius

/**
 * TMDB 品牌与署名组件。
 *
 * 用于标注数据来自 TMDB，把图文组合抽成可复用组件，
 * 详情页与设置页共用，避免两处样式写岔。
 */

/** TMDB 品牌色 */
private val TmdbBlue = Color(0xFF01B4E4)

/**
 * TMDB 标识徽章：品牌蓝底 + 胶片图标 + "TMDB" 文字。
 * 图标是自绘几何图形（res/drawable/ic_tmdb_logo.xml），不引用官方商标文件。
 */
@Composable
fun TmdbLogoBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier = modifier
            .clip(Radius.Sm)
            .background(TmdbBlue)
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_tmdb_logo),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 10.dp else 13.dp)
        )
        Text(
            text = "TMDB",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * 详情页用的轻量署名行：徽章 + 固定声明。
 * 叠在海报渐变区上，所以文字用半透明白，避免抢标题的视觉焦点。
 */
@Composable
fun TmdbCreditLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "数据来源：TMDB",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.58f),
            maxLines = 2
        )
    }
}

/**
 * 设置页用的完整免责声明块：徽章 + 中英文声明 + 数据来源说明。
 */
@Composable
fun TmdbDisclaimer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TmdbLogoBadge()

        Text(
            text = "影片的海报、背景图、艺术字 Logo、评分、简介与演职员信息来自 TMDB，" +
                "仅用于个人本地展示，图片做本地缓存不二次分发。",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary
        )
    }
}
