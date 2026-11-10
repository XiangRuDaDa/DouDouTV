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
 * 品牌色直接当按钮底色用，按钮文字是纯黑（黑字在 7 套色上对比度 4.9:1~9.5:1，
 * 比白字更稳），所以每套都有一个「黑字压得住」的亮度下限。
 *
 * 默认态 2026-11-10 从「石墨蓝」换成「苹果灰」（#8E8E93 / #AEAEB4）：
 * 蓝色主题配近黑底，整屏读起来是「蓝黑底 + 蓝光晕」；换成苹果灰之后，
 * 背景层、按钮、焦点、进度条统一在一套无彩色体系里，只靠明度分层。
 * id 仍是 graphite：存储键一改，已经存着这个 id 的设备会被 paletteFor 判成旧版本残留、
 * 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
 *
 * 其余六套保持原样：青瓷 193° 低饱和、鸢尾、赤铜、胭脂、汝窑、霜青。
 * 命名色还是那个颜色：汝窑取 193°、饱和度只留 22%（釉面失透、青中泛蓝而非泛绿）。
 */
val ThemePalettes: List<ThemePalette> = listOf(
    ThemePalette("graphite", "苹果灰", Color(0xFF8E8E93), Color(0xFFAEAEB4)),
    ThemePalette("celadon", "青瓷", Color(0xFF1CA193), Color(0xFF41D7C7)),
    ThemePalette("iris", "鸢尾", Color(0xFF9480EA), Color(0xFFCDC4F3)),
    ThemePalette("copper", "赤铜", Color(0xFFBF834D), Color(0xFFD5B293)),
    ThemePalette("rouge", "胭脂", Color(0xFFD66F90), Color(0xFFE9B9C8)),
    // id 仍是 bamboo：存储键一改，已经选中这套的设备就被 paletteFor 判成旧版本残留、
    // 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
    ThemePalette("bamboo", "汝窑", Color(0xFF6D97A2), Color(0xFFA6BEC4)),
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
