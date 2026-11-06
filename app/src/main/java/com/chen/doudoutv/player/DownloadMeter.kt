package com.chen.doudoutv.player

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局下载字节计数器：只累计真正从网卡读进来的字节。
 *
 * 为什么自己数而不用 Media3 自带的 DefaultBandwidthMeter：
 * 这个仓库的数据源链是手工搭的（OkHttpDataSource → VideoCacheDataSource → DefaultMediaSourceFactory），
 * 而 `setBandwidthMeter()` 那条自动把 meter 挂成 TransferListener 的路径只有在用
 * `MediaSourceFactory` 默认构造时才生效。挂不上，`onBandwidthEstimate` 就一次也不回调，
 * 屏幕上那个「下载 xx KB/s」永远是 0 —— 只有 seek 时播放器自己算的那一下才偶然蹦出数字。
 *
 * 计数点选在 OkHttp 的最后一个应用拦截器上（见 [PlayerFactory]）：
 *  - 磁盘缓存命中根本不发起请求，天然不进账；
 *  - 预读器（[VideoCacheFiller]）和播放器自取共用同一个 client，两条路的字节都算，
 *    因为这本来就是同一次真下载；
 *  - Range 改写、整文件 200 改道重试那些分支的响应同样出自这里，一条不漏；没被读走的
 *    响应体一个字节也不计，所以也不会重复计数。
 *
 * 值单调递增，读取方自己取差分。
 */
object DownloadMeter {
    private val bytes = AtomicLong(0L)

    /** 进程启动以来累计的网络下载字节 */
    val totalBytes: Long
        get() = bytes.get()

    fun add(count: Long) {
        if (count > 0L) bytes.addAndGet(count)
    }
}
