package com.maximiliano.maxit.data

internal actual class ConversationLock {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
