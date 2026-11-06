// 小米健康插件：独立构建为 APK，改名为 .ext 后由宿主安装。
//
// 与 :demo-plugin 的关键区别是它**自带实现依赖**（OkHttp、org.json），
// 因为小米云端接口的加解密、登录会话、分页都是站点私有的，
// 宿主不该为某一家站点提供网络栈。契约只承载边界（:plugin-api），实现全在插件里。
//
// 注意 :plugin-api 仍是 compileOnly：运行期必须复用宿主加载的那一份类，
// 否则 `instance is SourcePlugin` 会因类加载器不同而失败。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // 必须与 demo-plugin 的 packageName 不同：插件 id 与包名都不能重名
    namespace = "com.lifeboard.plugin.mihealth"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lifeboard.plugin.mihealth"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["pluginFeature"] = "lifeboard.plugin"
    }

    buildTypes {
        // 与 demo 一致：不混淆，便于真机排查
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 契约只在编译期可见，绝不打进 dex
    compileOnly(project(":plugin-api"))

    // 下面这些是插件自己的实现细节，**必须被打进插件 dex**：
    // 宿主不提供，也不会在宿主里出现小米专用的类
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
