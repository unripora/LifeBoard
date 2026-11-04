plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lifeboard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifeboard.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    kotlinOptions {
        jvmTarget = "17"
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":server"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 官方核心图标集：体积约 100KB，用于按钮/状态标识。
    // 不要引入 material-icons-extended：七千多个矢量图标约 40MB，会把 APK 从 25MB 撑到 57MB。
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Legacy Xposed API 桩：compileOnly，运行时由 LSPosed 注入
    compileOnly(libs.legacy.xposed.api)
}
