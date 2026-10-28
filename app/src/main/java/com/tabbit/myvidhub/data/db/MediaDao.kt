package com.tabbit.myvidhub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tabbit.myvidhub.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND m.kind IN ('MOVIE','SERIES') ORDER BY m.addedAt DESC"
    )
    fun observeLibrary(): Flow<List<MediaEntity>>

    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND m.positionMs > 5000 AND m.kind IN ('MOVIE','EPISODE') " +
            "ORDER BY m.lastPlayedAt DESC LIMIT 20"
    )
    fun observeContinueWatching(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE kind = 'MOVIE' ORDER BY addedAt DESC LIMIT 20")
    fun observeMovies(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE kind = 'SERIES' ORDER BY addedAt DESC LIMIT 20")
    fun observeSeries(): Flow<List<MediaEntity>>

    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND m.backdropUrl != '' ORDER BY m.addedAt DESC LIMIT 8"
    )
    fun observeFeatured(): Flow<List<MediaEntity>>

    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND m.favorite = 1 AND m.kind IN ('MOVIE','SERIES') " +
            "ORDER BY m.addedAt DESC"
    )
    fun observeFavorites(): Flow<List<MediaEntity>>

    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND (m.title LIKE '%' || :keyword || '%' " +
            "OR m.pinyinInitials LIKE '%' || :pinyin || '%') " +
            "AND m.kind IN ('MOVIE','SERIES') ORDER BY m.addedAt DESC LIMIT 60"
    )
    suspend fun search(keyword: String, pinyin: String = keyword): List<MediaEntity>

    /**
     * 拼音联想：对用户输入做前缀匹配（只匹配 MOVIE/SERIES，不包含分集），
     * 返回 top 10 的原标题供 UI 下拉展示。
     */
    @Query(
        "SELECT DISTINCT m.title FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND m.pinyinInitials LIKE :pinyinPrefix " +
            "AND m.kind IN ('MOVIE','SERIES') ORDER BY m.addedAt DESC LIMIT 10"
    )
    suspend fun pinyinSuggest(pinyinPrefix: String): List<String>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: Long): MediaEntity?

    /** 拼音索引重建用：所有出现过的片名 */
    @Query("SELECT DISTINCT title FROM media")
    suspend fun distinctTitles(): List<String>

    /** 拼音索引重建用：按片名批量改写首字母 */
    @Query("UPDATE media SET pinyinInitials = :initials WHERE title = :title")
    suspend fun updatePinyinByTitle(title: String, initials: String)

    @Query("SELECT * FROM media WHERE remoteId = :remoteId LIMIT 1")
    suspend fun byRemoteId(remoteId: String): MediaEntity?

    @Query(
        "SELECT * FROM media WHERE seriesKey = :seriesKey AND kind = 'EPISODE' " +
            "ORDER BY season ASC, episode ASC"
    )
    suspend fun episodes(seriesKey: String): List<MediaEntity>

    /** 某剧指定播放源（云盘/线路）的分集 */
    @Query(
        "SELECT * FROM media WHERE seriesKey = :seriesKey AND kind = 'EPISODE' " +
            "AND playSource = :playSource ORDER BY season ASC, episode ASC"
    )
    suspend fun episodesBySource(seriesKey: String, playSource: String): List<MediaEntity>

    /** 某剧所有不同的播放源名称（按入库顺序） */
    @Query(
        "SELECT playSource FROM media WHERE seriesKey = :seriesKey AND kind = 'EPISODE' " +
            "AND playSource != '' GROUP BY playSource ORDER BY MIN(addedAt)"
    )
    suspend fun playSourcesOf(seriesKey: String): List<String>

    @Query("SELECT DISTINCT season FROM media WHERE seriesKey = :seriesKey AND kind='EPISODE' ORDER BY season")
    suspend fun seasons(seriesKey: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaEntity): Long

    /** TVBox 批量入库：一次事务插入，避免逐条 insert */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MediaEntity>): List<Long>

    /** TVBox 批量入库：一次事务更新已有行 */
    @Update
    suspend fun updateAll(items: List<MediaEntity>)

    /** 批量按 remoteId 取已有行（TVBox 批量入库前一次查完，替代逐条 byRemoteId） */
    @Query("SELECT * FROM media WHERE remoteId IN (:remoteIds)")
    suspend fun byRemoteIds(remoteIds: List<String>): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaEntity): Long

    @Update
    suspend fun update(item: MediaEntity)

    @Query("UPDATE media SET positionMs = :position, lastPlayedAt = :ts WHERE id = :id")
    suspend fun saveProgress(id: Long, position: Long, ts: Long)

    @Query("UPDATE media SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE media SET durationMs = :duration WHERE id = :id AND durationMs = 0")
    suspend fun fillDuration(id: Long, duration: Long)

    @Query("DELETE FROM media WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** 清空某剧的全部分集（TVBox 详情重入时去重用，不删剧集壳） */
    @Query("DELETE FROM media WHERE seriesKey = :seriesKey AND kind = 'EPISODE'")
    suspend fun deleteEpisodesBySeriesKey(seriesKey: String)

    /** 清理旧版本遗留的 Emby 剧集壳（remoteId 未带 sourceId 的那批） */
    @Query(
        "DELETE FROM media WHERE sourceId = :sourceId AND remoteId LIKE 'emby:%' " +
            "AND remoteId NOT LIKE 'emby:' || :sourceId || ':%'"
    )
    suspend fun deleteLegacyEmbyShells(sourceId: Long)

    @Query("SELECT COUNT(*) FROM media WHERE sourceId = :sourceId")
    suspend fun countBySource(sourceId: Long): Int

    /** 统计某来源下 remoteId 前缀匹配的条目数（判断某 TVBox 站点是否已同步过） */
    @Query("SELECT COUNT(*) FROM media WHERE sourceId = :sourceId AND remoteId LIKE :prefix")
    suspend fun countByRemotePrefix(sourceId: Long, prefix: String): Int

    @Query("SELECT COUNT(*) FROM media")
    suspend fun countAll(): Int

    /** 首页分类画廊：每类只取最新 N 条，避免一次性加载全库 */
    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND m.kind = :kind ORDER BY m.addedAt DESC LIMIT :limit"
    )
    fun observeRecent(kind: String, limit: Int): Flow<List<MediaEntity>>

    /** 动漫：类型字段包含"动漫/动画/Animation"即命中 */
    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND (m.genres LIKE '%动漫%' OR m.genres LIKE '%动画%' " +
            "OR m.genres LIKE '%Animation%') ORDER BY m.addedAt DESC LIMIT :limit"
    )
    fun observeAnime(limit: Int): Flow<List<MediaEntity>>

    /** 分类浏览页：按类型/题材分页查询，kind 或 genre 传 null 表示不过滤 */
    @Query(
        "SELECT m.* FROM media m JOIN sources s ON m.sourceId = s.id " +
            "WHERE s.enabled = 1 AND (:kind IS NULL OR m.kind = :kind) " +
            "AND (:genre IS NULL OR m.genres LIKE '%' || :genre || '%') " +
            "ORDER BY m.addedAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun byGenre(kind: String?, genre: String?, limit: Int, offset: Int): List<MediaEntity>

    @Query("SELECT DISTINCT genres FROM media WHERE genres != ''")
    suspend fun distinctGenres(): List<String>

    /**
     * TMDB 刮削结果回填。
     *
     * 只补空字段（COALESCE/NULLIF 判断），不覆盖 Emby 已有的元数据：
     * Emby 是用户自己的库，优先级更高；TMDB 只做补充。
     * 同时写入 scrapedAt，下次打开详情页就能凭它判断"已有缓存、不必再联网"。
     */
    @Query(
        "UPDATE media SET " +
            "posterUrl = CASE WHEN posterUrl = '' THEN :posterUrl ELSE posterUrl END, " +
            "backdropUrl = CASE WHEN backdropUrl = '' THEN :backdropUrl ELSE backdropUrl END, " +
            "logoUrl = CASE WHEN logoUrl = '' THEN :logoUrl ELSE logoUrl END, " +
            "overview = CASE WHEN overview = '' THEN :overview ELSE overview END, " +
            "genres = CASE WHEN genres = '' THEN :genres ELSE genres END, " +
            "rating = CASE WHEN rating <= 0 THEN :rating ELSE rating END, " +
            "scrapedAt = :scrapedAt " +
            "WHERE id = :id"
    )
    suspend fun updateScrapedMeta(
        id: Long,
        posterUrl: String,
        backdropUrl: String,
        logoUrl: String,
        overview: String,
        genres: String,
        rating: Double,
        scrapedAt: Long
    )

    /** 取单条，刮削前核对库里是否已有可用元数据 */
    @Query("SELECT * FROM media WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaEntity?

    /** 批量标记：启动清理时把过期的 scrapedAt 归零，让详情页重新刮 */
    @Query("UPDATE media SET scrapedAt = 0 WHERE scrapedAt > 0 AND scrapedAt < :before")
    suspend fun clearStaleScrapeMarks(before: Long): Int

    /** 清空全部刮削标记（设置页"清空刮削缓存"用）：TMDB 补的字段保持不动，只让下次重新校验 */
    @Query("UPDATE media SET scrapedAt = 0")
    suspend fun clearAllScrapeMarks(): Int
}
