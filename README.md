# DouDouTV —— 安卓手机 + TV 双端视频播放器 v2.3

自研的安卓个人影音客户端，**只对接两类来源：Emby 与 TVBox**。
界面与交互形态参照 VidHub、Netflix 的成熟做法（沉浸式大图首页、海报墙、聚焦色背景、十英尺遥控器 UI）。
代码为本项目原创实现，不含任何对第三方商业应用的逆向或反编译产物；TVBox 侧仅参考公开协议与
开源项目（CatVod / TVBox 配置规范、FongMi/TV）的行为约定，未复制其源码，详见「十、参考项目与许可」。

---

## 一、能力总览

| 模块 | 实现 |
|---|---|
| 来源类型 | **Emby**（服务器+账号）、**TVBox**（配置 JSON + spider 爬虫） |
| 播放内核 | Media3 / ExoPlayer 1.4.1，支持 MKV、HEVC、AV1、HLS、DASH |
| Emby 鉴权 | `Users/AuthenticateByName` 换 AccessToken，落库复用，无需反复登录 |
| Emby 影库 | `Users/{id}/Items` 拉电影+剧集，`Shows/{id}/Episodes` 拉分集 |
| Emby 播放 | `Videos/{id}/stream?static=true` 原码流直传，局域网不转码 |
| TVBox 站点 | 读取配置里的 `type=3` spider 站点，DexClassLoader 加载 spider 包后反射调用 |
| TVBox 解析 | spider 标准方法：`homeContent` / `categoryContent` / `searchContent` / `detailContent` / `playerContent` |
| 手机首页 | Hero 大图轮播 → 继续观看横滑 → 收藏 → 电影 → 剧集 → 动漫 |
| 电视首页 | 满屏横版主视觉（9s 轮换）+ 横向海报行 + 顶部横排入口 |
| 详情页背景 | **封面聚焦色填充**，头图向下长距离溶解为整页底色，无硬边接缝 |
| 详情页布局 | 手机上下堆叠 / 电视左海报右文案两栏（各自独立实现） |
| 断点续播 | Room 记录进度，首页「继续观看」按最近播放排序 |
| 自动连播 | 单集播完自动接 `seriesKey` 内下一集 |
| 播放缓存 | **1GB LRU 磁盘缓存**（v2.1 由 512MB 提升） |
| 双端架构 | **四套独立实现**：`ui/phone` `ui/tv` `player/phone` `player/tv`，不再用 `isTv` 在同屏分叉 |

---

## 二、v2.1 更新要点

### 1. 视频加载与起播优化

`player/PlayerFactory.kt` 重写，针对「转圈久、播两秒又卡」的常见症状逐项调整：

| 参数 | v2.0 | v2.1 | 作用 |
|---|---|---|---|
| 起播所需缓冲 | 2.0s | **1.0s** | 首帧提前，肉眼可感的提升 |
| 缓冲池上限 | 默认值（很小） | **手机 64MB / 电视 96MB** | 高清流不再频繁回收缓冲 |
| 最小缓冲 | 40s / 150s | 手机 20s/120s，电视 30s/180s | 拖动进度后不至于立刻断流 |
| 回退缓冲 | 无 | **30s** | 回拖不重新走网络 |
| 错误重试 | 3 次 | **6 次** | 弱网、跨境链路自愈概率更高 |
| 跨协议重定向 | 关闭 | **开启** | 应对 http→https 跳转 |
| 超时 | 默认 | 手机 8s/12s，电视 12s/18s | 挂起连接早失败早重连 |
| 磁盘缓存 | 512MB | **1GB** | 回看同一片段不重复下载 |

另开启 `setPrioritizeTimeOverSizeThresholds(true)`，宁可多占内存也不因到顶而停止预读；
缓存块损坏时以 `FLAG_IGNORE_CACHE_ON_ERROR` 立即回源，不让坏块拖死整次播放。

### 2. 手机端与电视端代码彻底分离

不再是一个 `isTv: Boolean` 参数贯穿所有页面，而是两条完整的实现线：

```
ui/phone/PhoneApp.kt          ← 手机外壳：底部标签栏 + 左右推入转场
ui/tv/TvApp.kt                ← 电视外壳：顶部横排入口 + 淡入淡出

player/phone/PhonePlayerActivity.kt   ← 手机播放器：触摸手势、横竖屏自适应
player/tv/TvPlayerActivity.kt         ← 电视播放器：遥控器专用、固定全屏

ui/tv/TvHomeScreen.kt / TvSearchScreen.kt / TvGenreScreen.kt /
      TvDetailScreen.kt / TvComponents.kt   ← 电视端五个页面，独立重写
```

`MainActivity` 精简至 **43 行**，只做一次环境判定后转交对应实现：

```kotlin
val isTv = remember { context.isTelevision() || context.forceTvMode() }
if (isTv) TvApp(vm) else PhoneApp(vm)
```

好处是改手机端界面不会牵连电视端，反之亦然；两端的播放器参数、交互范式、焦点策略互不影响。
判定仍保留「无触摸屏」兜底，兼容部分国产盒子 `uiMode` 上报不准的情况。

### 3. 双端 UI 重做

**电视端（十英尺界面）**

- 首页：满屏横版主视觉，标题与按钮压在左下，底部渐隐接入页面底色
- 海报卡：158dp 竖版，**聚焦放大 10% + 2.5dp 白边**（手机靠涟漪和阴影表达状态，三米外完全看不见，电视必须用放大和描边）
- 有播放进度的条目底部带进度线，一眼看出「这片看了一半」
- 搜索：650dp 大输入框默认聚焦，输入停顿 350ms 自动搜索，结果 6 列大卡网格
- 分类：题材筛选是**单行可左右滚动**的横条（避免遥控器卡在换行处），滚到底自动加载下一页
- 详情：左海报（220dp）+ 右文案两栏，按钮只有两三个大按钮横排

**手机端**

- 外壳改为底部标签栏（媒体库 / 来源 / 设置），进入详情用右侧小幅推入，返回反向退出
- 详情页背景升级为聚焦色填充（详见下节）

### 4. 详情页背景：封面聚焦色填充 + 渐变过渡

1. 封面加载完成后用 `Palette` 提取聚焦色，优先取饱和度较高的色块；
   灰度封面取不到色相时退回品牌色，保证页面总有色调。
2. 聚焦色压成**两个端点**：顶部色相最浓（明度 0.34），底部同色相压暗（明度 0.14）。
3. 两端色之间用插值生成整页竖向渐变，长列表滚到底也不会露出纯黑断层。
4. 头图下方 45% 区间叠一层溶解蒙版（黑 0.34 → 透明 → 填充色 0.30 → 0.78 → 1.0），
   让封面逐渐溶解成整页底色，两者之间没有硬边。
5. 聚焦色按图片 URL 做 LRU 缓存（容量 64），首页、继续观看、详情页重复出现的封面不重复解码。

### 5. 电视播放器遥控器交互

| 按键 | 行为 |
|---|---|
| **OK / 确认** | 暂停或继续，并显示进度条 |
| **下方向键** | 弹出功能面板：倍速（0.5x–2x）/ 字幕 / 音轨 / 选集，**3 秒无操作自动收起** |
| **左 / 右** | 快退 / 快进 10 秒，顶部显示时间提示 |
| **返回** | 面板打开时先关面板，再按退出播放 |

- **默认全屏**，固定 16:9 适应，**不提供「适应 / 铺满 / 缩放」比例切换**
- 面板一级是标签，二级是选项，选项间用遥控器左右移动，聚焦项高亮为白底黑字
- 退出时进度保存改用独立协程作用域，避免 Activity 销毁导致最后一笔进度丢失

---

## 三、v2.3 更新要点

### 1. TV 端来源界面彻底重写

旧版 `TvSourcesScreen` 存在三方面十英尺适配缺失：卡片无 TV 焦点反馈（无 scale + 白边）、用 AlertDialog 做表单弹窗（Leanback 焦点导航困难）、无 Emby/TVBox 分组且缺少删除二次确认。v2.3 完整重写为四个独立 Composable：

**`TvSourcesScreen` 主页面**

- `BoxWithConstraints` + `LazyVerticalGrid` 响应式网格：以 380dp 为基准算列数（720p→3 列 / 1080p→5 列 / 4K→10 列），卡片高度 `width × 0.76` 且 clamp 260–340dp
- Emby / TVBox 分组标题 + 计数标签，与手机端 SourcesScreen 信息架构对齐
- 顶部栏复用 `TvBackButton` / `TvPillButton(compact=true)`，同步状态用脉冲圆点

**`TvSourceCard` 可聚焦卡片**

- 完全对齐 `TvPosterCard` 焦点模式：`collectIsFocusedAsState` → `animateFloatAsState(1.05f)` → `graphicsLayer.scale` → `Modifier.border(2.dp, Color.White)` → `Modifier.focusable()`
- 56dp 类型图标 + 12dp 彩色状态圆点（绿=在线 / 橙=Token 失效 / 灰=停用）+ 名称 + 徽章 + URL + 底部统计栏（条目数 / 登录状态 / 同步时间）
- 操作按钮（同步 / 编辑 / 删除）默认 opacity 0，卡片获得焦点时才显示，避免常态信息过载
- 禁用卡片整体 alpha 0.5 视觉降噪

**`TvSourceFormPanel` 全屏面板表单**（不使用 AlertDialog）

- `Box(fillMaxSize, Color.Black.copy(alpha=0.65f))` + `Surface(width=55%, height=88%)` 居中面板
- 类型选择器：Emby 蓝色 / TVBox 黄色 大卡片（220×90），选中态品牌蓝描边 + 图标反白
- `Modifier.height(64.dp)` OutlinedTextField，品牌蓝聚焦 + 3px 外发光
- Emby 用户名 / 密码左右双列（各 `weight(1f)`），节省面板高度

**`TvDeleteConfirmDialog` 危险操作防护**（旧版缺失）

- 56dp 圆形 ⚠️ 警告图标 + 红色危险提示条，显示具体条目数（"284 部影片将被永久删除"）
- `LaunchedEffect { cancelRequester.requestFocus() }` 默认焦点落到"取消"按钮，防止遥控器默认聚焦红色删除按钮导致误触
- 红色 PillButton 变体，聚焦时白底红字 + `scale(1.06)`

### 2. 手机端来源卡片布局修复

第一行 `Row` 里 `Text(weight=1f, fill=false)` 和 `Spacer(weight=1f)` 两个带 weight 的子元素同时存在，"使用中"徽章的有无改变了 Switch 位置。

修复：第一行拆成左右两个子 Row —— 左侧 `Row(weight=1f)` 撑满剩余空间（状态点 + 名称），右侧 Row 用自然宽度锚定最右（徽章 + Switch），Switch 位置不再随徽章有无漂移。

### 3. 手机端来源表单优化

- 新增 `MobileFormField` 统一组件：`height(56.dp)` OutlinedTextField + AppColors.Bg 容器底 + AppColors.Brand 聚焦边框 + 15sp 字号 + 10dp 圆角
- Emby 用户名 / 密码左右双列布局，节省 AlertDialog 纵向空间
- `trimmedName` / `trimmedLoc` 非空校验，空时按钮 disabled（`canSubmit`）
- 类型 FilterChip 加宽文字（"Emby 媒体服务器" / "TVBox 接口"），AlertDialog 圆角提升至 18dp

---

## 四、工程结构

```
MyVidHub/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md
└── app/
    ├── build.gradle.kts / proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/{strings,colors,themes}.xml
        │   └── drawable/{ic_app_icon,tv_banner}.xml
        └── java/com/tabbit/myvidhub/
            ├── MyVidHubApp.kt              # Application，持有 Room 单例
            ├── MainActivity.kt             # 唯一入口：仅做 isTv 分发（43 行）
            ├── core/Prefs.kt               # 应用级偏好
            ├── data/
            │   ├── MediaRepository.kt      # Emby/TVBox 入库、详情、进度
            │   ├── db/
            │   │   ├── AppDatabase.kt      # v2，破坏性迁移
            │   │   ├── SourceDao.kt
            │   │   ├── MediaDao.kt
            │   │   └── Converters.kt
            │   ├── model/Entities.kt       # SourceEntity / MediaEntity
            │   ├── emby/EmbyClient.kt      # ★ Emby REST 客户端
            │   └── tvbox/TvBoxClient.kt    # ★ TVBox 客户端（spider）
            ├── player/PlayerFactory.kt     # 双端缓冲策略 + 1GB 缓存 + ExoPlayer 装配
            └── ui/
                ├── LibraryViewModel.kt
                ├── theme/Theme.kt          # AppColors 深色影音配色 + isTelevision()
                ├── components/             # 手机端共用组件
                │   ├── PosterCard.kt       # 2:3 海报卡
                │   ├── ContinueCard.kt     # 16:9 继续观看卡
                │   └── HeroBanner.kt       # 首页大图轮播
                ├── home/                   # 手机端页面
                │   ├── HomeScreen.kt       # 首页（含 GenreScreen / SearchScreen）
                │   └── ...
                ├── detail/
                │   ├── DetailScreen.kt     # 手机端详情页
                │   └── DetailColors.kt     # 聚焦色提取/压暗/缓存（两端共用计算）
                ├── sources/SourcesScreen.kt# 来源增删（仅 Emby / TVBox）
                ├── settings/SettingsScreen.kt
                ├── phone/
                │   └── PhoneApp.kt         # ★ 手机端外壳
                ├── tv/                     # ★ 电视端全部页面（独立实现）
                │   ├── TvApp.kt            # 电视端外壳
                │   ├── TvHomeScreen.kt     # 横版 hero + 横向海报行
                │   ├── TvSearchScreen.kt   # 6 列网格搜索
                │   ├── TvGenreScreen.kt    # 单行题材条 + 分页
                │   ├── TvDetailScreen.kt   # 左海报右文案两栏
                │   └── TvComponents.kt     # TvPillButton / TvPosterCard / TvFilterChip
                └── player/
                    ├── phone/PhonePlayerActivity.kt   # ★ 手机播放器
                    └── tv/TvPlayerActivity.kt         # ★ 电视播放器
```

---

## 五、如何得到可安装的 APK

> 本仓库已包含：完整源码、Gradle Wrapper（`gradlew`，无需预装 Gradle）、一键脚本 `build.sh`、
> 云端构建配置 `.github/workflows/build-apk.yml`。三条路任选一条，产物都是
> `app/build/outputs/apk/debug/app-debug.apk`。

| 方式 | 适用场景 | 你需要准备 | 首次耗时 |
|---|---|---|---|
| **A. GitHub Actions 云端构建** | 不想在电脑上装任何 Android 环境 | 一个 GitHub 账号 | 约 6–10 分钟 |
| **B. `./build.sh` 一键构建** | 已有 JDK 与 Android SDK | JDK 17、Android SDK 34 | 约 10 分钟（含下载依赖） |
| **C. Android Studio** | 想改代码、看 Compose 预览、断点调试 | 安装 Android Studio | 约 5 分钟同步 |

### 方式 A：GitHub Actions 云端构建（最省事，推荐给只想拿安装包的人）

1. 在 GitHub 新建一个空仓库（Private 也可以，Actions 免费额度足够）
2. 把 `MyVidHub` 目录内容推到该仓库的 `main` 分支：
   ```bash
   cd MyVidHub
   git init && git add . && git commit -m "init"
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
3. 推送后自动触发构建（`main`、`master`、`dev` 分支均已配置，也可在仓库 **Actions** 页点 `Build APK` → `Run workflow` 手动触发）
4. 构建完成（绿色 ✓）后，在仓库 **Releases** 页下载 `DouDouTV-v2.3-<分支>.apk`
5. 下载到的就是原始 APK 文件，无需解压 → 传到手机 / U 盘拷到电视盒子安装

CI 固定使用 **JDK 17（temurin）**、Gradle 8.7、AGP 8.5.2，直接复用 ubuntu-latest 预装的 Android SDK，
不额外安装 cmdline-tools；构建失败会自动重试一次，仍失败则报红，不会产出空包。

### 方式 B：命令行一键构建

前置条件（缺一不可）：

- **JDK 17**（本工程 `jvmTarget = 17`，AGP 8.5.2 要求 JDK 17+）
- **Android SDK**，含 `platforms/android-34` 与 `build-tools/34.0.0`
- 网络能访问 `dl.google.com` 与 `repo.maven.apache.org`

```bash
cd MyVidHub
bash build.sh          # Windows 用 Git Bash 或 WSL
```

脚本会依次校验 Java 版本、定位 SDK 并写 `local.properties`、缺失组件时自动调
`sdkmanager` 安装、最后执行 `gradlew assembleDebug`，并打印 APK 路径与 `adb install` 命令。

### 方式 C：Android Studio（推荐用于开发）
1. Android Studio Hedgehog (2023.1.1) 或更新
2. `File → Open` 选择 `MyVidHub` 目录
3. 等待 Gradle 同步（首次下载依赖约 400–600MB）
4. 连接设备/模拟器，点 ▶ 运行

### IntelliJ IDEA（Ultimate 版）
本工程是标准 Gradle + AGP 项目，IDEA 可以打开，但需要区分版本能力：

**IntelliJ IDEA Ultimate**（含 Android 插件）
1. `File → Open` 选择 `MyVidHub` 的 `build.gradle.kts`（或整个目录），选 **Open as Project**
2. `Settings → Plugins → Marketplace` 搜 **Android**，安装并重启
3. `Settings → Languages & Frameworks → Android SDK` 指定 SDK 路径（需 Platform 34）
4. `Settings → Build Tools → Gradle → Gradle JVM` 设为 **JDK 17**
5. 首次 Sync 会下载依赖；完成后可直接选设备 Run

**IntelliJ IDEA Community**（无 Android 插件）
- 可以正常打开、浏览与编辑代码（Kotlin / Compose 语法高亮、跳转、补全均可用）
- 但没有 Android SDK 集成、没有 Run 配置、没有 Compose Preview、没有 Layout Inspector
- 想构建 APK 只能用终端跑 `./gradlew assembleDebug`

> 纯 Android 开发仍建议用 Android Studio：Compose Preview、Layout Inspector、APK Analyzer、
> 设备镜像等工具链比 IDEA 领先数个版本。

### 命令行
```bash
# 需要 JDK 17 + Android SDK Platform 34
export ANDROID_HOME=$HOME/Android/Sdk          # macOS/Linux
# Windows: set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk

echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug                         # macOS/Linux
gradlew.bat assembleDebug                       # Windows
# → app/build/outputs/apk/debug/app-debug.apk
```

Wrapper 已随工程提供（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`），
无需预装 Gradle，首次运行会自动下载 Gradle 8.7。

### 版本矩阵（改版本前请对照）

| 组件 | 本工程版本 | 说明 |
|---|---|---|
| Gradle | **8.7** | AGP 8.5.2 要求 ≥ 8.7 |
| Android Gradle Plugin | **8.5.2** | 需 JDK 17 |
| Kotlin | **1.9.24** | 与 Compose Compiler 严格配对 |
| Compose Compiler | **1.5.14** | 必须匹配 Kotlin 1.9.24 |
| KSP | **1.9.24-1.0.20** | 版本号必须与 Kotlin 严格一致 |
| JDK | **17** | AGP 8.5.2 最低要求 |
| jvmTarget | **17** | 与 JDK 对齐 |
| compileSdk / targetSdk | 34 | minSdk 21 |
| Media3 | 1.4.1 | exoplayer / ui / hls / dash / datasource-okhttp / database |
| Room | 2.6.1 | KSP 注解处理 |
| Coil | 2.6.0 | 海报加载 |
| Palette | 1.0.0 | 详情页聚焦色提取 |

> ⚠️ Kotlin 与 Compose Compiler 是强绑定关系，单独升级其中一个必定编译失败。
> KSP 版本号也必须和 Kotlin 主版本精确对齐（1.9.24-X.Y.Z 对应 Kotlin 1.9.24）。
> 若要升到 Kotlin 2.x，需同时启用 `org.jetbrains.kotlin.plugin.compose` 插件并移除
> `composeOptions { kotlinCompilerExtensionVersion }`。

---

## 六、使用流程

### 1. 添加 Emby 来源
- 来源页 → 「添加来源」→ 选 **Emby**
- 服务器地址：`https://emby.yourdomain.com`（不带 `/web`）
- 填用户名密码 → 点添加，程序会自动 `AuthenticateByName` 换 token
- 若显示「未登录」，点该卡片右侧刷新按钮重新登录

### 2. 添加 TVBox 来源
- 选 **TVBox**，填 TVBox 配置 JSON 地址，需包含 `spider` 字段与 `type=3` 站点
  例如形如 `https://example.com/tvbox.json`
- 添加后同步即可拉到首页列表

### 3. 同步与播放
- 首页右上角同步全部来源
- 点海报进详情页；剧集页可切季、选集
- 详情页「继续播放」按钮会接着上次的进度

### 4. 手机端操作
- 底部标签栏切「媒体库 / 来源 / 设置」
- 播放页：点击画面呼出控制层、双击左右快进快退、上下滑调亮度音量、支持比例切换

### 5. TV 端操作
- 若盒子未被识别为 TV：设置 → 打开「强制电视模式」
- 首页遥控器上下换分区、左右换条目、OK 进入
- 播放页：**OK 暂停 / 下键呼出功能面板 / 左右 ±10 秒 / 返回退出**
- 播放页固定全屏，没有比例切换按钮

---

## 七、几个设计取舍

### 1. TVBox 为什么分两次请求
spider 的首页列表（`homeContent`）**不含播放地址**，只有 `detailContent` 才返回 `vod_play_url`。
因此列表阶段只入库「剧集壳」，进入详情页才按需拉分集 —— 否则同步几百部剧会打出几百次请求。
云盘线路（`quark://`、`aliyundrive://` 等）的地址原样保留，播放时再由 `playerContent` 解析成真实直链。

### 2. 唯一索引为什么放在 remoteId
剧集壳条目的 `uri` 是空字符串。如果唯一索引建在 `uri` 上，第二个壳就会因重复而插入失败。
改用 `remoteId`（`emby:来源id:itemId` / `tvbox:来源id:vodId:集数`）天然唯一且稳定。

### 3. Emby 用 static=true
`static=true` 表示不转码、直接原码流传输。局域网千兆下这是画质与延迟的最优解，
代价是客户端必须能解码（Media3 对 HEVC/MKV 支持良好）。若遇到不支持的编码，
把 `EmbyClient.playbackUrl` 的 `static` 改为 `false` 即可让服务器转码。

### 4. 为什么坚持双端分开写
最早的版本用 `isTv: Boolean` 参数在所有页面里分叉，代价是每个页面都背着一堆
`if (isTv) ... else ...`，改手机端动到电视端的风险很高，而且两端的交互范式本来就不同：
手机靠手势与涟漪，电视靠焦点移动与描边放大。拆成两条实现线后，各自可以独立演进。

### 5. 电视端为什么用「放大 + 白边」
手机上的卡片状态靠水波纹和阴影表达，这两者在三米外完全不可见。
电视只能用**尺寸变化**（放大 10%）加**高对比描边**（2.5dp 白边）来传达「当前选中」，
同时行首尾留白 44dp，保证放大时不被屏幕边缘裁切。

### 6. 手机端转场的方向感
- 进详情 = 下钻 → 新页面从右侧小幅推入（`it/6`）并淡入
- 回列表 = 上浮 → 详情向左退（`it/4`），列表从左侧轻滑回来
- 切底部标签 = 平级 → 只做交叉淡化，不引入方向，避免「左右乱跳」

---

## 八、已知限制

1. **Emby 播放进度不回写服务器**：目前进度只存本地 Room，多端之间不同步
2. **TVBox 只用第一条线路**：多线路数据已在 `loadTvBoxDetail` 返回，但 UI 尚未提供切换
3. **远程搜索未接线**：`EmbyClient.search()` 与 `TvBoxClient.search()` 已实现，页面只查本地库
4. **Emby 外挂字幕未使用**：`subtitleUrl` 字段已解析，播放页尚未加载
5. **无自动化测试**：暂无 `src/test` 与 `src/androidTest`
6. **数据库为破坏性迁移**：`fallbackToDestructiveMigration()`，升级版本会清空播放进度与收藏
7. **README 与代码仍需人工同步**：文档更新滞后于代码是这类个人项目的常见问题

---

## 九、可继续扩展

1. **Emby 播放进度回写**：加 `Sessions/Playing/Progress` 上报，实现多端同步
2. **TVBox 多线路切换 UI**：给电视播放器面板加一个「线路」标签即可
3. **远程搜索接入**：把已实现的 `search()` 接到搜索页，本地库无结果时回落远程
4. **外挂字幕加载**：把 `subtitleUrl` 接进 Media3 的 `SubtitleConfiguration`
5. **演员/相似推荐**：Emby `Items/{id}/Similar` 与 `People` 字段
6. **海报墙性能**：条目过千时给 Room 加分页（Paging 3）
7. **加密存储凭据**：把来源账号密码与 token 从明文改为 `EncryptedSharedPreferences`

---

## 十、参考项目与许可

### 1. 协议与行为参考（只参考公开协议与行为，未复制源码）

| 参考对象 | 参考内容 |
|---|---|
| CatVod / TVBox 配置规范 | `type=3` spider 站点、配置级 `spider` 包、`flags`、`searchable` / `quickSearch`，以及 `homeContent` / `categoryContent` / `searchContent` / `detailContent` / `playerContent` 的方法语义与返回 JSON（`list` / `class` / `parse` / `jx` / `header`） |
| [FongMi/TV](https://github.com/FongMi/TV)（GPL-3.0） | 仅参考其 TVBox 侧的行为约定：直链也先走 `playerContent`、配置 `flags` 作为 `vipFlags`、`parse=1` 视为需要网页解析、远程搜索按 `searchable` / `quickSearch` 过滤。**未复制其代码**，本项目与其不存在源码派生关系 |
| VidHub / Netflix | 交互与视觉形态参考（沉浸式大图首页、海报墙、继续观看、聚焦色背景、十英尺遥控器 UI），不涉及实现代码 |

### 2. 开源依赖

- AndroidX / Compose、Media3 / ExoPlayer、Room、Coil、Palette、Kotlin Coroutines（Apache-2.0）
- OkHttp、Gson、Fastjson（Apache-2.0）
- jsoup（MIT）、jpinyin（Apache-2.0）

### 3. 其他

- 本项目代码可自由使用、修改、分发
- spider 包由使用者自己的 TVBox 配置提供，本项目不内置任何片源
- 请仅用于播放你拥有合法权利的内容；第三方接口的可用性与合法性由使用者自行判断
