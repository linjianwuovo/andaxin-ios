package com.andaxin.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * commonMain 里没有 Dispatchers.IO，也没有墙钟。两个 expect 各平台给：
 * Android/JVM 是 IO 与 System.currentTimeMillis()；
 * iOS 用 Default（Darwin 没有专门的 IO 池）与 NSDate 纪元毫秒。
 */
expect val ioDispatcher: CoroutineDispatcher

expect fun nowMillis(): Long
