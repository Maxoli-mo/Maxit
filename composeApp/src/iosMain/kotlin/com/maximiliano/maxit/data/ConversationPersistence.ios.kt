// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.maximiliano.maxit.db.KaiDatabase

actual fun createConversationSqlDriver(): SqlDriver? = NativeSqliteDriver(KaiDatabase.Schema, "conversations.db")
