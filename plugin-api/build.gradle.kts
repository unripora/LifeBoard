plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 本模块是宿主与第三方插件共用契约，**不得依赖 :core**：
    // 否则插件会被迫连带整个宿主模型层，契约边界就失效了。
    // 只允许纯 Kotlin/JVM 依赖（当前为零依赖）。
    testImplementation(libs.junit)
}
