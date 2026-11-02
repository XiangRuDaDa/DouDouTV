package com.tabbit.myvidhub.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角三档 + 胶囊，取代之前散在 19 个文件里的 15 种半径。
 *
 * 分档按「容器的角色」而不是嵌套深度：
 *   [Sm] 徽章、小标签、套在别人卡片里的内行（半径必须小于父容器，否则同层级看不出嵌套）
 *   [Md] 海报卡、列表行、输入框、分组面板
 *   [Lg] 对话框、底部弹层
 *   [Pill] 胶囊按钮、进度条、圆点指示器、chip
 *
 * 用 `RoundedCornerShape(percent = 50)` 而不是 999.dp：百分比形式对任何高度都是标准胶囊，
 * 不会在高瘦元素上变成「半个圆」以外的高度依赖结果，也省掉为不同高度调半径。
 */
object Radius {
    val Sm: Shape = RoundedCornerShape(6.dp)
    val Md: Shape = RoundedCornerShape(12.dp)
    val Lg: Shape = RoundedCornerShape(20.dp)
    val Pill: Shape = RoundedCornerShape(percent = 50)
}

/**
 * 描边层 —— 之前整个界面一条 1dp 亮边都没有，卡片和背景全靠色差别开，
 * 在近黑底色上这点色差几乎读不出来，于是所有元素都像贴在同一张纸上。
 *
 * [Hairline] 给静止态的卡片/容器勾边；[HairlineStrong] 给抬起态和输入控件。
 * 两者都是白的低透明度而不是固定灰：勾边的作用是「描出边缘」，
 * 跟着底色亮度走才能在 L1/L2/L3 任意一层都刚好看得见。
 */
object Edge {
    val Hairline: Color = Color(0x14FFFFFF)
    val HairlineStrong: Color = Color(0x2BFFFFFF)
}

/**
 * 抬起高度（dp）。真正给投影只放在会动的元素上。
 *
 * 静止的海报卡不给投影、只给描边是有意的：一屏七十张卡各挂一层投影就是七十多个
 * graphicsLayer，弱盒子的滚动帧时间吃不消（和 TV 端刻意关掉海报淡入是同一类取舍）。
 * 投影留给焦点态和对话框这类同屏只出现一两个的地方。
 */
object Lift {
    /** 焦点卡片 */
    val Focus = 14.dp

    /** 对话框 / 弹层 */
    val Overlay = 24.dp
}

/**
 * 动效时长与曲线。之前 tween 的毫秒数有 11 种（90/140/150/160/220/260/300/320/600/900），
 * 数值彼此只差十几毫秒，观感上不成节奏。收成三档：
 *
 *   [FAST] 120 —— 状态翻转（按压、焦点、显隐），要跟上手指
 *   [BASE] 200 —— 位移、淡入淡出，日常过渡的默认值
 *   [SLOW] 320 —— 页面级转场、弹层进出
 *
 * 刻意保留在 token 之外的几处不属于「过渡」：轮播 460ms 驻留淡入淡出、Coil 图片淡入、
 * shimmer 900ms 光扫，那些是内容节奏，纳进三档反而会被误当成可调参数。
 */
object Motion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 320

    /** 标准减速曲线：起步快、收尾慢，比 EaseOut 更贴「东西停下了」的手感 */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
