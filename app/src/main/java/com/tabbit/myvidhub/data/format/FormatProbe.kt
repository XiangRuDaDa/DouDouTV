package com.tabbit.myvidhub.data.format

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/**
 * 画质 / 音轨探测，两条来源互补：
 *
 * · [fromText]：源侧文本（vod_remarks、分集文件名）。详情页不播放就能显示，代价是靠文件名猜，可能误判。
 * · [fromTracks]：播放期 Media3 真实轨道。以码流为准，但要播过一次才拿得到。
 *
 * 两者都写进同一组 media 列，播放期读到真值后覆盖文本推测（以码流为准）。
 */
object FormatProbe {

    data class Info(
        val resolution: String = "",
        val hdr: String = "",
        val codec: String = "",
        val audio: String = ""
    ) {
        val isEmpty: Boolean
            get() = resolution.isBlank() && hdr.isBlank() && codec.isBlank() && audio.isBlank()
    }

    fun fromText(text: String): Info {
        if (text.isBlank()) return Info()
        val t = text.uppercase()
        return Info(
            resolution = when {
                t.contains("2160P") || t.contains("4K") || t.contains("UHD") -> "4K"
                t.contains("1080P") || t.contains("1080I") -> "1080p"
                t.contains("720P") -> "720p"
                else -> ""
            },
            hdr = when {
                t.contains("杜比视界") || t.contains("DOLBY VISION") || t.contains("DOVI") -> "Dolby Vision"
                t.contains("HDR10+") -> "HDR10+"
                t.contains("HDR10") -> "HDR10"
                t.contains("HLG") -> "HLG"
                t.contains("HDR") -> "HDR"
                else -> ""
            },
            codec = when {
                t.contains("H.265") || t.contains("H265") || t.contains("HEVC") || t.contains("X265") -> "HEVC"
                t.contains("H.264") || t.contains("H264") || t.contains("AVC") || t.contains("X264") -> "H.264"
                t.contains("AV1") -> "AV1"
                t.contains("VP9") -> "VP9"
                else -> ""
            },
            audio = when {
                t.contains("杜比全景声") || t.contains("ATMOS") -> "Atmos"
                t.contains("TRUEHD") -> "TrueHD"
                t.contains("AC-4") || t.contains("AC4") -> "AC-4"
                t.contains("DDP") || t.contains("DD+") || t.contains("EAC3") || t.contains("E-AC3") -> "DD+"
                t.contains("DTS") -> "DTS"
                t.contains("AC3") || t.contains("AC-3") -> "Dolby Digital"
                else -> ""
            }
        )
    }

    /**
     * 从播放器当前轨道读真实画质。
     * 只在至少拿到一项时返回，避免还没 prepare 完时把空值写回库里。
     */
    fun fromTracks(tracks: Tracks): Info? {
        var resolution = ""
        var hdr = ""
        var codec = ""
        var audio = ""
        for (group in tracks.groups) {
            val format = (0 until group.length)
                .mapNotNull { runCatching { group.getTrackFormat(it) }.getOrNull() }
                .firstOrNull() ?: continue
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    resolution = when {
                        format.height >= 2160 -> "4K"
                        format.height >= 1080 -> "1080p"
                        format.height >= 720 -> "720p"
                        format.height > 0 -> "${format.height}p"
                        else -> ""
                    }
                    val mime = format.sampleMimeType.orEmpty()
                    val codecs = format.codecs.orEmpty().lowercase()
                    val isDv = mime == MimeTypes.VIDEO_DOLBY_VISION ||
                        codecs.startsWith("dvh") || codecs.startsWith("dav")
                    // 杜比视界的底层编码仍是 HEVC，编码器徽章照样按 HEVC 显示
                    codec = when {
                        isDv -> "HEVC"
                        mime == MimeTypes.VIDEO_H265 -> "HEVC"
                        mime == MimeTypes.VIDEO_H264 -> "H.264"
                        mime == MimeTypes.VIDEO_AV1 -> "AV1"
                        mime == MimeTypes.VIDEO_VP9 -> "VP9"
                        else -> ""
                    }
                    hdr = when {
                        isDv -> "Dolby Vision"
                        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
                        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                        else -> ""
                    }
                }

                C.TRACK_TYPE_AUDIO -> {
                    audio = when (format.sampleMimeType) {
                        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "DD+"
                        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                        MimeTypes.AUDIO_AC4 -> "AC-4"
                        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                        else -> ""
                    }
                }
            }
        }
        val info = Info(resolution, hdr, codec, audio)
        return info.takeIf { !it.isEmpty }
    }
}
