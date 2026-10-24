package com.maximiliano.maxit.inference

/** A failed close remains a cleanup failure; a failed replacement never re-owns a closed handle. */
internal class NativeConversationOwner<T>(initial: T, private val closeHandle: (T) -> Unit) {
    var value: T? = initial
        private set
    private var closeFailure: Throwable? = null

    fun close() {
        closeFailure?.let { throw it }
        val closing = value ?: return
        value = null
        try {
            closeHandle(closing)
        } catch (failure: Throwable) {
            closeFailure = failure
            throw failure
        }
    }

    fun replace(create: () -> T): T {
        close()
        return create().also { value = it }
    }
}
