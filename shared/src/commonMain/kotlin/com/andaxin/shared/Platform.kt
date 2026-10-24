package com.andaxin.shared

/**
 * Spike 的那颗探针：expect/actual 机制在两个目标上都编得过，
 * 这台机器上"一套 Kotlin 出两个平台"才算真的立住。
 */
expect fun platformName(): String

/**
 * 给 Swift 用的出口：Kotlin 的顶层函数不会进 Objective-C 头文件，Swift 天生看不见
 * （第四发 CI 就是死在这：import shared 成功、PlatformKt 不在作用域）。类会导出，
 * 所以套这一层。以后给 iOS 侧的入口都走类，别再用顶层函数。
 */
class SharedPlatform {
    fun name(): String = platformName()
}
