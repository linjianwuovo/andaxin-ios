package com.andaxin.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun nowMillis(): Long = System.currentTimeMillis()
