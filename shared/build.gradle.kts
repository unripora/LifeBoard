// M0 可行性验证骨架：仅用于证明 CMP + Kotlin 2.3.21 + AGP 8.12.3 可协同构建
// 正式实现见 docs/tasks/refactor_desktop_support/P_plan.md
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm("desktop")

    sourceSets {
        // JVM 公共源集：JS 插件运行时（桥接层、加载器）依赖 java.io / java.security /
        // OkHttp 等纯 JVM API，但 commonMain 是 KMP 公共层，不能出现 java.*。
        // Android 与 desktop 都跑在 JVM 上，能力完全一致，所以放中间层写**一份**，
        // 避免几百行桥接代码在两个源集里各维护一遍（那必然漂移）。
        val jvmMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmMain)
        getByName("desktopMain").dependsOn(jvmMain)

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // ViewModel 的 KMP 版（org.jetbrains.androidx.lifecycle）。
            // 面板/仪表盘的 ViewModel 只在 commonMain 写一份，两端共用同一份状态逻辑；
            // 用 androidx 坐标会在桌面端缺实现（那是 Android 专用构件）。
            implementation(libs.compose.lifecycle.viewmodel)
            // 一套 SQL：commonMain 里的 .sq 由 SQLDelight 双端生成
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // 图表卡等配置的 JSON 编解码要在两端跑同一份代码：
            // Android 框架的 org.json 在桌面端不存在，必须用 KMP 可用的库
            implementation(libs.kotlinx.serialization.json)
            // 图表引擎（Vico 3.x）。它发布的是 KMP 构件，commonMain 直接可用，
            // 折线/面积/散点/柱状/饼图/环形都由它画；热力图 Vico 没有，仍自绘。
            // 注意不要改成只在 androidMain 依赖——那样 commonMain 编译不过。
            implementation(libs.vico.compose)
            implementation(libs.vico.compose.m3)
            // 领域模型（TaskConfig / DailyRecord / MetricRecord / ScheduleRule …）与
            // 数据源引用（SourceRef）都已经在这两个 KMP 模块的 commonMain 里，
            // Repository 契约直接复用它们，不再另造一套平行 DTO——
            // 同一概念两份定义必然漂移，且会把强类型（ScheduleRule）退化成手拼字符串。
            api(project(":core"))
            api(project(":plugin-api"))
        }
        jvmMain.dependencies {
            // JS 插件运行时要调引擎：桥接层（ctx.* / http / crypto）全在 JVM 公共层
            implementation(project(":js-engine"))
            // 插件脚本发起 HTTP 请求。OkHttp 是纯 JVM 实现，不依赖 Android
            implementation(libs.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        // 命名目标（jvm("desktop")）没有类型安全的访问器，按名查找
        getByName("desktopMain").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            // 固定版本，避免桌面 SQLite 行为随 JDK / 系统漂移
            implementation(libs.sqlite.jdbc)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            // WebSocket 桥测试需要真的 WS 服务端。手写 ServerSocket 做握手与帧编解码，
            // 会把「测试自己的 bug」混进断言里；用 OkHttp 官方的 mock 服务端更可信。
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
    }
}

sqldelight {
    databases {
        create("LifeBoardDb") {
            // 生成 com.lifeboard.shared.db.LifeBoardDb
            packageName.set("com.lifeboard.shared.db")
            // 迁移文件目录（M0 暂无迁移，预留结构）
            srcDirs.setFrom("src/commonMain/sqldelight")
        }
    }
}

// compose 版本钉住在 1.8.3：1.12.1 起 AAR 元数据要求 compileSdk >= 37，
// 而当前 AGP 8.12.3 上限是 36。
//
// 这段原本是为了压 Vico 传递进来的 androidx.compose.* 1.12.1（Vico 在 commonMain 里）。
// :app 里还挂着同样的约束，两处必须逐条一致，
// 否则同一张依赖图里会出现两个 compose 版本。
// 若将来升级 AGP 到支持 37 的版本，两处可以一并删掉。
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
)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if ("${requested.group}:${requested.name}" in composePinnedModules) {
            useVersion("1.8.3")
            because("高于 1.9 的 compose AAR 要求 compileSdk 37，AGP 8.12.3 不支持")
        }
    }
}

android {
    namespace = "com.lifeboard.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// `JsHostFunctionContractTest` 会读取仓库里的 `js-plugins/types/ctx.d.ts`，
// 校验 TS 声明与宿主实际注入的 `ctx.*` 函数集一致。
//
// 那个文件不在 Gradle 的任何 sourceSet 里，默认不参与任务输入计算，
// 于是出现「改了 ctx.d.ts，测试却显示 UP-TO-DATE 不重跑」——
// 测试看着是绿的，实际根本没执行，这条防线等于不存在。
// 所以必须显式声明为输入。
tasks.matching { it.name == "desktopTest" }.configureEach {
    inputs.file(rootProject.file("js-plugins/types/ctx.d.ts"))
        .withPropertyName("jsPluginCtxTypeDeclaration")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `MiHealthBundleSmokeTest` 会在真实 QuickJS 里加载这份构建产物。
    // 同理，产物不在 sourceSet 里，不声明成输入就会「改了产物但测试不重跑」。
    // 文件可能不存在（还没跑过 npm run build），用 optional 避免配置阶段失败。
    val bundle = rootProject.file("js-plugins/mihealth/main.js")
    inputs.files(files(bundle))
        .withPropertyName("miHealthJsBundle")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}
