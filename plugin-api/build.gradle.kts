// 宿主与第三方插件共用的契约模块。
// 必须保持零第三方依赖，且需同时支持 Android（APK/Dex 插件）与 JVM（桌面 JAR 插件），
// 因此改为 Kotlin Multiplatform 双目标；契约本身仍只落在 commonMain。
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
            // 零依赖：插件会被打进第三方包，多一个依赖就多一层耦合
        }
        commonTest.dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.lifeboard.plugin.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
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
