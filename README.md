# DouDouTV —— 安卓手机 + TV 双端视频播放器 v1.0.0

自研的安卓个人影音客户端，**只对接两类来源：Emby 与 TVBox**。
界面与交互形态参照 Netflix 等主流流媒体客户端的成熟做法（沉浸式大图首页、海报墙、聚焦色背景、十英尺遥控器 UI）。
代码为本项目原创实现，不含任何对第三方商业应用的逆向或反编译产物；TVBox 侧仅参考公开协议与
开源项目（CatVod / TVBox 配置规范、FongMi/TV）的行为约定，未复制其源码，详见「九、参考项目与许可」。

包名、应用名与对外标识统一为 `com.chen.doudoutv` / `DouDouTV`，Emby 认证头里上报的 `Client` 也是 `DouDouTV`
—— 不伪装成别的客户端。

---

## 一、能力总览

| 模块 | 实现 |
|---|---|
| 来源类型 | **Emby**（服务器 + 账号）、**TVBox**（配置 JSON + spider 爬虫） |
| 播放内核 | Media3 / ExoPlayer 1.4.1，支持 MKV、HEVC、AV1、HLS、DASH |
| Emby 鉴权 | `Users/AuthenticateByName` 换 AccessToken，落库复用，无需反复登录 |
| Emby 影库 | `Users/{id}/Items` 拉电影 + 剧集，`Shows/{id}/Episodes` 按需拉分集 |
| Emby 播放 | `Videos/{id}/stream?static=true` 原码流直传，局域网不转码 |
| TVBox 站点 | 读取配置里的 `type=3` spider 站点，DexClassLoader 加载 spider 包后反射调用 |
| TVBox 加密壳 | `csp_XxxGuard` 这类壳由 [SpiderLoader](app/src/main/java/com/chen/doudoutv/data/tvbox/SpiderLoader.kt) 加载，并起一个本机代理 `127.0.0.1:9978` 供壳的 native 初始化回调（[LocalProxyServer](app/src/main/java/com/chen/doudoutv/data/tvbox/LocalProxyServer.kt)） |
| TVBox 解析 | spider 标准方法：`homeContent` / `categoryContent` / `searchContent` / `detailContent` / `playerContent` |
| TVBox 多线路 | 详情页把全部播放源（云盘 / 线路）铺成 chips，选中哪条就只播那条，切线路自动把选中项滚进可见区域 |
| 元数据刮削 | 可选 TMDB（默认关，需自填 apiKey / read token）；豆瓣热门榜用于搜索联想；刮削结果独立表 `scraped_meta` 复用 |
| 画质徽章 | 源侧文本先显示，播放期用真实轨道信息校正（分辨率 / HDR / Codec）；分集名一律沿用播放源原名，不自编 `SxxExx` |
| 手机首页 | Hero 大图轮播 → 继续观看横滑 → 收藏 → 电影 → 剧集 → 动漫 |
| TV 首页 | **两套布局可切**：经典版（满屏横版 hero + 横向海报行 + 顶部横排入口）/ Netflix 版（整屏轮播 + 一行继续观看，分类挪到「分类」页，常驻导航条） |
| 详情页背景 | 封面聚焦色填充，头图向下长距离溶解为整页底色，无硬边接缝 |
| 断点续播 | Room 记录进度，首页「继续观看」按最近播放排序；返回时恢复离开前的滚动位置与焦点落点（[ReturnSpot](app/src/main/java/com/chen/doudoutv/ui/ReturnSpot.kt)） |
| 自动连播 | 单集播完自动接 `seriesKey` 内下一集，片尾预热下一集 |
| 播放缓存 | 磁盘缓存六档（关闭 / 256M / 512M / 1G / 2G / 4G），LRU；能安全按字节切片的内容才登记，HLS 分片与 Emby 直链按登记制放行 |
| 可观测 | 全局下载字节计数（只算真正从网卡读进的字节，缓存命中不计，[DownloadMeter](app/src/main/java/com/chen/doudoutv/player/DownloadMeter.kt)）；解析出的直链在日志里脱敏（[LogGate](app/src/main/java/com/chen/doudoutv/core/LogGate.kt)） |
| 外观 | 主题色板七档（默认「石墨蓝」），缩略图尺寸三档，排版口径双端统一 |
| 搜索 | 本地库标题模糊 + 拼音首字母前缀联想，并发远程搜索各站点、边出边显示；搜索历史最多 10 条，三端共用一份 |
| 双端架构 | 手机 / 电视两条完整实现线，播放器也各一个 Activity，不用 `isTv` 在同屏分叉 |

---

## 二、TV 端布局与设置档位

TV 端界面已收敛成 **Netflix 版一套**（`ui/tv/nf/`：常驻导航条 + 首页只留整屏轮播与继续观看）。
经典版（`ui/tv/`：顶部横排入口 + 分类行全部铺开）代码原样保留，但入口已下线：
`Prefs.tvLayout` 读出来恒为 `NETFLIX`，设置页不再提供布局风格选择，存储键与枚举保留不动。

设置页（`TvSettingsScreen`）能改的档位：

| 档位 | 取值 | 说明 |
|---|---|---|
| 缩略图尺寸 | 小 92/132 · 中 118/162 · 大 144/192（dp，手机/TV） | 海报卡与网格列宽跟着走 |
| 移动效果 | 放大（默认）/ 上抬 | 海报卡聚焦时的动法 |
| 视频缓存 | 关闭 / 256M / 512M / 1G / 2G / 4G | 整个缓存目录的上限，不是单集上限 |
| 主题色 | 石墨蓝 / 青瓷 / 鸢尾 / 赤铜 / 胭脂 / 竹青 / 霜青 | 只换品牌色两位，中性色与默认色板一像素不变 |
| TMDB 刮削 | 开关 + apiKey + read token | 默认关；关闭时不发任何外部请求，完全沿用来源自带元数据 |

「移动效果」两档的像素账：行内纵向余量只有 `NfRowVerticalPadding = 14dp`，超出就会被 Lazy 容器裁掉一条边。

- **放大**：只有图片这一层从中心涨 5%（`ArtZoom = 1.05`），上下各多占 6~7dp；片名在图外，一动不动。
- **上抬**：整卡不放大，往上抬 12dp（14dp 余量内的极限）。

---

## 三、工程结构

```
myhub/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── README.md / CHANGELOG.md
├── gradle/wrapper/            # Gradle 8.7，无需预装 Gradle
├── build.sh                   # 一键构建（校验 JDK / 定位 SDK / 补缺组件 / assembleDebug）
├── .github/workflows/build-apk.yml
└── app/
    ├── build.gradle.kts / proguard-rules.pro / debug.keystore
    ├── schemas/               # Room 导出的历史表结构（exportSchema = true）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml        # 双入口：LAUNCHER + LEANBACK_LAUNCHER
        │   ├── res/                       # values / drawable / layout / mipmap（含 tv_banner）
        │   └── java/com/chen/doudoutv/
        │       ├── DouDouApp.kt           # Application（类名 DouDouTvApp）：Coil、崩溃落盘、DB 单例
        │       ├── MainActivity.kt        # 唯一入口：判定 isTv 后分发 PhoneApp / TvApp
        │       ├── core/                  # Prefs / AppCache / VideoCache / LogGate
        │       ├── data/
        │       │   ├── MediaRepository.kt # Emby / TVBox 入库、详情、进度
        │       │   ├── db/                # AppDatabase(v12) + Source/Media/HotTitle/ScrapedMeta DAO + Pinyin
        │       │   ├── emby/EmbyClient.kt # ★ Emby REST 客户端
        │       │   ├── tvbox/             # ★ TvBoxClient + SpiderLoader + LocalProxyServer + 结果解析
        │       │   ├── tmdb/              # TmdbClient / TmdbCache / ScrapeTitle（检索词清洗）
        │       │   └── scrape/ douban/ format/ image/ model/
        │       ├── player/                # PlayerFactory / VideoCacheFiller / StreamSpaceDataSource /
        │       │                          # PlaybackNormalizer（防和谐）/ DownloadMeter
        │       └── ui/
        │           ├── LibraryViewModel.kt / ReturnSpot.kt
        │           ├── theme/             # Theme / ThemePalette / ThemeBackdrop / Material
        │           ├── components/        # 海报卡、继续观看卡、Hero 轮播、骨架屏、格式徽章…
        │           ├── home/ detail/ sources/ settings/   # 手机端页面
        │           ├── phone/PhoneApp.kt  # ★ 手机端外壳（底部标签栏）
        │           ├── tv/                # ★ 电视端经典版页面 + TvComponents + HeroAmbient + TvSourcesScreen
        │           ├── tv/nf/             # ★ 电视端 Netflix 版：外壳 / 首页 / 分类 / 我的 / 搜索 / 作用域抽屉
        │           └── player/phone/ player/tv/           # ★ 两个播放器 Activity
        └── androidTest/java/com/chen/doudoutv/E2eTvBoxTest.kt   # 真机端到端走 TVBox 全流程
```

---

## 四、关键设计取舍

### 1. TVBox 为什么分两次请求
spider 的首页列表（`homeContent`）**不含播放地址**，只有 `detailContent` 才返回 `vod_play_url`。
因此列表阶段只入库「剧集壳」，进入详情页才按需拉分集 —— 否则同步几百部剧会打出几百次请求。
云盘线路（`quark://`、`aliyundrive://` 等）的地址原样保留，播放时再由 `playerContent` 解析成真实直链。

### 2. 唯一索引为什么放在 remoteId
剧集壳条目的 `uri` 是空字符串。如果唯一索引建在 `uri` 上，第二个壳就会因重复而插入失败。
改用 `remoteId`（`emby:来源id:itemId` / `tvbox:来源id:vodId:集数`）天然唯一且稳定。

### 3. Emby 为什么用 static=true
`static=true` 表示不转码、直接原码流传输。局域网千兆下这是画质与延迟的最优解，
代价是客户端必须能解码（Media3 对 HEVC/MKV 支持良好）。若遇到不支持的编码，
把 `EmbyClient.playbackUrl` 的 `static` 改为 `false` 即可让服务器转码。
本项目**不接 HLS 自动降档**：目标是要高码率原码，卡顿只做可见化（下载计量 + 缓冲状态）。

### 4. 为什么双端、乃至 TV 两套布局都分开写
最早的版本用 `isTv: Boolean` 参数在所有页面里分叉，代价是每个页面都背着一堆
`if (isTv) ... else ...`，改手机端动到电视端的风险很高，而且两端的交互范式本来就不同：
手机靠手势与水波涟漪，电视靠焦点移动与描边。TV 端后来新增 Netflix 版时同样不合并，
而是在 `ui/tv/nf/` 另拉一套 —— 切换只改一个枚举值，任何一边都能独立回滚。

### 5. 电视端焦点怎么表达「当前选中」
手机上的卡片状态靠水波纹和阴影表达，这两者在三米外完全不可见。
- 经典版：聚焦**放大 + 2.5dp 白边**，行首尾留白保证放大时不被屏幕边缘裁切。
- Netflix 版：片名改到图外（图内不再叠字），焦点动效两档可切（见第二节）；
  圆角与白环挂在图片层，缩放也只作用于图片层，文字层因此不参与重组。

### 6. 缓存与 seek 的坐标系必须只有一套
伪装前缀的平移放在数据源层（`StreamSpaceDataSource`）而不是拦截器层，于是 Media3 的区间记账
和播放器的 seek 只剩「流坐标」一套坐标系 —— 两套坐标系叠在一起必抛 `READ_POSITION_OUT_OF_RANGE`。
走不走缓存只看一条判据：这条 URL 有没有被登记过；未登记的仍然是裸 `OkHttpDataSource`，取数路径一字节不变。

### 7. 播放参数以当前代码为准
`player/PlayerFactory.kt` 里现值：起播缓冲 2.0s、rebuffer 后 3.0s、常态 min 缓冲手机 50s / 电视 60s、
target 240s / 300s、回退缓冲 60s、`setPrioritizeTimeOverSizeThresholds(true)`、
缓冲池 target 字节 256MB / 384MB、磁盘缓存分块 32MiB。
这些数值是按实测限速窗口调出来的（大分块完成间隔会 12.5s→21s→32s 一路劣化，最坏一次 66 秒几乎无字节），
改之前先读代码里那段注释，别照文档回退。

### 8. 手机端转场的方向感
- 进详情 = 下钻 → 新页面从右侧小幅推入并淡入
- 回列表 = 上浮 → 详情向左退，列表从左侧轻滑回来
- 切底部标签 = 平级 → 只做交叉淡化，不引入方向，避免「左右乱跳」

### 9. 主题色只走该走的地方
色板只替换品牌色两位：按钮、聚焦描边、开关跟随；详情页背景继续走封面聚焦色，
默认色板「石墨蓝」下的既有画面一像素都不变。

### 10. 分集命名
一律用播放源给的分集名（如 `[5.4GB]E01.mkv`），不自编 `SxxExx`；只有 Emby 这类没有源分集名的才编号兜底。

---

## 五、如何得到可安装的 APK

> 仓库已包含：完整源码、Gradle Wrapper（无需预装 Gradle）、一键脚本 `build.sh`、
> 云端构建配置 `.github/workflows/build-apk.yml`。三条路任选一条，产物都是
> `app/build/outputs/apk/debug/app-debug.apk`。

| 方式 | 适用场景 | 你需要准备 | 首次耗时 |
|---|---|---|---|
| **A. GitHub Actions 云端构建** | 不想在电脑上装任何 Android 环境 | 一个 GitHub 账号 | 约 6–10 分钟 |
| **B. `bash build.sh`** | 已有 JDK 与 Android SDK | JDK 17+、Android SDK 34 | 约 10 分钟（含下载依赖） |
| **C. Android Studio** | 想改代码、看 Compose 预览、断点调试 | 安装 Android Studio | 约 5 分钟同步 |

### 方式 A：GitHub Actions 云端构建

1. 在 GitHub 新建仓库（Private 也可以，Actions 免费额度足够），把本目录内容推上去
2. 推送到 `main` / `master` / `dev` 即触发构建；也可在 **Actions** 页点 `Build APK` → `Run workflow` 手动触发
3. 构建完成后在 **Releases** 页下载 `DouDouTV-v<versionName>-<分支>.apk`
   （分支包的 tag 固定 `<分支>-latest`，每次覆盖；非 `main`/`master` 分支标为 pre-release）
4. 推送形如 `v1.0.0` 的 tag 会额外出一个**正式包** `DouDouTV-v1.0.0.apk`，挂在同名 tag 上，
   Release 正文取自 `CHANGELOG.md`；同名 Release 已存在时 CI 直接失败，不覆盖（见「发版纪律」）
5. 下载到的就是原始 APK，无需解压 → 传到手机 / U 盘拷到电视盒子安装

CI 用 **JDK 17（temurin）** + 仓库自带 Wrapper（Gradle 8.7），直接复用 ubuntu-latest 预装的 Android SDK，
不额外安装 cmdline-tools；构建任务为 `assembleDebug`，失败自动重试一次，仍失败则报红，不产出空包。
分支包名里的版本号由 workflow 从 `app/build.gradle.kts` 的 `versionName` 现读，改版本号不用动 workflow。

### 方式 B：命令行一键构建

前置条件（缺一不可）：

- **JDK 17 及以上**（`jvmTarget = 17`，AGP 8.5.2 要求 17+；本地用 jbr-21 同样可过）
- **Android SDK**，含 `platforms/android-34` 与 `build-tools/34.0.0`
- 网络能访问 `dl.google.com` 与 `repo.maven.apache.org`

```bash
bash build.sh          # Windows 用 Git Bash 或 WSL
```

脚本依次校验 Java 版本、定位 SDK 并写 `local.properties`、缺失组件时自动调 `sdkmanager` 安装、
最后执行 `./gradlew assembleDebug`，并打印 APK 路径与 `adb install` 命令。

### 方式 C：Android Studio / IDEA

- Android Studio Hedgehog (2023.1.1) 或更新：`File → Open` 选本目录，等待同步（首次下载依赖约 400–600MB）
- IntelliJ IDEA Ultimate：装 Android 插件，指定 SDK（Platform 34）与 Gradle JVM（17+）
- IDEA Community：能读能改能补全，但没有 Android SDK 集成 / Run 配置 / Compose Preview，构建只能用终端

> 纯 Android 开发仍建议用 Android Studio：Compose Preview、Layout Inspector、APK Analyzer、
> 设备镜像等工具链比 IDEA 领先数个版本。

### 手工命令行

```bash
export ANDROID_HOME=$HOME/Android/Sdk          # macOS/Linux
# Windows: set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### 版本矩阵（改版本前请对照）

| 组件 | 本工程版本 | 说明 |
|---|---|---|
| Gradle | **8.7**（wrapper） | AGP 8.5.2 要求 ≥ 8.7 |
| Android Gradle Plugin | **8.5.2** | 需 JDK 17+ |
| Kotlin | **1.9.24** | 与 Compose Compiler 严格配对 |
| Compose Compiler | **1.5.14** | 必须匹配 Kotlin 1.9.24 |
| KSP | **1.9.24-1.0.20** | 版本号必须与 Kotlin 严格一致 |
| Compose BOM | **2024.06.00** | 对应 Compose UI 1.6.8 / material3 1.2.1 |
| compileSdk / targetSdk | 34 | minSdk 21 |
| Media3 | 1.4.1 | exoplayer / ui / hls / dash / datasource(-okhttp) / database |
| Room | 2.6.1 | KSP 注解处理，`exportSchema = true` |
| Coil / OkHttp / Palette | 2.6.0 / 4.12.0 / 1.0.0 | 海报 / 网络 / 聚焦色提取 |
| jsoup / fastjson / Gson / jpinyin | 1.17.2 / 1.2.83 / 2.10.1 / 1.1.8 | spider 运行时与拼音首字母 |

> ⚠️ Kotlin 与 Compose Compiler 是强绑定关系，单独升级其中一个必定编译失败；
> KSP 版本号也必须和 Kotlin 主版本精确对齐（`1.9.24-X.Y.Z` 对应 Kotlin 1.9.24）。
> 若要升到 Kotlin 2.x，需同时启用 `org.jetbrains.kotlin.plugin.compose` 插件并移除
> `composeOptions { kotlinCompilerExtensionVersion }`。
> 曾试过升级 Compose，新版要求 minSdk 23 且焦点行为变化，真机验证后**已回滚**，仍留在 UI 1.6.8。

### 签名与升级安装

`app/debug.keystore` 随仓库提交（`.gitignore` 里为它留了例外），debug 与 release 复用同一颗：
换正式签名会导致覆盖安装直接失败、只能卸载重装，媒体库 / 进度 / 收藏全丢。
对外发布若要换正式 keystore，请知悉换完就断升级链，老包必须卸干净。

> 包名已改为 `com.chen.doudoutv`，这在系统眼里是一个**全新应用**：旧包名的数据不会带过来，
> 新旧两包可以并存安装，卸不卸旧包由使用者自己决定。
> 也正因为是新包，本包的 `versionCode` 从 1 起算不会撞到任何已装版本（旧包自己那套编号与它无关）。

---

## 六、使用流程

### 1. 添加 Emby 来源
来源页 → 「添加来源」→ 选 **Emby** → 服务器地址填 `https://emby.yourdomain.com`（不带 `/web`）→ 填用户名密码。
程序会自动 `AuthenticateByName` 换 token；若显示「未登录」，点该卡片上的刷新按钮重新登录。

### 2. 添加 TVBox 来源
选 **TVBox**，填 TVBox 配置 JSON 地址（如 `https://example.com/tvbox.json`），需包含 `spider` 字段与 `type=3` 站点。
添加后同步即可拉到首页列表；加密壳首次初始化约 20–30 秒，期间会显示「正在读取这条线路」。

### 3. 同步与播放
首页右上角同步全部来源 → 点海报进详情页 → 剧集可切季 / 选集 / 切线路 → 「继续播放」接着上次进度。

### 4. 手机端操作
底部标签栏切「媒体库 / 来源 / 设置」；播放页点击画面呼出控制条，双击左右快进快退，
上下滑调亮度音量，支持画面比例切换。

### 5. TV 端操作
- 若盒子未被识别为 TV：手机端设置 → 打开「强制电视模式」
- 首页遥控器上下换分区、左右换条目、OK 进入
- 播放页：**OK 暂停 / 下键呼出功能面板（倍速 · 字幕 · 音轨 · 选集）/ 左右 ±10 秒 / 返回先收面板再退出**
- 电视播放器固定全屏，没有比例切换按钮

---

## 七、已知限制

1. **Emby 播放进度不回写服务器**：进度只存本地 Room，多端之间不同步
2. **Emby 外挂字幕未接进播放器**：`EmbyClient.subtitleUrl()` 已能拼出地址，播放页尚未加载它
3. **数据库迁移需手写**：`AppDatabase`（当前 v12）刻意不调 `fallbackToDestructiveMigration()`，
   漏写 `Migration` 时升级会直接失败，而不是静默清库；历史表结构落在 `app/schemas/`
4. **没有 JVM 单元测试**：`src/test` 不存在，只有 `androidTest` 下的 `E2eTvBoxTest`，必须连真机才能跑
5. **spider 兼容面取决于配置本身**：加密壳、云盘线路、站点限速都由使用者提供的 spider 决定，本项目不内置任何片源
6. **README 与代码仍需人工同步**：文档更新滞后于代码是这类个人项目的常见问题

---

## 八、可继续扩展

1. **Emby 进度回写**：加 `Sessions/Playing/Progress` 上报，实现多端同步
2. **外挂字幕加载**：把 `subtitleUrl` 接进 Media3 的 `SubtitleConfiguration`
3. **演员 / 相似推荐**：Emby `Items/{id}/Similar` 与 `People` 字段
4. **海报墙性能**：条目过千时给 Room 加分页（Paging 3）
5. **凭据加密**：来源账号密码与 token 从明文改为 `EncryptedSharedPreferences`
6. **自动迁移**：`app/schemas/` 已导出，加索引一类的小改动可以用 Room 自动迁移补

---

## 九、参考项目与许可

### 1. 协议与行为参考（只参考公开协议与行为，未复制源码）

| 参考对象 | 参考内容 |
|---|---|
| CatVod / TVBox 配置规范 | `type=3` spider 站点、配置级 `spider` 包、`flags`、`searchable` / `quickSearch`，以及 `homeContent` / `categoryContent` / `searchContent` / `detailContent` / `playerContent` 的方法语义与返回 JSON（`list` / `class` / `parse` / `jx` / `header`） |
| [FongMi/TV](https://github.com/FongMi/TV)（GPL-3.0） | 仅参考其 TVBox 侧的行为约定：直链也先走 `playerContent`、配置 `flags` 作为 `vipFlags`、`parse=1` 视为需要网页解析、远程搜索按 `searchable` / `quickSearch` 过滤。**未复制其代码**，本项目与其不存在源码派生关系 |
| Netflix | 交互与视觉形态参考（沉浸式大图首页、海报墙、继续观看、聚焦色背景、十英尺遥控器 UI），不涉及实现代码 |

### 2. 开源依赖

- AndroidX / Compose、Media3 / ExoPlayer、Room、Coil、Palette、Kotlin Coroutines（Apache-2.0）
- OkHttp、Gson、Fastjson（Apache-2.0）
- jsoup（MIT）、jpinyin（Apache-2.0）

### 3. 其他

- 本项目代码可自由使用、修改、分发
- spider 包由使用者自己的 TVBox 配置提供，本项目不内置任何片源
- 请仅用于播放你拥有合法权利的内容；第三方接口的可用性与合法性由使用者自行判断
- TMDB 刮削是**默认关闭**的可选功能，需使用者自备 API 凭据，并遵守 TMDB 的使用条款与署名要求