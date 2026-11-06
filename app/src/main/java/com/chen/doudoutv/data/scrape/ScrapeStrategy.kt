package com.chen.doudoutv.data.scrape

import com.chen.doudoutv.core.Prefs

/**
 * 刮削引擎类型。
 *
 * 一个媒体条目通常有两条获取元数据的路：
 *   · 原有方案（LEGACY）：Emby 服务器自带的元数据、TVBox 源返回的字段，
 *     也就是不依赖任何第三方数据库时的兜底。
 *   · TMDB：联网检索海报、背景图、艺术字 Logo、评分、简介与演职员，
 *     数据更完整，但需要有可用凭据。
 *
 * App 冷启动时根据「是否填过 TMDB 凭据」决定走哪条，见 [ScrapeStrategy.refresh]。
 */
enum class ScrapeEngine {
    /** 已配置 TMDB 凭据：优先用 TMDB 补全元数据，Emby 数据仍作为兜底 */
    TMDB,

    /** 未配置凭据：完全沿用原有方案，不发起任何 TMDB 请求 */
    LEGACY
}

/**
 * 刮削策略：启动时判定一次，全局共享。
 *
 * 为什么不每处都直接读 Prefs？
 * 因为判定结果要在「启动」「详情页打开」「设置页展示」三处保持一致，
 * 集中在这里可以避免各处判断逻辑写岔，也方便以后加新的刮削源。
 */
object ScrapeStrategy {

    /** 当前生效的引擎，由 [refresh] 在启动时写入 */
    @Volatile
    var engine: ScrapeEngine = ScrapeEngine.LEGACY
        private set

    /**
     * 依据「总开关 + 凭据」推导应该用哪个引擎（不写入状态）。
     *
     * 注意这里是「与」的关系：开关默认关闭，所以即使以前填过令牌，
     * 只要用户没主动打开开关，就仍然走原有方案，不会偷偷联网。
     */
    fun resolve(): ScrapeEngine =
        if (Prefs.tmdbEnabled && Prefs.tmdbConfigured) ScrapeEngine.TMDB
        else ScrapeEngine.LEGACY

    /**
     * 重新判定并落地。App 启动时调用一次；用户在设置页改完凭据后也会再调一次，
     * 这样不用重启 App 就能让新填的密钥立刻生效。
     */
    fun refresh(): ScrapeEngine {
        engine = resolve()
        return engine
    }

    /** 是否走 TMDB 刮削 */
    val isTmdbActive: Boolean
        get() = engine == ScrapeEngine.TMDB

    /** 给日志/设置页用的可读描述 */
    fun describe(): String = when (engine) {
        ScrapeEngine.TMDB -> "TMDB 刮削"
        ScrapeEngine.LEGACY -> "原有方案（本地元数据）"
    }
}
