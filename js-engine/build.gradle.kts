plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":plugin-api"))
        }
        androidMain.dependencies {
            // Android 侧无需 JNA：走 JNI（见 src/main/cpp/jni_bridge.cpp）
        }
        getByName("desktopMain").dependencies {
            // 桌面侧走 JNA 按符号名绑定 lifeboard-js.dll（见 desktop_bridge.cpp）
            implementation(libs.jna)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.lifeboard.jsengine"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 29

        // JNI 测试必须跑在真机/模拟器上，需声明 runner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // QuickJS 是纯 C99 代码，不需要 C++ 标准库；
                // 但 JNI 桥接层用 C++，因此显式指定 c++17。
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }

        ndk {
            // QuickJS strip 后约 1MB/ABI，每多一个 ABI 就多约 1MB。
            //
            // 为什么是这两个而不是全部四个：
            //   - arm64-v8a：2018 年之后的主流设备
            //   - armeabi-v7a：更老的 32 位设备。之前只打 arm64，
            //     装上就是启动闪退（`UnsatisfiedLinkError`），而这类机器
            //     在国内存量不小，缺它等于把这批用户全挡在门外
            //   - x86 / x86_64：仅模拟器需要。真机市场份额为零，
            //     真要跑模拟器时在下面临时加一行即可
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 该模块只负责 JS 引擎，不依赖 :core，
    // 保持与插件契约解耦：契约在 :plugin-api，适配层在 :server。
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
