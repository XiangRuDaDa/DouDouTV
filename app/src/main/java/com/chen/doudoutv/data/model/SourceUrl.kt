package com.chen.doudoutv.data.model

/**
 * 来源地址的落库前规范化。
 *
 * 电视端表单与手机扫码推送都必须过这里：两边各写一份的话，手机推回来的地址
 * 没裁 `/web/index.html`、电视填的地址没补 `http://`，坏数据就分叉进库了。
 */
object SourceUrl {

    fun normalize(kind: SourceKind, location: String): String {
        var url = location.trim()
        if (url.isBlank()) return url
        // 用户常常只填 192.168.1.2:8096 或 example.com/api.php/provide/vod/
        // 不补协议时 OkHttp 会直接解析失败，导致同步静默返回空
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }
        if (kind == SourceKind.EMBY) {
            // 从浏览器地址栏复制来的常带 /web/index.html，这里裁回服务器根地址
            url = url.substringBefore("/web/", url).substringBefore("/web", url).trimEnd('/')
        }
        return url
    }
}
