// Modified for Maxit, 2026-10-20.
package com.maximiliano.maxit

import android.content.Context
import android.content.pm.ApplicationInfo
import org.koin.java.KoinJavaComponent.inject

actual val isDebugBuild: Boolean by lazy {
    val context: Context by inject(Context::class.java)
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
