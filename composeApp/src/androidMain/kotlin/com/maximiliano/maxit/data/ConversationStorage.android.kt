// Modified for Maxit, 2026-10-20.
package com.maximiliano.maxit.data

import com.maximiliano.maxit.getAppFilesDirectory
import java.io.File

private const val LEGACY_FILE_NAME = "conversations.enc"

actual fun readLegacyConversationFile(): ByteArray? {
    val file = File(getAppFilesDirectory(), LEGACY_FILE_NAME)
    return if (file.exists()) file.readBytes() else null
}

actual fun deleteLegacyConversationFile() {
    val file = File(getAppFilesDirectory(), LEGACY_FILE_NAME)
    if (file.exists()) file.delete()
}
