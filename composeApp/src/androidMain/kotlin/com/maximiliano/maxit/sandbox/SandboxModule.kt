// Modified for Maxit, 2026-10-20.
package com.maximiliano.maxit.sandbox

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val sandboxModule = module {
    single<LinuxSandboxManager> { LinuxSandboxManager(androidContext(), get(), get()) }
}
