package com.maximiliano.maxit

import com.maximiliano.maxit.data.TaskScheduler
import com.maximiliano.maxit.testutil.FakeDataRepository
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNotificationForegroundTest {
    @Test
    fun `response alerts follow the process foreground signal`() {
        val scheduler = TaskScheduler(FakeDataRepository(), enabled = false)
        startKoin { modules(module { single { scheduler } }) }
        try {
            scheduler.appInForeground = true
            assertFalse(shouldSendLlmNotification())
            scheduler.appInForeground = false
            assertTrue(shouldSendLlmNotification())
            scheduler.appInForeground = true
            assertFalse(shouldSendLlmNotification())
        } finally {
            stopKoin()
        }
    }
}
