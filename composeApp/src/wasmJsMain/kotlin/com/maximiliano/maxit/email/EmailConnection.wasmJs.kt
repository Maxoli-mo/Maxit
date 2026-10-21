// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.email

actual suspend fun createEmailConnection(host: String, port: Int, tls: Boolean): EmailConnection = throw UnsupportedOperationException("Email is not supported on this platform")
