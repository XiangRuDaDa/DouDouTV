package com.tabbit.myvidhub.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabbit.myvidhub.core.Prefs
import com.tabbit.myvidhub.data.scrape.ScrapeEngine
import com.tabbit.myvidhub.data.scrape.ScrapeStrategy
import com.tabbit.myvidhub.data.MediaRepository
import com.tabbit.myvidhub.data.emby.EmbyClient
import com.tabbit.myvidhub.data.model.MediaEntity
import com.tabbit.myvidhub.data.model.MediaKind
import com.tabbit.myvidhub.data.model.SourceEntity
import com.tabbit.myvidhub.data.model.SourceKind
import com.tabbit.myvidhub.data.tvbox.CatVodResultParser
import com.tabbit.myvidhub.data.tvbox.TvBoxClient
import com.tabbit.myvidhub.data.tvbox.TvBoxClient.TvBoxSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 首页分类行首批展示条数（够铺满电视一行），滚到尾部再追加 */
        const val CATEGORY_PREVIEW_BATCH = 10
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

    var scanMessage by mutableStateOf("")
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

    /** 首页来源/线路加载失败的真实原因，供空状态提示（壳初始化超时等） */
    var homeSitesError by mutableStateOf<String?>(null)
        private set

    /** 当前选中 TVBox 站点（线路）homeContent 返回的分类（class 数组）；空表示该线路没有分类 */
    var homeCategories by mutableStateOf<List<CatVodResultParser.ClassItem>>(emptyList())
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
        // 冷启动时若已配置来源但本地还没有内容，自动同步一次，避免用户以为"加完就坏了"
        viewModelScope.launch {
            runCatching {
                if (repo.enabledSources().isNotEmpty() && !repo.hasAnyMedia()) {
                    scanAll()
                }
            }
        }
        // 启动时汇总各来源已入库条目数（来源页「N 个条目」展示用）
        viewModelScope.launch { refreshSourceCounts() }
        // 拼音算法升级后，把库里已有片名重算一遍首字母（只跑一次）
        viewModelScope.launch { repo.reindexPinyinIfNeeded() }
        // 恢复上次选中的来源时，预取该 TVBox 来源的站点（线路）列表
        viewModelScope.launch {
            homeSourceId?.let { loadHomeSites(it) }
        }
        // 从未手动选择过首页来源时，默认按第一个配置的来源加载（选择后才写入 Prefs）
        viewModelScope.launch {
            if (Prefs.homeSourceId < 0) {
                repo.enabledSources().firstOrNull()?.let { selectHomeSource(it.id) }
            }
        }
    }

    // ---------- 首页来源 / 站点（线路）选择 ----------

    fun selectHomeSource(sourceId: Long?) {
        if (homeSourceId == sourceId) {
            // 重复点同一个来源不算切换，但把线路列表（重新）拉一次，避免之前失败留下空列表
            sourceId?.let { loadHomeSites(it) }
            return
        }
        homeSourceId = sourceId
        homeSiteKey = null
        homeSitesError = null
        homeCategories = emptyList()
        activeCategoryId = null
        _categoryVods.clear()
        _categoryPreviewLoading.clear()
        _categoryVisibleCount.clear()
        _categoryLoadedPage.clear()
        _categoryEnded.clear()
        categoryError = null
        activeSite = null
        Prefs.homeSourceId = sourceId ?: -1L
        Prefs.homeSiteKey = ""
        if (sourceId != null) loadHomeSites(sourceId)
    }

    fun selectHomeSite(siteKey: String?, site: TvBoxSite? = null) {
        homeSiteKey = siteKey
        Prefs.homeSiteKey = siteKey.orEmpty()
        homeSitesError = null
        // 切线路后重置分类状态
        homeCategories = emptyList()
        activeCategoryId = null
        _categoryVods.clear()
        _categoryPreviewLoading.clear()
        _categoryVisibleCount.clear()
        _categoryLoadedPage.clear()
        _categoryEnded.clear()
        categoryError = null
        activeSite = site
        val sourceId = homeSourceId ?: return
        val s = site ?: return
        viewModelScope.launch {
            val src = repo.getSource(sourceId) ?: return@launch
            // 每次切线路都拉一次首页：取 homeContent 的 class 分类名
            // 分类内容（categoryContent）按需懒加载，每个分类的 section 在 UI 首次可见时触发
            try {
                val result = repo.syncTvBoxSite(src, s)
                homeCategories = result.categories
            } catch (e: Exception) {
                homeSitesError = "线路「${s.name}」加载失败：${e.message ?: "网络或接口异常"}"
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
                val merged = items + more
                _categoryVods[typeId] = merged
                _categoryLoadedPage[typeId] = nextPage
                _categoryVisibleCount[typeId] = (visible + CATEGORY_PREVIEW_BATCH).coerceAtMost(merged.size)
            }
            _categoryPreviewLoading[typeId] = false
        }
    }

    /** TVBoxCategoryScreen 分页拉取：typeId + page → 该页 MediaEntity 列表 */
    suspend fun tvBoxCategoryPage(typeId: String, page: Int): List<MediaEntity> {
        val sourceId = homeSourceId ?: return emptyList()
        val site = activeSite ?: return emptyList()
        val src = repo.getSource(sourceId) ?: return emptyList()
        return runCatching { repo.tvBoxCategory(src, site, typeId, page) }
            .getOrDefault(emptyList())
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
        if (_homeSitesBySource[sourceId]?.isNotEmpty() == true) return
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
            val restored = homeSiteKey?.let { key -> sites.firstOrNull { it.key == key } }
            when {
                restored != null && activeSite == null -> selectHomeSite(restored.key, restored)
                // 只有一条线路的来源：自动选中，避免首页空白又没按钮可点。
                // 多线路（如 38 个站点的配置）保持「全部站点」聚合视图，由用户自己挑线路。
                homeSiteKey == null && homeSourceId == sourceId && sites.size == 1 ->
                    sites.first().let { selectHomeSite(it.key, it) }
            }
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
        if (siteKey.isBlank()) return true // 匿名 CMS 只有一条默认线路，全部条目归属该线路
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

    private fun normalizeLocation(kind: SourceKind, location: String): String {
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

    /** 新增/编辑完成后自动同步该来源，解决“添加后首页无数据” */
    private suspend fun syncAfterChange(source: SourceEntity, label: String, action: String) {
        scanning = true
        scanMessage = "正在同步 $label…"
        try {
            val count = runSync(source)
            message = if (count > 0) "$action $label，已同步 $count 个条目"
            else "$action $label，但未获取到内容"
        } catch (e: Exception) {
            message = "$action $label，同步失败：${e.message ?: "网络或接口异常"}"
        } finally {
            scanning = false
            scanMessage = ""
        }
    }

    private suspend fun runSync(source: SourceEntity): Int {
        val count = when (source.kind) {
            SourceKind.EMBY -> repo.syncEmby(source) { c -> scanMessage = "${source.name}：$c" }
            SourceKind.TVBOX -> repo.syncTvBox(source) { c -> scanMessage = "${source.name}：$c" }
        }
        // 同步成功才记录时间戳：失败时不该把「最近同步」刷新成刚刚
        markSynced(source.id)
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
                val count = runSync(refreshed)
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

    /** 分类浏览页：按类型/题材分页查询 */
    suspend fun browseGenre(kind: String?, genre: String?, limit: Int, offset: Int): List<MediaEntity> =
        repo.byGenre(kind, genre, limit, offset)

    /** 类型标签（按内容数量排序） */
    suspend fun genreTags(): List<String> = repo.genreTags()

    /** Emby 刮削信息（导演/演员/分级），非 Emby 源返回 null */
    suspend fun embyDetailOf(sourceId: Long, remoteId: String): EmbyClient.ItemDetail? =
        repo.embyDetail(sourceId, remoteId)

    /** TMDB 刮削（海报/背景图/Logo/评分/简介/演职员），未配置 API Key 时返回 null */
    suspend fun tmdbScrape(
        title: String,
        year: Int,
        kind: String,
        mediaId: Long = 0L
    ): com.tabbit.myvidhub.data.tmdb.TmdbClient.ScrapeResult? =
        repo.tmdbScrape(title, year, kind, mediaId)

    /** 清空刮削缓存，返回被清掉的缓存条目数 */
    suspend fun clearScrapeCache(): Int = repo.clearScrapeCache()

    /** 当前刮削缓存条目数 */
    fun scrapeCacheSize(): Int = repo.scrapeCacheSize()

    // ---------- 扫描 ----------

    /** 界面顶栏「全部同步」入口，语义等同 scanAll，命名更贴合按钮文案 */
    fun syncAll() = scanAll()

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
                    // 单个来源失败不能中断其他来源的同步
                    try {
                        total += runSync(src)
                    } catch (e: Exception) {
                        errors += "${src.name}：${e.message ?: "网络或接口异常"}"
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
            try {
                val added = runSync(source)
                message = if (added > 0) "${source.name}：$added 个条目" else "${source.name}：无内容"
            } catch (e: Exception) {
                message = "同步失败：${e.message ?: "未知错误"}"
            } finally {
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
    suspend fun seasonsOf(seriesKey: String): List<Int> = repo.seasons(seriesKey)

    /** TVBox 多播放源：某剧的全部云盘/线路名称 */
    suspend fun playSourcesOf(seriesKey: String): List<String> = repo.playSourcesOf(seriesKey)

    /** TVBox 多播放源：某剧指定云盘/线路下的分集 */
    suspend fun episodesBySource(seriesKey: String, playSource: String): List<MediaEntity> =
        repo.episodesBySource(seriesKey, playSource)
    suspend fun mediaOf(id: Long): MediaEntity? = repo.getMedia(id)
    suspend fun sourceOf(id: Long): SourceEntity? = repo.getSource(id)

    /** 远程搜索结果（负 id）落库并返回真实 id；失败返回 null */
    suspend fun materializeRemote(id: Long): Long? = repo.materializeRemote(id)

    fun saveProgress(mediaId: Long, positionMs: Long) {
        viewModelScope.launch { repo.saveProgress(mediaId, positionMs) }
    }
}