package com.andaxin.core.concurrency

/** 三平台互斥锁：JVM 系用 ReentrantLock，iOS 用 NSLock。synchronized 是 JVM 专属，共享层用它替代。 */
expect class Lock() {
    fun <T> withLock(block: () -> T): T
}
