plugins {
    // demo 插件是独立应用模块：build 出来的 APK 改名 .ext 即为插件包。
    // 它**不依赖宿主的任何实现模块**，只 compileOnly 契约，
    // 因此可以作为一个真正的外部插件来验证整条加载链路。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lifeboard.plugin.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifeboard.plugin.demo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 插件必须声明这个特性，加载器据此识别「这是给 LifeBoard 写的包」。
        // 用 uses-feature 而非自定义 permission：feature 不涉及权限授予，
        // 纯粹是能力声明，且 getPackageArchiveInfo 能读到（需要解析 manifest）。
        manifestPlaceholders["pluginFeature"] = "lifeboard.plugin"
    }

    buildTypes {
        release {
            // 插件包不做混淆：demo 用于验证加载器，保留类名便于排查
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // demo 不参与宿主打包，避免被误认为宿主功能的一部分
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // compileOnly：只在编译期拿符号，**绝不打进 dex**。
    // 运行期由宿主的 DexClassLoader 父加载器提供同一份实现，
    // 否则 instance is SourcePlugin 会因类不同而失败。详见 plugin-api/PLUGIN_SDK.md
    compileOnly(project(":plugin-api"))
}
