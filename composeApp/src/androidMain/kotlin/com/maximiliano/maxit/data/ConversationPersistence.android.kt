// Modified for Maxit, 2026-10-20.
package com.maximiliano.maxit.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.maximiliano.maxit.db.KaiDatabase
import org.koin.java.KoinJavaComponent.inject

actual fun createConversationSqlDriver(): SqlDriver? {
    val context: Context by inject(Context::class.java)
    return AndroidSqliteDriver(KaiDatabase.Schema, context, "conversations.db")
}
