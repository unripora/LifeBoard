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

        // 版本号每次构建自动递增，用于确认「设备上装的到底是哪一版」。
        //
        // 为什么需要它：插件被静默替换后，界面上只有 「v1.0」，
        // 根本分不清装的是新代码还是旧代码，排查时白白浪费一轮真机验证
        // （已经发生过一次：改动没触发重编译，测的其实是旧包）。
        //
        // 补丁号 = 当前毫秒数除以 6 万再对 10000 取模，即「分钟计数」，
        // 同一分钟内重复构建不会撞号，位数也不会长到没法看。
        val buildStamp = ((System.currentTimeMillis() / 60_000L) % 10_000L).toInt()
        versionCode = 10_000 + buildStamp
        // 与宿主同一套规则：versionCode 自动递增保证装得上，versionName 改功能时手动提升
        versionName = "1.1.$buildStamp"

        manifestPlaceholders["pluginFeature"] = "lifeboard.plugin"

        // 解析规则的仪器测试需要 runner：本模块是 application 而不是 library，
        // 不声明这一行，测试 APK 装到设备上也没有可执行的 instrumentation
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 解析规则依赖 org.json，JVM 单测里它只是空壳，所以放仪器测试验证
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
