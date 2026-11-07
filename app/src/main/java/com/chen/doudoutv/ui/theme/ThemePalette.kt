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
 * 7 套色板，第 0 个就是改造前的原色 —— 默认态下任何一个像素都不该变。
 *
 * 挑选标准是「能压住白字」：品牌色会直接当按钮底色用（contentColor 是纯白），
 * 所以每一套都按 WCAG 相对亮度算过，白字对比度不低于 3.2:1。
 * 青瓷、赤铜这两套原本分别是 2.46 / 2.81，橙绿这类看着高级、
 * 放上白字就糊的颜色统一往暗里压过一档；brandSoft 只当深色底上的强调色用
 * （对底色仍有 8:1 以上），跟着同色相压暗是为了两层明度差保持不变。
 *
 * 汝窑这套压得最狠，也错过一次：上一版 #64907F 色相 157°，落在绿色那一挂，
 * 看着就是竹青换了个名字。天青的釉色相在 185°–200°，是青里泛蓝、不是泛绿，
 * 所以这版取 193°；饱和度只留 21%（汝窑以釉面失透、不开艳色著称，一饱和就撞回青瓷）。
 * 真釉取样约 #A2C0C4，那种近乎发白的淡青直接当按钮底，白字对比不到 2:1，
 * 字等于浮在一片雾上，故明度往下压到 3.77:1。
 */
val ThemePalettes: List<ThemePalette> = listOf(
    ThemePalette("graphite", "石墨蓝", Color(0xFF4F8CFF), Color(0xFF6BA1FF)),
    ThemePalette("celadon", "青瓷", Color(0xFF1B9E90), Color(0xFF3FBFB0)),
    ThemePalette("iris", "鸢尾", Color(0xFF8A74E8), Color(0xFFA99BF5)),
    ThemePalette("copper", "赤铜", Color(0xFFBE8049), Color(0xFFD9A471)),
    ThemePalette("rouge", "胭脂", Color(0xFFD0597F), Color(0xFFE67EA0)),
    // id 仍是 bamboo：存储键一改，已经选中这套的设备就被 paletteFor 判成旧版本残留、
    // 回落默认色板 —— 用户看到的结果是「我选的色自己没了」。改名字不动键。
    ThemePalette("bamboo", "汝窑", Color(0xFF5F8A96), Color(0xFF93B4BE)),
    ThemePalette("steel", "霜青", Color(0xFF7E93B8), Color(0xFF9FB2D4))
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
