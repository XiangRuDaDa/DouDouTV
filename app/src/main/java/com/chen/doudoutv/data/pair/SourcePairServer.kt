package com.chen.doudoutv.data.pair

import android.util.Log
import com.chen.doudoutv.data.model.SourceKind
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** 手机页上展示的一条来源。密码与 token 绝不出电视，只告诉手机「库里有没有存」。 */
data class PairedSource(
    val id: Long,
    val name: String,
    val kind: String,
    val location: String,
    val username: String,
    val hasPassword: Boolean,
    val enabled: Boolean
)

/** 手机页点「保存并推送到电视」发上来的那一条。id=0 表示新增。 */
data class PairedSaveRequest(
    val id: Long,
    val name: String,
    val kind: SourceKind,
    val location: String,
    val username: String,
    val password: String
)

data class PairedSaveResult(val ok: Boolean, val message: String)

internal interface SourcePairHandler {
    suspend fun list(): List<PairedSource>
    suspend fun save(req: PairedSaveRequest): PairedSaveResult
}

/**
 * 手机扫码维护用的局域网 HTTP 服务。
 *
 * 为什么不复用 LocalProxyServer：那个绑 127.0.0.1（手机根本连不进来）、
 * POST 体直接 skipBody 丢掉（表单数据拿不到）、invoker 是单槽且已被 spider 占用。
 * 四条里任何一条都足以让它当不了维护页的后端，所以另起一个。
 *
 * 生命周期跟着电视端那个二维码弹层：打开才监听，关掉就 stop 并释放端口，
 * 不在后台长期挂着一个能改数据库的口子。
 *
 * 鉴权：启动时随机生成 token，二维码 URL 带 `?k=`，页面每个请求原样带回。
 * 没有 token 的同网段访问一律 403 —— 不加这道闸的话，邻居连上同一个 Wi-Fi
 * 就能往电视里写源。
 */
internal object SourcePairServer {

    private const val TAG = "SourcePair"

    /** 避开壳本地代理的 8964/9978 与它的扫描段 8964..9978 */
    private val PORTS = intArrayOf(10086, 10087)
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64L * 1024

    private val gson = Gson()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var handler: SourcePairHandler? = null

    /** 本次会话的访问令牌，随二维码 URL 一起发给手机 */
    var token: String = ""
        private set

    /** 实际监听到的端口，0 表示没起来 */
    var port: Int = 0
        private set

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "source-pair").apply { isDaemon = true }
    }

    /** 幂等启动；端口全被占用返回 false */
    @Synchronized
    fun start(newHandler: SourcePairHandler): Boolean {
        if (serverSocket != null) return true
        for (candidate in PORTS) {
            val socket = runCatching {
                ServerSocket().apply { reuseAddress = true }
                    .also { it.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate), 16) }
            }.getOrNull() ?: continue
            serverSocket = socket
            port = candidate
            token = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            handler = newHandler
            pool.execute {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { handle(client) }
                }
            }
            Log.i(TAG, "手机维护服务已监听 0.0.0.0:$candidate")
            return true
        }
        Log.w(TAG, "手机维护服务端口全部监听失败：${PORTS.joinToString()}")
        return false
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        token = ""
        handler = null
        Log.i(TAG, "手机维护服务已停止")
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return
            val requestLine = head.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(client, 400, "text/plain; charset=utf-8", "Bad Request".toByteArray())
                return
            }
            val method = parts[0]
            val target = parts[1]
            // 每请求一行：先证明包有没有到这台设备。日志里完全没有这行 = 网络层就没通，
            // 有这行但紧跟「拒绝」= token 不对，有这行且 200 = 问题在页面/浏览器那侧。
            Log.i(TAG, "请求 $method ${target.substringBefore('?')} 来自 ${client.inetAddress.hostAddress}")
            val body = readBody(input, head)

            if (method != "GET" && method != "POST") {
                respond(client, 405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray())
                return
            }
            val query = parseQuery(target.substringAfter('?', ""))
            if (query["k"].orEmpty() != token || token.isBlank()) {
                Log.w(TAG, "拒绝：token 不匹配 路径=${target.substringBefore('?')}")
                respond(client, 403, "text/plain; charset=utf-8", "Forbidden".toByteArray())
                return
            }

            // trimEnd('/') 会把根路径 "/" 吃成空串，导致 GET / 掉进 404 分支，所以根要补回来
            val path = target.substringBefore('?').trimEnd('/').ifEmpty { "/" }
            when {
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(client, 200, "text/html; charset=utf-8", PairWebPage.html.toByteArray())

                method == "GET" && path == "/api/sources" -> {
                    val list = call { handler?.list() } ?: emptyList()
                    json(client, 200, gson.toJson(mapOf("sources" to list)))
                }

                method == "POST" && path == "/api/source" -> {
                    val req = parseSave(body)
                    if (req == null) {
                        json(client, 400, gson.toJson(mapOf("ok" to false, "message" to "表单数据无法解析")))
                    } else {
                        val result = call { handler?.save(req) }
                            ?: PairedSaveResult(false, "电视端已退出维护页，请重新扫码")
                        json(client, 200, gson.toJson(mapOf("ok" to result.ok, "message" to result.message)))
                    }
                }

                else -> respond(client, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理手机请求失败：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** 请求线程停在 socket 读上，落到这里才进协程；handler 自己切主线程写 UI 状态 */
    private fun <T> call(block: suspend () -> T?): T? = runBlocking { block() }

    private fun parseSave(body: String): PairedSaveRequest? = runCatching {
        val obj: JsonObject = gson.fromJson(body, JsonObject::class.java) ?: return null
        val kindName = obj.str("kind")
        val kind = if (kindName.equals("EMBY", true)) SourceKind.EMBY else SourceKind.TVBOX
        PairedSaveRequest(
            id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            name = obj.str("name"),
            kind = kind,
            location = obj.str("location"),
            username = obj.str("username"),
            password = obj.str("password")
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun json(client: Socket, code: Int, payload: String) =
        respond(client, code, "application/json; charset=utf-8", payload.toByteArray())

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

    /** 表单是 JSON POST，体必须真读出来（不像壳代理那样可以直接丢） */
    private fun readBody(input: InputStream, head: String): String {
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: return ""
        if (length <= 0) return ""
        if (length > MAX_BODY_BYTES) {
            Log.w(TAG, "请求体过大：$length 字节，已拒绝")
            return ""
        }
        val buf = ByteArrayOutputStream(length.toInt())
        var left = length
        val chunk = ByteArray(8192)
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
            if (n <= 0) break
            buf.write(chunk, 0, n)
            left -= n
        }
        return buf.toString("UTF-8")
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            out[key] = decode(pair.substringAfter('=', ""))
        }
        return out
    }

    private fun decode(s: String): String = runCatching {
        URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    private fun respond(client: Socket, code: Int, contentType: String, body: ByteArray) {
        val out = client.getOutputStream()
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.0 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}

/** 手机走得通的那几张卡：Wi-Fi、热点、网线 */
private val LAN_IFACE_PREFIXES = listOf("wlan", "ap", "eth", "en", "bridge", "br")

/** 也报 site-local、但手机绝对连不进来的：蜂窝数据与各类隧道 */
private val CELL_IFACE_PREFIXES = listOf(
    "rmnet", "r_rmnet", "wwan", "ccmni", "vcc", "pdp", "tun", "ppp", "ipsec", "gsm", "cdma", "qmimms"
)

private fun ifaceRank(name: String): Int {
    val n = name.lowercase()
    return when {
        LAN_IFACE_PREFIXES.any { n.startsWith(it) } -> 0
        CELL_IFACE_PREFIXES.any { n.startsWith(it) } -> 2
        else -> 1
    }
}

/**
 * 电视在局域网里的 IPv4，手机扫码后连的就是它。
 * 遍历网卡而不是走 WifiManager：网线口的盒子、有线/无线同时在用的盒子都要能拿到。
 *
 * 不能直接取第一个 site-local：盒子的蜂窝口（rmnet_data3=10.x）同样算 site-local，
 * 而且常常排在 wlan 前面，二维码编上它，手机走 Wi-Fi 就打不开页面。
 * 所以按口名分三档，Wi-Fi/网线优先，蜂窝与隧道只兜底。
 */
internal fun localIpAddress(): String? {
    val pairs = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>().map { nif.name to it }
            }
            .toList()
    }.getOrDefault(emptyList())
    // sortedBy 稳定：同档内仍按枚举顺序，wlan0 与 wlan1 同时存在时保留原来的行为
    val hit = pairs
        .filter { !it.second.isLoopbackAddress && it.second.isSiteLocalAddress }
        .sortedBy { ifaceRank(it.first) }
        .firstOrNull()
    // 星号标出哪些算 site-local，「选中」带上口名：下次一眼看出编进二维码的是哪张卡
    Log.i(
        "SourcePair",
        "网卡 " + pairs.joinToString {
            "${it.first}=${it.second.hostAddress}${if (it.second.isSiteLocalAddress) "*" else ""}"
        } + " 选中=" + (hit?.let { "${it.first}=${it.second.hostAddress}" } ?: "无")
    )
    return hit?.second?.hostAddress
}
