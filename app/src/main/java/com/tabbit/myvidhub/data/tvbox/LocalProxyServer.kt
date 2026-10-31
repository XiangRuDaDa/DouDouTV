package com.tabbit.myvidhub.data.tvbox

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 壳依赖的本地代理（127.0.0.1:9978）。
 *
 * 为什么要自己起：壳的 native 初始化（`DexNative.getLoader` → `ProxyOrigin.init`）
 * 会**从 8964 起逐个端口递增探测**本地代理，一直到 9978 才算找到 ——
 * 实测扫 1014 个端口、每个约 23ms，合计 24 秒，就是冷启动「正在初始化本地代理」卡住的那段。
 * 只要在 8964（第一个被探测的端口）上应答 `ok`，探测第一步即命中，这一段降到 1 秒内。
 *
 * 两个端口都监听：
 *   8964 —— 壳探测起点，命中后壳把本地代理端口记为 8964，云盘播放地址用它；
 *   9978 —— 壳找不到代理时的兜底端口，也是历史记录里已落库的播放地址用的端口。
 * 两者进入同一个处理函数，行为完全一致；8964 被别的 App 占用时自动退回只用 9978。
 *
 * 请求处理对接壳暴露的 `Init.proxyInvoke(Map) -> Object[]`：
 *   result[0] 响应体（byte[] / String）、result[1] 响应头 Map<String, List<String>>、
 *   result[2] 可选状态码。Init 未完成前返回 200 空体，只为让端口探测通过。
 */
internal object LocalProxyServer {

    private const val TAG = "LocalProxy"

    /** 监听端口：8964 是壳的探测起点，9978 是壳与历史地址的兜底端口 */
    private val PORTS = intArrayOf(8964, 9978)
    private const val MAX_HEAD_BYTES = 32 * 1024

    @Volatile
    private var serverSockets: List<ServerSocket> = emptyList()

    @Volatile
    private var invoker: ((Map<String, String>) -> Array<Any?>?)? = null

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "local-proxy").apply { isDaemon = true }
    }

    /** 幂等启动。所有端口都被占用或权限不足返回 false，调用方继续走原来的反射兜底。 */
    @Synchronized
    fun start(): Boolean {
        if (serverSockets.isNotEmpty()) return true
        val bound = ArrayList<ServerSocket>(PORTS.size)
        for (port in PORTS) {
            runCatching {
                // reuseAddress 必须在 bind 之前设置，否则上次进程残留的 TIME_WAIT 会让 bind 失败
                val s = ServerSocket().apply { reuseAddress = true }
                s.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32)
                bound += s
                pool.execute {
                    while (!s.isClosed) {
                        val client = runCatching { s.accept() }.getOrNull() ?: break
                        pool.execute { handle(client) }
                    }
                }
                Log.w(TAG, "本地代理已监听 127.0.0.1:$port")
            }.onFailure {
                Log.w(TAG, "本地代理端口 $port 监听失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
        serverSockets = bound
        return bound.isNotEmpty()
    }

    /** 绑定壳的 proxyInvoke。必须等 Init.init 返回后再绑定：Init 未完成时 loader 为 null，
     *  进入壳的 native proxyInvoke 会直接 JNI abort 整个进程（已实测）。 */
    fun bind(invoke: (Map<String, String>) -> Array<Any?>?) {
        invoker = invoke
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return
            val requestLine = head.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(client, 400, "Bad Request", ByteArray(0), emptyMap())
                return
            }
            val method = parts[0]
            val target = parts[1]
            if (method != "GET" && method != "POST") {
                respond(client, 405, "Method Not Allowed", ByteArray(0), emptyMap())
                return
            }
            skipBody(input, head)
            val params = parseQuery(target.substringAfter('?', ""))
            Log.w(TAG, "$method ${target.substringBefore('?')} 参数=$params")

            // 壳用 /proxy?do=ck 探测本地代理是否可用，只认响应体 "ok"（对照 OK影视 的 9978 实测）。
            // 这个检查发生在 native 初始化期间，那时壳的 proxyInvoke 还不能调（loader 为 null 会 JNI abort），
            // 必须由我们自己直接应答。
            if (params["do"] == "ck") {
                respond(
                    client, 200, "OK", "ok".toByteArray(),
                    mapOf("Content-Type" to "text/plain; charset=utf-8")
                )
                return
            }

            val result = invoker?.invoke(params)
            if (result == null) {
                // Init 还没跑完：探测只需要端口通，先给空 200
                respond(client, 200, "OK", ByteArray(0), emptyMap())
                return
            }
            val body = toBytes(result.getOrNull(0))
            val headers = toStringMap(result.getOrNull(1))
            val code = (result.getOrNull(2) as? Number)?.toInt() ?: 200
            respond(client, code, "OK", body, headers)
        } catch (e: Exception) {
            Log.w(TAG, "处理请求失败：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** 读到空行结束，返回整个头部文本；超长直接返回 null */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                else -> if (b == '\r'.code) 1 else 0
            }
            if (matched == 4) return buf.toString("UTF-8")
        }
        return null
    }

    /** POST 体先读掉，避免连接上残留数据影响后续（当前协议只用 query 参数） */
    private fun skipBody(input: InputStream, head: String) {
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: return
        var left = length.coerceAtMost(1L shl 20)
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            out[key] = value
        }
        return out
    }

    private fun decode(s: String): String = runCatching {
        URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    private fun toBytes(value: Any?): ByteArray = when (value) {
        null -> ByteArray(0)
        is ByteArray -> value
        is String -> value.toByteArray()
        else -> value.toString().toByteArray()
    }

    private fun toStringMap(value: Any?): Map<String, String> {
        val map = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        map.forEach { (k, v) ->
            if (k == null || v == null) return@forEach
            // proxyInvoke 的响应头是 Map<String, List<String>>
            out[k.toString()] = if (v is List<*>) v.joinToString(", ") { it.toString() } else v.toString()
        }
        return out
    }

    private fun respond(
        client: Socket,
        code: Int,
        reason: String,
        body: ByteArray,
        headers: Map<String, String>
    ) {
        val out = client.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.0 ").append(code).append(' ').append(reason).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        sb.append("Content-Type: ").append(contentType ?: "application/octet-stream").append("\r\n")
        headers.forEach { (k, v) ->
            if (!k.equals("Content-Type", true) && !k.equals("Content-Length", true) &&
                !k.equals("Connection", true)
            ) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
