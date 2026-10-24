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
            }
        }

        // 自建两条中间链（默认层级只在同类目标≥2 时才建 iosMain 这种中间集，这里手动接）：
        //   jvmSharedMain：android+jvm 共用（OkHttp 引擎、Dispatchers.IO）
        //   nonAndroidMain：ios+jvm 共用（org.json 垫片）
        //   iosMain：挂在 nonAndroidMain 下，iosArm64Main 再挂它
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        val nonAndroidMain by creating { dependsOn(commonMain.get()) }
        val iosMain by creating { dependsOn(nonAndroidMain) }

        androidMain { dependsOn(jvmSharedMain) }
        jvmMain {
            dependsOn(jvmSharedMain)
            dependsOn(nonAndroidMain)
        }
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
