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
