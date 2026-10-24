package com.tabbit.myvidhub.data.tvbox

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * TVBox type 3 爬虫 JAR 运行时加载器（进程级单例）。
 *
 * 这类配置里的 csp_XxxGuard 是**加密壳**：
 *   spider JAR 内只有 39KB 的 classes.dex（壳类 + Init 引导类）、
 *   assets/wexguard_v7.so、assets/wexguard_v8.so（解密用的 native 库）、
 *   assets/wexshinidie.guard（约 1MB 的加密 dex）。
 *
 * 关键点：
 *  1. 必须把**整个 JAR（zip）**交给 DexClassLoader，不能只抽 classes.dex。
 *     因为 Init 通过 Class.getResourceAsStream("assets/wexguard_v8.so") 从自己的
 *     容器里读 so 和 guard；纯 dex 没有资源，读出来是 null，解密必然失败。
 *  2. Init.init(context) 会自己把 so 解出来写进 cacheDir、chmod、System.load，
 *     再用 native 把 .guard 解密成真正的 dex。
 *  3. 真正的 Guard 实现不在壳 dex 里，所以 Init 之后需要重新扫描 cacheDir
 *     找到解密出来的 dex，用「jar + 解密 dex」组成新的 classpath 再加载 Guard。
 *
 * 为什么是单例：TVBox 配置有多个站点（线路），每条线路都会 new 一个 TvBoxClient，
 * 如果缓存是实例级的，每次切线路就重复下载 JAR、重复跑壳 Init、重复起本地代理 ——
 * 这正是「每点一次都弹『正在初始化本地代理』」的根因。
 * 这里把 JAR 落盘、解密 dex、壳 Init、Guard 实例全部做成进程级共享：
 * 整个 App 进程对同一 spider 包只 Init 一次、只起一次本地代理，之后任意线路直接复用。
 */
object SpiderLoader {

    /** 已初始化的 Application Context（init 幂等，进程内只跑一次） */
    @Volatile
    private var appContext: Context? = null

    private lateinit var jarDir: File
    private lateinit var optDir: File

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** key → 可加载 Guard 的 classloader（可能是重建后的） */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val instances = ConcurrentHashMap<String, SpiderProxy>()

    /** key → jar 路径 */
    private val jars = ConcurrentHashMap<String, String>()

    /** key → 已确认的初始化失败原因；命中后直接报错，避免重复触发壳初始化（重复弹「正在初始化本地代理」） */
    private val failedInit = ConcurrentHashMap<String, String>()

    /** 壳初始化专用线程：Init.init 是纯阻塞反射调用，协程取消对纯阻塞无效，只能放独立线程上用超时等待 */
    private val initExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spider-init").apply { isDaemon = true }
    }

    /** Guard 类加载/实例化专用线程：loadClass 触发 Guard 类 static{}，newInstance 触发实例构造，
     *  壳可能把代理/JSVM 初始化塞进这些地方导致无限阻塞，同样只能靠独立线程 + 超时兜底 */
    private val classExec: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "spider-class").apply { isDaemon = true }
    }

    /** 最近一次失败原因，供上层把真实错误透出到 UI */
    @Volatile
    var lastError: String = ""
        private set

    private const val TAG = "SpiderLoader"
    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"
    private const val INIT_TIMEOUT_MS = 30_000L
    /** Guard 类 loadClass/newInstance 的超时：壳的 Guard 类 static{} 可能无限阻塞，
     *  这两个调用触发它时必须由超时兜底，否则整个流程挂死 */
    private const val CLASS_TIMEOUT_MS = 30_000L
    /** 壳 Init 内部会回连本地代理（127.0.0.1:8964 起递增端口）确认就绪，
     *  代理未起时抛 ConnectException。策略：第 1 次 Init 失败后轮询等代理端口
     *  出现，端口通了再调第 2 次 Init。最多 2 次 Init，避免反复弹「正在初始化本地代理」。 */
    private const val PROXY_WAIT_MS = 30_000L
    private const val PROXY_WAIT_INTERVAL_MS = 2_000L

    /** 进程级初始化：幂等，多次调用（多个 TvBoxClient）只跑一次。 */
    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val app = context.applicationContext
            jarDir = File(app.cacheDir, "spiders").also { it.mkdirs() }
            optDir = File(app.cacheDir, "spider_opt").also { it.mkdirs() }
            appContext = app
        }
    }

    /** 已初始化的 Context；未 init 就调用加载方法是内部错误 */
    private fun ctx(): Context = appContext
        ?: throw IllegalStateException("SpiderLoader 尚未初始化（需要 Context 才能加载爬虫）")

    private fun parseSpider(raw: String): Pair<String, String> {
        val parts = raw.trim().split(';').map { it.trim() }
        return parts.firstOrNull().orEmpty() to parts.getOrNull(2).orEmpty()
    }

    suspend fun ensureLoaded(spiderRaw: String): String = withContext(Dispatchers.IO) {
        val (url, md5) = parseSpider(spiderRaw)
        check(url.isNotBlank()) { "配置里没有 spider 字段（此类配置需要 spider 包才能解析）" }

        val key = md5.ifBlank { md5Hex(url) }
        loaders[key]?.let { return@withContext key }

        // 上次初始化失败过的包：先尝试用已落盘的解密 dex 恢复（Init 可能在超时后于后台完成），
        // 恢复不了则直接报错，不再重复调用壳的 Init —— 否则会一直弹「正在初始化本地代理」。
        failedInit[key]?.let { err ->
            tryRecoverLoader(key)?.let { recovered ->
                failedInit.remove(key)
                loaders[key] = recovered
                return@withContext key
            }
            fail(err)
        }

        try {
            prepareLoader(key, url, md5)
        } catch (e: Exception) {
            failedInit[key] = e.message ?: "spider 包加载失败"
            throw e
        }
        key
    }

    private suspend fun prepareLoader(key: String, url: String, md5: String) {
        // 1) 下载整个 JAR（保持 zip 原样，不解压）
        val jarFile = File(jarDir, "$key.jar")
        if (!jarFile.exists() || (md5.isNotBlank() && md5Hex(jarFile) != md5)) {
            download(url, jarFile)
            if (md5.isNotBlank() && md5Hex(jarFile) != md5) {
                fail("spider 包 MD5 校验失败（期望 $md5）")
            }
        }
        // Android 14（targetSdk 34）禁止从可写文件加载 dex，必须置为只读
        if (jarFile.canWrite()) jarFile.setReadOnly()
        jars[key] = jarFile.absolutePath

        // 2) 壳 dex 的 classloader
        val stubLoader = DexClassLoader(jarFile.absolutePath, optDir.absolutePath, null, ctx().classLoader)

        // 3) 调 Init.init(context) —— 让壳自己去解 so + 解密 guard。
        //    用独立线程 + 超时等待：壳的本地代理/JSVM 初始化可能挂起（就是一直提示
        //    「正在初始化本地代理」的那个动作），纯阻塞反射调用无法被协程取消，必须由超时兜底。
        //    另外壳的 Init 会回连本地代理（127.0.0.1:8964）确认就绪，代理慢启动时首次
        //    Init 会抛 ConnectException，需隔几秒重试直到代理起来。
        val initStarted = System.currentTimeMillis()
        val initClazz = runCatching { stubLoader.loadClass("com.github.catvod.spider.Init") }
            .getOrElse {
                fail("spider 包缺少 Init 类：${it.message}")
            }
        try {
            invokeInitTimed(initClazz)
        } catch (e: Exception) {
            fail("spider 壳初始化失败：${e.message}")
        }

        // 4) 扫描 Init 写出的解密 dex，重组 classpath
        val decrypted = scanDecryptedDex(initStarted)
        Log.d(TAG, "Init 完成，解密 dex ${decrypted.size} 个：${decrypted.joinToString { File(it).name }}")

        // 解密出的 dex 同样必须只读，否则 Android 14 拒载
        decrypted.forEach { runCatching { File(it).takeIf { f -> f.canWrite() }?.setReadOnly() } }

        val classpath = (listOf(jarFile.absolutePath) + decrypted).joinToString(File.pathSeparator)
        val loader = if (classpath == jarFile.absolutePath) stubLoader
        else DexClassLoader(classpath, optDir.absolutePath, null, ctx().classLoader)

        loaders[key] = loader
    }

    /**
     * 带超时的 Init.init 调用（最多 2 次 + 代理端口轮询）。
     * Init.init 是纯阻塞反射调用（解 so + native 解密 + 本地代理/JSVM 初始化），
     * 协程取消对它无效，只能放独立线程上用 Future.get 等待，超时即放弃。
     * 壳的 Init 内部会回连本地代理（127.0.0.1:8964）确认就绪，代理未起时抛
     * ConnectException；第 1 次失败后轮询等代理端口出现，端口通了再调第 2 次。
     */
    private suspend fun invokeInitTimed(initClazz: Class<*>): Any? {
        try {
            return callInit(initClazz, INIT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "壳初始化失败（第 1 次）：${e.message}")
        }
        // 等壳的本地代理端口起来
        if (!waitProxyReady(PROXY_WAIT_MS)) {
            throw IllegalStateException(
                "壳初始化失败：本地代理(127.0.0.1:8964)未启动，连接被拒（${isPortOpen(8964)}）"
            )
        }
        return callInit(initClazz, INIT_TIMEOUT_MS)
    }

    private fun callInit(initClazz: Class<*>, timeoutMs: Long): Any? {
        val future = initExecutor.submit<Any?> { invokeInit(initClazz) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException(
                "壳初始化超时（${timeoutMs / 1000}s 未完成，通常是本地代理/JSVM 初始化挂起）"
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IllegalStateException("壳初始化失败：${e.cause?.message ?: e.message}")
        } catch (e: InterruptedException) {
            throw IllegalStateException("壳初始化被中断")
        }
    }

    /** Guard 类 loadClass/newInstance 的带超时执行：超时或异常都返回 null（异常原因由调用方通过
     *  lastError 描述），保证壳 Guard 类 static{}/构造器无限阻塞时流程不会挂死 */
    private fun <T> timedClass(block: () -> T): T? {
        val future = classExec.submit<T?> { block() }
        return try {
            future.get(CLASS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "Guard 类加载/实例化超时（${CLASS_TIMEOUT_MS / 1000}s 未返回，已放弃等待）")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Guard 类加载/实例化异常：${e.message}")
            null
        }
    }

    /** 轮询壳的本地代理端口（127.0.0.1:8964）直到可连接 */
    private suspend fun waitProxyReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(8964)) return true
            delay(PROXY_WAIT_INTERVAL_MS)
        }
        return isPortOpen(8964)
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_500) }
    }.isSuccess

    /** 不触发壳 Init，直接用已落盘的解密 dex 重建 classloader（兼容 Init 超时后于后台完成的情况） */
    private fun tryRecoverLoader(key: String): DexClassLoader? {
        val jarPath = jars[key] ?: return null
        val decrypted = scanDecryptedDex(System.currentTimeMillis() - 10 * 60_000)
        if (decrypted.isEmpty()) return null
        decrypted.forEach { runCatching { File(it).takeIf { f -> f.canWrite() }?.setReadOnly() } }
        val classpath = (listOf(jarPath) + decrypted).joinToString(File.pathSeparator)
        return runCatching { DexClassLoader(classpath, optDir.absolutePath, null, ctx().classLoader) }.getOrNull()
    }

    private fun invokeInit(initClazz: Class<*>): Any? {
        // 饭太硬等加密壳要求 init(Context) 的实参必须是 Application（dex 内 check-cast 到 Application），
        // 传普通 Context 会抛 ClassCastException → native 解密不执行 → Init.loader() 为 null。
        val appCtx: Context = ctx().applicationContext

        // 常见签名：static void init(Context) / static void init(Context, String)
        runCatching {
            initClazz.getDeclaredMethod("init", Context::class.java).invoke(null, appCtx)
        }.onSuccess { return it }
        return runCatching {
            initClazz.getDeclaredMethod("init", Context::class.java, String::class.java).invoke(null, appCtx, "")
        }.getOrElse { throw it }
    }

    /** 递归扫描 cacheDir 里以 dex 魔数开头的文件（Init 解密产物） */
    private fun scanDecryptedDex(after: Long): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 4) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.length() > 4096 && f.lastModified() >= after - 2000 && hasDexMagic(f)) {
                    out += f.absolutePath
                }
            }
        }
        walk(ctx().cacheDir, 0)
        return out
    }

    private fun hasDexMagic(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val buf = ByteArray(4)
            ins.read(buf) == 4 && buf.contentEquals(DEX_MAGIC)
        }
    }.getOrDefault(false)

    fun getSpider(loaderKey: String, api: String, ext: Any?): SpiderProxy? {
        val cacheKey = "$loaderKey:$api"
        instances[cacheKey]?.let { return it }

        val className = resolveClassName(api)
        val clazz = loadGuardClass(loaderKey, className, api) ?: run {
            lastError = "找不到爬虫类 $className（spider 包解密失败或站点名不匹配）"
            Log.e(TAG, lastError)
            return null
        }

        var newInstanceError: Throwable? = null
        val instance = timedClass<Any?> {
            runCatching {
                clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrElse {
                newInstanceError = it
                null
            }
        }
        if (instance == null) {
            lastError = "实例化 $className 失败：${newInstanceError?.message ?: "超时（${CLASS_TIMEOUT_MS / 1000}s 未返回）"}"
            Log.e(TAG, lastError, newInstanceError)
            return null
        }

        val proxy = SpiderProxy(clazz, instance)
        if (!proxy.init(ctx(), extStr(ext))) {
            // 壳 spider 的 init 可能依赖本地代理/JSVM，失败或超时不致命，
            // 但绝不能无限阻塞主流程 —— 已由 SpiderProxy 内 30s 超时兜底
            Log.w(TAG, "$className.init 失败或超时（不致命，继续尝试）")
        }
        instances[cacheKey] = proxy
        return proxy
    }

    /**
     * 加载 Guard 类。首次失败时重新扫描 cacheDir 里的解密 dex 并重建 classloader，
     * 兼容「Init 懒解密 / 首个 class 请求时才落盘」的实现。
     */
    private fun loadGuardClass(loaderKey: String, className: String, api: String): Class<*>? {
        loaders[loaderKey]?.let { l ->
            timedClass { l.loadClass(className) }?.let { return it }
            timedClass { l.loadClass(api) }?.let { return it }
        }

        val jarPath = jars[loaderKey] ?: return null
        val decrypted = scanDecryptedDex(System.currentTimeMillis() - 60_000)
        if (decrypted.isEmpty()) return null
        decrypted.forEach { runCatching { File(it).takeIf { f -> f.canWrite() }?.setReadOnly() } }

        val classpath = (listOf(jarPath) + decrypted).joinToString(File.pathSeparator)
        val rebuilt = runCatching {
            DexClassLoader(classpath, optDir.absolutePath, null, ctx().classLoader)
        }.getOrNull() ?: return null

        loaders[loaderKey] = rebuilt
        return timedClass { rebuilt.loadClass(className) } ?: timedClass { rebuilt.loadClass(api) }
    }

    private fun extStr(ext: Any?): String = when (ext) {
        null -> ""
        is String -> ext
        else -> ext.toString()
    }

    private fun resolveClassName(api: String): String {
        var name = api
        if (name.startsWith("csp_")) name = name.removePrefix("csp_")
        if (name.startsWith("com.github.catvod.spider.")) return api
        return "com.github.catvod.spider.$name"
    }

    private fun fail(msg: String): Nothing {
        lastError = msg
        Log.e(TAG, msg)
        throw IllegalStateException(msg)
    }

    private fun download(url: String, target: File) {
        // 旧文件可能是只读的（上一轮已置只读），先恢复可写再覆盖
        if (target.exists()) target.setWritable(true)
        val punyUrl = TvBoxClient.toPunycode(url)
        val request = Request.Builder().url(punyUrl).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) fail("下载 spider 包失败 HTTP ${resp.code}")
            val body = resp.body ?: fail("下载 spider 包返回空内容")
            FileOutputStream(target).use { fos -> body.byteStream().copyTo(fos) }
        }
    }

    private fun md5Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Spider 实例的反射代理。
 * CatVod Spider 基类的标准方法：
 *   void init(Context, String)
 *   String homeContent(boolean)
 *   String categoryContent(String, String, boolean, HashMap<String,String>)
 *   String detailContent(List<String>)
 *   String playerContent(String, String, List<String>)
 *   String searchContent(String, boolean)
 */
class SpiderProxy(private val clazz: Class<*>, private val instance: Any) {

    companion object {
        private const val TAG = "SpiderProxy"
        private const val CALL_TIMEOUT_MS = 30_000L
    }

    /** 爬虫方法统一走独立线程 + 超时：壳内方法常自带长阻塞（网络/JSVM），协程取消对纯阻塞无效，由超时兜底 */
    private val exec: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "spider-call").apply { isDaemon = true }
    }

    private fun <T> timedCall(block: () -> T): T? {
        val future = exec.submit<T?> { block() }
        return try {
            future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "爬虫方法调用超时（${CALL_TIMEOUT_MS / 1000}s 未返回，已放弃等待）")
            null
        } catch (e: Exception) {
            // 反射调用抛 InvocationTargetException，真实原因在 cause 里，必须拆开透出
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            val msg = cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: e.message
            Log.w(TAG, "爬虫方法调用异常：$msg")
            if (cause != null) Log.w(TAG, "爬虫方法调用异常堆栈", cause)
            null
        }
    }

    /** 壳 spider 实例的 init(Context, String)。走 30s 超时，避免壳实现阻塞时挂死整个流程 */
    fun init(context: Context, ext: String): Boolean {
        return runCatching {
            timedCall<Boolean> {
                clazz.getMethod("init", Context::class.java, String::class.java)
                    .invoke(instance, context, ext)
                true
            }
        }.getOrDefault(false) == true
    }

    fun homeContent(filter: Boolean = true): String? = timedCall {
        clazz.getMethod("homeContent", Boolean::class.javaPrimitiveType).invoke(instance, filter) as String
    }

    fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean = true,
        extend: Map<String, String>? = null
    ): String? = timedCall {
        val map = (extend as? HashMap<String, String>) ?: HashMap()
        clazz.getMethod(
            "categoryContent",
            String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, HashMap::class.java
        ).invoke(instance, tid, pg, filter, map) as String
    }

    fun detailContent(ids: List<String>): String? = timedCall {
        clazz.getMethod("detailContent", List::class.java).invoke(instance, ids) as String
    }

    fun playerContent(flag: String, id: String, vipFlags: List<String>? = null): String? = timedCall {
        clazz.getMethod("playerContent", String::class.java, String::class.java, List::class.java)
            .invoke(instance, flag, id, vipFlags ?: emptyList<String>()) as String
    }

    fun searchContent(key: String, quick: Boolean = true): String? = timedCall {
        clazz.getMethod("searchContent", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(instance, key, quick) as String
    }
}
