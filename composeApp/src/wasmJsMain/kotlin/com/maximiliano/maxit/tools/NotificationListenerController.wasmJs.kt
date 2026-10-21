// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.tools

actual class NotificationListenerController actual constructor() {
    actual fun isSupported(): Boolean = false
    actual fun isAccessGranted(): Boolean = false
    actual fun openAccessSettings() {}
}
