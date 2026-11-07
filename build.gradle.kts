plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Compose 编译器从 Kotlin 2.0 起由 Kotlin 仓库发布（org.jetbrains.kotlin.plugin.compose），
    // 不再由 AGP 的 composeOptions 指定 extension 版本
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
