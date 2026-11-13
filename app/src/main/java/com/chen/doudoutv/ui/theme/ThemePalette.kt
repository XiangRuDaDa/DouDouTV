package com.chen.doudoutv.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.chen.doudoutv.core.Prefs

/**
 * 一套全局主题色。
 *
 * 只描述「品牌色」这两个值，不动 Bg / Surface / TextPrimary 这些中性色：
 * 中性色是整套界面的对比度基线，跟着色板一起漂会把文字可读性重新变成未知量，
 * 而背景的氛围感本来就该由 [themeBackdrop] 的光晕层来做，不需要改底色本身。
 */
data class ThemePalette(
    val id: String,
    val label: String,
    val brand: Color,
    val brandSoft: Color
)

/**
 * 7 套色板，第 0 套是默认态。
 *
 * 品牌色直接当按钮底色用，按钮文字是纯黑（黑字比白字稳，七套色都压得住），
 * 所以每套都有一个「黑字压得住」的亮度下限。
 *
 * 默认态 2026-11-12 从「苹果灰」换成「NF红」：
 * 无彩色品牌色在近黑底上只靠明度分层，三米外「选中 vs 未选中」读不出来，
 * 整套界面因此缺一个能一眼认出是谁家的色相。红是流媒体十英尺界面里最成熟的那一个。
 * id 仍是 graphite：存储键一改，已经存着这个 id 的设备会被 paletteFor 判成旧版本残留、
 * 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
 *
 * 其余五套保持原样：青瓷 193° 低饱和、鸢尾、赤铜、胭脂、霜青。
 * bamboo 那一档换成「Plex 橙」，同样只改 label 与色值、不动 id。
 *
 * 两套命名色都不取品牌原值，而是**锁住色相、只压饱和度**（同日真机走查：原值太艷）：
 * NF红 357° S96%→72%（#E50914 → #D93D44），Plex 橙 41° S94%→70%（#E5A00D → #D9A841），
 * 明度同时收 90%→85%。色相一度没动，所以「这是 Netflix 红 / 这是 Plex 橙」还认得出来，
 * 但铺到天光、按钮、下划线、缺图海报上不再是一整片荧光。
 * 浅色档跟着同色相压：#EF6A72（S56%）、#EDC572（S52%）。
 *
 * 黑字下限：主按钮色阶最底那一档是 lerp(brand, Black, 0.16)。
 * 压饱和之后这一档反而更稳 —— NF红 #B63339 黑字 3.52:1（原值 3.28:1，是七套里最低的一档），
 * Plex 橙 #B68D37 6.85:1；中间档 #D93D44 4.70:1、#D9A841 9.63:1，都过 3.2 的线。
 */
val ThemePalettes: List<ThemePalette> = listOf(
    ThemePalette("graphite", "NF红", Color(0xFFD93D44), Color(0xFFEF6A72)),
    ThemePalette("celadon", "青瓷", Color(0xFF1CA193), Color(0xFF41D7C7)),
    ThemePalette("iris", "鸢尾", Color(0xFF9480EA), Color(0xFFCDC4F3)),
    ThemePalette("copper", "赤铜", Color(0xFFBF834D), Color(0xFFD5B293)),
    ThemePalette("rouge", "胭脂", Color(0xFFD66F90), Color(0xFFE9B9C8)),
    // id 仍是 bamboo：存储键一改，已经选中这套的设备就被 paletteFor 判成旧版本残留、
    // 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
    ThemePalette("bamboo", "Plex 橙", Color(0xFFD9A841), Color(0xFFEDC572)),
    ThemePalette("steel", "霜青", Color(0xFF7E93B8), Color(0xFFBAC5D7))
)

val DefaultPalette: ThemePalette = ThemePalettes.first()

/** 认不出来的 id（改过名、旧版本残留）一律回落默认色板，不崩也不空白 */
fun paletteFor(id: String): ThemePalette =
    ThemePalettes.firstOrNull { it.id == id } ?: DefaultPalette

/**
 * 当前生效的色板。
 *
 * 做成全局可观察状态而不是只读 Prefs，是为了「点一下就整屏换色」：
 * Prefs 是 SharedPreferences，读它不会触发重组；把它包进 mutableStateOf，
 * 选择时写一次、界面立刻跟上。冷启动那次读盘放在 object 首次访问时做，
 * 不需要谁再显式调一次 restore。
 */
object AppPalette {

    var current: ThemePalette by mutableStateOf(paletteFor(Prefs.themeId))
        private set

    fun select(id: String) {
        Prefs.themeId = id
        current = paletteFor(id)
    }
}

/**
 * 供 [AppColors] 的 composable getter 读取。
 *
 * 必须是 compositionLocalOf 而不是 static 版本：static 的读取点不会订阅值变化，
 * 换色板时深处那些「入参没变、可以被 skip」的子组件会拿着旧颜色留在屏幕上。
 * 这里要的就是每个读点各自订阅、精确失效，代价只是一次颜色状态的追踪。
 */
val LocalPalette = compositionLocalOf { DefaultPalette }

/**
 * 当前界面是不是十英尺形态。
 *
 * 背景光晕按它分档：电视的可视内容带更窄（首页 Hero 吃掉大半屏高），
 * 光晕要更足才看得见；手机屏幕小、光晕占比高，反而要收一档别抢文字。
 */
val LocalTvMode = compositionLocalOf { false }
