@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.maximiliano.maxit.network

import com.maximiliano.maxit.data.AppSettings
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAuthConcurrencyTest {
    private class BlockingSettings(private val values: MapSettings = MapSettings()) : Settings by values {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)

        @Volatile var blockWrites = false

        override fun putString(key: String, value: String) {
            if (blockWrites && key == "instance_one_codex_auth") {
                writeStarted.countDown()
                check(releaseWrite.await(10, TimeUnit.SECONDS))
            }
            values.putString(key, value)
        }
    }

    @Test
    fun `logout and final persistence have one atomic boundary across real threads`() {
        val settings = BlockingSettings()
        settings.putString("instance_one_codex_auth", """{"accessToken":"old-access","refreshToken":"old-refresh","accountId":"account","expiresAtMillis":200000}""")
        val client = HttpClient(object : HttpClientEngineBase("codex-auth-concurrency-test") {
            override val config = HttpClientEngineConfig()
            override val dispatcher = Dispatchers.Unconfined
            override suspend fun execute(data: HttpRequestData): HttpResponseData = HttpResponseData(
                statusCode = HttpStatusCode.OK,
                requestTime = GMTDate(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""".encodeToByteArray()),
                callContext = callContext(),
            )
        })
        val auth = CodexAuth(AppSettings(settings), client, clock = { 0L })
        val executor = Executors.newFixedThreadPool(2)
        val logoutStarted = CountDownLatch(1)
        val logoutFinished = CountDownLatch(1)
        settings.blockWrites = true
        try {
            val refresh = executor.submit<CodexTokens> { runBlocking { auth.credentials("one") } }
            assertTrue(settings.writeStarted.await(5, TimeUnit.SECONDS))
            val logout = executor.submit {
                logoutStarted.countDown()
                auth.signOut("one")
                logoutFinished.countDown()
            }
            assertTrue(logoutStarted.await(5, TimeUnit.SECONDS))
            // The token write is paused before touching storage; logout must not pass it and lose.
            assertFalse(logoutFinished.await(100, TimeUnit.MILLISECONDS))
            settings.releaseWrite.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            logout.get(5, TimeUnit.SECONDS)
            assertFalse(auth.isSignedIn("one"))
            assertNull(settings.getStringOrNull("instance_one_codex_auth"))
        } finally {
            settings.releaseWrite.countDown()
            executor.shutdownNow()
            client.close()
        }
    }
}
