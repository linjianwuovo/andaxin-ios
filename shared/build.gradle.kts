/*
 * Spike 模块：iOS 这条线的地基。这一份刻意什么都不依赖、什么都不装——
 * 它要证的只有三件事：
 *   1. AGP 9（自带 Kotlin）和 org.jetbrains.kotlin.multiplatform 在同一个构建里共存；
 *   2. 新插件/编译器能从阿里云镜像拉下来；
 *   3. Windows 主机上 kotlin-native 能交叉编译 iosArm64。
 * 三件全绿之后才往这里搬真代码（core 缝 → data → 界面）。
 *
 * Android 侧走 AGP 9 那个 KMP 库插件（com.android.kotlin.multiplatform.library），
 * 不写 android {} 块、不用 com.android.library——那个老 DSL 和 androidTarget()
 * 在 AGP 9 的 newDsl 下不兼容，是第一次试编的报错原文指的路。
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

    // 静态 framework：侧载链路（Sideloadly/爱思）重签时不用再处理嵌套 dylib，模拟器/CI 也省一步。
    // baseName 定死 "shared"，iosApp 那个 Xcode 工程按这个名字挂依赖。
    iosArm64() {
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
                // 空的。见文件头。
                // （2026-10-24 探针结论：org.json 只发 JVM jar，没有 iosArm64 变体——
                //   data/ 进 commonMain 靠的是"org.json 兼容垫片"，Android 端继续用平台那份，
                //   非 Android 目标用自写垫片，见下次提交。）
            }
        }
    }
}
