package com.tabbit.myvidhub

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端设备测试：自己走完牛二（WexGuard 壳）TVBox 源的完整流程。
 * 拉配置 → 解析站点 → 逐站点 siteHome → 观察壳 Init/本地代理是否工作。
 * 运行：./gradlew :app:connectedDebugAndroidTest（结果在 logcat 的 E2E tag）。
 */
@RunWith(AndroidJUnit4::class)
class E2eTvBoxTest {

    private val niuerUrl = "https://9280.kstore.vip/newwex.json"

    @Test
    fun walkNiuErSites() {
        runBlocking {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            Log.i("E2E", "=== 开始走牛二完整流程 url=$niuerUrl ===")
            val client = TvBoxClient(niuerUrl, ctx)
            val sites = client.availableSites()
            Log.i("E2E", "availableSites=${sites.size}: ${sites.map { it.key }}")
            for (site in sites.take(12)) {
                val t0 = System.currentTimeMillis()
                try {
                    val result = client.siteHome(site, 20)
                    val vods = result.vods
                    val cats = result.categories
                    Log.i(
                        "E2E",
                        "站点=${site.key} 类型=${site.type} 成功 vod=${vods.size} 分类=${cats.size}" +
                            " 分类前3=${cats.take(3).joinToString(";") { "${it.typeId}:${it.typeName}" }}" +
                            " 首个=${vods.firstOrNull()?.name} 耗时=${System.currentTimeMillis() - t0}ms"
                    )
                    // 有分类但首页无影片的线路：验证 categoryContent 能拉到内容
                    if (vods.isEmpty() && cats.isNotEmpty()) {
                        val tid = cats.first().typeId
                        val page = client.categoryContent(site, tid, 1)
                        Log.i(
                            "E2E",
                            "站点=${site.key} 分类[${cats.first().typeName}] categoryContent=${page.size} 条" +
                                " 首个=${page.firstOrNull()?.name}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e("E2E", "站点=${site.key} 失败: ${e.message} 耗时=${System.currentTimeMillis() - t0}ms", e)
                }
            }
            Log.i("E2E", "=== 流程结束 ===")
        }
    }

    /** 扫描各线路，找一个能快速返回详情（含云盘播放源）的站点，验证解析结果 */
    @Test
    fun scanDetailSources() {
        runBlocking {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val client = TvBoxClient(niuerUrl, ctx)
            val sites = client.availableSites()
            Log.i("E2E", "共 ${sites.size} 条线路: ${sites.joinToString { it.key }}")

            for (site in sites) {
                val t0 = System.currentTimeMillis()
                val vods = withTimeoutOrNull(45_000) { client.siteHome(site, 10).vods }
                if (vods.isNullOrEmpty()) {
                    Log.w("E2E", "线路[${site.key}] 无影片或超时 (${System.currentTimeMillis() - t0}ms)")
                    continue
                }
                val vod = vods.first()
                Log.i("E2E", "线路[${site.key}] 首条: ${vod.name} (vodId=${vod.vodId})")

                val detail = withTimeoutOrNull(60_000) { client.detail(vod.vodId, site.key) }
                if (detail == null) {
                    Log.w("E2E", "  detail 失败/超时")
                    continue
                }
                val hasCloud = detail.playSources.any { src ->
                    src.episodes.any { !it.url.startsWith("http") }
                }
                Log.i(
                    "E2E",
                    "  解析: playSources=${detail.playSources.size} 含云盘=$hasCloud | " +
                        detail.playSources.take(3).joinToString { "[${it.name}:${it.episodes.size}集]" }
                )
                detail.playSources.take(2).forEach { src ->
                    Log.i(
                        "E2E",
                        "  线路[${src.name}] 前2集: " +
                            src.episodes.take(2).joinToString { "${it.name}=${it.url.take(40)}" }
                    )
                }
                // 找到一个含云盘的就停，够验证解析逻辑了
                if (hasCloud) {
                    Log.i("E2E", "=== 找到含云盘的线路 ${site.key}，停止扫描 ===")
                    break
                }
            }
        }
    }

    /** 聚焦测试：MyPan(我的网盘) → 取首条详情，验证云盘播放源解析 */
    @Test
    fun myPanDetail() {
        runBlocking {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val client = TvBoxClient(niuerUrl, ctx)
            val sites = client.availableSites()
            val pan = sites.firstOrNull { it.key == "MyPan" }
                ?: error("没找到 MyPan 线路")
            Log.i("E2E", "MyPan ext=${pan.ext}")

            val vods = withTimeoutOrNull(120_000) { client.siteHome(pan, 10).vods }
            if (vods.isNullOrEmpty()) {
                Log.e("E2E", "MyPan siteHome 无数据或超时")
                return@runBlocking
            }
            Log.i("E2E", "MyPan 首条: ${vods.first().name} vodId=${vods.first().vodId}")

            val detail = withTimeoutOrNull(120_000) { client.detail(vods.first().vodId, pan.key) }
            if (detail == null) {
                Log.e("E2E", "detail 返回 null 或超时")
                return@runBlocking
            }
            val hasCloud = detail.playSources.any { s -> s.episodes.any { !it.url.startsWith("http") } }
            Log.i(
                "E2E",
                "解析: playSources=${detail.playSources.size} 含云盘=$hasCloud | " +
                    detail.playSources.joinToString { "[${it.name}:${it.episodes.size}集]" }
            )
            detail.playSources.take(3).forEach { src ->
                Log.i(
                    "E2E",
                    "线路[${src.name}] 前2集: " +
                        src.episodes.take(2).joinToString { "${it.name}=${it.url.take(50)}" }
                )
            }
        }
    }

    /** 聚焦测试：花卷线路 → 找「凡人修仙传」→ detailContent 原始 JSON */
    @Test
    fun huajuanDetail() {
        runBlocking {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val client = TvBoxClient(niuerUrl, ctx)
            val sites = client.availableSites()
            val huajuan = sites.firstOrNull { it.key == "AiQwMkv" }
                ?: error("没找到花卷(AiQwMkv)线路")
            Log.i("E2E", "花卷线路 ext=${huajuan.ext}")

            // categoryContent 在花卷上会挂死，直接用 siteHome 首页列表找片。
            val page = client.siteHome(huajuan, 50).vods
            Log.i("E2E", "siteHome 列表共 ${page.size} 条")
            val fanren = page.firstOrNull { it.name.contains("凡人修仙传") }
            if (fanren == null) {
                Log.e("E2E", "没找到凡人修仙传，前5条=${page.take(5).joinToString { it.name }}")
                return@runBlocking
            }
            Log.i("E2E", "凡人修仙传 vodId=${fanren.vodId} name=${fanren.name}")

            val detail = withTimeoutOrNull(120_000) { client.detail(fanren.vodId, huajuan.key) }
            if (detail == null) {
                Log.e("E2E", "detail 返回 null 或超时")
                return@runBlocking
            }
            Log.i(
                "E2E",
                "解析结果: playSources=${detail.playSources.size} " +
                    detail.playSources.joinToString(" | ") { "[${it.name}:${it.episodes.size}集]" }
            )
            detail.playSources.forEach { src ->
                Log.i(
                    "E2E",
                    "线路[${src.name}] 前2集样例: " +
                        src.episodes.take(2).joinToString { "${it.name}=${it.url}" }
                )
            }
        }
    }
}
