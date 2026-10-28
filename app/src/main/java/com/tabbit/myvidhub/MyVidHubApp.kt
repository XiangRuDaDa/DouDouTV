package com.tabbit.myvidhub

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.db.AppDatabase
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy

class MyVidHubApp : Application(), ImageLoaderFactory {

    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)

        // 记录未捕获异常：MIUI 有时会吞掉 crash 缓冲区日志，
        // 这里额外写一份到应用私有目录，方便排查"闪退但没有崩溃日志"的问题。
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Log.e("MyVidHubCrash", "uncaught on ${thread.name}", error)
                val file = java.io.File(filesDir, "crash_last.txt")
                file.writeText(
                    "time=${System.currentTimeMillis()}\nthread=${thread.name}\n" +
                        Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }

        // 启动时判定刮削方案：总开关开启且令牌非空才走 TMDB，否则沿用原有方案。
        // 这里只做本地判断，不发网络请求，所以不会拖慢冷启动。
        val engine = ScrapeStrategy.refresh()
        Log.i(
            "MyVidHub",
            "刮削方案：${engine.name} / ${ScrapeStrategy.describe()}" +
                "（TMDB 开关${if (Prefs.tmdbEnabled) "开" else "关"}，" +
                "令牌${if (Prefs.tmdbConfigured) "已填" else "未填"}）"
        )
    }

    /**
     * Coil 全局 ImageLoader：开启淡入，
     * 让「先出占位、再到清晰图」的切换更平滑，不闪白也不闪黑。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .crossfade(220)
            .build()

    companion object {
        lateinit var instance: MyVidHubApp
            private set
    }
}
