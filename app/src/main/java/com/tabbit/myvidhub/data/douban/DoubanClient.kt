package com.tabbit.myvidhub.data.douban

import com.tabbit.myvidhub.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 豆瓣热门榜客户端（只取片名，用于搜索联想）。
 *
 * 为什么选豆瓣：TMDB 的 API 域名国内被 DNS 污染、Bangumi 接口直连超时，
 * 实测 `movie.douban.com/j/search_subjects` 国内可直连。
 *
 * 这是非官方 JSON 接口，可能被限流或调整：所有失败都静默返回空，
 * 调用方保留本地旧缓存即可，绝不影响搜索主流程。
 */
class DoubanClient {

    data class HotTitle(val title: String, val kind: String)

    /** 拉取热门电影 + 热门剧集片名（各 200 条） */
    suspend fun hotTitles(): List<HotTitle> = withContext(Dispatchers.IO) {
        val movies = fetch("movie", MediaKind.MOVIE)
        val series = fetch("tv", MediaKind.SERIES)
        (movies + series).distinctBy { it.title }
    }

    private fun fetch(type: String, kind: String): List<HotTitle> {
        val tag = URLEncoder.encode("热门", "UTF-8")
        val url = "https://movie.douban.com/j/search_subjects" +
            "?type=$type&tag=$tag&page_limit=200&page_start=0"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://movie.douban.com/")
            .header("Accept", "application/json")
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return emptyList()
                val subjects = JSONObject(body).optJSONArray("subjects") ?: return emptyList()
                (0 until subjects.length()).mapNotNull { i ->
                    val title = subjects.optJSONObject(i)
                        ?.optString("title", "")
                        .orEmpty()
                        .trim()
                    if (title.isBlank()) null else HotTitle(title, kind)
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** 豆瓣会拒绝空 UA / 默认 UA 的请求 */
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /**
         * 复用单例连接池。超时收得比较短：这个接口只用来补联想，
         * 慢或不通时不能让搜索页等太久。
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
