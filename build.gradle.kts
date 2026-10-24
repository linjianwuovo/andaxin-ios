plugins {
    alias(libs.plugins.android.application) apply false
    // shared 那个 KMP 库模块用的两件。不带版本的插件必须先在根上把带版本的 marker 解析掉，
    // 否则子模块再按版本要它会撞"already on the classpath with an unknown version"。
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // kotlinx.serialization 的插件等 CaptureFeed 真开始写报文时再加：
    // 本机现在拉不到它（要走代理），骨架阶段一行都没用到，不留一个下不来的依赖。
}
