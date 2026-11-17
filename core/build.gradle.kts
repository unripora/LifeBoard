// 契约与工具层：Android 与桌面共用，改为 Kotlin Multiplatform 双目标。
// 业务模型（SourceRef / MetricSpec / 日期工具）落在 commonMain，
// 平台能力通过 expect/actual 或纯 JVM API 收敛。
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
            // 契约模块：SourceRef / MetricBucket / MetricSpec 等插件可见类型的唯一出处
            api(project(":plugin-api"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.junit)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            // LSPosed libxposed API 仅编译期使用，运行时由框架提供
            compileOnly(libs.libxposed.api)
        }
        // 命名目标（jvm("desktop")）没有类型安全的访问器，按名查找
        getByName("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "com.lifeboard.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
