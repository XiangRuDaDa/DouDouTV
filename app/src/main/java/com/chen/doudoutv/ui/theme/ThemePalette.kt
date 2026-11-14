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
 * 5 套色板，第 0 套是默认态。
 *
 * 品牌色直接当按钮底色用，按钮文字是纯黑（黑字比白字稳，每套都压得住），
 * 所以每套都有一个「黑字压得住」的亮度下限。
 *
 * 2026-11-13 走查砍掉两套：
 *   鸢尾 #9480EA —— 中紫 + 近黑底是「AI 默认主题」的既视感，soft 发灰，焦点态糊；
 *   霜青 #7E93B8 —— 低饱和灰蓝在近黑底上显脏，选中/未选中三米外拉不开。
 * 已选中这两套的设备由 paletteFor 回落默认色板（NF红），不崩不空白。
 *
 * 默认态 2026-11-12 从「苹果灰」换成「NF红」：
 * 无彩色品牌色在近黑底上只靠明度分层，三米外「选中 vs 未选中」读不出来，
 * 整套界面因此缺一个能一眼认出是谁家的色相。红是流媒体十英尺界面里最成熟的那一个。
 * id 仍是 graphite：存储键一改，已经存着这个 id 的设备会被 paletteFor 判成旧版本残留、
 * 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
 *
 * bamboo 那套「Plex 橙」同日把色相从 41° 挪到 26°（#D9A841 → #DF813A）：
 * 原 41° 与评分语义金 Rating #FFC107（45°）几乎同色相，详情页里品牌按钮和
 * 「8.6 分」金标同屏撞色，语义系统被搞混。26° 是真橙，Plex 味还在、金字不再打架。
 *
 * brandSoft（渐变亮端）统一规则，不再逐套手工配色：
 *   HSL 同色相，明度 +13 个点（上限 82%），饱和度 ×0.90。
 * 旧版各套 soft 配对标准漂移（有的提亮、有的发灰、有的浅到粉白），
 * 导致「贵气程度」随主题波动；锁规则后七套 → 五套的 soft 全部重算。
 *
 * 黑字下限：主按钮色阶最底那一档是 lerp(brand, Black, 0.16)。
 * NF红 #B63339 黑字 3.52:1；新橙 #DF813A 压到最底档 #BB6C31 黑字对比同样过 3.2 的线。
 */
val ThemePalettes: List<ThemePalette> = listOf(
    ThemePalette("graphite", "NF红", Color(0xFFD93D44), Color(0xFFDE7A7F)),
    ThemePalette("celadon", "青瓷", Color(0xFF1CA193), Color(0xFF2FD0BF)),
    ThemePalette("copper", "赤铜", Color(0xFFBF834D), Color(0xFFCCA582)),
    ThemePalette("rouge", "胭脂", Color(0xFFD66F90), Color(0xFFE1A6B9)),
    // id 仍是 bamboo：存储键一改，已经选中这套的设备就被 paletteFor 判成旧版本残留、
    // 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
    ThemePalette("bamboo", "Plex 橙", Color(0xFFDF813A), Color(0xFFE2A679))
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
