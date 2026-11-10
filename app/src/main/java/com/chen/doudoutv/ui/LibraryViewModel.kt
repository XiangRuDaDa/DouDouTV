package com.chen.doudoutv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chen.doudoutv.core.Prefs
import com.chen.doudoutv.data.scrape.ScrapeStrategy
import com.chen.doudoutv.data.MediaRepository
import com.chen.doudoutv.data.emby.EmbyClient
import com.chen.doudoutv.data.model.MediaEntity
import com.chen.doudoutv.data.model.MediaKind
import com.chen.doudoutv.data.model.SourceEntity
import com.chen.doudoutv.data.model.SourceKind
import com.chen.doudoutv.data.model.SourceUrl
import com.chen.doudoutv.data.pair.PairedSaveRequest
import com.chen.doudoutv.data.pair.PairedSaveResult
import com.chen.doudoutv.data.pair.PairedSource
import com.chen.doudoutv.data.pair.SourcePairHandler
import com.chen.doudoutv.data.tvbox.CatVodResultParser
import com.chen.doudoutv.data.tvbox.TvBoxClient
import com.chen.doudoutv.data.tvbox.TvBoxClient.TvBoxSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 首页分类行首批展示条数（够铺满电视一行），滚到尾部再追加 */
        const val CATEGORY_PREVIEW_BATCH = 10

        /**
         * 进程级唯一实例，不再随 Activity 一起换。
         *
         * 真机日志（同 pid、没有任何 finish() 堆栈、onCreate 的 savedInstanceState 非空）显示
         * MainActivity 每次进出播放器都被系统整只重建一次：挂在 Activity 上的 ViewModel 于是每回换一个，
         * 线路绑定（[activeSite] / [homeCategories]）和分类分页缓存全丢 ——
         * 表现就是「从详情页返回分类页，每次都要重新加载」；
         * 而且新实例的第一趟分页正好撞在线路还没绑回来的窗口里，被 [loadBrowsePage] 收口成
         * 「这条线路没有内容」固化进缓存，整个分类页灰到底，只有换分类才恢复。
         */
        fun shared(app: Application): LibraryViewModel = synchronized(this) {
            instance ?: LibraryViewModel(app).also { instance = it }
        }

        private var instance: LibraryViewModel? = null
    }

    private val repo = MediaRepository(app)

    val sources: StateFlow<List<SourceEntity>> = repo.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 启用中的来源列表（首页来源/线路选择与回退只基于启用来源） */
    val enabledSources: StateFlow<List<SourceEntity>> = repo.observeSources()
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val library: StateFlow<List<MediaEntity>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 设置页「媒体条目」用的计数。
     * 那一页只要一个数字，没必要为此把整张表订阅进内存
     * （表每次失效都会把全部条目重新读一遍）。
     */
    val libraryCount: StateFlow<Int> = repo.observeMediaCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val continueWatching: StateFlow<List<MediaEntity>> = repo.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featured: StateFlow<List<MediaEntity>> = repo.observeFeatured()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<MediaEntity>> = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 首页画廊：每类只取最新 10 条（懒加载） */
    val recentMovies: StateFlow<List<MediaEntity>> = repo.observeRecent(MediaKind.MOVIE, 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentSeries: StateFlow<List<MediaEntity>> = repo.observeRecent(MediaKind.SERIES, 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentAnime: StateFlow<List<MediaEntity>> = repo.observeAnime(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var scanning by mutableStateOf(false)
        private set

    /** 正在同步的单个来源 id：卡片按钮只对自己转圈，不再所有卡片一起转 */
    private val _syncingSourceIds = mutableStateMapOf<Long, Boolean>()
    val syncingSourceIds: Map<Long, Boolean> get() = _syncingSourceIds
    fun isSourceSyncing(id: Long): Boolean = _syncingSourceIds[id] == true

    var scanMessage by mutableStateOf("")
        private set

    /** 本次同步已拉取条数 / 服务端总条数：顶部进度条的分子分母，总数未知（TVBox）时为 0 */
    var scanFetched by mutableStateOf(0)
        private set

    var scanTotal by mutableStateOf(0)
        private set

    var message by mutableStateOf<String?>(null)

    // ---------- 来源页展示用的派生数据 ----------

    /** 各来源已入库条目数（内存缓存，来源增删同步后刷新） */
    private val _sourceCounts = mutableStateMapOf<Long, Int>()
    val sourceCounts: Map<Long, Int> get() = _sourceCounts

    /** 各来源最近同步时间的镜像，变化时触发界面重组；真实值落盘在 Prefs */
    var sourceLastSync by mutableStateOf<Map<Long, Long>>(emptyMap())
        private set

    fun lastSyncOf(sourceId: Long): Long =
        sourceLastSync[sourceId] ?: Prefs.sourceLastSync(sourceId)

    /** 刷新所有来源的条目数（删除来源后不清也无妨，界面按 id 取） */
    private suspend fun refreshSourceCounts() {
        runCatching {
            repo.allSources().forEach { _sourceCounts[it.id] = repo.countBySource(it.id) }
        }
    }

    /** 记录某来源同步成功的时间与条目数 */
    private suspend fun markSynced(sourceId: Long) {
        val ts = System.currentTimeMillis()
        Prefs.setSourceLastSync(sourceId, ts)
        sourceLastSync = sourceLastSync + (sourceId to ts)
        _sourceCounts[sourceId] = runCatching { repo.countBySource(sourceId) }.getOrDefault(0)
    }

    // ---------- 首页来源 / 站点（线路）选择 ----------

    /** 选中的首页加载来源；null = 未选择（加载时回退到第一个配置的来源） */
    var homeSourceId by mutableStateOf<Long?>(Prefs.homeSourceId.takeIf { it >= 0 })
        private set

    /** 选中的 TVBox 站点 key；null = 全部站点 */
    var homeSiteKey by mutableStateOf<String?>(Prefs.homeSiteKey.takeIf { it.isNotBlank() })
        private set

    private val _homeSitesBySource = mutableStateMapOf<Long, List<TvBoxSite>>()

    /** 各 TVBox 来源的站点（线路）列表缓存 */
    val homeSitesBySource: Map<Long, List<TvBoxSite>> get() = _homeSitesBySource

    var homeSitesLoading by mutableStateOf(false)
        private set

    /**
     * 当前线路的 homeContent（分类名 + 轮播条目）是否正在拉。
     * 分类页据此在骨架期显示「正在读取这条线路的分类…」，而不是立刻判成「这条线路没有下发分类」。
     */
    var homeSiteLoading by mutableStateOf(false)
        private set

    /** 首页来源/线路加载失败的真实原因，供空状态提示（壳初始化超时等） */
    var homeSitesError by mutableStateOf<String?>(null)
        private set

    /** 当前选中 TVBox 站点（线路）homeContent 返回的分类（class 数组）；空表示该线路没有分类 */
    var homeCategories by mutableStateOf<List<CatVodResultParser.ClassItem>>(emptyList())
        private set

    /**
     * 当前线路 homeContent 的条目，**保持源站返回顺序**。
     * 首页轮播取它的前 N 条：源站推荐顺序才有意义，库里按 addedAt 排的是同步批次顺序。
     * 切换线路 / 刷新时更新，切走时清空，回退到本地库的兜底逻辑。
     */
    var siteHomeItems by mutableStateOf<List<MediaEntity>>(emptyList())
        private set

    /** 当前展开的站点分类 typeId；null = 未选择分类（首页按原来的动态分区展示） */
    var activeCategoryId by mutableStateOf<String?>(null)
        private set

    /** 已加载的站点分类内容（typeId → 实体），内存缓存，切分类不重复请求 */
    private val _categoryVods = mutableStateMapOf<String, List<MediaEntity>>()
    val categoryVods: Map<String, List<MediaEntity>> get() = _categoryVods

    /** 每个分类是否正在加载（用于首页 section 显示骨架屏） */
    private val _categoryPreviewLoading = mutableStateMapOf<String, Boolean>()
    val categoryPreviewLoading: Map<String, Boolean> get() = _categoryPreviewLoading

    /**
     * 每个分类当前展示条数：首屏只铺满一行（10 条），横向滚到尾部再追加。
     * 之前一次塞 60~72 张海报进 LazyRow，电视上首屏组合开销很大。
     */
    private val _categoryVisibleCount = mutableStateMapOf<String, Int>()
    val categoryVisibleCount: Map<String, Int> get() = _categoryVisibleCount

    /** 每个分类已加载到第几页（page 从 1 开始） */
    private val _categoryLoadedPage = mutableStateMapOf<String, Int>()

    /** 每个分类是否已经到底（下一页返回空） */
    private val _categoryEnded = mutableStateMapOf<String, Boolean>()

    var categoryLoading by mutableStateOf(false)
        private set

    /**
     * 首页刷新轮次：每次「刷新当前来源/线路」自增。
     * 分类行的懒加载是 `LaunchedEffect(cat.typeId)`，typeId 不变就不会重跑，
     * 光清缓存会让行永久空白，所以 UI 侧把这个轮次一起放进 key。
     */
    var homeRefreshEpoch by mutableStateOf(0)
        private set

    var categoryError by mutableStateOf<String?>(null)
        private set

    /** 当前选中的站点对象（selectHomeSite 时缓存，供 selectHomeCategory 直接用） */
    private var activeSite: TvBoxSite? = null

    /** 当前刮削引擎：启动时判定，设置页改凭据后会刷新 */
    var scrapeEngine by mutableStateOf(ScrapeStrategy.engine)
        private set

    /** 重新判定刮削方案（用户改完 TMDB 凭据后调用，免重启生效） */
    fun refreshScrapeEngine() {
        scrapeEngine = ScrapeStrategy.refresh()
    }

    init {
        // 启动判定：TMDB 凭据非空 → TMDB 刮削；为空 → 原有方案
        ScrapeStrategy.refresh()
        scrapeEngine = ScrapeStrategy.engine
        // 恢复上次选中的来源时，预取该 TVBox 来源的站点（线路）列表
        viewModelScope.launch {
            homeSourceId?.let { loadHomeSites(it) }
        }
        // 从未手动选择过首页来源时，默认按第一个配置的来源加载（选择后才写入 Prefs）
        viewModelScope.launch {
            if (Prefs.homeSourceId < 0) {
                repo.enabledSources().firstOrNull()?.let { selectHomeSource(it.id) }
            }
            // spider 预热只跑当前这一条链路：加密壳 Init 在电视上要二十多秒纯本地计算，而且和首页刷新
            // 抢同一把加载锁。跑完后首页/切线路/详情页再走到 SpiderLoader 时直接命中进程级缓存，
            // 不再重复解密、重复弹「正在初始化本地代理」。排在默认选择之后，才不会拿到 -1 空跑。
            // 没被选中的来源一概不碰（当前来源是 Emby 时这里直接返回）。
            repo.warmUpTvBoxSpider(Prefs.homeSourceId)
        }
        // 冷启动里这三件小事串成一条顺序跑：以前各自一个 launch，
        // 和壳预热、首页线路加载同时抢 IO，启动那几秒全在一起排队
        viewModelScope.launch {
            // 冷启动时若已配置来源但本地还没有内容，自动同步一次，避免用户以为"加完就坏了"
            runCatching {
                val enabled = repo.enabledSources()
                if (enabled.isNotEmpty() && !repo.hasAnyMedia()) {
                    scanAll()
                } else {
                    // 已有内容：启动只对 Emby 源做增量更新（runSync 内部按 count 自动选全量/增量），
                    // TVBox 不在启动时重拉。增量只取上次之后的变化，轻量、避免反复整库同步被限流。
                    enabled.filter { it.kind == SourceKind.EMBY }.forEach { src ->
                        runCatching { runSync(src) }
                    }
                }
            }
            // 启动时汇总各来源已入库条目数（来源页「N 个条目」展示用）
            refreshSourceCounts()
            // 拼音算法升级后，把库里已有片名重算一遍首字母（只跑一次）
            runCatching { repo.reindexPinyinIfNeeded() }
        }
    }

    // ---------- 首页来源 / 站点（线路）选择 ----------

    /** 清空分类行的内存缓存并自增刷新轮次（切来源 / 切线路 / 首页刷新共用） */
    private fun clearCategoryState() {
        _categoryVods.clear()
        _categoryPreviewLoading.clear()
        _categoryVisibleCount.clear()
        _categoryLoadedPage.clear()
        _categoryEnded.clear()
        // 「查看全部」的分页缓存同源同线路：换线路/刷新后旧内容就不该再出现在列表里
        _browseCache.clear()
        categoryError = null
        homeRefreshEpoch++
    }

    fun selectHomeSource(sourceId: Long?) {
        if (homeSourceId == sourceId) {
            // 重复点同一个来源不算切换，但把线路列表（重新）拉一次，避免之前失败留下空列表
            sourceId?.let { loadHomeSites(it); rebindActiveSite(it) }
            return
        }
        homeSourceId = sourceId
        // 线路按来源各自记忆：切走再切回时恢复该来源上次选的线路（空 = 全部站点）
        homeSiteKey = sourceId?.let { Prefs.homeSiteKeyOf(it).takeIf { key -> key.isNotBlank() } }
        homeSitesError = null
        homeCategories = emptyList()
        siteHomeItems = emptyList()
        activeCategoryId = null
        clearCategoryState()
        activeSite = null
        Prefs.homeSourceId = sourceId ?: -1L
        Prefs.homeSiteKey = homeSiteKey.orEmpty()
        if (sourceId != null) {
            loadHomeSites(sourceId)
            // 线路列表已经在缓存里时 loadHomeSites 直接返回，恢复动作得自己补一次
            rebindActiveSite(sourceId)
        }
    }

    /**
     * 把这条来源「记忆中的线路」接回运行时：[activeSite] 和该线路的分类/轮播内容都只在这一步补齐。
     *
     * 之前只有 [loadHomeSites] 真正发请求之后才会做这个恢复，而线路列表一旦进过缓存它就直接返回 ——
     * 于是「切到一个已经拉过线路的 TVBox 来源」会出现 `homeSiteKey` 有值、`activeSite` 却是 null 的半状态：
     * 分类页既拿不到 `homeCategories`（空 → 「这条线路没有下发分类」），
     * 分页请求也在 [loadBrowsePage] 里因为 activeSite 为 null 被收口成 ended，整页没有数据。
     * 现在缓存命中和重新拉取两条路都走这里。
     */
    private fun rebindActiveSite(sourceId: Long) {
        val sites = _homeSitesBySource[sourceId].orEmpty().filter { it.key.isNotBlank() }
        if (sites.isEmpty() || homeSourceId != sourceId) return
        val key = homeSiteKey
        val restored = key?.let { k -> sites.firstOrNull { it.key == k } }
        when {
            // 记忆里有条线路，且当前还站在「全部站点」上：按那条线路把内容拉回来
            restored != null && activeSite == null -> selectHomeSite(restored.key, restored)
            // 记录的线路在配置里已不存在（站点被删/改 key）：清掉，否则首页会按不存在的 key 过滤成空白
            key != null && restored == null -> selectHomeSite(null, null)
            // 只有一条线路的来源：自动选中，避免首页空白又没按钮可点
            key == null && sites.size == 1 -> sites.first().let { selectHomeSite(it.key, it) }
        }
    }

    fun selectHomeSite(siteKey: String?, site: TvBoxSite? = null) {
        homeSiteKey = siteKey
        Prefs.homeSiteKey = siteKey.orEmpty()
        // 记到来源名下：切到别的来源再切回来，恢复这条线路
        homeSourceId?.let { Prefs.setHomeSiteKeyOf(it, siteKey.orEmpty()) }
        homeSitesError = null
        // 切线路后重置分类状态
        homeCategories = emptyList()
        siteHomeItems = emptyList()
        activeCategoryId = null
        clearCategoryState()
        activeSite = site
        val sourceId = homeSourceId ?: return
        val s = site ?: return
        homeSiteLoading = true
        viewModelScope.launch {
            // 每次切线路都拉一次首页：取 homeContent 的 class 分类名
            // 分类内容（categoryContent）按需懒加载，每个分类的 section 在 UI 首次可见时触发
            try {
                val src = repo.getSource(sourceId) ?: return@launch
                val sync = repo.syncTvBoxSite(src, s)
                // 请求期间用户可能又切了线路：旧结果不能盖掉新线路的分类/轮播
                if (homeSiteKey != s.key) return@launch
                homeCategories = sync.result.categories
                siteHomeItems = sync.entities.filter {
                    it.posterUrl.isNotBlank() || it.backdropUrl.isNotBlank()
                }
            } catch (e: Exception) {
                if (homeSiteKey != s.key) return@launch
                homeSitesError = "线路「${s.name}」加载失败：${e.message ?: "网络或接口异常"}"
            } finally {
                // 只有仍站在发起这次请求的线路上才收口，否则新的请求正在跑，会把它的骨架擦掉
                if (homeSiteKey == s.key) homeSiteLoading = false
            }
        }
    }

    /**
     * 按需懒加载某个分类的首页预览条目（前 LAZY_TAKE=10 条）。
     * UI 层每个分类 section 首次进入 composition 时调用，
     * 避免并发预加载所有分类导致首页卡顿。
     */
    fun ensureCategoryPreview(typeId: String) {
        if (_categoryVods.containsKey(typeId)) return
        // 同一分类可能被「预载」和「行进入可见区」两处同时触发，请求中直接返回
        if (_categoryPreviewLoading[typeId] == true) return
        val sourceId = homeSourceId ?: return
        val site = activeSite ?: return
        viewModelScope.launch {
            _categoryPreviewLoading[typeId] = true
            val src = repo.getSource(sourceId) ?: return@launch
            val items = runCatching { repo.tvBoxCategory(src, site, typeId, 1) }
                .getOrNull().orEmpty()
            _categoryVods[typeId] = items
            _categoryLoadedPage[typeId] = 1
            _categoryVisibleCount[typeId] = items.size.coerceAtMost(CATEGORY_PREVIEW_BATCH)
            if (items.isEmpty()) _categoryEnded[typeId] = true
            _categoryPreviewLoading[typeId] = false
        }
    }

    /**
     * 分类行滚到尾部时追加内容：
     * 先把已拉到内存的条目再放出一批（10 条），内存用完才向源站要下一页。
     */
    fun loadMoreCategoryPreview(typeId: String) {
        val items = _categoryVods[typeId] ?: return
        val visible = _categoryVisibleCount[typeId] ?: CATEGORY_PREVIEW_BATCH
        if (visible < items.size) {
            _categoryVisibleCount[typeId] = (visible + CATEGORY_PREVIEW_BATCH).coerceAtMost(items.size)
            return
        }
        if (_categoryEnded[typeId] == true || _categoryPreviewLoading[typeId] == true) return
        val sourceId = homeSourceId ?: return
        val site = activeSite ?: return
        val nextPage = (_categoryLoadedPage[typeId] ?: 1) + 1
        viewModelScope.launch {
            _categoryPreviewLoading[typeId] = true
            val src = repo.getSource(sourceId)
            val more = if (src == null) emptyList()
            else runCatching { repo.tvBoxCategory(src, site, typeId, nextPage) }.getOrDefault(emptyList())
            if (more.isEmpty()) {
                _categoryEnded[typeId] = true
            } else {
                // 源站翻页时同一条目常会重复出现（上一个视频还在第二页开头），
                // 直接拼接会得到两个相同 id 的卡片，LazyRow 的 key 冲突直接崩，
                // 所以追加时按 remoteId 去重
                val seen = items.mapTo(HashSet(items.size)) { it.remoteId }
                val merged = items + more.filter { seen.add(it.remoteId) }
                _categoryVods[typeId] = merged
                _categoryLoadedPage[typeId] = nextPage
                _categoryVisibleCount[typeId] = (visible + CATEGORY_PREVIEW_BATCH).coerceAtMost(merged.size)
            }
            _categoryPreviewLoading[typeId] = false
        }
    }

    // ---------- 分类「查看全部」分页缓存（双端） ----------

    /**
     * 一条分类浏览游标：已加载的条目 + 翻到第几页。
     *
     * 放在 ViewModel 而不是页面里，是因为进详情会把这一页的子树整个销毁重建：
     * 页面自己记的数据随组合一起没了，返回就只能重新请求 —— 而源站每一页返回的同一条影片
     * 会拿到一个新的内存负 id（MediaRepository.browseEntity），旧的落点记录再也对不上，
     * 焦点与滚动位置双双失效。数据留在这儿，id 就留着，返回时原样恢复。
     */
    private class BrowsePaging {
        val items = mutableStateListOf<MediaEntity>()
        var loadedPage by mutableIntStateOf(0)
        var loading by mutableStateOf(false)
        var ended by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
    }

    private val _browseCache = mutableStateMapOf<String, BrowsePaging>()

    /** 缓存按「来源 + 线路 + 分类」分键：换任何一个维度都是另一份内容 */
    private fun browseKey(typeId: String): String? {
        val sourceId = homeSourceId ?: return null
        val siteKey = homeSiteKey ?: return null
        return "$sourceId|$siteKey|$typeId"
    }

    private fun browseState(typeId: String): BrowsePaging? =
        browseKey(typeId)?.let { _browseCache[it] }

    /** 已加载到内存的全部条目（页面直接渲染这个，不需要自己再攒） */
    fun browseItems(typeId: String): List<MediaEntity> = browseState(typeId)?.items.orEmpty()

    fun browseItemsLoading(typeId: String): Boolean = browseState(typeId)?.loading ?: false

    fun browseItemsEnded(typeId: String): Boolean = browseState(typeId)?.ended ?: false

    fun browseItemsError(typeId: String): String? = browseState(typeId)?.error

    /**
     * 确保第 1 页在内存里：已经有数据就直接返回，一个请求都不发。
     * 只有「从没拉过 / 刚被刷新清掉」才会真的去请求第 1 页。
     */
    fun ensureBrowsePage(typeId: String) {
        val key = browseKey(typeId) ?: return
        val st = _browseCache.getOrPut(key) { BrowsePaging() }
        if (st.loadedPage > 0 || st.loading) return
        loadBrowsePage(typeId, st, 1)
    }

    /** 触底翻页 */
    fun loadMoreBrowsePage(typeId: String) {
        val st = browseState(typeId) ?: return
        if (st.loading || st.ended || st.error != null || st.loadedPage == 0) return
        loadBrowsePage(typeId, st, st.loadedPage + 1)
    }

    /** 失败后重试：已经拿到过条目就接着往下翻，否则重新拉第 1 页 */
    fun retryBrowsePage(typeId: String) {
        val st = browseState(typeId) ?: return
        if (st.loading) return
        st.error = null
        loadBrowsePage(typeId, st, if (st.loadedPage > 0) st.loadedPage + 1 else 1)
    }

    private fun loadBrowsePage(typeId: String, st: BrowsePaging, page: Int) {
        val sourceId = homeSourceId ?: return
        val site = activeSite ?: run {
            // 线路还没选中（冷启动直接进这一页）：和改造前一样按「没有内容」收口，
            // 不能把 loading 留着，否则页面永久转圈
            st.ended = true
            return
        }
        viewModelScope.launch {
            st.loading = true
            val src = repo.getSource(sourceId)
            if (src == null) {
                st.error = "来源已不可用"
                st.loading = false
                return@launch
            }
            val more = runCatching { repo.tvBoxCategory(src, site, typeId, page) }
                .getOrElse {
                    st.error = it.message ?: "网络或接口异常"
                    st.loading = false
                    return@launch
                }
            when {
                // 按 remoteId 而不是内存 id 去重：源站翻页会把上一页尾部的条目再带一遍
                more.isEmpty() -> st.ended = true
                else -> {
                    val seen = st.items.mapTo(HashSet(st.items.size)) { it.remoteId }
                    val fresh = more.filter { seen.add(it.remoteId) }
                    if (fresh.isEmpty()) {
                        // 源站忽略 page 参数、反复返回同一页：没有新增就当到底
                        st.ended = true
                    } else {
                        st.items.addAll(fresh)
                        st.loadedPage = page
                    }
                }
            }
            st.loading = false
        }
    }

    /** 点选站点分类：加载该分类的内容并展开为分区（对应 TVBox 的 categoryContent） */
    fun selectHomeCategory(typeId: String) {
        if (activeCategoryId == typeId) return
        activeCategoryId = typeId
        categoryError = null
        if (_categoryVods.containsKey(typeId)) return
        val sourceId = homeSourceId ?: return
        val site = activeSite ?: return
        viewModelScope.launch {
            categoryLoading = true
            try {
                val src = repo.getSource(sourceId) ?: return@launch
                val items = repo.tvBoxCategory(src, site, typeId)
                _categoryVods[typeId] = items
                _categoryLoadedPage[typeId] = 1
                _categoryVisibleCount[typeId] = items.size.coerceAtMost(CATEGORY_PREVIEW_BATCH)
                if (items.isEmpty()) categoryError = "该分类暂无内容"
            } catch (e: Exception) {
                categoryError = "分类加载失败：${e.message ?: "网络或接口异常"}"
            } finally {
                categoryLoading = false
            }
        }
    }

    /** 首页首次进入 / 冷启动时确保线路列表已加载（之前只有「切来源」才会拉，冷启动首页没有线路按钮） */
    fun ensureHomeSites(sourceId: Long) = loadHomeSites(sourceId)

    private fun loadHomeSites(sourceId: Long) {
        // 之前只要走过一次（哪怕拿到的是空列表）就不再重试，导致切到第二个来源后永远没有线路可选
        if (_homeSitesBySource[sourceId]?.isNotEmpty() == true) {
            // 列表已经在缓存里：不发请求，但线路恢复照样要做（见 [rebindActiveSite]）
            rebindActiveSite(sourceId)
            return
        }
        viewModelScope.launch {
            homeSitesLoading = true
            homeSitesError = null
            val src = repo.getSource(sourceId)
            val sites = if (src?.kind == SourceKind.TVBOX) {
                runCatching { repo.tvBoxSites(src) }.getOrElse {
                    homeSitesError = "获取「${src.name}」线路列表失败：${it.message ?: "网络或接口异常"}"
                    emptyList()
                }
            } else emptyList()
            _homeSitesBySource[sourceId] = sites
            homeSitesLoading = false
            // 冷启动恢复上次选中的线路时，重新拉一次首页分类（分类只在 homeContent 里，重启后需重新获取）
            // 只有一条线路的来源：自动选中，避免首页空白又没按钮可点。
            // 多线路（如 38 个站点的配置）保持「全部站点」聚合视图，由用户自己挑线路。
            rebindActiveSite(sourceId)
        }
    }

    /** 首页内容按「选中的来源 + 站点」过滤；无来源时返回原列表 */
    fun filterForHome(list: List<MediaEntity>): List<MediaEntity> {
        val sourceId = effectiveHomeSourceId() ?: return list
        val siteKey = homeSiteKey
        return list.filter { item ->
            item.sourceId == sourceId &&
                (siteKey == null || homeSiteMatches(item, sourceId, siteKey))
        }
    }

    /**
     * 当前生效的首页来源：手动选择的来源（已删除或已停用则回退），
     * 从未选择过时默认取第一个启用中的来源。
     */
    fun effectiveHomeSourceId(): Long? =
        homeSourceId?.takeIf { id -> enabledSources.value.any { it.id == id } }
            ?: enabledSources.value.firstOrNull()?.id

    private fun homeSiteMatches(item: MediaEntity, sourceId: Long, siteKey: String): Boolean {
        if (siteKey.isBlank()) return true // 空 key 表示聚合视图，全部条目都算命中
        return item.remoteId.startsWith("tvbox:$sourceId:$siteKey:")
    }

    // ---------- 来源管理 ----------

    fun addSource(
        name: String,
        kind: SourceKind,
        location: String,
        username: String,
        password: String
    ) {
        if (name.isBlank() || location.isBlank()) {
            message = "名称与地址不能为空"
            return
        }
        viewModelScope.launch {
            val entity = SourceEntity(
                name = name.trim(),
                kind = kind,
                location = normalizeLocation(kind, location),
                username = username.trim(),
                password = password
            )
            val id = repo.addSource(entity)
            val created = entity.copy(id = id)

            // Emby 立即尝试登录换取 token，失败也保留来源便于用户修正
            if (kind == SourceKind.EMBY) {
                val error = repo.embyLogin(created)
                if (error != null) {
                    message = "$error（来源已保存，可稍后编辑修正）"
                    return@launch
                }
            }
            syncAfterChange(repo.getSource(id) ?: created, created.name, "已添加")
        }
    }

    fun updateSource(
        id: Long,
        name: String,
        kind: SourceKind,
        location: String,
        username: String,
        password: String
    ) {
        if (name.isBlank() || location.isBlank()) {
            message = "名称与地址不能为空"
            return
        }
        viewModelScope.launch {
            val existing = repo.getSource(id) ?: return@launch
            val newLocation = normalizeLocation(kind, location)
            val newName = name.trim()
            val newUser = username.trim()
            val credentialsChanged = existing.location != newLocation ||
                existing.username != newUser || existing.password != password

            var updated = existing.copy(
                name = newName,
                kind = kind,
                location = newLocation,
                username = newUser,
                password = password,
                token = if (credentialsChanged) "" else existing.token,
                userId = if (credentialsChanged) "" else existing.userId
            )
            repo.updateSource(updated)

            if (kind == SourceKind.EMBY && updated.token.isBlank()) {
                val error = repo.embyLogin(updated)
                if (error != null) {
                    message = "$error（配置已保存，可稍后重试）"
                    return@launch
                }
            }
            updated = repo.getSource(id) ?: updated
            syncAfterChange(updated, newName, "已更新")
        }
    }

    /**
     * 手机扫码页要的两件事：列出已有来源、保存推送来的那一条。
     * 列表里刻意不带 password / token —— 手机页只要知道「库里存过没有」。
     */
    internal fun pairHandler(): SourcePairHandler = object : SourcePairHandler {
        override suspend fun list(): List<PairedSource> = withContext(Dispatchers.IO) {
            repo.allSources().map {
                PairedSource(
                    id = it.id, name = it.name, kind = it.kind.name,
                    location = it.location, username = it.username,
                    hasPassword = it.password.isNotBlank(), enabled = it.enabled
                )
            }
        }

        override suspend fun save(req: PairedSaveRequest): PairedSaveResult =
            saveSourceFromPair(req)
    }

    /**
     * 手机扫码页推送来的保存 —— 与电视端 addSource/updateSource 同一条链：
     * 地址规范化、Emby 登录探测、保存后自动同步，一个环节都不省。
     *
     * 网页是外部输入，所以这里不校验「来源不存在」「名称为空」之外的东西，
     * 校验结果原样变成手机页上的那句话。密码留空 = 保持库里原值（网页永远拿不到明文密码）。
     */
    suspend fun saveSourceFromPair(req: PairedSaveRequest): PairedSaveResult =
        withContext(Dispatchers.Main.immediate) {
            val name = req.name.trim()
            val location = SourceUrl.normalize(req.kind, req.location)
            if (name.isBlank() || location.isBlank())
                return@withContext PairedSaveResult(false, "名称与地址不能为空")

            val existing = if (req.id > 0L) repo.getSource(req.id) else null
            if (req.id > 0L && existing == null)
                return@withContext PairedSaveResult(false, "这条来源在电视上已被删除，请刷新手机页")

            val username = req.username.trim()
            val password = if (existing == null) req.password else req.password.ifBlank { existing.password }
            val action: String
            var saved: SourceEntity

            if (existing == null) {
                val entity = SourceEntity(
                    name = name, kind = req.kind, location = location,
                    username = username, password = password
                )
                saved = entity.copy(id = repo.addSource(entity))
                action = "已添加"
            } else {
                val credentialsChanged = existing.location != location ||
                    existing.username != username || existing.password != password
                saved = existing.copy(
                    name = name, kind = req.kind, location = location, username = username,
                    password = password,
                    token = if (credentialsChanged) "" else existing.token,
                    userId = if (credentialsChanged) "" else existing.userId
                )
                repo.updateSource(saved)
                action = "已更新"
            }

            if (req.kind == SourceKind.EMBY && saved.token.isBlank()) {
                val error = repo.embyLogin(saved)
                // 登录失败也保留配置：与电视端一致，让用户在手机页改了再推一次
                if (error != null)
                    return@withContext PairedSaveResult(false, "$error（配置已保存，可修正后重推）")
            }

            saved = repo.getSource(saved.id) ?: saved
            val count = syncAfterChange(saved, name, action)
            PairedSaveResult(
                ok = true,
                message = if (count >= 0) "$action「$name」，已同步 $count 个条目"
                else "$action「$name」，但同步未取到内容"
            )
        }

    private fun normalizeLocation(kind: SourceKind, location: String): String =
        SourceUrl.normalize(kind, location)

    /** 新增/编辑完成后自动同步该来源，解决“添加后首页无数据”。返回同步条数，-1 表示同步失败。 */
    private suspend fun syncAfterChange(source: SourceEntity, label: String, action: String): Int {
        scanning = true
        scanMessage = "正在同步 $label…"
        return try {
            val count = runSync(source)
            message = if (count > 0) "$action $label，已同步 $count 个条目"
            else "$action $label，但未获取到内容"
            count
        } catch (e: Exception) {
            message = "$action $label，同步失败：${e.message ?: "网络或接口异常"}"
            -1
        } finally {
            scanning = false
            scanMessage = ""
        }
    }

    private suspend fun runSync(source: SourceEntity, force: Boolean = false): Int {
        var mode = "全量"
        // 每个来源各自一份进度：全部同步时进度条按当前来源重新走一遍
        scanFetched = 0
        scanTotal = 0
        val count = when (source.kind) {
            SourceKind.EMBY -> {
                val last = lastSyncOf(source.id)
                val hasContent = runCatching { repo.countBySource(source.id) > 0 }.getOrDefault(false)
                // A①回填：迁移后老条目无库归属，需一次性全量补齐库标签。用持久化标记判「是否已回填」，
                // 不再按「本地还有无标签的壳」反复判定——总有少量条目逐库查询覆盖不到（如 BB 的 7 条剧集），
                // 旧判据恒真会让每次刷新都整库重拉，触发公益服限流。
                val backfilled = Prefs.sourceLibraryBackfilled(source.id)
                val incremental = !force && hasContent && last > 0 && backfilled
                val synced = if (incremental) {
                    // 已同步过全量：只拉上次之后的变化，避免反复整库重拉触发服务端限流
                    mode = "增量"
                    repo.syncEmbyIncremental(source, last) { c, t ->
                        scanFetched = c
                        scanTotal = t
                        scanMessage = "${source.name}：增量 $c${if (t > 0) " / $t" else ""}"
                    }
                } else {
                    // 首次（本地为空）或强制（重新登录）：分页拉全量
                    repo.syncEmby(source) { c, t ->
                        scanFetched = c
                        scanTotal = t
                        scanMessage = "${source.name}：$c${if (t > 0) " / $t" else ""}"
                    }
                }
                // 全量同步后确实打上库标签（拿到 /Views 并逐库同步）才置位；/Views 失败回退整库（无标签）时不置位，
                // 下次刷新再试，直到拿到库清单。残留的无标签壳不影响置位，避免重蹈恒真覆辙。
                if (!incremental && runCatching { repo.embyLibraries(source.id).isNotEmpty() }.getOrDefault(false)) {
                    Prefs.setSourceLibraryBackfilled(source.id, true)
                }
                synced
            }
            SourceKind.TVBOX -> repo.syncTvBox(source) { c ->
                scanFetched = c
                scanMessage = "${source.name}：$c"
            }
        }
        // 取证锚点：同步条数只进界面状态、不落 logcat，排查「拉全没」时无据可查；这里补一行（不改逻辑）
        android.util.Log.i("MediaSync", "${source.name} [$mode${if (force) "/强制" else ""}] 同步 $count 条")
        // 同步成功才记录时间戳：失败时不该把「最近同步」刷新成刚刚
        markSynced(source.id)
        // 题材清单跟着内容一起更新：分类页与首页的动态分类都要跟着这次同步走
        if (source.kind == SourceKind.EMBY) refreshEmbyGenres(source.id)
        return count
    }

    fun removeSource(source: SourceEntity) {
        viewModelScope.launch {
            repo.deleteSource(source)
            message = "已删除：${source.name}"
        }
    }

    /** 强制重新登录 Emby（token 过期时用），成功后立即重新同步以刷新播放直链 */
    fun reloginEmby(source: SourceEntity) {
        // 同步期一律不接新请求：并发同步会同时打服务端，也让顶部进度条失去意义
        if (scanning) {
            message = "正在同步，请稍候"
            return
        }
        viewModelScope.launch {
            scanning = true
            val error = repo.embyLogin(source)
            if (error != null) {
                message = error
                scanning = false
                return@launch
            }
            val refreshed = repo.getSource(source.id) ?: source
            message = try {
                val count = runSync(refreshed, force = true)
                "已重新登录：${source.name}，同步 $count 个条目"
            } catch (e: Exception) {
                "已重新登录，但同步失败：${e.message ?: "网络或接口异常"}"
            } finally {
                scanning = false
                scanMessage = ""
            }
        }
    }

    /** 启用 / 停用来源：停用后不同步，其内容也从首页隐藏 */
    fun toggleSourceEnabled(source: SourceEntity) {
        viewModelScope.launch {
            repo.setSourceEnabled(source.id, !source.enabled)
            message = if (source.enabled) "已停用：${source.name}" else "已启用：${source.name}"
        }
    }

    // ---------- 收藏 ----------

    fun toggleFavorite(mediaId: Long, favorite: Boolean) {
        viewModelScope.launch { repo.setFavorite(mediaId, favorite) }
    }

    /** 从头播放：重置进度后再进入播放器，就会从 0s 开始 */
    fun resetPosition(mediaId: Long) {
        viewModelScope.launch { repo.resetPosition(mediaId) }
    }

    suspend fun searchMedia(keyword: String): List<MediaEntity> =
        if (keyword.isBlank()) emptyList() else repo.search(keyword)

    /** 拼音联想（debounce 在 UI 层做，这里直接跑） */
    suspend fun pinyinSuggest(input: String): List<String> = repo.pinyinSuggest(input)

    /** 刷新豆瓣热门榜联想缓存；返回 true 表示榜单有更新，UI 需重新查联想 */
    suspend fun refreshHotSuggestions(): Boolean = repo.refreshHotSuggestions()

    /**
     * 按来源分组的搜索结果：返回 Map<来源名称, List<MediaEntity>>。
     * 同一部剧可能从 TVBox 多个云盘线路入库，按 sourceName 分组后 UI 直接分组展示。
     */
    suspend fun searchGrouped(keyword: String): Map<String, List<MediaEntity>> =
        searchMedia(keyword).groupBy { it.sourceName.ifBlank { "未知来源" } }

    /**
     * 远程搜索：TVBox 各站点并发搜索，谁先搜完先把结果回调给 UI（[onPartial]），
     * 全部站点共用一个 5 秒截止时间，命中关键词缓存时立即返回。
     * 结果 id 为负数，点进去时走 TVBox 详情页；本地结果 id 正数，走已有详情页。
     */
    suspend fun searchRemote(
        keyword: String,
        onPartial: (List<MediaEntity>) -> Unit = {}
    ): List<MediaEntity> =
        runCatching {
            repo.tvBoxRemoteSearch(keyword) { partial ->
                // 回调来自 IO 线程，切回主线程再改 Compose 状态
                withContext(Dispatchers.Main.immediate) { onPartial(partial) }
            }
        }.getOrDefault(emptyList())

    /** 分类浏览页：按类型/题材/媒体库分页查询（library 为 null 即不按库过滤） */
    suspend fun browseGenre(kind: String?, genre: String?, limit: Int, offset: Int, library: String? = null): List<MediaEntity> =
        repo.byGenre(kind, genre, library, limit, offset)

    /** 某 Emby 来源已入库的媒体库清单（本地、离线）：分类页/首页按库分区用 */
    suspend fun embyLibraries(sourceId: Long): List<String> = repo.embyLibraries(sourceId)

    /** 类型标签（按内容数量排序） */
    suspend fun genreTags(): List<String> = repo.genreTags()

    /** Emby 刮削信息（导演/演员/分级），非 Emby 源返回 null */
    suspend fun embyDetailOf(sourceId: Long, remoteId: String): EmbyClient.ItemDetail? =
        repo.embyDetail(sourceId, remoteId)

    /** 各 Emby 来源的服务端题材清单缓存：按来源记一份，进程内不重复请求 */
    private val _embyGenres = mutableStateMapOf<Long, List<String>>()

    /** 当前来源已缓存的服务端题材；空表示还没拉到（上层回退本地聚合） */
    fun embyGenres(sourceId: Long): List<String> = _embyGenres[sourceId].orEmpty()

    /** 拉一次某 Emby 来源的服务端题材并缓存；已有缓存或非 Emby 直接返回，不频繁打 /Genres */
    fun ensureEmbyGenres(sourceId: Long) {
        if (_embyGenres.containsKey(sourceId)) return
        viewModelScope.launch {
            val src = repo.getSource(sourceId) ?: return@launch
            if (src.kind != SourceKind.EMBY) return@launch
            val genres = runCatching { repo.embyGenres(src) }.getOrDefault(emptyList())
            // 拉到空（失败/无题材）也记一份空列表，避免每次进分类页都重试打接口
            _embyGenres[sourceId] = genres
        }
    }

    /** Emby 同步完重拉题材清单：库里内容变了，首页与分类页的题材要跟着变，不能留旧缓存 */
    private fun refreshEmbyGenres(sourceId: Long) {
        viewModelScope.launch {
            val src = repo.getSource(sourceId) ?: return@launch
            if (src.kind != SourceKind.EMBY) return@launch
            _embyGenres[sourceId] = runCatching { repo.embyGenres(src) }.getOrDefault(emptyList())
        }
    }

    /** TMDB 刮削（海报/背景图/Logo/评分/简介/演职员），未配置 API Key 时返回 null */
    suspend fun tmdbScrape(
        title: String,
        year: Int,
        kind: String,
        mediaId: Long = 0L
    ): com.chen.doudoutv.data.tmdb.TmdbClient.ScrapeResult? =
        repo.tmdbScrape(title, year, kind, mediaId)

    /**
     * 只查刮削表、绝不联网的那一份：详情页首帧拿它抢在分集/详情那条慢链之前，
     * 把已经刮到的海报 / 背景图 / 片名 Logo 先摆上屏。表里没有就是 null。
     */
    suspend fun tmdbCached(
        title: String,
        year: Int,
        kind: String
    ): com.chen.doudoutv.data.tmdb.TmdbClient.ScrapeResult? = repo.scrapedMetaOf(title, year, kind)

    /** 清空刮削缓存，返回被清掉的缓存条目数 */
    suspend fun clearScrapeCache(): Int = repo.clearScrapeCache()

    /** 当前刮削缓存条目数（数 scraped_meta 表，跨重启也还在的那些） */
    suspend fun scrapeCacheSize(): Int = repo.scrapeCacheSize()

    // ---------- 扫描 ----------

    /** 界面顶栏「全部同步」入口，语义等同 scanAll，命名更贴合按钮文案 */
    fun syncAll() = scanAll()

    /**
     * 首页刷新按钮：只刷当前生效的首页来源，不动其他来源。
     *   · TVBox 且已选中线路 → 只拉该线路的 homeContent（并刷新它的分类）
     *   · TVBox 聚合视图 / Emby → 只同步这一个来源
     * 全量刷新（所有来源）保留在来源页的「全部同步」。
     */
    fun refreshHome() {
        if (scanning) return
        val sourceId = effectiveHomeSourceId()
        if (sourceId == null) {
            message = "还没有可用的来源，请先在「来源」里添加"
            return
        }
        viewModelScope.launch {
            scanning = true
            // 换来源重新起一条进度：上一条的分母不能留到这一条
            scanFetched = 0
            scanTotal = 0
            try {
                val src = repo.getSource(sourceId)
                if (src == null) {
                    message = "首页来源已不存在，请重新选择"
                    return@launch
                }
                // 只有 TVBox 且当前有选中线路时才单刷线路；聚合视图仍要聚合该来源的前几个站点
                val site = if (src.kind == SourceKind.TVBOX && homeSiteKey != null) activeSite else null
                val added = if (site != null) {
                    scanMessage = "正在刷新「${site.name}」…"
                    val sync = repo.syncTvBoxSite(src, site)
                    // 分类行内容不落库，刷新时要连带重拉：先清缓存再自增轮次触发 UI 重新 ensure
                    clearCategoryState()
                    homeCategories = sync.result.categories
                    siteHomeItems = sync.entities.filter {
                        it.posterUrl.isNotBlank() || it.backdropUrl.isNotBlank()
                    }
                    sync.result.vods.size
                } else {
                    scanMessage = "正在刷新 ${src.name}…"
                    runSync(src)
                }
                val scopeName = site?.name ?: src.name
                message = "已刷新「$scopeName」：$added 个条目"
            } catch (e: Exception) {
                message = "刷新失败：${e.message ?: "网络或接口异常"}"
            } finally {
                scanning = false
                scanMessage = ""
            }
        }
    }

    fun scanAll() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            scanMessage = "准备同步…"
            var total = 0
            val errors = mutableListOf<String>()
            try {
                val enabled = repo.enabledSources()
                if (enabled.isEmpty()) {
                    message = "还没有可用的来源，请先添加"
                    return@launch
                }
                for (src in enabled) {
                    scanMessage = "正在同步 ${src.name}…"
                    _syncingSourceIds[src.id] = true
                    // 单个来源失败不能中断其他来源的同步
                    try {
                        total += runSync(src)
                    } catch (e: Exception) {
                        errors += "${src.name}：${e.message ?: "网络或接口异常"}"
                    } finally {
                        _syncingSourceIds.remove(src.id)
                    }
                }
                message = when {
                    total > 0 && errors.isEmpty() -> "同步完成，共 $total 个条目"
                    total > 0 -> "同步完成，共 $total 个条目；部分来源失败：${errors.joinToString("；")}"
                    errors.isNotEmpty() -> "同步失败：${errors.joinToString("；")}"
                    else -> "没有获取到内容"
                }
            } finally {
                scanning = false
                scanMessage = ""
            }
        }
    }

    fun scanOne(source: SourceEntity) {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            _syncingSourceIds[source.id] = true
            try {
                val added = runSync(source)
                message = if (added > 0) "${source.name}：$added 个条目" else "${source.name}：无内容"
            } catch (e: Exception) {
                message = "同步失败：${e.message ?: "未知错误"}"
            } finally {
                _syncingSourceIds.remove(source.id)
                scanning = false
                scanMessage = ""
            }
        }
    }

    // ---------- 详情 ----------

    /** TVBox 详情按需拉取分集 */
    suspend fun loadTvBoxDetail(sourceId: Long, remoteId: String): List<TvBoxClient.PlaySource> {
        val src = repo.getSource(sourceId) ?: return emptyList()
        return try {
            repo.loadTvBoxDetail(src, remoteId)
        } catch (e: Exception) {
            message = "获取详情失败：${e.message ?: "网络或接口异常"}"
            emptyList()
        }
    }

    /**
     * 补 TVBox 剧集壳的演员/导演/备注（新列在老库里是空的）。
     * 失败或源不给这些字段时静默返回 false，详情页就不显示主演行；不弹提示。
     */
    suspend fun refreshTvBoxMeta(sourceId: Long, remoteId: String): Boolean =
        try {
            repo.refreshTvBoxMeta(sourceId, remoteId)
        } catch (e: Exception) {
            false
        }

    /** Emby 分集按需拉取（列表同步只入库剧集壳，分集进详情时再取） */
    suspend fun loadEmbyEpisodes(sourceId: Long, remoteId: String): Boolean {
        val src = repo.getSource(sourceId) ?: return false
        return try {
            repo.loadEmbyDetail(src, remoteId)
        } catch (e: Exception) {
            message = "获取分集失败：${e.message ?: "网络或接口异常"}"
            false
        }
    }

    suspend fun episodesOf(seriesKey: String): List<MediaEntity> = repo.episodes(seriesKey)

    /** 分集行所属的剧集壳：详情页把「继续观看」的分集记录换成整部剧来展示 */
    suspend fun shellOf(seriesKey: String): MediaEntity? = repo.shellOf(seriesKey)
    suspend fun seasonsOf(seriesKey: String): List<Int> = repo.seasons(seriesKey)

    /** TVBox 多播放源：某剧的全部云盘/线路名称 */
    suspend fun playSourcesOf(seriesKey: String): List<String> = repo.playSourcesOf(seriesKey)

    /** TVBox 多播放源：某剧指定云盘/线路下的分集 */
    suspend fun episodesBySource(seriesKey: String, playSource: String): List<MediaEntity> =
        repo.episodesBySource(seriesKey, playSource)
    suspend fun mediaOf(id: Long): MediaEntity? = repo.getMedia(id)

    /** 详情页种子色：单条订阅，替代为一颗色订阅整库 */
    fun seedMediaFlow(id: Long): Flow<MediaEntity?> = repo.observeMedia(id)
    suspend fun sourceOf(id: Long): SourceEntity? = repo.getSource(id)

    /** 远程搜索结果（负 id）落库并返回真实 id；失败返回 null */
    suspend fun materializeRemote(id: Long): Long? = repo.materializeRemote(id)

    fun saveProgress(mediaId: Long, positionMs: Long) {
        viewModelScope.launch { repo.saveProgress(mediaId, positionMs) }
    }

    /** 首页「继续观看」卡片上的清除记录：归零后 Flow 自动把卡片摘掉 */
    fun clearWatchProgress(mediaId: Long) {
        viewModelScope.launch { repo.clearProgress(mediaId) }
    }
}