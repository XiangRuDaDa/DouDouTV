pluginManagement {
    repositories {
        // Central 对美区流量会把大 jar 302 给 GitHub Release 资产，GitHub 资产分发一 504，
        // CI 就在根工程配置阶段挂掉（kotlin-compiler-embeddable 实测踩过）。GCS 是 Central
        // 官方镜像、直出字节不跳转，sha1 已与官方核对一致。只在 CI 前置：本机在国内连不通 GCS，
        // 而连接超时是硬失败，不会回退到下一个仓库。
        if (System.getenv("CI") != null) {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != null) {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DouDouTV"
include(":app")
