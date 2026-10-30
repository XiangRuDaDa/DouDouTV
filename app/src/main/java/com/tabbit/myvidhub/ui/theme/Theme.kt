package com.tabbit.myvidhub.ui.theme

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
     * 品牌色的静态基准值 —— 与默认色板同色。
     *
     * 保留它是为了那些不在组合式上下文里的用法（详情页取色的兜底分支），
     * 那些地方拿不到 CompositionLocal，也不需要跟随主题。
     */
    val BrandBase = Color(0xFF4F8CFF)
    val BrandSoftBase = Color(0xFF6BA1FF)

    /** 品牌色：跟随设置页选的主题色板，只在组合式上下文里可读 */
    val Brand: Color
        @Composable get() = LocalPalette.current.brand

    /** 品牌浅色：用于品牌色上的文字与弱强调 */
    val BrandSoft: Color
        @Composable get() = LocalPalette.current.brandSoft

    val Bg = Color(0xFF0B0D12)
    val Surface = Color(0xFF141822)
    val SurfaceHi = Color(0xFF1E2430)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFF9BA4B4)
    val TextTertiary = Color(0xFF6B7482)
    val Rating = Color(0xFFFFC107)

    /**
     * 详情页顶栏 scrim 的底色。
     *
     * 这里要的是「和屏幕底色完全一致」，不是一个会随主题漂移的语义品牌色，
     * 所以详情页直接用它、绕开 MaterialTheme；详情页自己有一套从海报取色的配色体系。
     */
    val ScrimTop = Color(0xFF0B0D12)
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

    /** 计数、时间戳这类可有可无的信息 */
    val Muted = Color(0x73FFFFFF)

    /** 分隔点、极弱提示 */
    val Hairline = Color(0x40FFFFFF)
}

/**
 * Material3 配色表。
 *
 * 原来是一个顶层 val：Compose 的 object 初始化只跑一次，色板换了它也拿不到新值，
 * 所以改成按色板现算。传进来的是色板而不是直接读状态，让「谁订阅了主题」这件事
 * 只发生在 MyVidHubTheme 那一处，下面整棵树都靠 CompositionLocal 各自订阅。
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
 */
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
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
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
fun MyVidHubTheme(isTv: Boolean = false, content: @Composable () -> Unit) {
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
