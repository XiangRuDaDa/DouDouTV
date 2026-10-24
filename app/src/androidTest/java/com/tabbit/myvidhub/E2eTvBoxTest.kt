package com.tabbit.myvidhub

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import kotlinx.coroutines.runBlocking
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
}
