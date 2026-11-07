plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compose 编译器插件：Kotlin 2.0 起由 Kotlin 仓库发布，取代旧的 composeOptions 写法
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Room 把每个版本的表结构 JSON 落到这里，配合 AppDatabase 的 exportSchema = true：
// 下次改表（加索引、加字段）能看出漏没漏迁移，也能直接上自动迁移。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.chen.doudoutv"
    // Compose 1.9 的 foundation 要求 compileSdk 35（aar-metadata 里 minCompileSdk=35）
    compileSdk = 35

    // 固定 debug 签名：确保升级安装不丢数据
    signingConfigs {
        create("debugSigning") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.chen.doudoutv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 复用 debug 那颗 keystore：正式签名和 debug 签名不一致会导致覆盖安装直接失败，
            // 只能卸载重装，媒体库、观看进度、收藏全丢。
            // 对外发布要换正式 keystore 时注意：换完就断升级链，老包必须卸干净。
            signingConfig = signingConfigs.getByName("debugSigning")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // debug 包与 release 同包名，方便覆盖安装测试
            signingConfig = signingConfigs.getByName("debugSigning")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST"
        )
    }
}

dependencies {
    val media3 = "1.4.1"

    // TVBox Spider Guard 内部依赖（饭太硬/花卷/玩偶/观影等线路的加密壳都用了 Gson 做 JSON 解析）
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Compose：BOM 2025.08.00 = ui/foundation 1.9.0 + material3 1.3.2
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 播放内核
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")

    // 网络 / JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 手机扫码维护：电视端把局域网地址编码成二维码（纯 Java，只用到 core）
    implementation("com.google.zxing:core:3.5.3")

    // 汉字 → 拼音（联icode 区间估想用的首字母必须准确：Un算会把「兰」算成 b）
    implementation("com.github.stuxuhai:jpinyin:1.1.8")

    // TVBox type 3 爬虫运行时（Jsoup + Fastjson —— spider JAR 依赖这些类在 Dex 里解析）
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.alibaba:fastjson:1.2.83")

    // 本地媒体库
    // Room 2.6.1 的编译器在 Kotlin 2.x / KSP2 下不工作，必须跟到 2.7.2
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // 海报图
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 海报主色提取（详情页沉浸式渐变背景）
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 端到端设备测试（自己走完 TVBox 源完整流程用）
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
