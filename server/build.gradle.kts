plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lifeboard.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        // CredentialVault 依赖 Android Keystore / GCM / Room，JVM 单测无法覆盖，
        // 真机验证走 :server 的 connectedAndroidTest。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/*
 * 把 demo 插件的 APK 放进 androidTest 的 assets。
 *
 * 为什么走 assets 而不是 Gradle 直接依赖 :demo-plugin：
 * 插件在运行期是被 DexClassLoader 从文件加载的，不是编译期依赖。
 * 测试要验证的正是「拿到一个真实的 .ext 文件能不能装进插件目录并实例化」，
 * 所以需要的是**文件**本身，而不是把它变成测试的 classpath 依赖——
 * 后者会让契约类被测试进程的类加载器抢先加载，验证就失真了。
 */
val copyDemoPluginApk by tasks.registering(Copy::class) {
    // 依赖 demo 插件的装配任务，保证 APK 已生成
    dependsOn(":demo-plugin:assembleDebug")
    from(project(":demo-plugin").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        // 加载器只认 .ext 后缀，这里直接改成插件包名
        rename { "demo-plugin.ext" }
    }
    into(layout.projectDirectory.dir("src/androidTest/assets"))
}

/*
 * 小米健康插件同样作为文件放进 assets。
 *
 * 它比 demo 更能证明契约可用：自带 OkHttp、多轮登录、双量纲，
 * 而且契约类同样必须是 compileOnly（否则 instance is SourcePlugin 会失败）。
 * 这里只验证「能装、能实例化、能声明指标」，真实登录与拉数需要账号，不进自动化。
 */
val copyMiHealthPluginApk by tasks.registering(Copy::class) {
    dependsOn(":mihealth-plugin:assembleDebug")
    from(project(":mihealth-plugin").layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { "mihealth-plugin.ext" }
    }
    into(layout.projectDirectory.dir("src/androidTest/assets"))
}

// 资源合并任务在配置阶段结束时才注册，因此放到 afterEvaluate 里绑定，
// 用 configureEach 保证即使任务稍后创建也能挂上依赖。
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
        .configureEach { dependsOn(copyDemoPluginApk, copyMiHealthPluginApk) }
    // 编译 androidTest 源码也要先有 assets，否则第一次跑测试时目录是空的
    tasks.matching { it.name == "compileDebugAndroidTestKotlin" }
        .configureEach { dependsOn(copyDemoPluginApk, copyMiHealthPluginApk) }
}
