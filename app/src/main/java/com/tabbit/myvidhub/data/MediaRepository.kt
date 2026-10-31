package com.tabbit.myvidhub.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tabbit.myvidhub.MyVidHubApp
import com.tabbit.myvidhub.data.db.AppDatabase
import com.tabbit.myvidhub.data.db.HotTitleEntity
import com.tabbit.myvidhub.data.db.toPinyinInitials
import com.tabbit.myvidhub.data.douban.DoubanClient
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import com.tabbit.myvidhub.data.tvbox.TvBoxClient.TvBoxSite
import com.tabbit.myvidhub.data.tmdb.TmdbCache
import com.tabbit.myvidhub.data.tmdb.TmdbClient
import com.tabbit.myvidhub.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 媒体库仓储。
 *
 * 两类来源走两条完全不同的入库路径：
 * · Emby：一次拉回库里的电影/剧集，剧集再按 seriesId 逐部取分集
 * · TVBox：列表只入库「剧集壳」，分集等用户点进详情页再按需拉取
 *   （详情接口才带播放地址，提前全量拉取会打爆请求量）
 */
class MediaRepository(private val context: Context) {

    private val db: AppDatabase = MyVidHubApp.instance.db
    private val sourceDao = db.sourceDao()
    private val mediaDao = db.mediaDao()
    private val hotDao = db.hotTitleDao()

    fun observeSources(): Flow<List<SourceEntity>> = sourceDao.observeAll()
    fun observeLibrary(): Flow<List<MediaEntity>> = mediaDao.observeLibrary()
    fun observeContinueWatching(): Flow<List<MediaEntity>> = mediaDao.observeContinueWatching()
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

    suspend fun setFavorite(id: Long, favorite: Boolean) = mediaDao.setFavorite(id, favorite)

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
     * @param episode 分集实体（需包含 sourceId、seriesKey、playSource、uri）
     * @return 解析结果（URL + headers）；非 TVBox 来源原样返回 uri
     */
    suspend fun resolveTvBoxPlayUrl(episode: MediaEntity): TvBoxClient.ResolvedPlayUrl = withContext(Dispatchers.IO) {
        val source = sourceDao.get(episode.sourceId)
            ?: return@withContext TvBoxClient.ResolvedPlayUrl(episode.uri)
        if (source.kind != SourceKind.TVBOX) return@withContext TvBoxClient.ResolvedPlayUrl(episode.uri)

        // episode.seriesKey = tvbox:sourceId:siteKey:vodId
        val (siteKey, _) = parseTvBoxKey(episode.sourceId, episode.seriesKey)
        val vipFlags = mediaDao.playSourcesOf(episode.seriesKey)

        val client = TvBoxClient(source.location, context)
        client.resolvePlayUrl(siteKey, episode.playSource, episode.uri, vipFlags)
    }

    // ---------------- 首页分类 / 浏览页 ----------------

    /** 首页画廊：每类只取最新 N 条（懒加载，避免整库刷进内存） */
    fun observeRecent(kind: String, limit: Int): Flow<List<MediaEntity>> =
        mediaDao.observeRecent(kind, limit)

    fun observeAnime(limit: Int): Flow<List<MediaEntity>> = mediaDao.observeAnime(limit)

    /** 分类浏览页分页查询 */
    suspend fun byGenre(kind: String?, genre: String?, limit: Int, offset: Int): List<MediaEntity> =
        mediaDao.byGenre(kind, genre, limit, offset)

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

    /**
     * TMDB 刮削：按标题+年份搜索并拉取海报/背景图/Logo/评分/简介/演职员。
     * 作为 Emby 刮削的补充，TVBox 源没有元数据时尤其有用。
     * 未配置 TMDB API Key 时返回 null。
     */
    /**
     * TMDB 刮削（带防频繁请求保护）。
     *
     * 三层拦截，越靠前越省钱：
     * 1. 内存缓存命中 → 直接返回（7 天内不重复请求；刮不到的也缓存 1 天）
     * 2. 同 key 并发请求去重 → 复用第一次的结果
     * 3. 都未命中才真正发网络请求，成功后顺手回填数据库
     *
     * [mediaId] 传入时，刮到的元数据会写回 media 表并标记 scrapedAt，
     * 这样即使 App 重启、内存缓存丢了，库里也还有数据可用。
     */
    suspend fun tmdbScrape(
        title: String,
        year: Int,
        kind: String,
        mediaId: Long = 0L
    ): TmdbClient.ScrapeResult? = withContext(Dispatchers.IO) {
        val apiKey = Prefs.tmdbApiKey
        val token = Prefs.tmdbReadToken
        if (apiKey.isBlank() && token.isBlank()) return@withContext null

        val cacheKey = TmdbCache.keyOf(title, year, kind)
        val result = runCatching {
            TmdbCache.getOrFetch(cacheKey) {
                TmdbClient(apiKey, token).scrape(title, year, kind)
            }
        }.getOrNull() ?: return@withContext null

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

    /** 清空 TMDB 内存缓存 + 数据库刮削标记（设置页入口） */
    suspend fun clearScrapeCache(): Int = withContext(Dispatchers.IO) {
        TmdbCache.clear()
        mediaDao.clearAllScrapeMarks()
    }

    /** 当前缓存条目数 */
    fun scrapeCacheSize(): Int = TmdbCache.size()

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
     * 按 remoteId 写入元数据。
     *
     * 已存在时保留 id / 播放进度 / 收藏 / 添加时间，并刷新源同步来的字段。
     *
     * 关键语义：**源同步字段有值就用源的，源给空字符串就保留 DB 已有值**。
     * 这样 Emby/TVBox 重同步时不会把 TMDB 补好的 logoUrl / posterUrl / overview
     * 等字段冲成空。优先级：Emby 自带元数据 > TVBox 返回字段 > TMDB 补空。
     *
     * 不能用 upsert(REPLACE)：那会删行重插，进度和收藏都会丢。
     */
    private suspend fun saveMedia(entity: MediaEntity) {
        // 入库前统一补齐拼音首字母和来源名称（运行时零开销，库里有就覆盖）
        val srcName = sourceDao.get(entity.sourceId)?.name ?: ""
        val enriched = entity.copy(
            pinyinInitials = entity.title.toPinyinInitials(),
            sourceName = srcName
        )
        val existing = mediaDao.byRemoteId(enriched.remoteId)
        if (existing == null) {
            mediaDao.insert(enriched)
        } else {
            mediaDao.update(
                enriched.copy(
                    id = existing.id,
                    // ---- 播放相关：始终保留已有进度，源同步不会覆盖 ----
                    positionMs = existing.positionMs,
                    lastPlayedAt = existing.lastPlayedAt,
                    favorite = existing.favorite,
                    addedAt = existing.addedAt,
                    durationMs = if (entity.durationMs > 0) entity.durationMs else existing.durationMs,
                    // ---- 媒体元数据：源有值就用它，源给空就保留 DB 已有（可能是 TMDB 补的） ----
                    posterUrl = entity.posterUrl.ifBlank { existing.posterUrl },
                    backdropUrl = entity.backdropUrl.ifBlank { existing.backdropUrl },
                    logoUrl = entity.logoUrl.ifBlank { existing.logoUrl },
                    overview = entity.overview.ifBlank { existing.overview },
                    genres = entity.genres.ifBlank { existing.genres },
                    rating = if (entity.rating > 0) entity.rating else existing.rating,
                    year = if (entity.year > 0) entity.year else existing.year
                )
            )
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
     * 同步 Emby 媒体库 —— 整库只发一次列表请求。
     *
     * 与 VidHub 等主流播放器一致：列表阶段只入库「电影 + 剧集壳」，
     * 分集在用户点进详情页时按需加载（loadEmbyDetail）。
     * 之前逐部剧集循环拉分集，几十部剧就是几十个请求，又慢又容易被服务器限流。
     */
    suspend fun syncEmby(source: SourceEntity, onProgress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            if (source.token.isBlank()) {
                throw IllegalStateException("Emby 尚未登录，请先点刷新重新登录")
            }
            val client = EmbyClient(source.location, source.token, source.userId)

            // 旧版本剧集壳的 remoteId 没带 sourceId，会与新数据重复，先清掉
            mediaDao.deleteLegacyEmbyShells(source.id)

            val items = client.fetchLibrary()
            if (items.isEmpty()) return@withContext 0

            var count = 0
            for (item in items) {
                // remoteId 统一带 sourceId：多台 Emby 时不会因 itemId 相同而互相覆盖
                val key = "emby:${source.id}:${item.id}"
                val isSeries = item.isSeries
                saveMedia(
                    MediaEntity(
                        sourceId = source.id,
                        uri = if (isSeries) "" else EmbyClient.playbackUrl(source.location, item.id, source.token),
                        remoteId = key,
                        seriesKey = if (isSeries) key else "",
                        title = item.name,
                        kind = if (isSeries) MediaKind.SERIES else MediaKind.MOVIE,
                        year = item.year,
                        overview = item.overview,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        logoUrl = item.logoUrl,
                        rating = item.rating,
                        genres = item.genres,
                        durationMs = item.runTimeMs
                    )
                )
                count++
                if (count % 20 == 0) onProgress(count)
            }
            count
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
            episodes.forEachIndexed { index, ep ->
                saveMedia(
                    MediaEntity(
                        sourceId = source.id,
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
                        logoUrl = shell.logoUrl
                    )
                )
            }
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
     * 启动预热：把启用中的 TVBox 来源的 spider 包准备到能调用（落盘 → 壳 Init → 起本地代理），
     * 不请求任何站点数据、失败静默。
     *
     * 加密壳的 Init 是纯本地单核计算，电视上实测要二十多秒。放在这里跑，是为了让首页/分类/详情
     * 那几条链路走到 SpiderLoader 时直接命中进程级缓存，不再当着我的面初始化；跑完远程搜索
     * 也就有可用 loader（搜索走 cachedLoaderKeyOrNull，只认缓存不触发加载）。
     *
     * 逐个来源串行执行：壳 Init 吃满一个核，和首页渲染并发会互相抢 CPU，反而双双变慢。
     * 多个来源共用同一个 spider 包时，第二个来源在 SpiderLoader 缓存上瞬时返回，不重复解密。
     *
     * @param preferredSourceId 首页当前来源，排在最前面预热 —— 用户第一眼要用的那条链路不能被别的来源挡在后面
     */
    suspend fun warmUpTvBoxSpiders(preferredSourceId: Long = -1L): Unit = withContext(Dispatchers.IO) {
        val tvboxSources = runCatching { enabledSources().filter { it.kind == SourceKind.TVBOX } }
            .getOrDefault(emptyList())
            .sortedByDescending { it.id == preferredSourceId }
        for (source in tvboxSources) {
            val t0 = SystemClock.elapsedRealtime()
            val result = runCatching { TvBoxClient(source.location, context).prepareSpider() }
            val cost = SystemClock.elapsedRealtime() - t0
            if (result.getOrDefault(false)) {
                Log.w("SpiderLoader", "预热完成 ${source.name} 用时 ${cost}ms")
            } else {
                Log.w("SpiderLoader", "预热未就绪 ${source.name} 用时 ${cost}ms：${result.exceptionOrNull()?.message ?: "配置无 spider 字段"}")
            }
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
     */
    suspend fun syncTvBoxSite(
        source: SourceEntity,
        site: TvBoxSite,
        onProgress: (Int) -> Unit = {}
    ): TvBoxClient.SiteHomeResult = withContext(Dispatchers.IO) {
        val client = TvBoxClient(source.location, context)
        // 不吞异常：把站点加载失败的真实原因（壳初始化超时等）透出到 UI
        val result = client.siteHome(site)
        saveTvBoxVods(source, result.vods, onProgress)
        result
    }

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
            genres = vod.typeName
        )
    }

    /** 浏览临时 id 注册表（与搜索共用详情页落库入口） */
    private val browseHits = java.util.concurrent.ConcurrentHashMap<Long, RemoteHit>()
    private val browseIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val browseSeq = java.util.concurrent.atomic.AtomicLong(1_000_000L)

    /**
     * TVBox 列表入库（全站点聚合 / 单站点切换共用）。
     *
     * 改成批处理：一次 `byRemoteIds` 查已有行 → 一次 `insertAll` / 一次 `updateAll` → 一次回填 id，
     * 整批只走 3~4 次 DB 调用，替代之前的「每条 insert + byRemoteId」。
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
                    year = if (entity.year > 0) entity.year else old.year
                )
            }
        }
        if (toInsert.isNotEmpty()) mediaDao.insertAll(toInsert)
        if (toUpdate.isNotEmpty()) mediaDao.updateAll(toUpdate)
        onProgress(incoming.size)
        val saved = mediaDao.byRemoteIds(incoming.map { it.remoteId }).associateBy { it.remoteId }
        return incoming.map { saved[it.remoteId] ?: it }
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
            genres = vod.typeName
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

        // 用详情补全海报与简介
        shell?.let {
            mediaDao.update(
                it.copy(
                    overview = detail.content.ifBlank { it.overview },
                    posterUrl = detail.pic.ifBlank { it.posterUrl },
                    backdropUrl = detail.pic.ifBlank { it.backdropUrl },
                    rating = if (detail.score > 0) detail.score else it.rating,
                    year = if (detail.year > 0) detail.year else it.year
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
     */
    suspend fun clearProgress(mediaId: Long) {
        mediaDao.clearProgress(mediaId)
    }

    private companion object {
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
