package com.github.catvod.crawler

import android.util.Log

/**
 * TVBox 爬虫调试工具类（宿主 App 提供）。
 *
 * 部分加密壳解密出的真实 spider 实现（如 AiNewWogg 等）会引用
 * com.github.catvod.crawler.SpiderDebug，若宿主 App 不提供该类，
 * 真实 spider 的 homeContent 等方法会抛 NoClassDefFoundError 导致加载失败。
 * 方法签名与 CatVod 标准 SpiderDebug 一致。
 */
class SpiderDebug {

    companion object {
        private const val TAG = "SpiderDebug"

        @JvmStatic
        fun log(msg: String) {
            Log.i(TAG, msg)
        }

        @JvmStatic
        fun log(t: Throwable) {
            Log.e(TAG, Log.getStackTraceString(t))
        }

        @JvmStatic
        fun error(msg: String) {
            Log.e(TAG, msg)
        }
    }
}
