package com.maximiliano.maxit.data

import platform.Foundation.NSRecursiveLock

internal actual class ConversationLock {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
