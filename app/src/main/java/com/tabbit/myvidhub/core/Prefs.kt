package com.tabbit.myvidhub.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量本地配置。
 * Emby 的 AccessToken 存在数据库里（可续期），这里只放应用级偏好。
 */
object Prefs {

    private const val FILE = "myvidhub_prefs"
    private const val KEY_TMDB_API_KEY = "tmdb_api_key"
    private const val KEY_TMDB_READ_TOKEN = "tmdb_read_token"
    private const val KEY_TMDB_ENABLED = "tmdb_enabled"
    private const val KEY_POSTER_SIZE = "poster_size"
    private const val KEY_TONE_PREFIX = "detail_tone_"
    private const val KEY_HOME_SOURCE_ID = "home_source_id"
    private const val KEY_HOME_SITE_KEY = "home_site_key"

    /** 缩略图尺寸三档 */
    enum class PosterSize(val mobileDp: Int, val tvDp: Int) {
        SMALL(92, 132), MEDIUM(118, 162), LARGE(144, 192)
    }

    var posterSize: PosterSize
        get() {
            val name = sp.getString(KEY_POSTER_SIZE, PosterSize.MEDIUM.name)
            return runCatching { PosterSize.valueOf(name.orEmpty()) }.getOrDefault(PosterSize.MEDIUM)
        }
        set(value) {
            sp.edit().putString(KEY_POSTER_SIZE, value.name).apply()
        }

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * TMDB 刮削总开关。默认 false。
     *
     * 关闭时不展示任何 TMDB 设置与声明，也不发起任何外部请求，
     * 完全沿用 Emby 自带元数据 / TVBox 返回字段。这是默认形态。
     */
    var tmdbEnabled: Boolean
        get() = sp.getBoolean(KEY_TMDB_ENABLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_TMDB_ENABLED, value).apply()
        }

    /**
     * TMDB API Key（v3 auth）。
     * 走 query 参数 `?api_key=...`，是最常用的方式。
     */
    var tmdbApiKey: String
        get() = sp.getString(KEY_TMDB_API_KEY, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_TMDB_API_KEY, value.trim()).apply()
        }

    /**
     * TMDB Read Access Token（v4 auth）。
     * 走 HTTP 头 `Authorization: Bearer ...`，和 v3 Key 二选一即可。
     * 两者都填时优先用 v4 令牌，因为它不会把密钥暴露在 URL 里。
     */
    var tmdbReadToken: String
        get() = sp.getString(KEY_TMDB_READ_TOKEN, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_TMDB_READ_TOKEN, value.trim()).apply()
        }

    /**
     * 详情页主色持久化（按标题做 key）。
     *
     * 只存在内存里的话，冷启动第一次进详情页仍然要等海报下载完才能取到颜色，
     * 那段时间页面只能退回近黑的兜底色 —— 这就是「刚进详情页海报下方是黑的」的直接原因。
     * 把算好的主色按标题落盘，下次进入时第一帧就能直接用上，不用等图片。
     */
    fun detailTone(title: String): Int {
        if (title.isBlank()) return 0
        return sp.getInt(KEY_TONE_PREFIX + title.hashCode(), 0)
    }

    fun putDetailTone(title: String, argb: Int) {
        if (title.isBlank()) return
        sp.edit().putInt(KEY_TONE_PREFIX + title.hashCode(), argb).apply()
    }

    /** 是否已配置任一 TMDB 凭据 */
    val tmdbConfigured: Boolean
        get() = tmdbApiKey.isNotBlank() || tmdbReadToken.isNotBlank()

    /** 首页选中的加载来源 id；-1 表示「全部来源」 */
    var homeSourceId: Long
        get() = sp.getLong(KEY_HOME_SOURCE_ID, -1L)
        set(value) {
            sp.edit().putLong(KEY_HOME_SOURCE_ID, value).apply()
        }

    /** 首页选中的 TVBox 站点 key；空字符串表示「全部站点」 */
    var homeSiteKey: String
        get() = sp.getString(KEY_HOME_SITE_KEY, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_HOME_SITE_KEY, value).apply()
        }
}
