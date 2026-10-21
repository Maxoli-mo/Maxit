// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.maximiliano.maxit.db.KaiDatabase
import com.maximiliano.maxit.getAppFilesDirectory
import java.io.File

actual fun createConversationSqlDriver(): SqlDriver? {
    val file = File(getAppFilesDirectory(), "conversations.db")
    file.parentFile?.mkdirs()
    return JdbcSqliteDriver(url = "jdbc:sqlite:${file.absolutePath}", schema = KaiDatabase.Schema)
}
