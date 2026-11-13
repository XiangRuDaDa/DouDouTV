package com.chen.doudoutv.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AppColors {
    /**
     * 详情页取色的静态兜底 —— 固定中性灰，**不跟主题**。
     *
     * 详情页背景由海报取色决定，取不到色时才落到这里；它不接主题是有意的：
     * 换主题色不该把某一部片的详情页底色一起换掉。所以默认色板换成 Netflix 红之后
     * 这两个值仍留灰，不跟着变。
     */
    val BrandBase = Color(0xFF8E8E93)
    val BrandSoftBase = Color(0xFFAEAEB4)

    /** 品牌色：跟随设置页选的主题色板，只在组合式上下文里可读 */
    val Brand: Color
        @Composable get() = LocalPalette.current.brand

    /** 品牌浅色：用于品牌色上的文字与弱强调 */
    val BrandSoft: Color
        @Composable get() = LocalPalette.current.brandSoft

    /**
     * 材质四层里的 L0 —— 页面底。
     *
     * 这一整套中性色原来带蓝调（B 通道比 R 高 6~23），铺开就是「蓝黑」：
     * 蓝底配任何品牌色相都像两套系统焊在一起。
     * 2026-11-10 起四层全部换成纯中性灰，**明度一档没动** ——
     * 对比度基线原样保留（正文在页面底上仍是 18.2:1），变的只是色相：
     * 从「冷蓝」拉回「无色相」。
     * 引用这个常量的地方（背景层、大图渐隐、详情页 scrim）会一起跟上，不会出现两套底色。
     */
    val Bg = Color(0xFF080808)

    /** L1 —— 分区容器、分组面板、图片占位 */
    val Surface = Color(0xFF131313)

    /** L2 —— 卡片、列表行、输入控件 */
    val SurfaceHi = Color(0xFF1F1F1F)

    /** L3 —— 焦点/抬起态的填充，十英尺下靠这一层区分「选中」和「没选中」 */
    val SurfaceRaise = Color(0xFF2C2C2C)

    /** 文字三级：白 → 灰。同样去蓝调，明度未动 */
    val TextPrimary = Color(0xFFF4F4F4)
    val TextSecondary = Color(0xFFA3A3A3)
    val TextTertiary = Color(0xFF747474)
    val Rating = Color(0xFFFFC107)

    /**
     * 语义色三档：危险 / 警告 / 正常。
     *
     * 这三个颜色原来在各个页面里各写一遍字面量（危险红 8 处、警告橙 6 处），
     * 改一个语义要翻六七个文件，而且新页面很容易漏写又造一个近似值。
     * 这里收口成令牌：状态类颜色是「语义」，不属于用户可换的品牌色板，
     * 所以不跟随主题色变，也就不进 [ThemePalette]。
     *
     * 取值沿用页面上已经在用的那三档，改成令牌不改观感：
     * 危险红在 Bg 上对比度 5.12:1，警告橙 8.66:1，正常绿在 Bg 上约 10:1，都过 AA。
     */
    val Danger = Color(0xFFE5484D)

    /** 警告橙：登录失效这类「还能用但不对」的状态 */
    val Warn = Color(0xFFFF8A65)

    /** 正常绿：在线、启用、同步成功 */
    val Ok = Color(0xFF34C759)

    /** 已停用这类中性弱状态（原先散落在各处的灰色字面量） */
    val Disabled = Color(0xFF747474)

    /**
     * 详情页顶栏 scrim 的底色。
     *
     * 这里要的是「和屏幕底色完全一致」，不是一个会随主题漂移的语义品牌色，
     * 所以详情页直接用它、绕开 MaterialTheme；详情页自己有一套从海报取色的配色体系。
     * 和 [Bg] 取同一个值 —— 底色调了这里必须跟着调，否则接缝重新露出来。
     */
    val ScrimTop = Bg
}

/**
 * 电视端文字色阶 —— 参考 Netflix：整屏只用「白 → 灰」一条轴拉开层级，
 * 正文、元信息、辅助提示靠不透明度分级，不引入第二种色相；
 * 品牌色只留给按钮和进度条这类「可操作 / 进行中」的元素。
 *
 * 数值取 Netflix TV 的量级：正文约 70%，元信息约 60%，辅助约 45%。
 * 统一走这里的档位，不要在页面里再手写 Color.White.copy(alpha = …)。
 */
object TvText {
    /** Hero 片名、按钮文字 */
    val Title = Color(0xFFFFFFFF)

    /** 分区标题、卡片标题 */
    val Section = Color(0xE0FFFFFF)

    /** 简介正文、次按钮文字、返回按钮 */
    val Body = Color(0xB3FFFFFF)

    /** 元信息、空状态的补充说明 */
    val Meta = Color(0x99FFFFFF)

    /**
     * 计数、时间戳这类可有可无的信息。
     *
     * 不透明度从 0x73 提到 0x80（50% 白）。0x73 在页面底 Bg 上是 4.50:1，
     * 但落在卡片那两层（SurfaceHi 4.39 / SurfaceRaise 4.09）达不到 WCAG AA 的 4.5:1；
     * 0x80 在这四个表面上分别是 5.35 / 5.33 / 5.11 / 4.70，全部过线，
     * 且和 Meta(0x99) 仍差出一档，层级不会塌掉。
     */
    val Muted = Color(0x80FFFFFF)

    /** 分隔点、极弱提示 */
    val Hairline = Color(0x40FFFFFF)
}

/**
 * Material3 配色表。
 *
 * 原来是一个顶层 val：Compose 的 object 初始化只跑一次，色板换了它也拿不到新值，
 * 所以改成按色板现算。传进来的是色板而不是直接读状态，让「谁订阅了主题」这件事
 * 只发生在 DouDouTvTheme 那一处，下面整棵树都靠 CompositionLocal 各自订阅。
 */
private fun schemeFor(palette: ThemePalette) = darkColorScheme(
    primary = palette.brand,
    onPrimary = Color.White,
    primaryContainer = palette.brandSoft,
    secondary = palette.brandSoft,
    background = AppColors.Bg,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceHi,
    onSurfaceVariant = AppColors.TextSecondary
)

/**
 * 手机端字号表。这里列出的档位是唯一真相：没写进来的档位会静默回落到 Material3 默认值，
 * 曾经就是因此让电视端标题拿到了 57sp/Regular 的默认 displayLarge。
 *
 * 带数字的档位统一加 `tnum`（等宽数字）：进度「01:24 / 45:00」、评分、计数这类值一变，
 * 比例数字的字宽就跟着抖，行宽跳来跳去。系统字体不支持这个特性时它是空操作，不会报错。
 */
private const val TabularDigits = "tnum"

private val MobileTypography = Typography(
    displayLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, fontFeatureSettings = TabularDigits),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TabularDigits),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularDigits),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularDigits),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularDigits)
)

/**
 * 电视端字号表：尺度参考 Netflix TV 客户端。
 *
 * Netflix 在电视上没有把标题堆到「海报级」的大字，而是靠层级差和行高拉开重点：
 *   · Hero 片名约 44sp、SemiBold —— 粗黑体在远距观看下反而发糊，Medium/SemiBold 更干净；
 *   · 行标题 20sp、正文 17sp，正文行高约 1.45 倍（25sp）—— 大段简介靠行呼吸而不是靠字号；
 *   · 最小的一档也保住 12sp：三米外低于这个值就只能看个形状。
 * 这里列出的档位是唯一真相：没写进来的档位会静默回落到 Material3 默认值。
 */
private val TvTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 44.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 52.sp,
        letterSpacing = (-0.4).sp
    ),
    displayMedium = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 42.sp,
        letterSpacing = (-0.3).sp
    ),
    displaySmall = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 31.sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp, fontFeatureSettings = TabularDigits),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp, fontFeatureSettings = TabularDigits),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, fontFeatureSettings = TabularDigits),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, fontFeatureSettings = TabularDigits),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, fontFeatureSettings = TabularDigits)
)

fun Context.isTelevision(): Boolean {
    val manager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

    // 兜底：电视盒子常以普通模式上报，用 Leanback 特性/无触摸屏来识别
    val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return hasLeanback || !hasTouchscreen
}

@Composable
fun DouDouTvTheme(isTv: Boolean = false, content: @Composable () -> Unit) {
    val palette = AppPalette.current
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalTvMode provides isTv
    ) {
        MaterialTheme(
            colorScheme = schemeFor(palette),
            typography = if (isTv) TvTypography else MobileTypography,
            content = content
        )
    }
}
