package com.tabbit.myvidhub.data

import android.content.Context
import com.tabbit.myvidhub.MyVidHubApp
import com.tabbit.myvidhub.data.db.AppDatabase
import com.tabbit.myvidhub.data.db.toPinyinInitials
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 媒体库仓储。
 *
 * 两类来源走两条完全不同的入库路径：
 * · Emby：一次拉回库里的电影/剧集，剧集再按 seriesId 逐部取分集
 * · TVBox：列表只入库「剧集壳」，分集等用户点进详情页再按需拉取
 *   （CMS 接口的详情才带播放地址，提前全量拉取会打爆请求量）
 */
class MediaRepository(private val context: Context) {

    private val db: AppDatabase = MyVidHubApp.instance.db
    private val sourceDao = db.sourceDao()
    private val mediaDao = db.mediaDao()

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
        return if (q.any { it in 'a'..'z' }) mediaDao.pinyinSuggest("$q%")
        else mediaDao.search(q, q).map { it.title }.distinct().take(10)
    }

    /**
     * TVBox 远程搜索：遍历所有已启用的 TVBox 源，每个源下再遍历它的全部线路站点，
     * 并发调 Guard.searchContent()。返回的 Vod 不入库，直接转临时 MediaEntity 展示。
     */
    suspend fun tvBoxRemoteSearch(keyword: String): List<MediaEntity> = withContext(Dispatchers.IO) {
        val tvboxSources = enabledSources().filter { it.kind == SourceKind.TVBOX }
        if (tvboxSources.isEmpty()) return@withContext emptyList()

        // 所有 (source, site) 对并发搜索
        val jobs = tvboxSources.flatMap { source ->
            runCatching {
                val client = TvBoxClient(source.location, context)
                client.availableSites().filter { it.type == 3 }.map { site ->
                    async {
                        runCatching { client.search(site, keyword) }
                            .getOrDefault(emptyList())
                            .map { it to source.name }
                    }
                }
            }.getOrDefault(emptyList())
        }
        val flat = jobs.awaitAll().flatten()

        // 去重（同一影片可能被多个线路搜到）
        val dedup = flat.distinctBy { (vod, _) -> vod.vodId + vod.siteKey }

        dedup.mapIndexed { index, (vod, srcName) ->
            MediaEntity(
                id = -(index + 1).toLong(),  // 负数 id = 远程临时结果
                sourceId = 0L,
                uri = "",
                remoteId = "remote:${vod.siteKey}:${vod.vodId}",
                title = vod.name,
                kind = MediaKind.MOVIE,
                year = vod.year,
                posterUrl = vod.pic,
                genres = vod.typeName,
                overview = vod.content,
                sourceName = srcName,
                // 远程结果也计算拼音（方便再搜）
                pinyinInitials = vod.name.toPinyinInitials()
            )
        }
    }
    suspend fun setFavorite(id: Long, favorite: Boolean) = mediaDao.setFavorite(id, favorite)

    /** 从头播放：把进度归零，播放器读取后就从 0s 开始 */
    suspend fun resetPosition(id: Long) = mediaDao.saveProgress(id, 0L, 0L)

    suspend fun addSource(source: SourceEntity): Long = sourceDao.insert(source)
    suspend fun getSource(id: Long): SourceEntity? = sourceDao.get(id)
    suspend fun allSources(): List<SourceEntity> = sourceDao.getAll()
    suspend fun enabledSources(): List<SourceEntity> = sourceDao.getEnabled()
    suspend fun updateSource(source: SourceEntity) = sourceDao.update(source)
    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = sourceDao.setEnabled(id, enabled)

    suspend fun deleteSource(source: SourceEntity) {
        mediaDao.deleteBySource(source.id)
        sourceDao.delete(source)
    }

    suspend fun getMedia(id: Long): MediaEntity? = mediaDao.get(id)
    suspend fun byRemoteId(remoteId: String): MediaEntity? = mediaDao.byRemoteId(remoteId)
    suspend fun episodes(seriesKey: String): List<MediaEntity> = mediaDao.episodes(seriesKey)
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
     * 解析 TVBox 云盘播放地址。
     *
     * 云盘 scheme 地址（quark://、aliyundrive:// 等）不是视频直链，
     * 播放器启动前需调用 spider 的 playerContent 解析出真实 URL 及请求头。
     *
     * @param episode 分集实体（需包含 sourceId、seriesKey、playSource、uri）
     * @return 解析结果（URL + headers）；非云盘地址返回 uri 且 headers 为空
     */
    suspend fun resolveTvBoxPlayUrl(episode: MediaEntity): TvBoxClient.ResolvedPlayUrl = withContext(Dispatchers.IO) {
        val source = sourceDao.get(episode.sourceId)
            ?: return@withContext TvBoxClient.ResolvedPlayUrl(episode.uri)
        if (source.kind != SourceKind.TVBOX) return@withContext TvBoxClient.ResolvedPlayUrl(episode.uri)

        // 云盘 scheme（非 http/https）才需要解析
        val uri = episode.uri
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return@withContext TvBoxClient.ResolvedPlayUrl(uri)
        }

        // episode.seriesKey = tvbox:sourceId:siteKey:vodId
        val (siteKey, _) = parseTvBoxKey(episode.sourceId, episode.seriesKey)
        val vipFlags = mediaDao.playSourcesOf(episode.seriesKey)

        val client = TvBoxClient(source.location, context)
        client.resolvePlayUrl(siteKey, episode.playSource, uri, vipFlags)
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
                throw IllegalStateException("接口未返回任何影片，请确认地址能返回 ac=videolist 的 JSON")
            }
            saveTvBoxVods(source, list, onProgress).size
        }

    /** 拉取 TVBox 配置里的站点（线路）列表，供首页线路切换 */
    suspend fun tvBoxSites(source: SourceEntity): List<TvBoxSite> = withContext(Dispatchers.IO) {
        if (source.kind != SourceKind.TVBOX) return@withContext emptyList()
        runCatching { TvBoxClient(source.location, context).availableSites() }.getOrDefault(emptyList())
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
     * 拉取某站点某分类的内容（categoryContent）并入库，返回带 DB id 的实体列表，
     * 供首页点分类后按分区展示、并能进详情。
     */
    suspend fun tvBoxCategory(
        source: SourceEntity,
        site: TvBoxSite,
        typeId: String,
        page: Int = 1
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        if (source.kind != SourceKind.TVBOX) return@withContext emptyList()
        val vods = TvBoxClient(source.location, context).categoryContent(site, typeId, page)
        if (vods.isEmpty()) return@withContext emptyList()
        saveTvBoxVods(source, vods) {}
    }

    /** TVBox 列表入库（全站点聚合 / 单站点切换 / 分类内容共用）；返回入库后的实体列表（含 DB id） */
    private suspend fun saveTvBoxVods(
        source: SourceEntity,
        list: List<TvBoxClient.Vod>,
        onProgress: (Int) -> Unit
    ): List<MediaEntity> {
        val saved = ArrayList<MediaEntity>(list.size)
        var count = 0
        for (vod in list) {
            val entity = vodToEntity(source.id, vod)
            saveMedia(entity)
            saved += mediaDao.byRemoteId(entity.remoteId) ?: entity
            count++
            if (count % 10 == 0) onProgress(count)
        }
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

        detail.playSources.forEachIndexed { sourceIndex, src ->
            src.episodes.forEachIndexed { epIndex, ep ->
                saveMedia(
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
                        playSource = src.name
                    )
                )
            }
        }

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
}
