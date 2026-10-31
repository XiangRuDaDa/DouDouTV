package com.tabbit.myvidhub.ui.components

import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind

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
