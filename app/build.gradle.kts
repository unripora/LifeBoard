plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // CMP 插件：让 :app 与 :shared/:desktop 共用同一份 Compose 依赖解析结果。
    // 不加它就只能吃 AndroidX BOM 的版本，两边 Compose 会出现两套版本号。
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "com.lifeboard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifeboard.app"
        minSdk = 29
        targetSdk = 36
        // 版本号规则（别再手工维护 versionCode）：
        //
        // - versionName 由人决定，改功能时手动提升，用来在设备上认出「装的是哪一版」
        // - versionCode 由构建时间自动生成，**每次构建必然递增**
        //
        // 为什么不让 versionCode 也手工写：忘了加就会覆盖安装失败
        // （adb 报 INSTALL_FAILED_VERSION_DOWNGRADE），而且从 APK 上看不出差别；
        // 自动递增后「有新代码就一定装得上」，不需要任何人记得这件事。
        //
        // 取分钟计数而不是秒：同一分钟内重复构建不撞号，数值也不会长到没法看。
        // 起点 10000 保证高于历史上手工填的 1、2，避免降级。
        val buildStamp = ((System.currentTimeMillis() / 60_000L) % 10_000L).toInt()
        versionCode = 10_000 + buildStamp
        versionName = "0.3.9"
        // 原 :server 的 androidTest 已并入本模块（M3-3.6）。
        // CredentialVault 依赖 Android Keystore / GCM，JVM 单测覆盖不到，只能真机跑。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 内测分发用的签名：复用 AGP 自带的 debug 证书，这样 release 包也能直接安装，
    // 且与 debug 包的签名一致——同一个设备上换装不需要先卸载。
    //
    // 请注意这不是生产签名。密钥库在 ~/.android/debug.keystore，口令公开为 android，
    // 任何人拿到它都能签出「同签名」的包。等项目要正式发布时必须换成自己的密钥库，
    // 并去掉这里的 signingConfigs.release。
    signingConfigs {
        create("release") {
            initWith(getByName("debug"))
        }
    }

    // LSPosed 加载 Legacy 模块入口时走 LspModuleClassLoader（ByteBufferDexClassLoader），
    // 加载的是 APK 内全部 dex，入口类在哪个 classesN.dex 都能正常加载。
    // 详见 docs/postmortem_lsposed_injection.md：曾误判"入口必须在 classes.dex"，
    // 据此写的 main-dex-list / 重排 dex 均为无效负债，已删除，不要重新引入。
    //
    // 仍需避免的是引入体积大又非必需的依赖（典型 material-icons-extended 约 40MB）。

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 注意：不可排除 /META-INF/xposed/*，LSPosed 模块元数据就在此目录下
            merges += "/META-INF/xposed/*"
        }
    }
}

// Vico 3.2.3 的 Android 产物把 androidx.compose.* 传递到 1.12.1，
// 而 1.12.1 起 AAR 元数据要求 compileSdk >= 37，当前 AGP 8.12.3 上限是 36，
// 于是 checkDebugAarMetadata 直接失败。
//
// 这里统一压回 1.8.3（CMP 1.12.2 在 Android 侧对应的 androidx compose 线），
// 而不是把 compileSdk 抬到 37：AGP 8.12.3 官方支持到 36，
// 强上 37 属于未验证组合，且会影响真机安装的 targetSdk 语义。
// 若将来升级 AGP 到支持 37 的版本，这段约束可以一并删掉。
//
// 只压「androidx.compose 主线编号」的模块，且逐个点名而不是按 group 前缀：
// - material-icons / material3 各自独立版本线，没有 1.8.3；
// - runtime-retain 是 1.9 才有的新模块，1.8.3 也没有。
// 用 group 前缀一刀切会让这些模块被强制套上不存在的版本，直接解析失败。
// 这里枚举的是「Vico 传递上来、且确实有 1.8.3 版本」的那几个。
val composePinnedModules = setOf(
    "androidx.compose.ui:ui",
    "androidx.compose.ui:ui-graphics",
    "androidx.compose.ui:ui-text",
    "androidx.compose.ui:ui-unit",
    "androidx.compose.ui:ui-geometry",
    "androidx.compose.ui:ui-util",
    "androidx.compose.foundation:foundation",
    "androidx.compose.foundation:foundation-layout",
    "androidx.compose.animation:animation",
    "androidx.compose.animation:animation-core",
    "androidx.compose.runtime:runtime",
    "androidx.compose.runtime:runtime-saveable",
    "androidx.compose.material:material-ripple",
    // tooling 系列只在 debugImplementation 里，但 AAR 元数据检查对 debug 变体同样生效
    "androidx.compose.ui:ui-tooling",
    "androidx.compose.ui:ui-tooling-data",
    "androidx.compose.ui:ui-tooling-preview",
)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if ("${requested.group}:${requested.name}" in composePinnedModules) {
            useVersion("1.8.3")
            because("高于 1.9 的 compose AAR 要求 compileSdk 37，AGP 8.12.3 不支持")
        }
    }
}

dependencies {
    implementation(project(":core")) {
        // :core 已改为 KMP 双目标，Android 侧必须显式取 androidJvm 变体
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm,
            )
        }
    }
    // 原 :server 已并入本模块（M3-3.6）：Android 平台实现不再单独成库。
    // 这么做不是为了少一个 Gradle 模块，而是因为「:server」这个名字已经名不副实——
    // 里面没有任何服务端逻辑，只有 Android 侧实现；而它与 :app 之间那条
    // implementation 边界从未被真正使用过（两者永远同时打包进同一个 APK）。
    // JS 插件运行时：提供 QuickJS 引擎，JS 插件适配器基于它实现 SourcePlugin
    implementation(project(":js-engine"))
    // 共享层：平台接口（RemindBackend 等）来自这里，M1 起 ViewModel 用构造注入而非 AndroidViewModel
    implementation(project(":shared")) {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm,
            )
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // 以下三项随原 :server 一并并进来：
    // - work-runtime-ktx：DailyAggregateWorker 等后台聚合
    // - okhttp：小米健康插件客户端与 JS 插件的 HTTP 桥
    // - org.json：插件清单与 JS 侧的数据交换
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)

    // Compose 由 CMP 插件统一提供（compose.ui / compose.material3 等 dsl 访问器）。
    // 不再叠加 AndroidX BOM：CMP 1.12.2 已经把它依赖的 androidx compose 全部
    // 传递进来并锁定版本，再叠一份 BOM 会在「谁赢」上产生不可控的版本仲裁，
    // 而且 BOM 一旦抬到 compose 1.12.1+ 就要求 compileSdk 37（AGP 8.12.3 上限 36）。
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    // 官方核心图标集：体积约 100KB，用于按钮/状态标识。
    // 不要引入 material-icons-extended：七千多个矢量图标约 40MB，会把 APK 从 25MB 撑到 57MB。
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(compose.uiTooling)

    implementation(libs.kotlinx.coroutines.android)

    // 图表引擎。雷达图官方不支持（本任务不做），饼图属实验性 API，
    // 因此图表适配层必须隔离，避免其破坏性变更扩散到卡片代码。
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // 纯逻辑（页签名归一化、布局状态判定）在 JVM 单测里验证，
    // 不必为了跑几个纯函数上真机
    testImplementation(libs.junit)
    // 插件清单与 JS 桥的单测要解析 JSON
    testImplementation(libs.org.json)

    // 真机测试：CredentialVault 依赖 Android Keystore / GCM，JVM 单测无法覆盖
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Legacy Xposed API 桩：compileOnly，运行时由 LSPosed 注入
    compileOnly(libs.legacy.xposed.api)
}
