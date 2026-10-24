package com.andaxin.core.concurrency

import java.util.concurrent.locks.ReentrantLock

actual class Lock {
    private val inner = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T {
        inner.lock()
        try {
            return block()
        } finally {
            inner.unlock()
        }
    }
}
