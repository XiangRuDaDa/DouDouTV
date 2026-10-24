package com.github.catvod.crawler

import android.content.Context
import java.util.HashMap

/**
 * TVBox 爬虫基类（宿主 App 提供）。
 *
 * 饭太硬等加密壳 spider 包的 Guard 壳类（如 com.github.catvod.spider.DouDouGuard）
 * 继承本类。壳 dex 里没有这个类，必须由宿主 App 的 classloader 提供，
 * 否则加载 Guard 时父类解析失败 → ClassNotFoundException。
 *
 * 方法签名与 CatVod 标准 Spider 一致，Guard 壳会 override 这些方法
 * 并委托给 native 解密出的真实 spider 实例。
 */
abstract class Spider {

    open fun init(context: Context, extend: String) {}

    open fun homeContent(filter: Boolean): String = ""

    open fun homeVideoContent(): String = ""

    open fun categoryContent(
        cid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>
    ): String = ""

    open fun detailContent(ids: List<String>): String = ""

    open fun playerContent(flag: String, id: String, vipFlags: List<String>): String = ""

    open fun searchContent(key: String, quick: Boolean): String = ""

    open fun searchContent(key: String, quick: Boolean, pn: String): String = ""

    open fun action(action: String): String = ""

    open fun destroy() {}

    open fun clearCookies() {}
}
