package com.chen.doudoutv.data.emby

/**
 * 分集媒体流信息的进程级内存缓存。
 *
 * 分辨率 / 码率 / 音轨这些参数不落库：详情页每次打开都会重新拉分集
 * （[com.chen.doudoutv.data.MediaRepository.loadEmbyDetail]），顺带写进这里，
 * UI 按分集 remoteId 读。进程退出即清，LRU 封顶防止长 session 无限增长。
 */
object EmbyMediaCache {
    private const val MAX_ENTRIES = 4096

    private val map = object : LinkedHashMap<String, EmbyClient.MediaInfo>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EmbyClient.MediaInfo>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun putAll(entries: Map<String, EmbyClient.MediaInfo>) {
        map.putAll(entries)
    }

    @Synchronized
    fun get(remoteId: String): EmbyClient.MediaInfo? = map[remoteId]
}