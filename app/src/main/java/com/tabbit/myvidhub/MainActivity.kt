package com.tabbit.myvidhub

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyVidHubTheme {
                val context = LocalContext.current
                // 判定只发生在最外层一次：系统 uiMode 为主，设置里的强制开关为辅，
                // 并对部分国产盒子 uiMode 上报不准的情况做了无触摸屏兜底。
                val isTv = remember { context.isTelevision() || context.forceTvMode() }
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
     */
    override fun finish() {
        Log.e("MyVidHubNav", "MainActivity.finish() 被调用\n" + Log.getStackTraceString(Throwable()))
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
