package com.chen.doudoutv.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.core.VideoCache
import java.io.IOException

/**
 * 磁盘缓存的预读器：趁播放器不缺数据的时候，把这条直链往后的字节提前拉下来落盘。
 *
 * 全程只用流坐标（播放器眼里的资源，真实容器起算第 0 字节），和 [StreamSpaceDataSource]
 * 同一套 —— 交给 CacheDataSource 的 DataSpec 也是流坐标，落盘的区间才对得上。
 *
 * 让路规则（和「别把卡顿弄得更糟」是同一件事）：
 *  - 播放器重新开口要数据（[onPlayerMiss]）→ 打断在飞的预读，已落盘的半块照样算数；
 *  - 播放器进 BUFFERING（[busy]）→ 不开新块，等它吃饱；
 *  - 用户暂停、停在片尾菜单 → 播放器不再拉，预读全速跑。
 * 网盘线路本来就跑不满富余，所以这一份缓存主要攒在「播放器吃饱之后的间隙」和暂停时间里；
 * 真遇到限速，它就是什么都不攒 —— 静默降级，不影响播放。
 *
 * 一次播放一个实例，一条线程，一块在飞；换集时 URL 变了就停旧起新。
 */
@UnstableApi
internal class VideoCacheFiller(
    private val pipeline: CloudCachePipeline,
    private val chunkBytes: Long,
    /** 播放器自己是否正在吃带宽 */
    private val busy: () -> Boolean
) {

    private class Session(val url: String, val key: String) {
        /** 播放器最近一次读到的流位置（开口位 + 已交付字节）；预读游标从它后面两个分块起攒 */
        @Volatile
        var playerPos = 0L

        @Volatile
        var stop = false
    }

    private val lock = Any()

    @Volatile
    private var cancelled = false

    private var worker: Thread? = null

    @Volatile
    private var current: Session? = null

    /** 已经收工（或看完作废）的 key，别再为它重开线程 */
    private var retired: String? = null

    /** 让路日志的全局节流时刻，见 [waitForIdle] */
    @Volatile
    private var lastYieldLogAt = 0L

    /**
     * 播放器每开一次口调一次（由 [VideoCacheDataSource] 转过来）。
     * 没登记缓存 key 的直链（Emby、m3u8）压根走不到这里。
     */
    fun onPlayerMiss(playUrl: String, streamPos: Long) {
        if (cancelled) return
        val key = VideoCache.keyFor(playUrl) ?: return
        synchronized(lock) {
            val existing = current
            if (existing != null && existing.url == playUrl) {
                existing.playerPos = streamPos
                // 播放器重新开口 ≠ 它缺数据（读片头尾的 moov 也会重开），所以这里不打断在飞的块；
                // 真让路只在 STATE_BUFFERING 时由 [onPlayerBuffering] 发
                return
            }
            if (retired == key) {
                Log.w("VideoCacheFill", "本集预读此前已收工，不再重开 key=$key")
                return
            }
            existing?.stop = true
            worker?.interrupt()
            val session = Session(playUrl, key)
            session.playerPos = streamPos
            current = session
            val thread = Thread({ run(session) }, "VideoCacheFill").apply { isDaemon = true }
            worker = thread
            thread.start()
        }
    }

    /**
     * 播放器每读一段调一次（由 [VideoCacheDataSource] 转过来）—— 只更新已有会话的游标。
     *
     * 一次 open 之后加载器会连着读掉几十个分块才重新开口，只盯 open 位的话，
     * 播放器都跑到 800MB 了游标还停在 490MB，预读就变成在它屁股后面反复读盘。
     * 读回调永远晚于开口，所以这里绝不新建会话，免得给 Emby/m3u8 之外的路径惹出新线程。
     */
    fun onPlayerRead(playUrl: String, streamPos: Long) {
        val session = current ?: return
        if (session.url == playUrl) session.playerPos = streamPos
    }

    /**
     * 播放器进了 BUFFERING：这才是真的缺数据，把在飞的预读块砍下来给它让路。
     * 已经落盘的半块照样算数（close 时提交）。
     */
    fun onPlayerBuffering() {
        worker?.interrupt()
    }

    /**
     * 看完：这一集的缓存马上作废，预读没有意义了。
     *
     * 删除排在预读线程收手之后 —— 半块提交和整条删资源抢的是同一个 key，
     * 先删后提交会把孤儿字节重新写回缓存。播放器回调在主线程，所以等待和删除都另起线程。
     */
    fun onItemEnded(playUrl: String) {
        val key = VideoCache.keyFor(playUrl) ?: return
        val stopping: Thread?
        synchronized(lock) {
            Log.w("VideoCacheFill", "看完作废缓存，预读收手 key=$key")
            retired = key
            current?.takeIf { it.key == key }?.let { it.stop = true }
            stopping = worker
            worker = null
            current = null
        }
        stopping?.interrupt()
        Thread {
            runCatching { stopping?.join(2_000) }
            VideoCache.drop(key)
        }.start()
    }

    /** 播放器释放：停线程；让路时留在盘上的字节仍然可给同一条资源复用 */
    fun cancel() {
        cancelled = true
        Log.w("VideoCacheFill", "播放器释放，预读整体收手")
        synchronized(lock) {
            current?.stop = true
            worker?.interrupt()
            worker = null
            current = null
        }
    }

    private fun run(session: Session) {
        val startedAt = SystemClock.elapsedRealtime()
        var blocks = 0
        var idleRounds = 0
        // 等总长按时间算，不按轮次算 —— 播放器每开一次口就 interrupt 我们一次，
        // 轮次会在毫秒内烧光，又变成「静默收工」
        var noLengthSince = 0L
        var nextNoLengthAnnounceAt = HEARTBEAT_MS
        Log.w("VideoCacheFill", "预读开始 档=${Prefs.videoCacheTier.label} 块=${chunkBytes / 1048576}MB")
        try {
            while (!cancelled && !session.stop) {
                // 上一轮让路留下的中断标记会立刻把 SimpleCache 的等锁打断，先清掉
                Thread.interrupted()
                if (!VideoCache.enabled) {
                    leave("容量档已关闭", startedAt, blocks)
                    return
                }
                val bundle = runCatching { pipeline.bundleOrNull() }.getOrNull()
                if (bundle == null) {
                    leave(
                        "拿不到缓存实例（enabled=${VideoCache.enabled} 档=${Prefs.videoCacheTier.label}）",
                        startedAt,
                        blocks,
                    )
                    return
                }
                val cache = bundle.cache
                val total = contentLength(cache, session.key)
                if (total == C.LENGTH_UNSET.toLong()) {
                    // 播放器还没写下本集总长（它正在开口，马上就写），等一会儿再说
                    val now = SystemClock.elapsedRealtime()
                    if (noLengthSince == 0L) noLengthSince = now
                    val waited = now - noLengthSince
                    if (waited > MAX_NO_LENGTH_MS) {
                        retire(session, "等不到本集总长（等了 ${waited}ms），收工")
                        return
                    }
                    if (waited >= nextNoLengthAnnounceAt) {
                        nextNoLengthAnnounceAt += HEARTBEAT_MS
                        Log.w(
                            "VideoCacheFill",
                            "预读等待：等播放器写下本集总长 已=${waited}ms " +
                                "占用=${usedBytesSafely(cache) / 1048576}MB"
                        )
                    }
                    sleepQuietly(session)
                    continue
                }
                noLengthSince = 0L
                nextNoLengthAnnounceAt = HEARTBEAT_MS
                if (busy() && !waitForIdle(session)) {
                    // 播放器重新开口会 interrupt 我们，那一记中断只是「让路」，不是收工指令；
                    // 只有 cancel()/换集/看完（cancelled、stop）才是真的不要了
                    if (cancelled || session.stop) break
                    continue
                }
                // 播放器正在下的那一块由它自己落盘，预读往后挪一格去够它还没要的那一段；
                // 紧贴着 playerPos 的话，两边抢同一段，预读只能等锁然后把播放器刚写的字节再读一遍
                val posAtPlan = session.playerPos
                val pos = (posAtPlan + AHEAD_CHUNKS * chunkBytes).coerceIn(0L, total)
                // 只跳「从 pos 起连续已缓存」的那一段。以前用的是 getCachedBytes，
                // 那是「窗口内已缓存的总字节数」：拿它当跳过量会一步跨过中间没缓存的洞，
                // 洞永远补不上，而且落点还在下一段已缓存里 —— 预读于是整块整块地读盘。
                val cachedRun = runCatching { cache.getCachedLength(session.key, pos, total - pos) }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
                val cursor = (pos + cachedRun).coerceAtMost(total)
                if (cursor >= total) {
                    retire(session, "尾段已全部缓存 流长=${total / 1048576}MB")
                    return
                }
                val fetchStartedAt = SystemClock.elapsedRealtime()
                val written = fetchOne(session, bundle, cursor, minOf(chunkBytes, total - cursor))
                if (written == null) continue
                if (written <= 0L) {
                    if (++idleRounds >= MAX_IDLE_ROUNDS) {
                        retire(session, "连续 $MAX_IDLE_ROUNDS 轮拉不到字节，停手")
                        return
                    }
                    sleepQuietly(session)
                    continue
                }
                idleRounds = 0
                blocks++
                val cost = SystemClock.elapsedRealtime() - fetchStartedAt
                Log.w(
                    "VideoCacheFill",
                    "预读 +${written / 1024}KB 到=${(cursor + written) / 1048576}MB/${total / 1048576}MB " +
                        "用时=${cost}ms 速率=${if (cost > 0) written / 1024 * 1000 / cost else 0}KB/s " +
                        "档=${Prefs.videoCacheTier.label} 占用=${usedBytesSafely(cache) / 1048576}MB " +
                        "播位=${posAtPlan / 1048576}MB 计划位=${pos / 1048576}MB " +
                        "连续已缓存=${cachedRun / 1048576}MB 起读=${cursor / 1048576}MB"
                )
            }
            leave("被砍掉（cancelled=$cancelled stop=${session.stop}）", startedAt, blocks)
        } catch (t: Throwable) {
            Log.w(
                "VideoCacheFill",
                "预读线程异常退出：${t.javaClass.simpleName} ${t.message} 存活=" +
                    "${SystemClock.elapsedRealtime() - startedAt}ms\n${Log.getStackTraceString(t)}"
            )
        }
    }

    /** 线程正常收手的所有出口都在这里留一条，免得又只剩「预读开始」一句 */
    private fun leave(reason: String, startedAt: Long, blocks: Int) {
        Log.w(
            "VideoCacheFill",
            "预读线程收手：$reason 完成块数=$blocks 存活=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
    }

    /**
     * 拉一块并落盘。
     * 返回 null 表示这一轮是被让路/换集/收尾砍掉的（不算失败），否则返回真正落盘的字节数。
     */
    private fun fetchOne(
        session: Session,
        bundle: CloudCachePipeline.Bundle,
        streamPos: Long,
        block: Long
    ): Long? {
        val source = runCatching { bundle.newDownloadSource() }.getOrElse {
            Log.w("VideoCacheFill", "预读数据源建不出来：${it.message}")
            return 0L
        }
        var bytes = 0L
        val openStartedAt = SystemClock.elapsedRealtime()
        Log.w(
            "VideoCacheFill",
            "预读取锁中 目标=${streamPos / 1048576}MB 块=${block / 1048576}MB " +
                "占用=${usedBytesSafely(bundle.cache) / 1048576}MB"
        )
        try {
            // 下载那条源会阻塞取锁：播放器锁着的区间等，等不到时靠线程中断脱身
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse(session.url))
                    .setPosition(streamPos)
                    .setLength(block)
                    .build()
            )
            val lockWait = SystemClock.elapsedRealtime() - openStartedAt
            if (lockWait >= LOCK_LOG_MS) {
                Log.w(
                    "VideoCacheFill",
                    "预读取锁用了 ${lockWait}ms 目标=${streamPos / 1048576}MB " +
                        "占用=${usedBytesSafely(bundle.cache) / 1048576}MB"
                )
            }
            val buffer = ByteArray(READ_BYTES)
            while (!cancelled && !session.stop && bytes < block) {
                // 阻塞读认不了 interrupt，所以每 64KB 再看一眼门
                if (busy()) {
                    Log.w(
                        "VideoCacheFill",
                        "预读被让路砍断 位置=${(streamPos + bytes) / 1048576}MB 已拉=${bytes / 1024}KB"
                    )
                    break
                }
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                bytes += n
            }
            return bytes
        } catch (e: IOException) {
            if (session.stop || cancelled) return null
            Log.w(
                "VideoCacheFill",
                "预读一块失败：${e.message}（已等 ${SystemClock.elapsedRealtime() - openStartedAt}ms " +
                    "已拉=${bytes / 1024}KB）"
            )
            return 0L
        } finally {
            // close 会把这一段（含半块）提交进缓存 —— 让路砍掉的字节不白拉
            runCatching { source.close() }
        }
    }

    private fun contentLength(cache: Cache, key: String): Long =
        ContentMetadata.getContentLength(cache.getContentMetadata(key))

    private fun usedBytesSafely(cache: Cache): Long =
        runCatching { cache.cacheSpace }.getOrDefault(0L)

    private fun waitForIdle(session: Session): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        while (!cancelled && !session.stop && busy()) {
            val now = SystemClock.elapsedRealtime()
            // 播放器每开一次口都会把我们从这里砍掉，本轮计时会重开；所以按全局 5 秒节流，
            // 只在看得到「一直在让路」的时候说话
            if (lastYieldLogAt == 0L || now - lastYieldLogAt >= HEARTBEAT_MS) {
                lastYieldLogAt = now
                Log.w(
                    "VideoCacheFill",
                    "播放器在吃带宽，预读让路 本轮已=${now - startedAt}ms 游标=${session.playerPos / 1048576}MB"
                )
            }
            if (!sleepQuietly(session)) return false
        }
        return !cancelled && !session.stop
    }

    private fun sleepQuietly(session: Session): Boolean = try {
        Thread.sleep(SLEEP_MS)
        !cancelled && !session.stop
    } catch (interrupted: InterruptedException) {
        false
    }

    private fun retire(session: Session, reason: String) {
        Log.w("VideoCacheFill", "预读收工：$reason")
        synchronized(lock) {
            if (current === session) {
                retired = session.key
                current = null
            }
        }
    }

    private companion object {
        const val READ_BYTES = 64 * 1024
        const val SLEEP_MS = 400L

        /** 播放器一直没写下总长就收工（大概是这条直链根本没进缓存路径） */
        const val MAX_NO_LENGTH_MS = 10_000L

        const val MAX_IDLE_ROUNDS = 3

        /** 预读游标 = 播放器位置 + 这几个分块；1 = 播放器正在下的那一块，留给它自己 */
        const val AHEAD_CHUNKS = 2L

        /** 取证用：等待类状态每约 5 秒报一次，避免刷屏又不至于看不出卡在哪一步 */
        const val HEARTBEAT_MS = 5_000L

        /** 取锁超过这个时长才单独报，正常抢锁是毫秒级 */
        const val LOCK_LOG_MS = 1_000L
    }
}
