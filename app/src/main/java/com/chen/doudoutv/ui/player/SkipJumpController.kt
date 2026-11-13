package com.chen.doudoutv.ui.player

import com.chen.doudoutv.data.tvbox.TvBoxClient.VodSkip

/**
 * 跳过片头的共用判定器（手机 / TV 两端播放器共用）。
 *
 * 优先级：手动配置的片头时长（introMs > 0，全剧生效）> 协议 skips > 不跳。
 * 消费规则：每集只跳一次 —— 手动跳过一次即消费；协议 skips 逐条消费，
 * 位置已经越过的条目直接标记消费（续播时不会倒回去跳）。
 * 暂停中、时长未知（duration <= 0）时一律不跳。
 *
 * 切集不用显式调用：内部按 episodeId 变化自动复位消费状态。
 */
class SkipJumpController {

    private var episodeId = -1L

    /** 手动跳过是否已在本集消费 */
    private var manualConsumed = false

    /** 协议 skips 消费进度：已消费到该索引（含），-1 表示一条都没消费 */
    private var protocolConsumed = -1

    /**
     * @param enabled 全局「自动跳过片头」开关，关掉时一律不跳
     * @param introMs 手动配置的片头时长（毫秒），<=0 表示未配置
     * @param skips 协议 playerContent 返回的跳过区间
     * @return 需要 seek 到的目标毫秒；null 表示本拍不跳
     */
    fun nextJump(
        enabled: Boolean,
        introMs: Long,
        skips: List<VodSkip>,
        episodeId: Long,
        position: Long,
        duration: Long,
        isPlaying: Boolean
    ): Long? {
        if (!enabled || !isPlaying || duration <= 0L) return null
        if (this.episodeId != episodeId) {
            this.episodeId = episodeId
            manualConsumed = false
            protocolConsumed = -1
        }
        if (introMs > 0L) {
            if (manualConsumed) return null
            manualConsumed = true
            // 续播位置已经越过片头终点：不跳
            if (position >= introMs || position < 0L) return null
            return introMs
        }
        if (skips.isEmpty()) return null
        for (i in (protocolConsumed + 1) until skips.size) {
            val s = skips[i]
            if (position >= s.toMs) {
                // 已经播过这条区间：直接消费，避免回拖时重复跳
                protocolConsumed = i
                continue
            }
            if (position >= s.fromMs) {
                protocolConsumed = i
                return s.toMs
            }
            // 还没到这条区间的起点，后面的更靠后，不用看
            return null
        }
        return null
    }
}
