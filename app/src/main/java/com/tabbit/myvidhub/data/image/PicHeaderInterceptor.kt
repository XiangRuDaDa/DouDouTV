package com.tabbit.myvidhub.data.image

import coil.intercept.Interceptor
import coil.request.ImageResult

/**
 * TVBox 的 vod_pic 支持在直链末尾挂请求头：
 *   https://img3.doubanio.com/xxx.jpg@Referer=https://movie.douban.com/@User-Agent=Mozilla/5.0
 *
 * 这是 FongMi / TVBox 通用的写法（豆瓣图床没有 Referer 会回 418）。Coil 不认识这种后缀，
 * 会把整串当图片路径去请求，于是「明明有封面却一多半加载失败」。这里在发请求前把头和头拆开，
 * 只把干净的 URL 交给下载器，其余转成真正的 HTTP 头。
 *
 * 走全局拦截器而不是逐个改 UI：首页卡片、轮播、继续观看、详情页、手机端全部共用同一个
 * ImageLoader，而且旧库里已经落盘的历史条目（posterUrl 带 @ 后缀）也能一起修好，不用迁移数据。
 */
class PicHeaderInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val raw = request.data as? String ?: return chain.proceed(request)
        if (!raw.contains(REFERER_MARKER, true) && !raw.contains(UA_MARKER, true)) {
            return chain.proceed(request)
        }

        val parsed = PicUrl.parse(raw)
        val builder = request.newBuilder().data(parsed.url)
        parsed.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return chain.proceed(builder.build())
    }

    private companion object {
        const val REFERER_MARKER = "@Referer="
        const val UA_MARKER = "@User-Agent="
    }
}

object PicUrl {
    private val MARKER = Regex("@(Referer|User-Agent)=", RegexOption.IGNORE_CASE)

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    data class Parsed(val url: String, val headers: Map<String, String>)

    fun parse(raw: String): Parsed {
        val first = MARKER.find(raw) ?: return Parsed(raw, emptyMap())
        val url = raw.substring(0, first.range.first)
        val tail = raw.substring(first.range.first)
        val found = MARKER.findAll(tail).toList()

        val headers = LinkedHashMap<String, String>()
        found.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = found.getOrNull(index + 1)?.range?.first ?: tail.length
            val name = when (match.groupValues[1].lowercase()) {
                "referer" -> "Referer"
                else -> "User-Agent"
            }
            headers[name] = tail.substring(start, end).trim()
        }

        // 豆瓣图床按 Referer 防盗链：源站偶尔只给 User-Agent，补一个豆瓣站内 Referer
        if (headers["Referer"].isNullOrBlank() && url.contains("doubanio.com", true)) {
            headers["Referer"] = "https://movie.douban.com/"
        }
        if (headers["User-Agent"].isNullOrBlank()) headers["User-Agent"] = DEFAULT_UA
        return Parsed(url, headers)
    }
}
