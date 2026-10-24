package com.andaxin.core.concurrency

import platform.Foundation.NSLock

actual class Lock {
    private val inner = NSLock()

    actual fun <T> withLock(block: () -> T): T {
        inner.lock()
        try {
            return block()
        } finally {
            inner.unlock()
        }
    }
}
