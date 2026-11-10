package com.chen.doudoutv.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chen.doudoutv.DouDouTvApp
import com.chen.doudoutv.data.db.AppDatabase
import com.chen.doudoutv.data.db.HotTitleEntity
import com.chen.doudoutv.data.db.ScrapedMetaEntity
import com.chen.doudoutv.data.db.toPinyinInitials
import com.chen.doudoutv.data.douban.DoubanClient
import com.chen.doudoutv.data.emby.EmbyClient
import com.chen.doudoutv.data.format.FormatProbe
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.data.scrape.ScrapeEngine
import com.chen.doudoutv.data.scrape.ScrapeStrategy
import com.chen.doudoutv.data.tvbox.TvBoxClient
import com.chen.doudoutv.data.tvbox.TvBoxClient.TvBoxSite
import com.chen.doudoutv.data.tmdb.ScrapeTitle
import com.chen.doudoutv.data.tmdb.TmdbCache
import com.chen.doudoutv.data.tmdb.TmdbClient
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.core.VideoCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 媒体库仓储。
 *
 * 两类来源走两条完全不同的入库路径：
 * · Emby：一次拉回库里的电影/剧集壳，分集在详情页按需拉取
 * · TVBox：列表只入库「剧集壳」，分集等用户点进详情页再按需拉取
 *   （详情接口才带播放地址，提前全量拉取会打爆请求量）
 */

/** 批量入库的单批条数：`IN (:ids)` 的占位符个数有上限，整库一次传会让查询直接失败 */
private const val MEDIA_UPSERT_BATCH_SIZE = 200

class MediaRepository(private val context: Context) {

    private val db: AppDatabase = DouDouTvApp.instance.db
    private val sourceDao = db.sourceDao()
    private val mediaDao = db.mediaDao()
    private val hotDao = db.hotTitleDao()
    private val metaDao = db.scrapedMetaDao()

    fun observeSources(): Flow<List<SourceEntity>> = sourceDao.observeAll()
    fun observeLibrary(): Flow<List<MediaEntity>> = mediaDao.observeLibrary()

    /** 详情页种子色用：只跟这一条，别为一颗色把整库拉进内存 */
    fun observeMedia(id: Long): Flow<MediaEntity?> =
        mediaDao.observeMediaById(id).map { it.firstOrNull() }

    /** 设置页的条目计数：口径与 [observeLibrary] 一致，但只回一个数 */
    fun observeMediaCount(): Flow<Int> = mediaDao.observeMediaCount()

    /**
     * 「继续观看」：记录行常常是分集（title 是播放源的分集名），
     * 卡片要显示用户记得住的剧名，所以按 seriesKey 一次批量回查壳行补 [MediaEntity.parentTitle]，
     * 顺带用壳行的海报/背景图补空（分集行这两个字段经常是空的）。
     */
    fun observeContinueWatching(): Flow<List<MediaEntity>> =
        mediaDao.observeContinueWatching().map { rows ->
            val keys = rows.filter { it.kind == MediaKind.EPISODE && it.seriesKey.isNotBlank() }
                .map { it.seriesKey }
                .distinct()
            if (keys.isEmpty()) return@map rows
            val shells = mediaDao.shellsOf(keys).associateBy { it.seriesKey }
            rows.map { row ->
                val shell = if (row.kind == MediaKind.EPISODE) shells[row.seriesKey] else null
                if (shell == null) row else row.copy(
                    posterUrl = row.posterUrl.ifBlank { shell.posterUrl },
                    backdropUrl = row.backdropUrl.ifBlank { shell.backdropUrl }
                ).also { it.parentTitle = shell.title }
            }
        }

    fun observeFeatured(): Flow<List<MediaEntity>> = mediaDao.observeFeatured()
    fun observeFavorites(): Flow<List<MediaEntity>> = mediaDao.observeFavorites()

    suspend fun search(keyword: String): List<MediaEntity> = mediaDao.search(keyword, keyword)

    /** 拼音联想：输入 "tthr" → 返回 ["偷天换日", "偷天换日2", ...] 最多 10 条 */
    suspend fun pinyinSuggest(input: String): List<String> {
        val q = input.trim().lowercase()
        if (q.isBlank()) return emptyList()
        if (q.none { it in 'a'..'z' }) return mediaDao.search(q, q).map { it.title }.distinct().take(10)
        val local = mediaDao.pinyinSuggest("$q%")
        // 本地库里没有的片（尤其是没同步进媒体库的热门片）用豆瓣热门榜补足
        val hot = runCatching { hotDao.byPrefix("$q%", 20) }.getOrDefault(emptyList())
        return (local + hot).distinct().take(10)
    }

    /**
     * 刷新豆瓣热门榜联想索引（搜索页进入时调用）。
     *
     * 24 小时内直接复用本地缓存不联网；拉取失败时保留旧数据，返回 false。
     * @return true 表示榜单已更新，UI 需要重新查一次联想
     */
    suspend fun refreshHotSuggestions(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val last = runCatching { hotDao.lastUpdated() }.getOrNull() ?: 0L
        val fresh = last > 0L && System.currentTimeMillis() - last < HOT_SUGGEST_TTL_MS
        if (!force && fresh) return@withContext false

        val hot = runCatching { DoubanClient().hotTitles() }.getOrDefault(emptyList())
        if (hot.isEmpty()) return@withContext false

        val now = System.currentTimeMillis()
        runCatching {
            hotDao.replaceAll(
                hot.distinctBy { it.title }.mapIndexed { index, item ->
                    HotTitleEntity(
                        title = item.title,
                        pinyinInitials = item.title.toPinyinInitials(),
                        kind = item.kind,
                        rank = index,
                        updatedAt = now
                    )
                }
            )
        }.isSuccess
    }

    /**
     * 一次性重建拼音索引。
     *
     * 老版本首字母是 Unicode 区间估算出来的（「兰」被算成 b），
     * 换成 TinyPinyin 后要把库里已有片名重算一遍，否则老数据仍然搜不到。
     * 用 Prefs.pinyinVersion 记录，只跑一次。
     */
    suspend fun reindexPinyinIfNeeded() = withContext(Dispatchers.IO) {
        if (Prefs.pinyinVersion >= PINYIN_VERSION) return@withContext
        runCatching {
            mediaDao.distinctTitles().forEach { title ->
                mediaDao.updatePinyinByTitle(title, title.toPinyinInitials())
            }
            hotDao.distinctTitles().forEach { title ->
                hotDao.updatePinyinByTitle(title, title.toPinyinInitials())
            }
            Prefs.pinyinVersion = PINYIN_VERSION
        }
    }

    /**
     * TVBox 远程搜索：遍历所有已启用的 TVBox 源，每个源下再遍历它的全部线路站点，
     * 并发调 Guard.searchContent()。返回的 Vod 不入库，直接转临时 MediaEntity 展示；
     * 命中会缓存在内存里，用户点进详情时再落库换成真实 id。
     * 再过滤一遍 —— 标题必须包含整串关键词（或拼音首字母包含），而不是按单字命中。
     *
     * 流式返回：哪个站点先搜完，[onPartial] 就先把当前已命中结果抛给 UI 上屏，
     * 不等慢站；所有站点共用一个 [SEARCH_DEADLINE_MS] 截止时间。
     */
    suspend fun tvBoxRemoteSearch(
        keyword: String,
        onPartial: (suspend (List<MediaEntity>) -> Unit)? = null
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isBlank()) return@withContext emptyList()
        remoteHits.clear()

        // 同一关键词的结果直接走内存缓存（重复搜索、从详情返回再搜都不再打网络）
        val cacheKey = kw.lowercase()
        val cached = synchronized(searchCache) { searchCache[cacheKey] }
        if (cached != null) return@withContext toRemoteEntities(cached)

        val tvboxSources = enabledSources().filter { it.kind == SourceKind.TVBOX }
        if (tvboxSources.isEmpty()) return@withContext emptyList()

        val lower = kw.lowercase()
        // 同一条命中在同一轮搜索里始终用同一个负 id，流式追加时 UI 的 key 不会跳变
        val idByKey = HashMap<String, Long>()
        val collected = LinkedHashMap<String, Pair<TvBoxClient.Vod, SourceEntity>>()

        // 完整关键词过滤 + 相关性排序，生成当前已命中集合的快照
        fun snapshot(): List<MediaEntity> = collected.values
            .filter { (vod, _) ->
                vod.name.lowercase().contains(lower) || vod.name.toPinyinInitials().contains(lower)
            }
            .sortedByDescending { (vod, _) -> searchScore(vod.name, lower) }
            .take(REMOTE_RESULT_LIMIT)
            .map { (vod, source) ->
                val key = "${source.id}:${vod.siteKey}:${vod.vodId}"
                val id = idByKey.getOrPut(key) { -(idByKey.size + 1).toLong() }
                remoteHits[id] = RemoteHit(source, vod)
                MediaEntity(
                    id = id,
                    sourceId = 0L,
                    uri = "",
                    remoteId = "remote:${source.id}:${vod.siteKey}:${vod.vodId}",
                    title = vod.name,
                    kind = MediaKind.MOVIE,
                    year = vod.year,
                    posterUrl = vod.pic,
                    genres = vod.typeName,
                    overview = vod.content,
                    sourceName = source.name,
                    // 线路（站点）名：搜索页在来源下面按线路再分一层，全靠它
                    playSource = vod.siteName.ifBlank { vod.siteKey },
                    // 远程结果也计算拼音（方便再搜）
                    pinyinInitials = vod.name.toPinyinInitials()
                )
            }

        // 每个 (source, site) 一个子协程，谁先搜完谁先投递到 Channel：
        // 快的站点几百毫秒就能出结果，不再被慢站点拖住。
        val batches = Channel<List<Pair<TvBoxClient.Vod, SourceEntity>>>(Channel.UNLIMITED)
        val jobs = tvboxSources.flatMap { source ->
            runCatching {
                val client = TvBoxClient(source.location, context)
                // searchable/quickSearch 是配置给站点的开关：不参与搜索、或明确不支持
                // 快速搜索（本次搜索用的就是 quick=true）的站点直接跳过，省掉一轮无用请求。
                client.availableSites()
                    .filter { it.searchable && it.quickSearch }
                    .map { site ->
                        launch {
                            val hits = runCatching { client.search(site, kw, quick = true) }
                                .getOrDefault(emptyList())
                                .take(REMOTE_RESULT_LIMIT)
                                .map { it to source }
                            runCatching { batches.send(hits) }
                        }
                    }
            }.getOrDefault(emptyList())
        }

        val deadline = System.currentTimeMillis() + SEARCH_DEADLINE_MS
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) break
            val batch = withTimeoutOrNull(remain) { batches.receive() } ?: break
            var added = false
            for ((vod, source) in batch) {
                val key = "${source.id}:${vod.siteKey}:${vod.vodId}"
                if (!collected.containsKey(key)) {
                    collected[key] = vod to source
                    added = true
                }
            }
            if (added) {
                val partial = snapshot()
                if (partial.isNotEmpty()) onPartial?.invoke(partial)
            }
        }
        // 截止后仍在跑的站点要显式取消 —— withContext 返回前会等所有子协程结束
        jobs.forEach { if (it.isActive) it.cancel() }
        batches.close()

        val matched = collected.values.toList()
        synchronized(searchCache) { searchCache[cacheKey] = matched }
        snapshot()
    }

    /** 相关性打分：标题完全一致 3 分、包含关键词 2 分、拼音首字母包含 1 分、其他 0 分 */
    private fun searchScore(title: String, lowerKeyword: String): Int {
        val lower = title.lowercase()
        return when {
            lower == lowerKeyword -> 3
            lower.contains(lowerKeyword) -> 2
            title.toPinyinInitials().contains(lowerKeyword) -> 1
            else -> 0
        }
    }

    /** 原始命中 → 临时 MediaEntity（负 id），并登记到 remoteHits 供详情页落库 */
    private fun toRemoteEntities(hits: List<Pair<TvBoxClient.Vod, SourceEntity>>): List<MediaEntity> =
        hits.mapIndexed { index, (vod, source) ->
            val id = -(index + 1).toLong()  // 负数 id = 远程临时结果
            remoteHits[id] = RemoteHit(source, vod)
            MediaEntity(
                id = id,
                sourceId = 0L,
                uri = "",
                remoteId = "remote:${source.id}:${vod.siteKey}:${vod.vodId}",
                title = vod.name,
                kind = MediaKind.MOVIE,
                year = vod.year,
                posterUrl = vod.pic,
                genres = vod.typeName,
                overview = vod.content,
                sourceName = source.name,
                actors = vod.actors,
                directors = vod.directors,
                remarks = vod.remarks,
                // 线路（站点）名：搜索页在来源下面按线路再分一层，全靠它
                playSource = vod.siteName.ifBlank { vod.siteKey },
                // 远程结果也计算拼音（方便再搜）
                pinyinInitials = vod.name.toPinyinInitials()
            )
        }
    /**
     * 把远程临时结果落库，返回真实 DB id。
     *
     * 搜索结果的负 id 只是内存里的临时标识，直接拿它进详情会查不到 →「条目不存在」。
     * 用户点开某一条时先按所属 TVBox 源入库（复用首页同步的入库路径），
     * 详情页就能正常加载分集、播放。
     */
    suspend fun materializeRemote(mediaId: Long): Long? = withContext(Dispatchers.IO) {
        val hit = remoteHits[mediaId] ?: browseHits[mediaId] ?: return@withContext null
        runCatching { saveTvBoxVods(hit.source, listOf(hit.vod)) {} }
            .getOrDefault(emptyList())
            .firstOrNull()?.id
            ?.takeIf { it > 0 }
    }

    private data class RemoteHit(val source: SourceEntity, val vod: TvBoxClient.Vod)

    /** 远程临时结果缓存：负 id → 原始站点 + Vod，点进详情时据此落库 */
    private val remoteHits = java.util.concurrent.ConcurrentHashMap<Long, RemoteHit>()

    /**
     * 远程搜索结果缓存：关键词（小写归一）→ 原始命中，LRU 最多 16 个关键词。
     * 命中缓存时直接重建临时结果，不再发起一轮网络请求。
     */
    private val searchCache = object :
        java.util.LinkedHashMap<String, List<Pair<TvBoxClient.Vod, SourceEntity>>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<Pair<TvBoxClient.Vod, SourceEntity>>>
        ) = size > 16
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        mediaDao.setFavorite(id, favorite)
        // Emby 源的收藏同步回服务端：在别的客户端/网页端也能看到同一份收藏
        runCatching { syncFavoriteToEmby(id, favorite) }
    }

    /** 从头播放：把进度归零，播放器读取后就从 0s 开始 */
    suspend fun resetPosition(id: Long) = mediaDao.saveProgress(id, 0L, 0L)

    suspend fun addSource(source: SourceEntity): Long = sourceDao.insert(source).also {
        if (source.kind == SourceKind.TVBOX) TvBoxClient.invalidateConfig(source.location)
    }

    suspend fun getSource(id: Long): SourceEntity? = sourceDao.get(id)
    suspend fun allSources(): List<SourceEntity> = sourceDao.getAll()
    suspend fun enabledSources(): List<SourceEntity> = sourceDao.getEnabled()
    suspend fun updateSource(source: SourceEntity) = sourceDao.update(source).also {
        if (source.kind == SourceKind.TVBOX) TvBoxClient.invalidateConfig(source.location)
    }
    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = sourceDao.setEnabled(id, enabled)

    suspend fun deleteSource(source: SourceEntity) {
        mediaDao.deleteBySource(source.id)
        sourceDao.delete(source)
        if (source.kind == SourceKind.TVBOX) TvBoxClient.invalidateConfig(source.location)
    }

    suspend fun getMedia(id: Long): MediaEntity? = mediaDao.get(id)
    suspend fun byRemoteId(remoteId: String): MediaEntity? = mediaDao.byRemoteId(remoteId)
    suspend fun episodes(seriesKey: String): List<MediaEntity> = mediaDao.episodes(seriesKey)

    /** 分集行所属的剧集壳；壳不在库里时返回 null */
    suspend fun shellOf(seriesKey: String): MediaEntity? =
        if (seriesKey.isBlank()) null else mediaDao.shellOf(seriesKey)
    suspend fun seasons(seriesKey: String): List<Int> = mediaDao.seasons(seriesKey)

    /** TVBox 多播放源：取某剧的全部播放源（云盘/线路）名称，按入库顺序 */
    suspend fun playSourcesOf(seriesKey: String): List<String> = mediaDao.playSourcesOf(seriesKey)

    /** TVBox 多播放源：取某剧指定播放源下的分集 */
    suspend fun episodesBySource(seriesKey: String, playSource: String): List<MediaEntity> =
        mediaDao.episodesBySource(seriesKey, playSource)
    suspend fun hasAnyMedia(): Boolean = mediaDao.countAll() > 0

    /** 某来源已入库的条目数（来源页展示「N 部影片」用） */
    suspend fun countBySource(sourceId: Long): Int = mediaDao.countBySource(sourceId)

    /**
     * 解析 TVBox 播放地址。
     *
     * 云盘 scheme 地址（quark://、aliyundrive:// 等）不是视频直链，必须走 spider 解析；
     * http(s) 直链同样交给 spider 处理（可能补 Cookie/Referer，也可能识别出需要网页解析）。
     * 直链解析失败/超时会由 [TvBoxClient.resolvePlayUrl] 内部回退原地址，不影响可播性。
     *
     * 云盘线路额外做一件事：分集 uri 里是带分享令牌的 id，令牌会过期，
     * 入库久了的记录拿去转存会被夸克回 HTTP 404。所以先按需刷新详情换新令牌，
     * 解析仍失败时再强刷一次重试。
     *
     * @param episode 分集实体（需包含 sourceId、seriesKey、playSource、uri）
     * @return 解析结果（URL + headers）；非 TVBox 来源原样返回 uri
     *
     * 结果按「分集 id|uri」缓存 [RESOLVED_URL_TTL_MS]：播放器会在本集临近结束时预热下一集，
     * 真到切集时命中缓存，省掉刷令牌 + 问 spider 要直链这两跳。
     */
    suspend fun resolveTvBoxPlayUrl(episode: MediaEntity): TvBoxClient.ResolvedPlayUrl =
        withContext(Dispatchers.IO) {
            val key = "${episode.id}|${episode.uri}"
            val resolved = RESOLVED_URLS[key]?.takeIf {
                System.currentTimeMillis() - it.at < RESOLVED_URL_TTL_MS
            }?.let {
                Log.w("SpiderProxy", "直链命中预热缓存，跳过解析 mediaId=${episode.id}")
                it.value
            } ?: run {
                val fresh = resolveTvBoxPlayUrlUncached(episode)
                if (RESOLVED_URLS.size >= RESOLVED_URL_MAX) {
                    RESOLVED_URLS.entries.removeAll { System.currentTimeMillis() - it.value.at >= RESOLVED_URL_TTL_MS }
                    if (RESOLVED_URLS.size >= RESOLVED_URL_MAX) RESOLVED_URLS.clear()
                }
                RESOLVED_URLS[key] = CachedPlayUrl(fresh, System.currentTimeMillis())
                fresh
            }
            // 磁盘缓存的 key 要跨会话稳定：签名直链每次解析都换 token，不能当 key。
            // 用「来源 + 库里唯一的 remoteId」；m3u8 与非 TVBox 来源由 VideoCache.bind 自己挡掉。
            if (episode.seriesKey.startsWith("tvbox:")) {
                VideoCache.bind(resolved.url, "tvbox:${episode.sourceId}:${episode.remoteId}")
            }
            resolved
        }

    private suspend fun resolveTvBoxPlayUrlUncached(episode: MediaEntity): TvBoxClient.ResolvedPlayUrl = withContext(Dispatchers.IO) {
        val source = sourceDao.get(episode.sourceId)
            ?: return@withContext TvBoxClient.ResolvedPlayUrl(episode.uri)
        if (source.kind != SourceKind.TVBOX) return@withContext TvBoxClient.ResolvedPlayUrl(episode.uri)

        // episode.seriesKey = tvbox:sourceId:siteKey:vodId
        val (siteKey, _) = parseTvBoxKey(episode.sourceId, episode.seriesKey)
        val client = TvBoxClient(source.location, context)

        // 直链没有时效问题，照旧一次解析
        if (episode.uri.startsWith("http://") || episode.uri.startsWith("https://")) {
            return@withContext client.resolvePlayUrl(siteKey, episode.playSource, episode.uri)
        }

        val current = refreshCloudShareUris(episode, client, siteKey, force = false) ?: episode
        try {
            client.resolvePlayUrl(siteKey, current.playSource, current.uri)
        } catch (e: Exception) {
            // 刷新被防抖挡下（刚刷过）或详情拉取失败时也得再试一次，
            // 否则缓存窗口内同一个坏 id 会一直被判死
            val retried = refreshCloudShareUris(episode, client, siteKey, force = true)
            if (retried != null) {
                Log.w("SpiderProxy", "云盘解析失败，已刷新分享令牌后重试：${e.message}")
                client.resolvePlayUrl(siteKey, retried.playSource, retried.uri)
            } else {
                Log.w("SpiderProxy", "云盘解析失败，刷新未生效，原地址重试一次：${e.message}")
                client.resolvePlayUrl(siteKey, current.playSource, current.uri)
            }
        }
    }

    /** 云盘分享令牌的保守有效期：入库超过这个时长就重新拉一次详情换新令牌 */
    private val cloudUriTtlMs = CLOUD_URI_TTL_MS

    /**
     * 用最新详情覆盖该剧同一条云盘线路的全部分集 uri。
     *
     * 只改 uri，不动行 id 与播放进度；直链分集不碰。
     *
     * @param force true 表示解析刚失败、怀疑令牌已失效，跳过时间窗口
     * @return 当前分集的最新副本；没刷新（还在窗口内/拉取失败/线路已不存在）时返回 null
     */
    private suspend fun refreshCloudShareUris(
        episode: MediaEntity,
        client: TvBoxClient,
        siteKey: String,
        force: Boolean
    ): MediaEntity? {
        val now = System.currentTimeMillis()
        val lastRefresh = CLOUD_REFRESH_AT[episode.seriesKey] ?: 0L
        if (!force) {
            val newest = maxOf(episode.addedAt, lastRefresh)
            if (now - newest < cloudUriTtlMs) return null
        }
        // force 不设窗口：刚刷新就失败的 id 不能再用，必须换一个新的

        val (_, vodId) = parseTvBoxKey(episode.sourceId, episode.seriesKey)
        if (vodId.isBlank()) return null
        val detail = runCatching { client.detail(vodId, siteKey) }.getOrNull() ?: return null
        CLOUD_REFRESH_AT[episode.seriesKey] = now

        val line = detail.playSources.firstOrNull { it.name == episode.playSource } ?: return null
        val rows = mediaDao.episodesBySource(episode.seriesKey, episode.playSource)
        if (rows.isEmpty()) return null

        // 源会改分集名（换体积标注、去掉【分享标记】、换扩展名），全等匹配太脆：
        // 全名 → 归一化核心名（唯一时）→ 集号（唯一时）→ 数量一致时按下标兜底，逐级降级
        val byName = line.episodes.groupBy { it.name }.mapValues { it.value.first() }
        val byCore = line.episodes.groupBy { cloudNameCore(it.name) }
            .filterValues { it.size == 1 }
            .mapValues { it.value.first() }
        val byNumber = line.episodes
            .mapNotNull { ep -> cloudEpisodeNumber(ep.name)?.let { it to ep } }
            .groupBy { it.first }
            .filterValues { it.size == 1 }
            .mapValues { it.value.first().second }
        val byIndex = rows.size == line.episodes.size

        val updates = ArrayList<MediaEntity>(rows.size)
        val unmatched = ArrayList<String>(2)
        var freshUri: String? = null
        for ((index, row) in rows.withIndex()) {
            if (row.uri.startsWith("http")) continue
            val bySourceName = byName[row.title]
                ?: byCore[cloudNameCore(row.title)]
                ?: cloudEpisodeNumber(row.title)?.let { byNumber[it] }
            val hit = bySourceName ?: if (byIndex) line.episodes.getOrNull(index) else null
            if (hit == null) {
                unmatched += row.title
                continue
            }
            // 按名字命中时把新名也写回去，详情页显示的就是源当前的名字；
            // 按下标兜底时名字可能真不一样，只换地址，别改标题
            val next = if (bySourceName != null) {
                row.copy(uri = hit.url, title = hit.name, pinyinInitials = hit.name.toPinyinInitials())
            } else {
                row.copy(uri = hit.url)
            }
            if (next.uri != row.uri || next.title != row.title) updates += next
            if (row.id == episode.id) freshUri = hit.url
        }
        if (unmatched.isNotEmpty()) {
            Log.w(
                "SpiderProxy",
                "云盘分集对不上：db=${rows.size} src=${line.episodes.size} " +
                    "未匹配=${unmatched.take(3)} 源名=${line.episodes.take(3).map { it.name }}"
            )
        }
        if (updates.isNotEmpty()) {
            mediaDao.updateAll(updates)
            // 打印当前集 id 换没换：换了还 404 就说明锅不在分享令牌上
            val idNote = when {
                freshUri == null -> "当前集未在返回列表"
                freshUri == episode.uri -> "当前集 id 未变"
                else -> "当前集 id 已换(${episode.uri.hashCode()}→${freshUri.hashCode()})"
            }
            Log.w(
                "SpiderProxy",
                "云盘分集 uri 已刷新：${episode.seriesKey}/${episode.playSource} 更新 ${updates.size} 条，$idNote"
            )
        }
        return freshUri?.let { episode.copy(uri = it) }
    }

    /** 分集名归一化：去掉【分享标记】、[体积] 前缀、扩展名与空白，用于源改名后的兜底匹配 */
    private fun cloudNameCore(name: String): String = name
        .replace(Regex("【[^】]*】"), "")
        .replace(Regex("\\[[^\\]]*]"), "")
        .replace(Regex("\\.[A-Za-z0-9]{2,5}$"), "")
        .replace(Regex("\\s+"), "")
        .lowercase()

    /**
     * 从分集名里猜集号（E01 / EP01 / 第01集 / 01集 / 20 1080P），猜不出返回 null。
     * 必须在去掉空格之前匹配：「20 1080P」里 20 后面紧跟空格才是集号，
     * 直接取自数字会贪婪成 201。
     */
    private fun cloudEpisodeNumber(name: String): Int? {
        val base = name
            .replace(Regex("【[^】]*】"), "")
            .replace(Regex("\\[[^\\]]*]"), "")
            .trim()
        val m = Regex("^(?:ep?|第)?(\\d{1,3})(?:集)?(?=[\\s.\\-_（(]|$)", RegexOption.IGNORE_CASE)
            .find(base) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it in 1..999 }
    }

    /**
     * 播放期把 Media3 读到的真实画质回写到剧集壳（方案C 的校正一步）。
     *
     * 只对 TVBox 生效：Emby 的画质来自 MediaSources 接口，本来就是真值，别让播放器覆盖。
     * 新值为空时保留库里的旧值（轨道里没有的信息不该被抹掉）。
     */
    suspend fun savePlaybackFormat(item: MediaEntity, info: FormatProbe.Info) = withContext(Dispatchers.IO) {
        if (info.isEmpty) return@withContext
        val source = sourceDao.get(item.sourceId) ?: return@withContext
        if (source.kind != SourceKind.TVBOX) return@withContext

        val target = if (item.kind == MediaKind.EPISODE) mediaDao.shellOf(item.seriesKey) else item
        if (target == null) return@withContext
        val merged = target.copy(
            videoResolution = info.resolution.ifBlank { target.videoResolution },
            hdrType = info.hdr.ifBlank { target.hdrType },
            videoCodec = info.codec.ifBlank { target.videoCodec },
            audioType = info.audio.ifBlank { target.audioType }
        )
        if (merged == target) return@withContext
        mediaDao.update(merged)
        // W 级：电视 ROM 吞 D 级日志，画质回写是排障时要核对的事实
        Log.w(
            "FormatProbe",
            "画质回写 ${target.title}：${merged.videoResolution}/${merged.hdrType}/" +
                "${merged.videoCodec}/${merged.audioType}"
        )
    }

    // ---------------- 首页分类 / 浏览页 ----------------

    /** 首页画廊：每类只取最新 N 条（懒加载，避免整库刷进内存） */
    fun observeRecent(kind: String, limit: Int): Flow<List<MediaEntity>> =
        mediaDao.observeRecent(kind, limit)

    fun observeAnime(limit: Int): Flow<List<MediaEntity>> = mediaDao.observeAnime(limit)

    /** 分类浏览页分页查询：kind / genre / library 任一为 null 即不过滤该维度 */
    suspend fun byGenre(kind: String?, genre: String?, library: String?, limit: Int, offset: Int): List<MediaEntity> =
        mediaDao.byGenre(kind, genre, library, limit, offset)

    /** 某 Emby 来源已入库的媒体库清单（本地、离线）：动态分类按库分区用 */
    suspend fun embyLibraries(sourceId: Long): List<String> = mediaDao.distinctLibraries(sourceId)

    /** Emby 刮削信息（导演/演员/分级/工作室），仅 Emby 源可用 */
    suspend fun embyDetail(sourceId: Long, remoteId: String): EmbyClient.ItemDetail? =
        withContext(Dispatchers.IO) {
            val src = sourceDao.get(sourceId) ?: return@withContext null
            if (src.kind != SourceKind.EMBY || src.token.isBlank()) return@withContext null
            val itemId = remoteId.removePrefix("emby:${src.id}:")
            if (itemId.isBlank() || ':' in itemId) return@withContext null
            runCatching {
                EmbyClient(src.location, src.token, src.userId).fetchItemDetail(itemId)
            }.getOrNull()
        }

    /** Emby 全库题材清单：直接问服务端 /Genres，仅 Emby 源可用；失败回退空列表（上层再退本地聚合） */
    suspend fun embyGenres(source: SourceEntity): List<String> = withContext(Dispatchers.IO) {
        if (source.kind != SourceKind.EMBY || source.token.isBlank()) return@withContext emptyList()
        runCatching {
            EmbyClient(source.location, source.token, source.userId).fetchGenres()
        }.getOrDefault(emptyList())
    }

    /** 刮削表里的那一行：先按「清洗名 + 年份 + 类型」精确找，再退到不限年份的同名条目 */
    private suspend fun storedMeta(exactKey: String, titleKey: String): ScrapedMetaEntity? =
        runCatching { metaDao.byKey(exactKey) ?: metaDao.byTitleKey(titleKey) }.getOrNull()

    /**
     * 只读 scraped_meta 表：不看联网刮削开关、不发请求、也不回填 media 行。
     *
     * 详情页背景图是「刮削图优先、条目自有图兜底」，而完整那次刮削排在分集/详情那条慢链之后，
     * 于是已经刮过的片子每次进页面都会先上一帧源站老图。这一层给首帧一个抢跑的入口：
     * 表里有就省掉整段慢链的时间，没有就返回 null，仍然由 [tmdbScrape] 那条完整链负责。
     */
    suspend fun scrapedMetaOf(title: String, year: Int, kind: String): TmdbClient.ScrapeResult? =
        withContext(Dispatchers.IO) {
            val query = ScrapeTitle.forSearch(title)
            val meta = storedMeta(
                TmdbCache.keyOf(query, year, kind),
                TmdbCache.titleKeyOf(query, kind)
            )
            // 取证：和下面 tmdbScrape 那行成对看 —— 这行是抢跑，「刮削」那行是链末端，
            // 两行的时间差就是老海报在屏上多停的那段。没命中也要打，否则分不清「跑了没图」和「根本没跑」。
            Log.i(
                "ScrapeDiag",
                "表读 \"$title\" → \"$query\" year=$year kind=$kind " +
                    (if (meta == null) "未命中"
                else "命中 背景=${if (meta.backdropUrl.isBlank()) "空" else "有"} " +
                    "海报=${if (meta.posterUrl.isBlank()) "空" else "有"}")
            )
            meta?.toScrapeResult()
        }

    /**
     * 取一部片的刮削结果（带防频繁请求保护）。
     *
     * 拦截顺序，越靠前越省钱：
     * 1. scraped_meta 表命中 → 直接返回。这一步**不看「联网刮削」开关**：
     *    读自己盘上的数据不花钱，关掉开关只想停掉联网，不该把已经刮到的东西也藏起来。
     * 2. 开关 + 凭据都通过，才往下走：内存缓存命中 → 省掉一次数据库查询
     * 3. 同 key 并发请求去重 → 复用第一次的结果
     * 4. 都未命中才真正发网络请求，成功后落表
     *
     * [mediaId] 传入时，还会把海报/背景图/Logo/评分/简介回填到 media 行并标记 scrapedAt。
     * 注意那次回填是「只补空」的（保护 Emby 自己库里的数据），所以源站已经给了豆瓣海报时
     * TMDB 的海报进不了 media 行 —— 展示层要拿这份刮削结果，靠的是上面第 1 层的表，
     * 而不是 media 行里那几个字段。
     */
    suspend fun tmdbScrape(
        title: String,
        year: Int,
        kind: String,
        mediaId: Long = 0L
    ): TmdbClient.ScrapeResult? = withContext(Dispatchers.IO) {
        // 清洗放在这里，而不是 TmdbClient 里面：缓存键、表键和检索词必须用同一个串，
        // 否则「（臻彩）」和「[4K]」两种后缀会算成两条，换条线路重进就白刮一次。
        // 库里的 title、详情页展示、FormatProbe 读的文件名都不动，只有发 TMDB 的这个词被洗过。
        val query = ScrapeTitle.forSearch(title)
        val exactKey = TmdbCache.keyOf(query, year, kind)
        val titleKey = TmdbCache.titleKeyOf(query, kind)

        // 第一步永远先查表。命中就不联网，也让「跨重启、跨来源、跨线路」三部同名片子只刮一次，
        // 顺带让演职员、标语这些 media 行装不下的字段有了落点。
        val stored = storedMeta(exactKey, titleKey)
        val result = stored?.toScrapeResult() ?: run {
            // 表里没有才轮到开关和凭据。用 resolve() 现算而不是读 ScrapeStrategy.engine 那份快照：
            // 用户在设置页刚填完密钥、没重启退回来也该立刻能刮。
            if (ScrapeStrategy.resolve() != ScrapeEngine.TMDB) return@withContext null
            runCatching {
                TmdbCache.getOrFetch(exactKey) {
                    TmdbClient(Prefs.tmdbApiKey, Prefs.tmdbReadToken).scrape(query, year, kind)
                }
            }.getOrNull() ?: return@withContext null
        }

        // 取证：每次调用打一行，回答三个「到底停在哪」——
        // 键算成什么了（表没命中时先看这里，多半是清洗后的片名或 kind 不一样）、
        // 数据是表里拿的还是刚联网刮的、刮到的这份到底有没有海报
        Log.i(
            "ScrapeDiag",
            "刮削 \"$title\" → \"$query\" year=$year kind=$kind " +
                "来源=${if (stored != null) "表" else "联网"} " +
                "海报=${if (result.posterUrl.isBlank()) "空" else "有"} " +
                "简介=${if (result.overview.isBlank()) "空" else "有"} " +
                "主演=${result.actors.size}"
        )

        // 网络刮回来的这份写进表；表里本来就有就不再重复落盘。
        // 刮不到（result 来自负缓存或空结果）时不占表位，下次仍然有机会重刮。
        if (stored == null) {
            runCatching {
                metaDao.upsert(
                    ScrapedMetaEntity(
                        scrapeKey = exactKey,
                        titleKey = titleKey,
                        queryTitle = query,
                        kind = kind,
                        year = year,
                        overview = result.overview,
                        genres = result.genres,
                        rating = result.rating,
                        posterUrl = result.posterUrl,
                        backdropUrl = result.backdropUrl,
                        logoUrl = result.logoUrl,
                        tagline = result.tagline,
                        directorsJson = ScrapedMetaEntity.encodePeople(result.directors),
                        castJson = ScrapedMetaEntity.encodePeople(result.actors),
                        scrapedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        // 回填数据库：只在有 mediaId 时才写，避免列表预刮时乱写
        if (mediaId > 0L) {
            runCatching {
                mediaDao.updateScrapedMeta(
                    id = mediaId,
                    posterUrl = result.posterUrl,
                    backdropUrl = result.backdropUrl,
                    logoUrl = result.logoUrl,
                    overview = result.overview,
                    genres = result.genres,
                    rating = result.rating,
                    scrapedAt = System.currentTimeMillis()
                )
            }
        }
        result
    }

    /**
     * 清空刮削数据（设置页入口）：内存缓存、`scraped_meta` 表、media 行的 scrapedAt 标记一起抹掉，
     * 让下一次进详情页重新联网。三者必须一起清，只清内存的话表还在，
     * 按钮点了等于没点。
     */
    suspend fun clearScrapeCache(): Int = withContext(Dispatchers.IO) {
        TmdbCache.clear()
        metaDao.clear()
        mediaDao.clearAllScrapeMarks()
    }

    /**
     * 本机记住的影片数。
     * 数的是 scraped_meta 而不是内存缓存：重启后内存清空但表还在，
     * 这些片子照样不会重新请求，设置页要报的是这个口径。
     */
    suspend fun scrapeCacheSize(): Int = withContext(Dispatchers.IO) {
        runCatching { metaDao.count() }.getOrDefault(0)
    }

    /** 类型标签：拆分聚合后按出现次数排序 */
    suspend fun genreTags(limit: Int = 16): List<String> =
        mediaDao.distinctGenres()
            .flatMap { it.split(" / ", "/", ",", "，", " ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    /**
     * 按 remoteId 批量写入元数据（一批 = 一次查询 + 一次 insertAll + 一次 updateAll + 一次回填）。
     *
     * 语义与之前的逐条版本一致：
     * 已存在时保留 id / 播放进度 / 收藏 / 添加时间，并刷新源同步来的字段。
     *
     * 关键语义：**源同步字段有值就用源的，源给空字符串就保留 DB 已有值**。
     * 这样 Emby/TVBox 重同步时不会把 TMDB 补好的 logoUrl / posterUrl / overview
     * 等字段冲成空。优先级：Emby 自带元数据 > TVBox 返回字段 > TMDB 补空。
     *
     * 不能用 upsert(REPLACE)：那会删行重插，进度和收藏都会丢。
     * 调用方传入的条目需已补齐 pinyinInitials / sourceName。
     * @return 库里现在的这些条目（新行带上了自增 id）
     */
    private suspend fun upsertMediaBatch(incoming: List<MediaEntity>): List<MediaEntity> {
        if (incoming.isEmpty()) return emptyList()
        val existing = mediaDao.byRemoteIds(incoming.map { it.remoteId }).associateBy { it.remoteId }
        val toInsert = ArrayList<MediaEntity>(incoming.size)
        val toUpdate = ArrayList<MediaEntity>(incoming.size)
        for (entity in incoming) {
            val old = existing[entity.remoteId]
            if (old == null) {
                toInsert += entity
            } else {
                toUpdate += entity.copy(
                    id = old.id,
                    // ---- 播放相关：始终保留已有进度，源同步不会覆盖 ----
                    positionMs = old.positionMs,
                    lastPlayedAt = old.lastPlayedAt,
                    favorite = old.favorite,
                    addedAt = old.addedAt,
                    durationMs = if (entity.durationMs > 0) entity.durationMs else old.durationMs,
                    // ---- 媒体元数据：源有值就用它，源给空就保留 DB 已有（可能是 TMDB 补的） ----
                    posterUrl = entity.posterUrl.ifBlank { old.posterUrl },
                    backdropUrl = entity.backdropUrl.ifBlank { old.backdropUrl },
                    logoUrl = entity.logoUrl.ifBlank { old.logoUrl },
                    overview = entity.overview.ifBlank { old.overview },
                    genres = entity.genres.ifBlank { old.genres },
                    rating = if (entity.rating > 0) entity.rating else old.rating,
                    year = if (entity.year > 0) entity.year else old.year,
                    // 列表接口常常不给演员/备注，不能让下一次同步把详情里拿到的清空
                    actors = entity.actors.ifBlank { old.actors },
                    directors = entity.directors.ifBlank { old.directors },
                    remarks = entity.remarks.ifBlank { old.remarks },
                    // 库归属：逐库同步给值就用它；回退整库同步给空时保留已有标签，不清空
                    libraryId = entity.libraryId.ifBlank { old.libraryId },
                    libraryName = entity.libraryName.ifBlank { old.libraryName }
                )
            }
        }
        if (toInsert.isNotEmpty()) mediaDao.insertAll(toInsert)
        if (toUpdate.isNotEmpty()) mediaDao.updateAll(toUpdate)
        val saved = mediaDao.byRemoteIds(incoming.map { it.remoteId }).associateBy { it.remoteId }
        return incoming.map { saved[it.remoteId] ?: it }
    }

    /**
     * 整库同步的入口：按 [MEDIA_UPSERT_BATCH_SIZE] 分批走 [upsertMediaBatch]。
     *
     * 必须分批：`WHERE remoteId IN (:ids)` 的占位符个数有上限，
     * 几千条一次传进去，查询会直接失败（之前逐条写则是每条 3 次往返，600 条 ≈1800 个独立事务）。
     */
    private suspend fun upsertMediaAll(
        incoming: List<MediaEntity>,
        onProgress: (Int) -> Unit = {}
    ): List<MediaEntity> {
        var done = 0
        return incoming.chunked(MEDIA_UPSERT_BATCH_SIZE).flatMap { batch ->
            val saved = upsertMediaBatch(batch)
            done += batch.size
            onProgress(done)
            saved
        }
    }

    // ---------------- Emby ----------------

    /**
     * Emby 登录并保存 token。
     * @return null 表示成功，否则返回错误信息
     */
    suspend fun embyLogin(source: SourceEntity): String? = withContext(Dispatchers.IO) {
        try {
            val client = EmbyClient(source.location)
            val auth = client.authenticate(source.username, source.password)
            sourceDao.update(
                source.copy(token = auth.token, userId = auth.userId)
            )
            null
        } catch (e: Exception) {
            e.message ?: "登录失败：请检查服务器地址、用户名或密码"
        }
    }

    /**
     * Emby 全量同步 —— 仅首次（本地为空）或强制（重新登录）时走。
     *
     * 列表阶段分页拉全库「电影 + 剧集壳」入库，分集在用户点进详情页时按需加载（loadEmbyDetail）。
     * 之后每次启动/刷新走 [syncEmbyIncremental]，只拉变化，避免反复整库重拉触发服务端限流。
     */
    suspend fun syncEmby(source: SourceEntity, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            if (source.token.isBlank()) {
                throw IllegalStateException("Emby 尚未登录，请先点刷新重新登录")
            }
            val client = EmbyClient(source.location, source.token, source.userId)

            // 旧版本剧集壳的 remoteId 没带 sourceId，会与新数据重复，先清掉
            mediaDao.deleteLegacyEmbyShells(source.id)

            syncEmbyPerLibrary(client, source, null, onProgress)
        }

    /**
     * Emby 增量同步 —— 首次全量之后，启动/刷新只拉 [sinceMs] 之后新增/修改的条目。
     *
     * 复用全量的 Item→Entity 映射与批量入库（已存在条目保留进度/收藏）。
     * 不做删除校对：服务端被删的条目增量拉不到，本地残留按口径接受。
     * @return 本次更新的条数（0 表示没有变化）
     */
    suspend fun syncEmbyIncremental(
        source: SourceEntity,
        sinceMs: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        if (source.token.isBlank()) {
            throw IllegalStateException("Emby 尚未登录，请先点刷新重新登录")
        }
        val client = EmbyClient(source.location, source.token, source.userId)
        syncEmbyPerLibrary(client, source, sinceMs, onProgress)
    }

    /**
     * 按媒体库逐个同步（全量 [sinceMs]=null / 增量非 null 共用）。
     *
     * 先 /Views 拿库清单，再逐库 ParentId 分页拉、拉时即打库标签（libraryId/libraryName）——
     * 整库递归查询不返回 ParentId，无法事后归库，故走 Yamby/openemby 这套逐库路线。
     * /Views 拿不到时回退旧整库同步（无库标签，upsert 合并时保留已有标签不清空）。
     * 跳过 playlists（播放列表库不含 Movie/Series）；库间轻微节流，避免短时间连发触发限流。
     */
    private suspend fun syncEmbyPerLibrary(
        client: EmbyClient,
        source: SourceEntity,
        sinceMs: Long?,
        onProgress: (Int, Int) -> Unit
    ): Int {
        val views = client.fetchViews()
        if (views.isEmpty()) {
            val items = if (sinceMs != null) client.fetchUpdated(sinceMs) { f, t -> onProgress(f, t) }
            else client.fetchLibrary { f, t -> onProgress(f, t) }
            if (items.isEmpty()) return 0
            val incoming = items.map { embyItemToEntity(source, it) }
            upsertMediaAll(incoming)
            return incoming.size
        }
        val libs = views.filter { it.collectionType != "playlists" }
        var done = 0
        for ((index, lib) in libs.withIndex()) {
            val items = client.fetchLibraryItems(lib.id, sinceMs) { f, _ -> onProgress(done + f, 0) }
            if (items.isNotEmpty()) {
                upsertMediaAll(items.map { embyItemToEntity(source, it, lib.id, lib.name) })
                done += items.size
            }
            if (index < libs.size - 1) kotlinx.coroutines.delay(200)
        }
        onProgress(done, 0)
        return done
    }

    /** Emby Item → 本地 MediaEntity：全量与增量共用，remoteId 统一带 sourceId 防多台互覆盖 */
    private fun embyItemToEntity(
        source: SourceEntity,
        item: EmbyClient.Item,
        libraryId: String = "",
        libraryName: String = ""
    ): MediaEntity {
        val isSeries = item.isSeries
        return MediaEntity(
            sourceId = source.id,
            pinyinInitials = item.name.toPinyinInitials(),
            sourceName = source.name,
            uri = if (isSeries) "" else EmbyClient.playbackUrl(source.location, item.id, source.token),
            remoteId = "emby:${source.id}:${item.id}",
            seriesKey = if (isSeries) "emby:${source.id}:${item.id}" else "",
            title = item.name,
            kind = if (isSeries) MediaKind.SERIES else MediaKind.MOVIE,
            year = item.year,
            overview = item.overview,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            logoUrl = item.logoUrl,
            rating = item.rating,
            genres = item.genres,
            durationMs = item.runTimeMs,
            libraryId = libraryId,
            libraryName = libraryName
        )
    }

    /**
     * 详情页按需拉取 Emby 分集并入库。
     * @return true 表示拿到了分集
     */
    suspend fun loadEmbyDetail(source: SourceEntity, remoteId: String): Boolean =
        withContext(Dispatchers.IO) {
            // remoteId 形如 emby:{sourceId}:{itemId}
            val itemId = remoteId.removePrefix("emby:${source.id}:")
            if (itemId.isBlank() || ':' in itemId) return@withContext false
            val shell = mediaDao.byRemoteId(remoteId) ?: return@withContext false
            val seriesKey = shell.seriesKey.ifBlank { remoteId }

            val client = EmbyClient(source.location, source.token, source.userId)
            val episodes = client.fetchEpisodes(itemId)
            // 分集一次批量写：一部 100 集的剧之前要 300 次 DAO 往返，详情页就在这几秒里等
            upsertMediaAll(
                episodes.mapIndexed { index, ep ->
                    MediaEntity(
                        sourceId = source.id,
                        pinyinInitials = ep.name.toPinyinInitials(),
                        sourceName = source.name,
                        uri = EmbyClient.playbackUrl(source.location, ep.id, source.token),
                        remoteId = "emby:${source.id}:${ep.id}",
                        seriesKey = seriesKey,
                        title = ep.name.ifBlank { "第 ${index + 1} 集" },
                        kind = MediaKind.EPISODE,
                        season = ep.parentIndexNumber,
                        episode = ep.indexNumber,
                        overview = ep.overview,
                        durationMs = ep.runTimeMs,
                        posterUrl = ep.posterUrl,
                        backdropUrl = shell.backdropUrl,
                        logoUrl = shell.logoUrl,
                        libraryId = shell.libraryId,
                        libraryName = shell.libraryName
                    )
                }
            )
            episodes.isNotEmpty()
        }

    // ---------------- TVBox ----------------

    /** 同步 TVBox 首页列表（只入库剧集壳） */
    suspend fun syncTvBox(source: SourceEntity, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val client = TvBoxClient(source.location, context)
            val list = client.homeContent()
            if (list.isEmpty()) {
                throw IllegalStateException("配置里的 spider 站点没有返回任何影片，请检查蜘蛛包与网络")
            }
            saveTvBoxVods(source, list, onProgress).size
        }

    /**
     * 启动预热：只把首页当前来源这一条 TVBox 链路的 spider 包准备到能调用
     * （落盘 → 壳 Init → 起本地代理），不请求任何站点数据、失败静默。
     * 当前来源不是 TVBox（或未选来源）时什么都不做 —— 不该为了没用上的壳跑二十多秒纯本地计算。
     *
     * 加密壳的 Init 是纯本地单核计算，电视上实测要二十多秒。放在这里跑，是为了让首页/分类/详情
     * 那几条链路走到 SpiderLoader 时直接命中进程级缓存，不再当着我的面初始化。
     *
     * 其余来源不在这里预热：切到某条 TVBox 来源时，线路列表 / 首页 / 分类本身就会走到同一把
     * [SpiderLoader] 加载锁上，预热是重复劳动。代价是远程搜索只认已加载的缓存
     * （见 TvBoxClient 的 cachedLoaderKeyOrNull），没被用过的来源不参与远程搜索。
     */
    suspend fun warmUpTvBoxSpider(sourceId: Long): Unit = withContext(Dispatchers.IO) {
        val source = runCatching { getSource(sourceId) }.getOrNull()
            ?.takeIf { it.enabled && it.kind == SourceKind.TVBOX } ?: return@withContext
        val t0 = SystemClock.elapsedRealtime()
        val result = runCatching { TvBoxClient(source.location, context).prepareSpider() }
        val cost = SystemClock.elapsedRealtime() - t0
        if (result.getOrDefault(false)) {
            Log.w("SpiderLoader", "预热完成 ${source.name} 用时 ${cost}ms")
        } else {
            Log.w("SpiderLoader", "预热未就绪 ${source.name} 用时 ${cost}ms：${result.exceptionOrNull()?.message ?: "配置无 spider 字段"}")
        }
    }

    /** 拉取 TVBox 配置里的站点（线路）列表，供首页线路切换 */
    suspend fun tvBoxSites(source: SourceEntity): List<TvBoxSite> = withContext(Dispatchers.IO) {
        if (source.kind != SourceKind.TVBOX) return@withContext emptyList()
        TvBoxClient(source.location, context).availableSites()
    }

    /** 统计某来源下 remoteId 前缀匹配的条目数（判断某 TVBox 站点是否已同步过） */
    suspend fun countByRemotePrefix(sourceId: Long, prefix: String): Int =
        mediaDao.countByRemotePrefix(sourceId, prefix)

    /**
     * 按单个站点（线路）同步首页：切换线路时按需拉取该站点内容入库。
     * 返回影片列表 + 该线路 homeContent 的分类（class），分类供首页按动态分区展示。
     *
     * [SiteHomeSync.entities] 保持源站 homeContent 的原始顺序，供首页轮播使用
     * （轮播要的是源站推荐顺序，不是库里的入库时间顺序）。
     */
    suspend fun syncTvBoxSite(
        source: SourceEntity,
        site: TvBoxSite,
        onProgress: (Int) -> Unit = {}
    ): SiteHomeSync = withContext(Dispatchers.IO) {
        val client = TvBoxClient(source.location, context)
        // 不吞异常：把站点加载失败的真实原因（壳初始化超时等）透出到 UI
        val result = client.siteHome(site)
        val entities = saveTvBoxVods(source, result.vods, onProgress)
        SiteHomeSync(result, entities)
    }

    /** 单线路首页同步结果：spider 原始结果 + 已入库实体（保持源站顺序） */
    data class SiteHomeSync(
        val result: TvBoxClient.SiteHomeResult,
        val entities: List<MediaEntity>
    )

    /**
     * 拉取某站点某分类的内容（categoryContent）。
     *
     * 浏览列表**不落库**：之前每拉一页 72 条就逐条 insert/update（一页 100+ 次 DB 往返），
     * 首页 7 个分类就是几百次写库，电视上直接表现为卡顿。
     * 这里改成内存实体（负 id）并登记到 [browseHits]，点进详情时由 [materializeRemote] 单条落库。
     */
    suspend fun tvBoxCategory(
        source: SourceEntity,
        site: TvBoxSite,
        typeId: String,
        page: Int = 1
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        if (source.kind != SourceKind.TVBOX) return@withContext emptyList()
        val vods = TvBoxClient(source.location, context).categoryContent(site, typeId, page)
        // 网盘类线路同一页里常出现重复条目（同一个 vodId 出现两次），
        // 不去重会让 browseEntity 返回同一个临时负 id，列表 key 重复 → LazyRow 直接崩溃
        vods.distinctBy { it.siteKey to it.vodId }.map { browseEntity(source, it) }
    }

    /** 浏览列表 → 内存 MediaEntity（负 id），登记到 [browseHits] 供详情页落库 */
    private fun browseEntity(source: SourceEntity, vod: TvBoxClient.Vod): MediaEntity {
        val key = "${source.id}:${vod.siteKey}:${vod.vodId}"
        // 长会话浏览几千条后清一次，避免 Vod 的简介字符串常驻内存
        if (browseHits.size > 3000) {
            browseHits.clear()
            browseIds.clear()
        }
        val id = browseIds.getOrPut(key) { -browseSeq.getAndIncrement() }
        browseHits[id] = RemoteHit(source, vod)
        return MediaEntity(
            id = id,
            sourceId = 0L,
            uri = "",
            remoteId = tvboxKey(source.id, vod.siteKey, vod.vodId),
            title = vod.name,
            kind = MediaKind.SERIES,
            year = vod.year,
            overview = vod.content,
            posterUrl = vod.pic,
            backdropUrl = vod.pic,
            rating = vod.score,
            genres = vod.typeName,
            actors = vod.actors,
            directors = vod.directors,
            playSource = vod.siteName.ifBlank { vod.siteKey },
            remarks = vod.remarks
        )
    }

    /** 浏览临时 id 注册表（与搜索共用详情页落库入口） */
    private val browseHits = java.util.concurrent.ConcurrentHashMap<Long, RemoteHit>()
    private val browseIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val browseSeq = java.util.concurrent.atomic.AtomicLong(1_000_000L)

    /**
     * TVBox 列表入库（全站点聚合 / 单站点切换共用）。
     *
     * 与 Emby 共用 [upsertMediaBatch]：一次 `byRemoteIds` 查已有行 → 一次 `insertAll` /
     * 一次 `updateAll` → 一次回填 id，整批只走 3~4 次 DB 调用。
     */
    private suspend fun saveTvBoxVods(
        source: SourceEntity,
        list: List<TvBoxClient.Vod>,
        onProgress: (Int) -> Unit
    ): List<MediaEntity> {
        if (list.isEmpty()) return emptyList()
        val incoming = list.map { vod ->
            vodToEntity(source.id, vod).copy(
                pinyinInitials = vod.name.toPinyinInitials(),
                sourceName = source.name
            )
        }
        val saved = upsertMediaAll(incoming, onProgress)
        return saved
    }

    /** TVBox 条目 → 剧集壳 MediaEntity（remoteId 带 siteKey，多站点不冲突） */
    private fun vodToEntity(sourceId: Long, vod: TvBoxClient.Vod): MediaEntity {
        val key = tvboxKey(sourceId, vod.siteKey, vod.vodId)
        return MediaEntity(
            sourceId = sourceId,
            uri = "",
            remoteId = key,
            seriesKey = key,
            title = vod.name,
            kind = MediaKind.SERIES,
            year = vod.year,
            overview = vod.content,
            posterUrl = vod.pic,
            backdropUrl = vod.pic,
            rating = vod.score,
            genres = vod.typeName,
            actors = vod.actors,
            directors = vod.directors,
            // 壳行（MOVIE/SERIES）的 playSource 存的是「线路（站点）名」，搜索页按线路分组要用它；
            // 分集行（EPISODE）仍存云盘/播放源名，详情页切线路的三处查询都带 kind='EPISODE'，两者互不影响。
            playSource = vod.siteName.ifBlank { vod.siteKey },
            remarks = vod.remarks
        )
    }

    /**
     * 拉取 TVBox 详情并入库分集。
     * 由详情页触发，返回值供 UI 立即展示线路列表。
     */
    suspend fun loadTvBoxDetail(
        source: SourceEntity,
        remoteId: String
    ): List<TvBoxClient.PlaySource> = withContext(Dispatchers.IO) {
        val (siteKey, vodId) = parseTvBoxKey(source.id, remoteId)
        val client = TvBoxClient(source.location, context)
        val detail = client.detail(vodId, siteKey) ?: return@withContext emptyList()

        val shell = mediaDao.byRemoteId(remoteId)
        val seriesKey = shell?.seriesKey?.takeIf { it.isNotBlank() } ?: remoteId

        // 方案C 的源侧一步：CatVod 协议没有 HDR/杜比字段，能从源拿到的只有备注和分集文件名。
        // 这里先把文本推测写进壳行，详情页不播放就能显示；播放期读到真实轨道后会覆盖它。
        val textHint = buildString {
            append(detail.remarks)
            detail.playSources.forEach { src ->
                src.episodes.forEach { ep -> append(' ').append(ep.name) }
            }
        }
        val hint = FormatProbe.fromText(textHint)

        // 用详情补全海报与简介
        shell?.let {
            mediaDao.update(
                it.copy(
                    overview = detail.content.ifBlank { it.overview },
                    posterUrl = detail.pic.ifBlank { it.posterUrl },
                    backdropUrl = detail.pic.ifBlank { it.backdropUrl },
                    rating = if (detail.score > 0) detail.score else it.rating,
                    year = if (detail.year > 0) detail.year else it.year,
                    actors = detail.actors.ifBlank { it.actors },
                    directors = detail.directors.ifBlank { it.directors },
                    remarks = detail.remarks.ifBlank { it.remarks },
                    videoResolution = hint.resolution.ifBlank { it.videoResolution },
                    hdrType = hint.hdr.ifBlank { it.hdrType },
                    videoCodec = hint.codec.ifBlank { it.videoCodec },
                    audioType = hint.audio.ifBlank { it.audioType }
                )
            )
        }

        // 把所有播放源（云盘/线路）的分集都入库，每条源用 playSource 字段标记。
        // 详情页据此展示「云盘切换」选项，默认选第一条源。
        if (detail.playSources.isEmpty()) return@withContext emptyList()

        // 清掉该剧之前入库的分集（避免旧源残留），再按最新详情全量写入。
        // loadTvBoxDetail 只在分集为空时触发，这里通常删不到东西；仅为重入时去重。
        mediaDao.deleteEpisodesBySeriesKey(seriesKey)

        // 批量入库：原来每集走一次 saveMedia（查来源 + 查重复 + 单条写），
        // 3 条源 × 40 集就是 360 次 DB 往返，电视盒子上详情页会明显卡顿。
        val incoming = detail.playSources.flatMapIndexed { sourceIndex, src ->
            src.episodes.mapIndexed { epIndex, ep ->
                MediaEntity(
                    sourceId = source.id,
                    uri = ep.url,
                    // remoteId 带上源下标，保证不同云盘的同一集不冲突
                    remoteId = "$remoteId:$sourceIndex:$epIndex",
                    seriesKey = seriesKey,
                    title = ep.name,
                    kind = MediaKind.EPISODE,
                    season = 1,
                    episode = epIndex + 1,
                    overview = detail.content,
                    posterUrl = detail.pic,
                    backdropUrl = detail.pic,
                    playSource = src.name,
                    pinyinInitials = ep.name.toPinyinInitials(),
                    sourceName = source.name
                )
            }
        }
        // 上面已按 seriesKey 清空旧分集，这里不会有重复行，一次批量写入即可
        if (incoming.isNotEmpty()) mediaDao.insertAll(incoming)

        detail.playSources
    }

    /** 演员/备注补齐只在一条 seriesKey 上试一次：源本来就不给这些字段时，重复请求白挨一次超时 */
    private val metaTried = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 只补剧集壳的演职员与更新备注，不碰分集行。
     *
     * 为什么要单独一条路：actors/directors/remarks 是新加的列，老库里的 TVBox 条目全是空串，
     * 而详情页的分集守卫（库里已有分集就不再拉详情）正好让它们没机会补。
     * 直接复用 [loadTvBoxDetail] 会把分集全删重插，顺手清掉「继续观看」进度，所以这里只写壳。
     *
     * 返回 true 表示库里这条记录已经被更新，调用方重新读一次就能看到。
     */
    suspend fun refreshTvBoxMeta(source: SourceEntity, remoteId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (source.kind != SourceKind.TVBOX) return@withContext false
            if (metaTried.putIfAbsent(remoteId, true) != null) return@withContext false
            val shell = mediaDao.byRemoteId(remoteId) ?: return@withContext false
            if (shell.kind != MediaKind.SERIES) return@withContext false
            val (siteKey, vodId) = parseTvBoxKey(source.id, remoteId)
            val detail = runCatching {
                TvBoxClient(source.location, context).detail(vodId, siteKey)
            }.getOrNull() ?: return@withContext false
            if (detail.actors.isBlank() && detail.directors.isBlank() && detail.remarks.isBlank()) {
                return@withContext false
            }
            mediaDao.update(
                shell.copy(
                    actors = detail.actors.ifBlank { shell.actors },
                    directors = detail.directors.ifBlank { shell.directors },
                    remarks = detail.remarks.ifBlank { shell.remarks }
                )
            )
            true
        }

    /** 供 ViewModel 调用：按 sourceId 找源后补元数据 */
    suspend fun refreshTvBoxMeta(sourceId: Long, remoteId: String): Boolean =
        withContext(Dispatchers.IO) {
            val src = sourceDao.get(sourceId) ?: return@withContext false
            refreshTvBoxMeta(src, remoteId)
        }

    /** TVBox 条目唯一键：配置型源可能含多个站点，用 siteKey 区分 */
    private fun tvboxKey(sourceId: Long, siteKey: String, vodId: String): String =
        if (siteKey.isBlank()) "tvbox:$sourceId:$vodId" else "tvbox:$sourceId:$siteKey:$vodId"

    private fun parseTvBoxKey(sourceId: Long, remoteId: String): Pair<String, String> {
        val rest = remoteId.removePrefix("tvbox:$sourceId:")
        val idx = rest.lastIndexOf(':')
        return if (idx > 0) rest.substring(0, idx) to rest.substring(idx + 1) else "" to rest
    }

    // ---------------- 进度 ----------------

    suspend fun saveProgress(mediaId: Long, positionMs: Long) {
        mediaDao.saveProgress(mediaId, positionMs, System.currentTimeMillis())
    }

    suspend fun fillDuration(mediaId: Long, durationMs: Long) {
        mediaDao.fillDuration(mediaId, durationMs)
    }

    /**
     * 清除观看记录：进度与最后播放时间归零，条目随即离开「继续观看」。
     * 刻意不删媒体行 —— 分集行删掉后详情页要重新联网拉一次，代价和风险都比归零大。
     *
     * 分集行按整部剧清：列表里一部剧只露最新的那条，只归零这一条的话，
     * 同剧更早的分集会顶上来，看着就像「清除没生效」。
     */
    suspend fun clearProgress(mediaId: Long) {
        val row = mediaDao.get(mediaId)
        if (row != null && row.kind == MediaKind.EPISODE && row.seriesKey.isNotBlank()) {
            mediaDao.clearProgressBySeries(row.seriesKey)
        } else {
            mediaDao.clearProgress(mediaId)
        }
        // 服务端也要清：本地删了观看记录，网页端/别的客户端还留着「已看」就对不上了
        runCatching { clearWatchedOnEmby(row) }
    }

    // ---------------- Emby 回传：观看进度 / 已看 / 收藏 ----------------

    /**
     * 离开这条播放时回传一次服务端。
     *
     * 一次请求搞定：没看完就把位置写回「续播点」，看完九成就直接标「已看」。
     * 播放期间不回传 —— 服务端只关心「你最后看到哪」，中途的心跳纯属浪费请求。
     * 回传失败不打断播放：这是锦上添花，不能因为它把正在看的片子搞挂。
     */
    suspend fun reportPlaybackToEmby(mediaId: Long, positionMs: Long, durationMs: Long) {
        val row = mediaDao.get(mediaId) ?: return
        val itemId = embyItemIdOf(row) ?: return
        val client = embyClientOf(row) ?: return

        val ticks = positionMs * 10_000L
        val watched = durationMs > 0L && positionMs >= durationMs * 9 / 10
        runCatching {
            if (watched) {
                client.markPlayed(itemId, ticks)
            } else {
                client.updatePlaybackPosition(itemId, ticks)
            }
        }.onFailure {
            Log.w("EmbyReport", "进度回传失败：${it.message}")
        }
    }

    /** 收藏同步回服务端；非 Emby 条目直接跳过 */
    private suspend fun syncFavoriteToEmby(mediaId: Long, favorite: Boolean) {
        val row = mediaDao.get(mediaId) ?: return
        val itemId = embyItemIdOf(row) ?: return
        val client = embyClientOf(row) ?: return
        client.setFavorite(itemId, favorite)
    }

    /** 清除观看记录时把服务端那条「已看 / 续播点」也撤掉 */
    private suspend fun clearWatchedOnEmby(row: MediaEntity?) {
        val r = row ?: return
        val itemId = embyItemIdOf(r) ?: return
        val client = embyClientOf(r) ?: return
        // 两个请求各管一头：PlayedItems 撤「已看」，UserData 把续播点归零
        // （只撤「已看」的话，条目还挂在服务端的「继续观看」里）
        runCatching { client.unmarkPlayed(itemId) }
        runCatching { client.updatePlaybackPosition(itemId, 0L) }
    }

    /**
     * Emby 的条目 id：remoteId 形如 emby:{sourceId}:{itemId}。
     * 不是 Emby 条目（TVBox）返回 null，调用方据此跳过回传。
     */
    private fun embyItemIdOf(row: MediaEntity): String? {
        if (!row.remoteId.startsWith("emby:")) return null
        val parts = row.remoteId.split(':')
        if (parts.size < 3) return null
        return parts[2].takeIf { it.isNotBlank() }
    }

    /** 该条目的 Emby 客户端；来源不是 Emby 或 token 失效时返回 null */
    private suspend fun embyClientOf(row: MediaEntity): EmbyClient? {
        val src = sourceDao.get(row.sourceId) ?: return null
        if (src.kind != SourceKind.EMBY) return null
        if (src.token.isBlank()) return null
        return EmbyClient(src.location, src.token, src.userId)
    }

    /** 预热出来的直链 + 解析时刻 */
    private data class CachedPlayUrl(val value: TvBoxClient.ResolvedPlayUrl, val at: Long)

    private companion object {
        /** 云盘分享令牌的保守有效期：入库超过这个时长就重新拉一次详情换新令牌 */
        const val CLOUD_URI_TTL_MS = 5 * 60 * 1000L

        /**
         * 预热直链的复用窗口。
         * 比云盘令牌的 5 分钟更短：直链是带签名的临时地址，宁可切集时重新解析一次慢 8 秒，
         * 也不要拿一条已失效的地址去起播 —— 那是直接报错，比慢更难交代。
         */
        const val RESOLVED_URL_TTL_MS = 3 * 60 * 1000L

        /**
         * 「分集 id|uri」→ 已解析直链。
         * 进程级：预取跑在当前播放器里，读它的是切集后的新起播路径；
         * key 带上 uri，分享令牌一换新，旧条目自然落空。
         */
        val RESOLVED_URLS = java.util.concurrent.ConcurrentHashMap<String, CachedPlayUrl>()

        /** 追剧会一集攒一条（每条带签名 URL 和 Cookie），先给个够一晚用完的上限，超了就丢过期的 */
        const val RESOLVED_URL_MAX = 64

        /**
         * seriesKey → 上次刷新 uri 的时间。
         * 必须是进程级：播放器每开一个 Activity 都会 new 一个 MediaRepository，
         * 放实例字段会导致连播时每集都先拉一次详情。
         */
        val CLOUD_REFRESH_AT = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** 热门榜联想缓存有效期：24 小时 */
        const val HOT_SUGGEST_TTL_MS = 24 * 60 * 60 * 1000L

        /** 拼音算法版本：TinyPinyin 为 1，改动算法时 +1 触发一次性重建 */
        const val PINYIN_VERSION = 1

        /** 远程搜索总条数上限：搜索结果只看前 20 条，拉全部既慢又刷屏 */
        const val REMOTE_RESULT_LIMIT = 20

        /** 远程搜索整批截止时间（毫秒）：所有站点并发搜索，最多总共等这么久 */
        const val SEARCH_DEADLINE_MS = 5000L
    }
}
