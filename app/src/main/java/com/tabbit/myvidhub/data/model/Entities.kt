package com.tabbit.myvidhub.data.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/** 只保留两类来源 */
enum class SourceKind {
    EMBY,   // Emby 媒体服务器（自建影库）
    TVBOX   // TVBox 配置 JSON（type=3 spider 站点）
}

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val kind: SourceKind,
    /** EMBY: 服务器地址 https://emby.example.com ; TVBOX: TVBox 配置 JSON 地址 */
    val location: String,
    val username: String = "",
    val password: String = "",
    /** Emby 登录后的 AccessToken，避免每次重启重新登录 */
    val token: String = "",
    /** Emby 用户 Id */
    val userId: String = "",
    /** 是否启用：停用后不参与同步，其内容也不在首页展示 */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

object MediaKind {
    const val MOVIE = "MOVIE"
    const val SERIES = "SERIES"
    const val EPISODE = "EPISODE"
}

@Entity(
    tableName = "media",
    indices = [
        Index("sourceId"),
        Index("seriesKey"),
        // 唯一性放在 remoteId 上：Emby itemId / TVBox vodId:集数
        // 不能放 uri —— 剧集壳条目的 uri 为空字符串，多个壳会互相冲突
        Index(value = ["remoteId"], unique = true)
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceId: Long,
    /** 播放地址：Emby 直链 / TVBox m3u8 */
    val uri: String,
    /** 唯一标识：Emby itemId 或 TVBox siteKey:vodId[:集数] */
    val remoteId: String = "",
    /** 剧集归属键，电影为空；剧集/分集都用同一个 seriesKey */
    val seriesKey: String = "",
    val title: String,
    val kind: String = MediaKind.MOVIE,
    val season: Int = 0,
    val episode: Int = 0,
    val year: Int = 0,
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    /** 刮削的艺术字标题（Emby Logo 图），为空时界面回落到纯文字标题 */
    val logoUrl: String = "",
    val rating: Double = 0.0,
    val genres: String = "",
    val durationMs: Long = 0L,
    /** 播放进度，驱动「继续观看」 */
    val positionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    /** 是否收藏 */
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),

    /**
     * TMDB 刮削成功的时间戳，0 表示从未用 TMDB 刮过。
     * 用来判断「要不要为这条记录再发请求」：
     * 非 0 且在有效期内就直接用库里的数据，不再联网。
     */
    val scrapedAt: Long = 0L,

    // ---- Emby MediaSources 媒体信息（可选，非 Emby 源为空）----

    /** 视频分辨率标签，如 "4K" / "1080p" / "720p" / "SD"。从 Emby MediaSources.MediaStreams 解析 Width/Height。 */
    val videoResolution: String = "",
    /** HDR 类型："" 无；"HDR" / "Dolby Vision" / "HDR10" / "HLG" */
    val hdrType: String = "",
    /** 视频编码器：H.264 / HEVC (H.265) / AV1 / VP9 等 */
    val videoCodec: String = "",
    /** 音轨类型：DD+ / TrueHD / AC-4 / Dolby Digital 等，空表示未知或普通立体声 */
    val audioType: String = "",

    /**
     * TVBox 播放源（线路 / 云盘）名称。
     * 同一部剧可能有多条播放源（如「夸克网盘」「阿里云盘」「百度网盘」），
     * 每条源的分集都用 seriesKey 归在一起，靠这个字段区分来源，供详情页切换线路。
     * 非 TVBox 源（Emby）为空。
     */
    val playSource: String = "",

    /**
     * 拼音首字母缩写，由入库逻辑预计算。
     * 例："偷天换日" → "tthr"，"Verity" → "verity"（英文保留小写）。
     * 用于搜索时 LIKE 前缀匹配，运行时零开销。
     */
    val pinyinInitials: String = "",

    /** 来源名称（搜索结果按来源分组展示用） */
    val sourceName: String = "",

    /**
     * TVBox 源的主演名单（vod_actor）。
     * 协议里这只是逗号/顿号分隔的纯文本，源侧不返回演员头像，
     * 界面拿它补 Emby / TMDB 之外的主演行（无图时显示姓名首字）。
     */
    val actors: String = "",

    /** TVBox 源导演名单（vod_director），同上 */
    val directors: String = "",

    /**
     * TVBox 源更新备注（vod_remarks），如「更新至第12集」「HD」「正片」。
     * 详情页徽章行用它表示片源当前的更新状态。
     */
    val remarks: String = ""
) {
    /**
     * 所属剧集名，只在内存里填，不落库。
     *
     * 分集行的 title 是播放源给的分集名（"[5.4GB]E01.mkv" 这种），
     * 「继续观看」卡片要显示的是用户记得住的片名，由查询层按 seriesKey 回查壳行补上。
     * 放类体而不是主构造：Room 这个版本对构造参数上的 @Ignore 处理有误，
     * 会把整张表当成"没有可用构造函数"。
     */
    @Ignore var parentTitle: String = ""
}

/**
 * 把 vod_actor / vod_director 这类纯文本名单切成姓名列表。
 *
 * 分隔符各家 spider 不统一（逗号、顿号、分号、斜杠、空格都见过），
 * 所以先看有没有显式标点：有就只按标点切，避免把「Tom Hanks」这种英文名从空格劈成两半；
 * 一个标点都没有时才退回按空白切。
 */
fun splitCastNames(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val punctuation = charArrayOf(',', '，', '、', ';', '；', '/', '|', '\n', '\r', '\t')
    val hasPunctuation = raw.any { it in punctuation }
    val parts = if (hasPunctuation) raw.split(*punctuation) else raw.split(' ')
    return parts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}
