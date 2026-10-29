package com.tabbit.myvidhub

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.phone.PhoneApp
import com.tabbit.myvidhub.ui.settings.forceTvMode
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import com.tabbit.myvidhub.ui.theme.isTelevision
import com.tabbit.myvidhub.ui.tv.TvApp

/**
 * 唯一的 Activity 入口，只做一件事：判断这是不是电视，然后交给对应的一套实现。
 *
 * 手机端入口是 ui/phone/PhoneApp.kt，电视端入口是 ui/tv/TvApp.kt，
 * 两边各自拥有独立的导航结构、页面、组件与播放器，不通过 isTv 参数在同屏里分叉。
 * 这样改手机端界面不会牵连电视端，反之亦然。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * 爬虫壳触发原地重建的节流窗口（静态）：
         * recreate() 会产生新的 Activity 实例，实例字段会被重置，所以节流状态必须放静态。
         * 10 秒内超过 3 次说明重建后又被爬虫反复关，此时放行 finish 避免死循环。
         */
        private var spiderRestartWindowStart = 0L
        private var spiderRestartCount = 0

        /**
         * 当前是否停留在详情页（手机/TV 共用）。
         * 爬虫壳在拉取详情（网盘线路）时会顺手 finish 宿主 Activity，
         * 这个期间把 finish() 吞掉，避免「点详情直接闪退」。
         */
        @Volatile
        var detailVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 判定只发生在最外层一次：系统 uiMode 为主，设置里的强制开关为辅，
            // 并对部分国产盒子 uiMode 上报不准的情况做了无触摸屏兜底。
            // 字号表也依赖它：电视端要换整套放大过的 typography，所以判定必须在主题外层。
            val isTv = remember { isTelevision() || forceTvMode() }
            MyVidHubTheme(isTv = isTv) {
                val vm: LibraryViewModel = viewModel()

                if (isTv) {
                    TvApp(vm = vm)
                } else {
                    PhoneApp(vm = vm)
                }
            }
        }
    }

    /**
     * 诊断日志：偶发「点搜索结果后直接回到桌面」时，用来确认是谁主动结束了本 Activity。
     * 正常业务里没有任何地方调用 finish()，如果真的被调用，这里的堆栈能直接指出调用方。
     *
     * 现已确认调用方是内嵌的 catvod 爬虫壳（云盘/网盘配置流程）：
     * 它打开配置项后会直接 finish 宿主 Activity，靠「宿主重启」让新的配置/二维码界面生效，
     * 表现就是点「网盘配置 / 扫码登录」后回到桌面、进程却还活着，重开才看到二维码。
     *
     * 这里把它拦下来，改用 recreate() 原地重建 MainActivity：
     *   · 任务栈不变，不会闪回桌面；
     *   · 对爬虫壳来说等价于「宿主重启了一次」，它的二维码/配置界面会在重建后弹出。
     * 注意不能用 startActivity 重启自己：本 Activity 是 singleTask，
     * 那样只会给旧实例发 onNewIntent，任务栈清空后照样闪桌面。
     */
    override fun finish() {
        val stack = Log.getStackTraceString(Throwable())
        Log.e("MyVidHubNav", "MainActivity.finish() 被调用\n$stack")

        // 详情页/播放页期间一律忽略 finish()：
        // 内嵌爬虫壳在拉取详情（尤其是网盘线路）时会顺手结束宿主 Activity，
        // 之前用 recreate() 兜底，结果一进详情就被打回首页，表现就是「点详情直接闪退」。
        // 这里直接吞掉，页面保持不动；进程也不会退出。
        if (detailVisible) {
            Log.e("MyVidHubNav", "详情页期间拦截 finish()，保持当前页面")
            return
        }

        if (stack.contains("com.github.catvod.spider")) {
            val now = SystemClock.elapsedRealtime()
            if (now - spiderRestartWindowStart > 10_000L) {
                spiderRestartWindowStart = now
                spiderRestartCount = 0
            }
            spiderRestartCount++
            if (spiderRestartCount <= 3) {
                Log.e("MyVidHubNav", "拦截爬虫壳的 finish()，recreate() 原地重建 MainActivity")
                recreate()
                return
            }
            Log.e("MyVidHubNav", "爬虫壳连续触发 finish() 超过 3 次，放行以免无限重建")
        }
        super.finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d("MyVidHubNav", "onUserLeaveHint（用户按了 Home / 切换了应用）")
    }

    override fun onPause() {
        super.onPause()
        Log.d("MyVidHubNav", "onPause isFinishing=$isFinishing")
    }

    override fun onStop() {
        super.onStop()
        Log.d("MyVidHubNav", "onStop isFinishing=$isFinishing")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(
            "MyVidHubNav",
            "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations"
        )
    }
}
