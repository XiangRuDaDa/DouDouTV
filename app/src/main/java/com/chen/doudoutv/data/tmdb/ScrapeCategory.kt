package com.chen.doudoutv.data.tmdb

/**
 * 动画 / 真人剧类别：同名动画与电视剧消歧的唯一可靠信号。
 *
 * 年份经常缺（TVBox 源大量 year=0），靠年份过滤分不开「凡人修仙传」动画版与剧版；
 * 而壳行的 genres 存着源分类（TVBox typeName / Emby Genres），TMDB 侧则有 Animation(16)。
 * 三处口径共用这一份判定：搜索排序、刮削表读回落、内存缓存键。
 */
object ScrapeCategory {
    const val UNKNOWN = 0
    const val ANIME = 1
    const val LIVE = 2

    private val ANIME_WORD = Regex("""(?i)(动漫|动画|anime|animation)""")
    private val LIVE_WORD =
        Regex("""(?i)(电视剧|国产剧|美剧|韩剧|日剧|泰剧|英剧|港剧|台剧|短剧|剧集|剧情|drama)""")

    /** 源分类 → 类别；认不出就 UNKNOWN，消歧时不作限制 */
    fun ofSource(genres: String): Int = when {
        ANIME_WORD.containsMatchIn(genres) -> ANIME
        LIVE_WORD.containsMatchIn(genres) -> LIVE
        else -> UNKNOWN
    }

    /** TMDB 搜索结果的 genre_ids：16 = Animation */
    fun ofTmdbIds(hasAnimation: Boolean): Int = if (hasAnimation) ANIME else LIVE

    /** 刮削表行里存的 TMDB 英文 genre 名；老行可能为空 → UNKNOWN */
    fun ofStored(genres: String): Int = when {
        genres.isBlank() -> UNKNOWN
        ANIME_WORD.containsMatchIn(genres) -> ANIME
        else -> LIVE
    }

    /** 任一侧认不出就不拦；两侧都认出则必须同类，防剧版复用动画版那行 */
    fun compatible(want: Int, stored: Int): Boolean =
        want == UNKNOWN || stored == UNKNOWN || want == stored
}