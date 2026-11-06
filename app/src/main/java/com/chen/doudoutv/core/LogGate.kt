package com.chen.doudoutv.core

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * 日志脱敏门。
 *
 * 解析后的播放直链自带一次性签名（网盘的 sign / ptoken / Cookie 派生 token、Emby 的 api_key），
 * 完整 URL 落到 logcat 里，等于任何拿到日志的人都能直接播走这个资源。
 * 但这些地址又正是排查「转圈不出画面 / 404」时唯一能对比的证据，所以不能一刀切删掉：
 * 按构建类型分——debug 包（含 CI 出的 assembleDebug 产物）保留全文，正式包只留摘要。
 */
object LogGate {

    /** 当前进程是否可调试，即是不是 debug 包 */
    fun debuggable(context: Context?): Boolean =
        context != null &&
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 打进日志的地址。
     *
     * 正式包只留 host 与路径尾，查询串（签名参数都在 query 里）不进日志。
     * spider 返回的直链不保证是合法 URL，所以按文本切而不是用 HttpUrl 解析。
     */
    fun url(context: Context?, href: String): String =
        if (debuggable(context)) {
            "url=$href"
        } else {
            val withoutQuery = href.substringBefore('?')
            val host = withoutQuery.substringAfter("://", withoutQuery).substringBefore('/')
            "host=$host …${withoutQuery.takeLast(20)}（签名参数已隐去）"
        }
}
