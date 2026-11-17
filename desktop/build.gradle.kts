// M0 可行性验证骨架：Windows 桌面外壳
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        // 命名目标（jvm("desktop")）没有类型安全的访问器，按名查找
        getByName("desktopMain").dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            // `DashboardViewModel` 继承 `androidx.lifecycle.ViewModel`。
            // 这里原本没显式依赖 lifecycle，编译 `Main.kt` 时只在
            // 「读取 ViewModel 的父类型」这一步报错，看不出是缺依赖——
            // 版本必须与 `shared` 里的 composeLifecycle 一致（2.9.6），
            // 否则同一个 ViewModel 类会被两份 lifecycle-common 认成不同类
            implementation(libs.compose.lifecycle.viewmodel)
            // `Dispatchers.Main` 在 JVM 上没有默认实现，`viewModelScope.launch { }`
            // （`DashboardViewModel`、`LogViewModel` 都在用）会抛
            // 「Module with the Main dispatcher is missing」。
            // 这个坐标把 Main 映射到 Swing 的 EDT，必须与 coroutines-core 同版本
            implementation(libs.kotlinx.coroutines.swing)
            // M4-4.3：DPAPI（CryptProtectData / CryptUnprotectData）走 JNA，
            // 让 Windows 依据当前登录用户派生密钥，应用不保存任何主密钥
            implementation(libs.jna)
        }

        // 桌面端自己的单测（M4）：DPAPI 加解密、进程内调度的行为都只有
        // 在真实桌面上跑才有意义（DPAPI 依赖当前 Windows 登录用户，
        // 用 mock 反而测不到「换账户后解不开」这条真正的约束）
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.lifeboard.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "LifeBoard"
            packageVersion = "1.0.0"
        }
    }
}
