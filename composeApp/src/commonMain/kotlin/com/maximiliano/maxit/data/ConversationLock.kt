package com.maximiliano.maxit.data

/** Serializes synchronous storage commits and selection changes without spinning during disk I/O. */
internal expect class ConversationLock() {
    fun <T> locked(block: () -> T): T
}
