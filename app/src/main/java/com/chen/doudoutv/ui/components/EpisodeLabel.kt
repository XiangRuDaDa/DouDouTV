package com.chen.doudoutv.ui.components

import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind

/**
 * TVBox 的分集 title 就是播放源给的分集名（如 "[5.4GB]E01.mkv【xxx】"），
 * 展示时原样用，不再套 SxxExx；Emby 这类没有源分集名的才需要编号兜底。
 */
val MediaEntity.usesSourceEpisodeName: Boolean
    get() = kind == MediaKind.EPISODE && playSource.isNotBlank() && title.isNotBlank()

/** 分集在 UI 上的标签 */
fun MediaEntity.episodeLabel(): String = when {
    kind != MediaKind.EPISODE -> title
    usesSourceEpisodeName -> title
    season > 0 -> "S${season}E${episode}"
    episode > 0 -> "第 $episode 集"
    else -> title
}

/**
 * 「继续观看」卡的主标题：分集行显示所属剧名（parentTitle 由查询层补），
 * 拿不到壳行时退回分集名，至少不至于空着。
 */
val MediaEntity.continueTitle: String
    get() = parentTitle.ifBlank { title }

/**
 * 在另一条播放源（网盘 / 线路）里找「同一集」。
 *
 * 先认季 + 集号：两个号都是从源分集名解析出来的，跨网盘最稳；
 * 号对不上（有的源整季都是 0）再认源分集原名。都对不上返回 null，
 * 由调用方回到第一集 —— 猜错一集比回到第一集更难被用户发现。
 */
fun matchEpisodeInSource(anchor: MediaEntity?, list: List<MediaEntity>): MediaEntity? {
    if (anchor == null || list.isEmpty()) return null
    val byNumber = if (anchor.episode > 0) {
        list.firstOrNull { it.season == anchor.season && it.episode == anchor.episode }
    } else null
    val byName = if (byNumber == null && anchor.title.isNotBlank()) {
        list.firstOrNull { it.title == anchor.title }
    } else null
    return byNumber ?: byName
}
