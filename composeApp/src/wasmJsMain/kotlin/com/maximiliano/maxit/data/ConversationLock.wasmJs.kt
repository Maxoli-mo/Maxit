package com.maximiliano.maxit.data

internal actual class ConversationLock {
    actual fun <T> locked(block: () -> T): T = block()
}
