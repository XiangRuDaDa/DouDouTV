package com.tabbit.myvidhub.player

import androidx.media3.common.FileTypes
import androidx.media3.common.util.UnstableApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放链路的「防和谐」归一化层。
 *
 * 网盘/CDN 为躲扫描，会对视频做两类手脚，播放器直接读第 0 字节必然失败：
 *   1. 前面塞一张图片当诱饵（8 字节 PNG 魔术字，或一整张真实 PNG/JPEG/GIF）；
 *   2. 扩展名撒谎（实测 E06.mkv 的内容其实是 MP4）。
 *
 * 这里统一做三件事，全部是纯流式判断、不落盘、不额外占内存：
 *   - [looksLikeImage] / [detect] 在头部窗口里找出真实容器起点和容器类型；
 *   - [shiftRange] 把「播放器坐标」的 Range 平移到「文件坐标」；
 *   - 记住每个 URL 的结论（[remember] / [planOf]），供拦截器和 extractor 选型复用。
 *
 * 注意：只有「文件开头像图片」时才允许相信非 0 偏移。正常 MP4 前面常有 free/wide/uuid
 * 等 box，若对普通文件也接受偏移，会把正常文件误判成「带前缀」，进而截掉真实文件头。
 */
@UnstableApi
internal class PlaybackNormalizer {

    /** 容器类型；[fileType] 用于给 Media3 的 extractor 排序 */
    enum class Container(val label: String, val fileType: Int) {
        MATROSKA("MKV/WebM", FileTypes.MATROSKA),
        MP4("MP4/MOV", FileTypes.MP4),
        UNKNOWN("未知", FileTypes.UNKNOWN)
    }

    /**
     * 某个 URL 的归一化结论。
     * [prefixBytes] = 0 表示文件头就是容器（正常文件，不需要平移）。
     */
    data class Plan(val prefixBytes: Long, val container: Container, val evidence: String) {
        val shifted: Boolean get() = prefixBytes > 0
    }

    /** 按 URL 记录结论；每个播放器一份实例，生命周期刚好覆盖一次播放 */
    private val plans = ConcurrentHashMap<String, Plan>()

    /** 伪装图探测窗口：逐级放大，命中容器立即停止，避免在慢速 200 通道上多读 */
    val scanWindowLimits: LongArray = longArrayOf(16L * 1024, 64L * 1024, 256L * 1024)

    fun planOf(url: String): Plan? =
        plans[url] ?: canonical(url)?.let { plans[it] }

    fun remember(url: String, plan: Plan) {
        plans[canonical(url) ?: url] = plan
    }

    /**
     * 头部是否像一张图片（网盘伪装用的诱饵）；正常视频容器不会以这些字节开头。
     */
    fun looksLikeImage(head: ByteArray): Boolean {
        if (head.size >= PNG_MAGIC.size &&
            head.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)
        ) {
            return true
        }
        // JPEG: FF D8 FF
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        ) {
            return true
        }
        // GIF: "GIF8"
        return head.size >= 4 &&
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == '8'.code.toByte()
    }

    /**
     * 在已缓冲的头部数据里定位真实容器。
     *
     * [allowPrefix] = false 时只认第 0 字节（正常文件），避免把 MP4 的 free/wide 等
     * 前置 box 误当成伪装前缀。
     */
    fun detect(head: ByteArray, allowPrefix: Boolean): Plan? {
        val end = if (allowPrefix) head.size else minOf(head.size, 1)
        for (i in 0 until end) {
            val container = containerAt(head, i)
            if (container != Container.UNKNOWN) {
                val evidence = if (i == 0) "文件头即容器" else "伪装前缀 ${i}B 后是容器"
                return Plan(i.toLong(), container, evidence)
            }
        }
        return null
    }

    /**
     * 把「播放器坐标」的 Range 平移成「文件坐标」。
     *
     * 伪装头占 [prefixBytes] 字节，播放器看到的第 0 字节其实是文件的第 prefixBytes 字节。
     * [chunk] 非空时把开区间切成固定块（正常播放）；重发首个请求时传 null，
     * 让服务器返回真实剩余长度，避免播放器把总长算成 8MB。
     */
    fun shiftRange(request: Request, prefixBytes: Long, chunk: Long?): Request {
        val range = request.header("Range")
        if (range == null) {
            // 前缀为 0 的正常文件也要加：调用方用它把「整文件 200」强制改走 Range 通道
            return request.newBuilder().header("Range", "bytes=$prefixBytes-").build()
        }
        OPEN_RANGE_REGEX.find(range)?.let {
            val start = it.groupValues[1].toLong() + prefixBytes
            val end = chunk?.let { size -> start + size - 1 }
            return request.newBuilder()
                .header("Range", if (end == null) "bytes=$start-" else "bytes=$start-$end")
                .build()
        }
        CLOSED_RANGE_REGEX.find(range)?.let {
            val start = it.groupValues[1].toLong() + prefixBytes
            val end = it.groupValues[2].toLong() + prefixBytes
            return request.newBuilder().header("Range", "bytes=$start-$end").build()
        }
        return request
    }

    /** 偏移 i 处是什么容器；两者都不匹配返回 UNKNOWN */
    private fun containerAt(head: ByteArray, i: Int): Container {
        if (i + 4 <= head.size &&
            head[i] == EBML_MAGIC[0] && head[i + 1] == EBML_MAGIC[1] &&
            head[i + 2] == EBML_MAGIC[2] && head[i + 3] == EBML_MAGIC[3]
        ) {
            return Container.MATROSKA
        }
        return if (isMp4BoxAt(head, i)) Container.MP4 else Container.UNKNOWN
    }

    /** 偏移 i 处是不是一个 MP4 顶层 box 头（4 字节长度 + 4 字节类型） */
    private fun isMp4BoxAt(head: ByteArray, i: Int): Boolean {
        if (i + 8 > head.size) return false
        val size = ((head[i].toLong() and 0xFF) shl 24) or
            ((head[i + 1].toLong() and 0xFF) shl 16) or
            ((head[i + 2].toLong() and 0xFF) shl 8) or
            (head[i + 3].toLong() and 0xFF)
        // 0 = 延伸到文件末尾，1 = 后面跟 64 位长度，其余至少要有 8 字节的 box 头
        if (size != 0L && size != 1L && size < 8L) return false
        for (type in MP4_BOX_TYPES) {
            if (head[i + 4] == type[0] && head[i + 5] == type[1] &&
                head[i + 6] == type[2] && head[i + 7] == type[3]
            ) {
                return true
            }
        }
        return false
    }

    /** OkHttp 的 url 是规范化字符串，Media3 传进来的是原始 uri 字符串，两者可能细节不同 */
    private fun canonical(url: String): String? =
        runCatching { url.toHttpUrlOrNull()?.toString() }.getOrNull()

    private companion object {
        /** 开区间 Range 头（bytes=X-）：只有这种请求才需要补一个结尾，防止一路读到 EOF */
        val OPEN_RANGE_REGEX = Regex("bytes=(\\d+)-$")

        /** 闭区间 Range 头（bytes=X-Y），伪装头场景需要整体平移 */
        val CLOSED_RANGE_REGEX = Regex("bytes=(\\d+)-(\\d+)$")

        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** EBML 魔术字：MKV/WebM 的容器头，伪装图之后真正的内容从这里开始 */
        val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

        /**
         * MP4/MOV 顶层 box 类型。网盘伪装的文件里既有 MKV 也有 MP4，
         * 而且扩展名不可信（实测 E06.mkv 的内容其实是 MP4），所以要按内容定偏移。
         */
        val MP4_BOX_TYPES = listOf(
            "ftyp".toByteArray(Charsets.US_ASCII),
            "moov".toByteArray(Charsets.US_ASCII),
            "mdat".toByteArray(Charsets.US_ASCII),
            "moof".toByteArray(Charsets.US_ASCII),
            "styp".toByteArray(Charsets.US_ASCII)
        )
    }
}
