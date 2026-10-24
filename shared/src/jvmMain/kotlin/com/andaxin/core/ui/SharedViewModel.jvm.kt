package com.andaxin.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * JVM 目标不出货，纯当本机测试架：跟 iOS 同款作用域，把 expect 的账补上，
 * 让 compileKotlinJvm 能过。目前没有测试构造过这些 Model，真要构造时再议生命周期。
 */
actual abstract class SharedViewModel actual constructor() {
    actual val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
