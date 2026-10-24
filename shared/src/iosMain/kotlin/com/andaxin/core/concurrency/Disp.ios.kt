@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.andaxin.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.posix.time

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun nowMillis(): Long = time(null) * 1000L
