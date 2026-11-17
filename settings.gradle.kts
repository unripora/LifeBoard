rootProject.name = "LifeBoard"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像排在最前：本机到 Maven Central 的直连被网络层挡成 403，
        // 常见的第三方构件（kotlinx 等）从这里取，避免为了一个依赖改全局代理。
        // 镜像缺的内容仍会继续往下面的官方仓库回落。
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // libxposed API 发布在 JitPack
        maven("https://jitpack.io")
        // Legacy Xposed API 桩（de.robv.android.xposed:api）仅在此仓库
        maven("https://api.xposed.info/")
    }
}

include(":app")
include(":core")
// 桌面端支持：共享层（KMP：Android + Windows 共用业务与 Compose UI）与 Windows 外壳
include(":shared")
include(":desktop")
// JS 插件运行时：内嵌 QuickJS，向 Android 侧（原 :server，已并入 :app）提供跨平台脚本执行能力
include(":js-engine")
// 宿主与第三方插件共用的契约，必须保持零依赖
include(":plugin-api")
