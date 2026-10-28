package com.tabbit.myvidhub.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * 热门片名联想索引（豆瓣热门榜缓存）。
 *
 * 与 media 表分开存：这些片只是「搜索建议」，用户库里并没有对应资源，
 * 不能混进媒体库列表，也不参与播放/收藏。
 * 只缓存片名 + 拼音首字母 + 排名，24 小时刷新一次。
 */
@Entity(
    tableName = "hot_titles",
    indices = [Index("pinyinInitials")]
)
data class HotTitleEntity(
    @PrimaryKey val title: String,
    val pinyinInitials: String,
    /** MOVIE / SERIES */
    val kind: String,
    /** 榜单内排序，越小越热门 */
    val rank: Int,
    val updatedAt: Long
)

@Dao
interface HotTitleDao {

    /** 拼音首字母前缀匹配，按热度排序 */
    @Query(
        "SELECT title FROM hot_titles WHERE pinyinInitials LIKE :prefix " +
            "ORDER BY rank ASC LIMIT :limit"
    )
    suspend fun byPrefix(prefix: String, limit: Int): List<String>

    /** 最近一次刷新时间，null 表示从没拉过 */
    @Query("SELECT MAX(updatedAt) FROM hot_titles")
    suspend fun lastUpdated(): Long?

    /** 拼音索引重建用 */
    @Query("SELECT title FROM hot_titles")
    suspend fun distinctTitles(): List<String>

    /** 拼音索引重建用 */
    @Query("UPDATE hot_titles SET pinyinInitials = :initials WHERE title = :title")
    suspend fun updatePinyinByTitle(title: String, initials: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<HotTitleEntity>)

    @Query("DELETE FROM hot_titles")
    suspend fun clear()

    /** 整表替换：新榜单一次写入，避免旧数据残留 */
    @Transaction
    suspend fun replaceAll(items: List<HotTitleEntity>) {
        clear()
        upsertAll(items)
    }
}
