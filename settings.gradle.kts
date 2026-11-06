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
include(":server")
// 宿主与第三方插件共用的契约，必须保持零依赖
include(":plugin-api")
// 最小可用插件，用于验证加载链路；不参与宿主打包
include(":demo-plugin")
// 首个真实插件：小米健康。自带 OkHttp 等实现依赖，验证契约能表达真实登录/续期/分页
include(":mihealth-plugin")
