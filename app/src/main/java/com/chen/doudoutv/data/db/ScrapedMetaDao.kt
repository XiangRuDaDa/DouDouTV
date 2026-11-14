package com.chen.doudoutv.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.chen.doudoutv.data.tmdb.TmdbClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 刮削结果表：一条 = 一部片（按清洗后的片名归并），与 media 表的那一行解耦。
 *
 * 为什么要单独一张表：
 * · media 行的元数据是「只补空」写入的（保护 Emby 自己库里的数据），源站已经给了豆瓣海报时，
 *   TMDB 的海报就永远写不进去；logo 反而能写进去，于是出现「只有 logo 是刮来的」这种半截状态。
 * · 演职员与标语在 media 表里根本没有对应的列，以前只活在内存缓存中 —— 进程一重启，
 *   主演就退化成源站那一两个名字、简介消失。
 * · 同一部剧在两条线路 / 两个来源各有一行壳，按行存等于每行都要重刮一遍。
 *
 * 键的口径和 [com.chen.doudoutv.data.tmdb.TmdbCache] 完全一致：片名先清洗再归一化，
 * 所以「嫁金钗（臻彩）」与「嫁金钗[4K]」是同一条记录。
 * scrapeKey 带年份（精确命中用），titleKey 不带年份（同名不同年的回落用），
 * 两个都留着才能做到「同年优先、否则用同名最近一次的结果」而不是互相覆盖。
 */
@Entity(tableName = "scraped_meta", indices = [Index("titleKey")])
data class ScrapedMetaEntity(
    @PrimaryKey val scrapeKey: String,
    val titleKey: String,
    /** 清洗后的片名，只用来核对与排查 */
    val queryTitle: String,
    val kind: String,
    val year: Int,
    val overview: String,
    val genres: String,
    val rating: Double,
    val posterUrl: String,
    val backdropUrl: String,
    val logoUrl: String,
    val tagline: String,
    // 导演与主演都是 [{name, role, imageUrl}] 这一份结构，序列化成 JSON 串存
    val directorsJson: String,
    val castJson: String,
    val scrapedAt: Long
) {

    /** 还原成详情页用的结果对象（与网络刮回来的同一个类型，渲染路径不用分叉） */
    fun toScrapeResult(): TmdbClient.ScrapeResult = TmdbClient.ScrapeResult(
        overview = overview,
        rating = rating,
        genres = genres,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        logoUrl = logoUrl,
        tagline = tagline,
        directors = decodePeople(directorsJson),
        actors = decodePeople(castJson)
    )

    companion object {
        fun encodePeople(people: List<TmdbClient.Person>): String {
            val arr = JSONArray()
            people.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("name", p.name)
                        .put("role", p.role)
                        .put("imageUrl", p.imageUrl)
                )
            }
            return arr.toString()
        }

        private fun decodePeople(json: String): List<TmdbClient.Person> {
            if (json.isBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    if (name.isNotBlank()) add(TmdbClient.Person(name, o.optString("role"), o.optString("imageUrl")))
                }
            }
        }
    }
}

@Dao
interface ScrapedMetaDao {

    /** 同年精确命中 */
    @Query("SELECT * FROM scraped_meta WHERE scrapeKey = :scrapeKey LIMIT 1")
    suspend fun byKey(scrapeKey: String): ScrapedMetaEntity?

    /**
     * 同名回落：清洗后同名、但源站给的年份不一致（一条线路填 2026、另一条填 0）时用最近一次结果。
     * 排序按 scrapedAt 而不是按年份差，是为了让「刚刮过的那条」赢 —— 年份差得再远也可能是同名老片，
     * 与其赌一个更近的年份，不如复用最新一次确认过的结果。
     * 返回全部同名行：同名动画 / 剧版是两条，调用方按类别挑，不能只拿最新一条。
     */
    @Query("SELECT * FROM scraped_meta WHERE titleKey = :titleKey ORDER BY scrapedAt DESC")
    suspend fun byTitleKeyAll(titleKey: String): List<ScrapedMetaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScrapedMetaEntity)

    @Query("DELETE FROM scraped_meta")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM scraped_meta")
    suspend fun count(): Int
}
