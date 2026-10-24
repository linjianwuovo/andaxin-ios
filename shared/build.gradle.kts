/*
 * 共享内核模块。当前批次装的是"缝"：HTTP 引擎、IO 调度器、Vault、文件访问，
 * 都是 expect/actual —— Android 端沿用 app 里验证过的那套实现（OkHttp / Keystore），
 * iOS 端各写一份（Ktor+Darwin / Keychain）。业务代码（core/data/state/feature）后续批次平移。
 *
 * 另有两个中间源集：
 *   jvmSharedMain —— android 和 jvm 公用（OkHttp 引擎、Dispatchers.IO）
 *   nonAndroidMain —— ios 和 jvm 公用（org.json 垫片；Android 用平台自带那份，不掺和）
 * jvm() 目标不出货，纯当本机测试架：垫片和缝的语义在 Windows 上就能跑测试，不用等 CI。
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    // Compose 编译器插件（让 @Composable 注解过的代码能编出 Kotlin/Native 目标）
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.andaxin.shared"
        compileSdk = 37
        minSdk = 26
    }

    jvm()

    // 静态 framework：侧载链路（Sideloadly/爱思）重签时不用再处理嵌套 dylib。
    // baseName 定死 "shared"，iosApp 那个 Xcode 工程按这个名字挂依赖。
    // 模拟器目标：CI 的截屏/点开验证要跑 arm64 模拟器，Xcode 那步会按 SDK 自己挑对应目标编。
    iosArm64() {
        binaries {
            framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }

    iosSimulatorArm64() {
        binaries {
            framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // Store 用的 DataStore 多平台内核；文件路径由调用方给（Android 是 filesDir/datastore 那份老文件）
                implementation(libs.datastore.preferences.core)
                // 日期：java.time 的多平台替身（CaptureYwlz 的 LocalDate、之后请假表单那批全靠它）
                implementation(libs.kotlinx.datetime)

                // Compose Multiplatform：显式坐标（JB 插件和 AGP9 不兼容，走 artifact 直引）
                implementation("org.jetbrains.compose.runtime:runtime:1.8.0")
                implementation("org.jetbrains.compose.foundation:foundation:1.8.0")
                implementation("org.jetbrains.compose.material3:material3:1.8.0")
                implementation("org.jetbrains.compose.ui:ui:1.8.0")
            }
        }

        // 中间源集（默认层级在同类目标只有 1 个时不建中间集，手动接）：
        //   jvmSharedMain：android+jvm 共用（OkHttp 引擎、Dispatchers.IO）
        //   iosMain：org.json 垫片 + Ktor 依赖
        //   logicMain：业务逻辑层（Session/Snap/School/TokenBundle）。放这里不放 commonMain，
        //     是因为它们 import org.json —— commonMain 的元数据编译看不见平台物；
        //     logicMain 只在三个目标的编译里出现，org.json 各自从 SDK（Android）/ jar（JVM）/
        //     垫片源码（iOS）来，三端一行不改。
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        val iosMain by creating { dependsOn(commonMain.get()) }
        val logicMain by creating { dependsOn(commonMain.get()) }

        androidMain {
            dependsOn(jvmSharedMain)
            dependsOn(logicMain)
        }
        jvmMain {
            dependsOn(jvmSharedMain)
            dependsOn(logicMain)
        }
        iosMain.dependsOn(logicMain)
        iosArm64Main { dependsOn(iosMain) }
        iosSimulatorArm64Main { dependsOn(iosMain) }

        androidMain.dependencies {
            implementation(libs.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
