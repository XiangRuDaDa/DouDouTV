package com.chen.doudoutv.core

import android.content.Context
import android.util.Log
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网盘直链的磁盘缓存，直接用 Media3 官方的 [SimpleCache] + LRU 淘汰。
 *
 * 为什么值得开这一层：网盘 CDN 只有「带结尾的固定 Range 块」跑得满，速率还随时会被压到 ~100KB/s。
 * 播放器按 50/60 秒存粮边播边拉，拉到的字节丢掉就再也回不来；落盘之后往回 seek、
 * 重看同一集、看到一半退出后再进来，这些重复字节都不再吃网络。预读器（
 * [com.chen.doudoutv.player.VideoCacheFiller]）负责在播放器不缺数据时往前攒。
 *
 * 坐标系是这里最容易出错的地方，只有一套：**流坐标**。
 * 流坐标 = 播放器眼里的资源，从真实容器开始算第 0 字节；网盘文件里前面那张伪装图不在内。
 * [com.chen.doudoutv.player.StreamSpaceDataSource] 负责在 DataSpec 层做流坐标 ⇄ 文件坐标的平移，
 * 所以缓存的区间、Media3 记账的总长、播放器的 seek 全在同一个坐标系里。
 * （早先试过把平移留在 OkHttp 拦截器里再套 CacheDataSource，两套坐标叠起来直接
 * READ_POSITION_OUT_OF_RANGE，这也是这个仓库以前彻底禁用缓存的原因。）
 *
 * 边界：
 *  - 只有被 [bind] 登记过的直链才走这条路。Emby 直链、m3u8 分片从不登记，
 *    播放链路和现在逐字节一致。
 *  - 容量档关闭时这里一切返回 null / 空，播放回落到纯网络路径。
 *  - 缓存相关的任何异常都只让缓存失效，不外抛给播放链路。
 */
object VideoCache {

    private const val TAG = "VideoCache"
    private const val DIR_NAME = "video-cache"

    private val lock = Any()

    /** 直链 → 缓存 key。电视端播放器实例跨集复用，所以 key 只能按 URL 记，不能按播放器记。 */
    private val bindings = ConcurrentHashMap<String, String>()

    private var appContext: Context? = null
    private var cache: SimpleCache? = null
    private var cacheTierBytes = -1L

    /** 还活着的播放器数量；在用的时候不许 release / 删目录，会把正在读的连接一起掀翻 */
    private val activeUsers = AtomicInteger()

    /** 建播放器时用来判断「这条会话允许走缓存」；真正生效还要 URL 被登记过 */
    val enabled: Boolean
        get() = appContext != null && Prefs.videoCacheTier.maxBytes > 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---------- 直链登记 ----------

    /**
     * 播放某一集前登记「这条直链属于哪个 key」。
     *
     * key 必须是跨会话稳定的：签名直链每次解析都换 token，当 key 用等于永远不命中。
     * 用 `sourceId + remoteId`（remoteId 在库里唯一，TVBox 是 siteKey:vodId:集数）。
     *
     * 表键用原样字符串：Media3 递回来的是 `Uri.toString()`，与这里传进来的直链逐字符相同；
     * 一旦在中间做一遍 URL 规范化，两边就对不上，缓存会静默不生效。
     */
    fun bind(playUrl: String, key: String) {
        if (!enabled || playUrl.isBlank() || key.isBlank()) return
        // m3u8 的清单和分片 URL 每次都变、而且缓存它没有意义，一字节都不落盘
        if (playUrl.contains(".m3u8")) return
        bindings[playUrl] = key
        // 签名地址一次会话一条，只留最近这些，退出播放后残留的旧地址自然不会再被查到
        while (bindings.size > 64) {
            bindings.keys.firstOrNull()?.let { bindings.remove(it) } ?: break
        }
    }

    /** 探测不出流坐标（服务器不认 Range 等）时把这条直链踢回纯网络路径 */
    fun exclude(playUrl: String) {
        bindings.remove(playUrl)
    }

    /** 这条直链要不要走磁盘缓存；返回 null 表示按原来的纯网络路径走 */
    fun keyFor(playUrl: String?): String? {
        if (playUrl == null || !enabled) return null
        return bindings[playUrl]
    }

    // ---------- 缓存实例 ----------

    /**
     * 进程内唯一的 [SimpleCache]；没开容量档返回 null。
     *
     * 构造会打开缓存目录和一张 SQLite 索引表，所以调用方必须在 IO / 加载线程上
     * （由 [com.chen.doudoutv.player.CloudCachePipeline] 在首次取数时才建）。
     * 主线程用 [cacheIfReady]。
     */
    fun cacheOrNull(): Cache? {
        val context = appContext ?: run {
            Log.w(TAG, "拿不到缓存实例：VideoCache 还没 init")
            return null
        }
        val tier = Prefs.videoCacheTier
        if (tier.maxBytes <= 0L) {
            Log.w(TAG, "拿不到缓存实例：容量档是关闭")
            return null
        }
        synchronized(lock) {
            cache?.let { existing ->
                // 档位没变直接用；变了但还有播放器在读就先用旧实例，下次开播再生新的
                if (cacheTierBytes == tier.maxBytes || activeUsers.get() > 0) return existing
            }
            releaseLocked()
            val created = runCatching {
                SimpleCache(File(context.filesDir, DIR_NAME), LeastRecentlyUsedCacheEvictor(tier.maxBytes))
            }.onFailure { Log.w(TAG, "视频缓存初始化失败：${it.message}") }.getOrNull()
            cache = created
            cacheTierBytes = tier.maxBytes
            return created
        }
    }

    /** 已经建好的实例，没有就返回 null —— 主线程只能用它，绝不能顺手开 SQLite */
    fun cacheIfReady(): Cache? = synchronized(lock) { cache }

    /** 播放器创建 / 预读器开工时计一次，释放时减一次；见 [clearAll] */
    fun beginUse() {
        activeUsers.incrementAndGet()
    }

    fun endUse() {
        if (activeUsers.decrementAndGet() < 0) activeUsers.set(0)
    }

    // ---------- 清除与统计 ----------

    /** 看完自动失效；这是容量档敢开大的前提 */
    fun drop(key: String) {
        val target = cacheIfReady() ?: return
        runCatching { target.removeResource(key) }
            .onSuccess { Log.w(TAG, "看完清除缓存 key=$key") }
            .onFailure { Log.w(TAG, "清除缓存失败 key=$key：${it.message}") }
    }

    /**
     * 手动清空。SimpleCache 带着 SQLite 索引，只能整体释放后删目录，不能直接 rm；
     * 正在播放时不能 release（会把正在读的连接一起掀翻），改成逐条删资源。
     */
    fun clearAll(): Long {
        val current = cacheIfReady()
        val freed = current?.let { runCatching { it.cacheSpace }.getOrDefault(dirSize()) } ?: dirSize()
        if (current != null && activeUsers.get() > 0) {
            runCatching {
                current.keys.forEach { runCatching { current.removeResource(it) } }
            }.onFailure { Log.w(TAG, "播放中清空失败：${it.message}") }
        } else {
            synchronized(lock) {
                releaseLocked()
                appContext?.let { runCatching { dir(it).deleteRecursively() } }
            }
        }
        Log.w(TAG, "已清空视频缓存，释放 ${freed / 1048576}MB")
        return freed
    }

    /** 容量档变了：降档（含关闭）清空，升档只重建实例，旧字节继续可用 */
    fun applyTier(newMaxBytes: Long, previousMaxBytes: Long) {
        if (newMaxBytes == previousMaxBytes) return
        if (activeUsers.get() > 0) {
            // 播放中改档位极罕见（设置页和播放器不是一个界面），真碰到了就下次开播生效
            Log.w(TAG, "有播放器在用缓存，新档位下次开播生效")
            return
        }
        if (newMaxBytes < previousMaxBytes) clearAll()
        else synchronized(lock) { releaseLocked() }
    }

    /** 当前占用字节。缓存还没建起来时按目录实测，避免设置页显示 0 而目录里全是货 */
    fun usedBytes(): Long = cacheIfReady()?.let { runCatching { it.cacheSpace }.getOrNull() } ?: dirSize()

    private fun releaseLocked() {
        val current = cache ?: return
        runCatching { current.release() }.onFailure { Log.w(TAG, "释放缓存失败：${it.message}") }
        cache = null
        cacheTierBytes = -1L
    }

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME)

    /** 目录实测（含 SimpleCache 的索引文件），只在缓存未初始化时兜底用 */
    private fun dirSize(): Long {
        val context = appContext ?: return 0L
        val root = dir(context)
        if (!root.isDirectory) return 0L
        return runCatching {
            root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
    }
}
