package com.andaxin.core.concurrency

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.uname

@OptIn(ExperimentalForeignApi::class)
actual fun deviceModel(): String = memScoped {
    val u = alloc<platform.posix.utsname>()
    if (uname(u.ptr) == 0) u.machine.toKString() else "iOS"
}
