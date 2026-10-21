// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.inference

import com.maximiliano.maxit.getAppFilesDirectory
import java.io.File

actual fun getModelStorageDirectory(): String = getAppFilesDirectory() + "/litert_models"

actual fun getModelCacheDirectory(): String = System.getProperty("java.io.tmpdir") ?: getAppFilesDirectory()

actual fun getAvailableMemoryBytes(): Long = Long.MAX_VALUE // Desktop OSes manage memory via swap and cache eviction; skip the check

actual fun getTotalMemoryBytes(): Long = Long.MAX_VALUE

actual fun getAvailableDiskSpaceBytes(path: String): Long {
    var dir = File(path)
    while (!dir.exists()) {
        dir = dir.parentFile ?: return 0L
    }
    return dir.usableSpace
}

actual fun startDownloadNotificationService() {
    // No foreground service needed on desktop
}

actual fun stopDownloadNotificationService() {
    // No foreground service needed on desktop
}

actual fun updateDownloadNotificationProgress(percent: Int) {
    // No notification on desktop
}
