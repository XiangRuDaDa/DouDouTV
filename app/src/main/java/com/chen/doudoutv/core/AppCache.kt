package com.chen.doudoutv.core

import android.content.Context
import coil.Coil
import java.io.File

/**
 * 应用里真正会落盘、也真正能被清掉的那部分缓存。
 *
 * 这文件取代了原来挂在 [com.chen.doudoutv.player.PlayerFactory] 上的「播放缓存」口径：
 * 当年播放器禁用了 CacheDataSource（OkHttp 的 Range 拦截与 SimpleCache 的片段记账不兼容），
 * SimpleCache 从未被创建，`media_cache` 目录也从未被写过，设置页那行「当前占用」一直是 0 KB。
 * 磁盘实测下来唯一稳定长大的部分是 Coil 的封面磁盘缓存（手机上 image_cache 260MB /
 * 整个 cacheDir 286MB），所以面板改按这个口径统计。
 *
 * 现在网盘直链那一份视频缓存另有心跳，见 [VideoCache]（`files/video-cache`，带 SQLite 索引，
 * 容量档与清空都在设置页的「视频缓存」卡片上），不并入这里：两者的清除路径不能共用 ——
 * 视频缓存必须整体 release 再删目录，图片缓存必须走 Coil 自己的 clear。
 * 这里继续只清 `media_cache`：那是老版本 SimpleCache 的目录名，存量机器上可能还有残留字节。
 *
 * **不参与清除**的部分，删了是坏功能而不是省空间：
 * - `cacheDir/spiders`、`cacheDir/spider_opt`、`cacheDir/danMugo_*.so`：TVBox 壳和解出的 so，
 *   删掉要重新下载并重跑 Init（实测约 24 秒），期间网盘线路整体不可用；
 * - `files/AiWex`、`files/Pizazz`、`files/ali_*`、`files/quark_ut`：网盘授权与登录态；
 * - `databases`、`shared_prefs`：观看进度、收藏与设置。
 */
object AppCache {

    /** 老版本 SimpleCache 用的目录名，现在只在个别存量机器上可能还有残留 */
    private const val LEGACY_DIR = "media_cache"

    private fun legacyDir(context: Context) = File(context.cacheDir, LEGACY_DIR)

    private fun dirSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
    }

    /** 可清除缓存的当前体积：图片磁盘缓存 + 老版本残留的播放缓存目录 */
    fun sizeBytes(context: Context): Long {
        val appContext = context.applicationContext
        val imageCache = runCatching { Coil.imageLoader(appContext).diskCache?.size }.getOrNull() ?: 0L
        return imageCache + dirSize(legacyDir(appContext))
    }

    /**
     * 清除可清除的部分。
     *
     * 走 Coil 自己的 `diskCache.clear()` 而不是删目录：磁盘缓存带一个 journal 库，
     * 直接 rm 目录会让正在跑的 ImageLoader 持有失效句柄。
     * Coil 也明确要求 `clear()` 不能在 UI 线程调，调用方要自己包 IO。
     *
     * @return 实际释放的字节数
     */
    fun clear(context: Context): Long {
        val appContext = context.applicationContext
        val before = sizeBytes(appContext)
        runCatching { Coil.imageLoader(appContext).diskCache?.clear() }
        runCatching { legacyDir(appContext).deleteRecursively() }
        return (before - sizeBytes(appContext)).coerceAtLeast(0L)
    }
}
