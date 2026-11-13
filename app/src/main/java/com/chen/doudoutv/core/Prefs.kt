package com.chen.doudoutv.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量本地配置。
 * Emby 的 AccessToken 存在数据库里（可续期），这里只放应用级偏好。
 */
object Prefs {

    private const val FILE = "doudoutv_prefs"
    private const val KEY_TMDB_API_KEY = "tmdb_api_key"
    private const val KEY_TMDB_READ_TOKEN = "tmdb_read_token"
    private const val KEY_TMDB_ENABLED = "tmdb_enabled"
    private const val KEY_POSTER_SIZE = "poster_size"
    private const val KEY_TV_LAYOUT = "tv_layout_style"
    private const val KEY_TV_CARD_MOTION = "tv_card_motion"
    private const val KEY_TONE_PREFIX = "detail_tone_"
    private const val KEY_HOME_SOURCE_ID = "home_source_id"
    private const val KEY_HOME_SITE_KEY = "home_site_key"
    private const val KEY_HOME_SITE_OF_SOURCE = "home_site_of_source_"
    private const val KEY_SOURCE_LAST_SYNC = "source_last_sync_"
    private const val KEY_SOURCE_LIBRARY_BACKFILLED = "source_library_backfilled_"
    private const val KEY_SEARCH_HISTORY = "search_history_list"
    private const val KEY_PINYIN_VERSION = "pinyin_version"
    private const val KEY_VIDEO_CACHE_TIER = "video_cache_tier"
    private const val KEY_THEME_ID = "theme_id"
    private const val KEY_EMBY_DEVICE_ID = "emby_device_id"
    private const val KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"

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

    /**
     * 电视端布局风格：已收敛成 NF 一套，经典版入口下线。
     *
     * [TvLayout.CLASSIC]（ui/tv）与 [TvLayout.NETFLIX]（ui/tv/nf）两套页面都还在，
     * 存储键和枚举也原样保留 —— 老用户存的 CLASSIC 不删不改，只是读出来一律按 NF 走，
     * 于是圆角档、导航条、首页形态不会再出现「存的是经典、界面是 NF」的混装。
     */
    var tvLayout: TvLayout
        get() = TvLayout.NETFLIX
        set(value) {
            sp.edit().putString(KEY_TV_LAYOUT, value.name).apply()
        }

    /** 布局风格的历史取值；界面入口已下线，只留作存储兼容 */
    enum class TvLayout { CLASSIC, NETFLIX }

    /**
     * TV 端海报卡的焦点移动档，两套布局共用（nf 版在 NfPosterCard，经典版在 TvPosterCard）。
     *
     * 两档都必须留在行内纵向余量以内，超出就会被 Lazy 容器边界裁掉一条边：
     * nf 版取 `NfRowVerticalPadding` = 14dp，经典版横排取 `TvRowVerticalPadding` = 14dp
     * （原来只有 6~7dp，放大档那 10% 也一直是被切掉一点的）：
     * · [ART_ZOOM] nf 版图片从中心涨 5%（上下各 6~17dp，随卡宽），经典版整卡涨 10%；
     * · [LIFT] 整卡不涨、只上抬 12dp。
     */
    var tvCardMotion: CardMotion
        get() {
            val name = sp.getString(KEY_TV_CARD_MOTION, CardMotion.ART_ZOOM.name)
            return runCatching { CardMotion.valueOf(name.orEmpty()) }.getOrDefault(CardMotion.ART_ZOOM)
        }
        set(value) {
            sp.edit().putString(KEY_TV_CARD_MOTION, value.name).apply()
        }

    /** 焦点移动的两档；默认放大（片名不参与缩放） */
    enum class CardMotion { ART_ZOOM, LIFT }

    /**
     * 网盘直链磁盘缓存的容量档。
     *
     * 默认关闭：这一档决定的是「往 filesDir 里写多少视频字节」，
     * 打开后看电影会稳定占用几百 MB 存储，必须由用户自己选。
     * 降档（含改成关闭）会清空整个缓存目录，见 [VideoCache.applyTier]。
     */
    var videoCacheTier: VideoCacheTier
        get() {
            val name = sp.getString(KEY_VIDEO_CACHE_TIER, VideoCacheTier.OFF.name)
            return runCatching { VideoCacheTier.valueOf(name.orEmpty()) }.getOrDefault(VideoCacheTier.OFF)
        }
        set(value) {
            sp.edit().putString(KEY_VIDEO_CACHE_TIER, value.name).apply()
        }

    /** 视频缓存容量档；maxBytes 是「整个目录」的上限，不是单集的上限 */
    enum class VideoCacheTier(val label: String, val maxBytes: Long) {
        OFF("关闭", 0L),
        MB256("256 MB", 256L * 1024 * 1024),
        MB512("512 MB", 512L * 1024 * 1024),
        GB1("1 GB", 1024L * 1024 * 1024),
        GB2("2 GB", 2048L * 1024 * 1024),
        GB4("4 GB", 4096L * 1024 * 1024)
    }

    private lateinit var sp: SharedPreferences

    /**
     * 全局主题色板 id。
     *
     * 空串表示「没选过」，由 ui.theme 那边回落到默认色板：
     * core 不认得具体色板，也就不需要跟着色表一起改。
     */
    var themeId: String
        get() = sp.getString(KEY_THEME_ID, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_THEME_ID, value).apply()
        }

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * 「自动跳过片头」全局开关。默认开。
     *
     * 关掉后：详情页与播放器功能栏都不显示跳过片头按钮，
     * 已配置的片头时长也不再生效（配置本身保留，重新打开即恢复）。
     */
    var skipIntroEnabled: Boolean
        get() = sp.getBoolean(KEY_SKIP_INTRO_ENABLED, true)
        set(value) {
            sp.edit().putBoolean(KEY_SKIP_INTRO_ENABLED, value).apply()
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

    /**
     * 某个来源最近一次同步成功的时间戳（毫秒）；0 表示从未同步。
     *
     * 只用 SharedPreferences 存，不改 sources 表结构 —— 这是纯展示态数据，
     * 丢了也不影响同步逻辑，没必要为它写一次 Room Migration。
     */
    fun sourceLastSync(sourceId: Long): Long = sp.getLong(KEY_SOURCE_LAST_SYNC + sourceId, 0L)

    fun setSourceLastSync(sourceId: Long, timestamp: Long) {
        sp.edit().putLong(KEY_SOURCE_LAST_SYNC + sourceId, timestamp).apply()
    }

    /**
     * 某个来源是否已完成「按媒体库打标签」的一次性回填（迁移 v12→13 后补 libraryName）。
     *
     * 回填是迁移任务，只该跑一次：首次逐库全量同步成功打上库标签即置位，之后刷新走增量。
     * 之前用「本地还有无库标签的壳」做判据，但总有少量条目逐库查询覆盖不到（如 BB 的 7 条剧集），
     * 判据恒真导致每次刷新都整库重拉，触发公益服限流。改成持久化标记后不再按数据反复判定。
     */
    fun sourceLibraryBackfilled(sourceId: Long): Boolean =
        sp.getBoolean(KEY_SOURCE_LIBRARY_BACKFILLED + sourceId, false)

    fun setSourceLibraryBackfilled(sourceId: Long, value: Boolean) {
        sp.edit().putBoolean(KEY_SOURCE_LIBRARY_BACKFILLED + sourceId, value).apply()
    }

    /**
     * 上报给 Emby 服务端的设备 ID：每台装机唯一，首次读取时生成 UUID 并落盘。
     *
     * 之前鉴权头与 PlaybackInfo 各写死一个常量（还大小写不一致），所有装机上报同一个 ID：
     * 服务端按 DeviceId 管理设备，管理员拉黑这一个 ID 就会全量连坐，且一台机器被算成两个设备。
     * 改成装机唯一后，被封只影响那一台，也消掉「一机两设备」。鉴权头与播放前置统一取这里。
     */
    val embyDeviceId: String
        get() {
            val existing = sp.getString(KEY_EMBY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val id = java.util.UUID.randomUUID().toString()
            sp.edit().putString(KEY_EMBY_DEVICE_ID, id).apply()
            return id
        }

    /** 首页选中的 TVBox 站点 key；空字符串表示「全部站点」 */
    var homeSiteKey: String
        get() = sp.getString(KEY_HOME_SITE_KEY, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_HOME_SITE_KEY, value).apply()
        }

    /**
     * 某个来源上次选中的站点（线路）key；切走再切回时恢复，空字符串表示当时选的是「全部站点」。
     * 和全局的 [homeSiteKey] 并存：全局值只对「当前来源」有意义，跨来源要靠这份按 id 的记录。
     */
    fun homeSiteKeyOf(sourceId: Long): String =
        sp.getString(KEY_HOME_SITE_OF_SOURCE + sourceId, "").orEmpty()

    fun setHomeSiteKeyOf(sourceId: Long, siteKey: String) {
        sp.edit().putString(KEY_HOME_SITE_OF_SOURCE + sourceId, siteKey).apply()
    }

    /**
     * 搜索历史：最多 10 条，新的排最前。
     *
     * 用换行分隔的字符串存（而非 StringSet）——Set 不保证顺序，
     * 无法判断哪条是「最后一个」（最旧），也就无法在超出 10 条时正确淘汰。
     */
    fun getSearchHistory(): List<String> =
        sp.getString(KEY_SEARCH_HISTORY, null).orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }

    fun addSearchHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val list = getSearchHistory().filter { it != kw }.toMutableList()
        list.add(0, kw)
        // 超出 10 条自动清掉最旧的一条（即末尾那条）
        sp.edit().putString(KEY_SEARCH_HISTORY, list.take(10).joinToString("\n")).apply()
    }

    /**
     * 拼音索引版本。
     * 0 = 老版本用 Unicode 区间估算的首字母（大量错误），1 = TinyPinyin 重建。
     * 每次换拼音算法都 +1，启动时会据此把库里已有片名重算一遍。
     */
    var pinyinVersion: Int
        get() = sp.getInt(KEY_PINYIN_VERSION, 0)
        set(value) {
            sp.edit().putInt(KEY_PINYIN_VERSION, value).apply()
        }

    fun clearSearchHistory() = sp.edit().remove(KEY_SEARCH_HISTORY).apply()
}
