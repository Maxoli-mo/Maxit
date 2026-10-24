@file:OptIn(io.ktor.utils.io.InternalAPI::class, kotlin.io.encoding.ExperimentalEncodingApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAuthTest {
    private data class Reply(val body: String, val status: Int = 200)

    private class Fixture(now: () -> Long) {
        val settings = MapSettings()
        val appSettings = AppSettings(settings)
        val requests = mutableListOf<HttpRequestData>()
        var respond: suspend (HttpRequestData) -> Reply = { request ->
            when (request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" -> Reply("""{"device_auth_id":"device-id","user_code":"ABCD-EFGH","interval":"2"}""")
                "/api/accounts/deviceauth/token" -> Reply("""{"authorization_code":"one+time/code","code_verifier":"verifier+value/","code_challenge":"unused"}""")
                else -> Reply(tokenBody())
            }
        }
        val client = HttpClient(object : HttpClientEngineBase("codex-auth-test") {
            override val config = HttpClientEngineConfig()
            override val dispatcher = Dispatchers.Unconfined
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                requests += data
                val reply = respond(data)
                return HttpResponseData(
                    statusCode = HttpStatusCode.fromValue(reply.status),
                    requestTime = GMTDate(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    version = HttpProtocolVersion.HTTP_1_1,
                    body = ByteReadChannel(reply.body.encodeToByteArray()),
                    callContext = callContext(),
                )
            }
        })
        val auth = CodexAuth(appSettings, client, clock = now)

        fun store(instance: String = "one", expiresAt: Long = 200_000, access: String = "old-access") {
            settings.putString(
                "instance_${instance}_codex_auth",
                Json.encodeToString(CodexTokens(access, "old-refresh", "account", expiresAt)),
            )
        }

        fun saved(instance: String = "one"): CodexTokens? = settings.getStringOrNull("instance_${instance}_codex_auth")
            ?.let { Json.decodeFromString<CodexTokens>(it) }

        fun close() = client.close()
    }

    @Test
    fun `device flow uses account endpoints and one form encoded authorization code exchange`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        try {
            val authorization = f.auth.beginSignIn("one")
            assertEquals("ABCD-EFGH", authorization.userCode)
            assertEquals("https://auth.openai.com/codex/device", authorization.verificationUri)
            assertEquals(900_000L, authorization.expiresAtMillis)
            f.auth.completeSignIn("one", authorization)

            assertEquals(
                listOf(
                    "https://auth.openai.com/api/accounts/deviceauth/usercode",
                    "https://auth.openai.com/api/accounts/deviceauth/token",
                    "https://auth.openai.com/oauth/token",
                ),
                f.requests.map { it.url.toString() },
            )
            assertEquals(mapOf("client_id" to "app_EMoamEEZ73f0CkXaXp7hrann"), f.requests[0].jsonFields())
            assertEquals(mapOf("device_auth_id" to "device-id", "user_code" to "ABCD-EFGH"), f.requests[1].jsonFields())
            assertTrue(f.requests[0].body.contentType.toString().startsWith("application/json"))
            val form = parseQueryString(f.requests[2].bodyText())
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", form["client_id"])
            assertEquals("https://auth.openai.com/deviceauth/callback", form["redirect_uri"])
            assertEquals("one+time/code", form["code"])
            assertEquals("verifier+value/", form["code_verifier"])
            assertNull(form["code_challenge"])
            assertNull(form["client_secret"])
            assertTrue(f.requests[2].body.contentType.toString().startsWith("application/x-www-form-urlencoded"))
            assertTrue(f.requests.all { it.headers[HttpHeaders.Authorization] == null })
            assertEquals(setOf("instance_one_codex_auth"), f.settings.keys)
            val tokens = assertNotNull(f.saved())
            assertEquals("new-access", tokens.accessToken)
            assertEquals("new-refresh", tokens.refreshToken)
            assertEquals("account", tokens.accountId)
            assertEquals(3_600_000L, tokens.expiresAtMillis)
            assertTrue(f.auth.isSignedIn("one"))
            assertFalse(f.auth.isSignedIn("two"))
            assertFalse(tokens.toString().contains(tokens.accessToken))
            assertFalse(tokens.toString().contains(tokens.refreshToken))
            assertFalse(authorization.toString().contains(authorization.userCode))
        } finally {
            f.close()
        }
    }

    @Test
    fun `403 and 404 are pending and respect the server polling interval`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        var polls = 0
        f.respond = { request ->
            if (request.url.encodedPath.endsWith("deviceauth/token") && ++polls <= 2) {
                Reply("not an OAuth JSON response", if (polls == 1) 403 else 404)
            } else {
                normal(request)
            }
        }
        try {
            f.auth.completeSignIn("one", f.auth.beginSignIn("one"))
            assertEquals(3, polls)
            assertEquals(4_000L, testScheduler.currentTime)
        } finally {
            f.close()
        }
    }

    @Test
    fun `invalid polling intervals default safely and huge intervals are bounded`() = runTest {
        for ((interval, expected) in listOf(
            "" to 5_000L,
            ",\"interval\":\"oops\"" to 5_000L,
            ",\"interval\":0" to 5_000L,
            ",\"interval\":-1" to 5_000L,
            ",\"interval\":3" to 3_000L,
            ",\"interval\":\"9223372036854775807\"" to 900_000L,
        )) {
            val f = Fixture { testScheduler.currentTime }
            f.respond = { Reply("""{"device_auth_id":"device","usercode":"CODE"$interval}""") }
            try {
                assertEquals(expected, f.auth.beginSignIn("one").intervalMillis)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `pending authorization stops at fifteen minutes without exchanging a code`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        f.respond = { request ->
            if (request.url.encodedPath.endsWith("usercode")) {
                Reply("""{"device_auth_id":"device","user_code":"CODE","interval":"60"}""")
            } else {
                Reply("pending", 403)
            }
        }
        try {
            val error = assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", f.auth.beginSignIn("one")) }
            assertContains(error.message.orEmpty(), "expired")
            assertEquals(900_000L, testScheduler.currentTime)
            assertEquals(15, f.requests.count { it.url.encodedPath.endsWith("deviceauth/token") })
            assertFalse(f.requests.any { it.url.encodedPath == "/oauth/token" })
            assertFalse(f.auth.isSignedIn("one"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `hung polling request is bounded by authorization expiry`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        f.respond = { if (it.url.encodedPath.endsWith("deviceauth/token")) awaitCancellation() else normal(it) }
        try {
            assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", f.auth.beginSignIn("one")) }
            assertEquals(900_000L, testScheduler.currentTime)
            assertNull(f.saved())
        } finally {
            f.close()
        }
    }

    @Test
    fun `JWT ID claims win then access claims and expiration are used as fallbacks`() = runTest {
        val access = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"access-account"},"exp":7200}""")
        for ((id, expected) in listOf(
            jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"id-account"}}""") to "id-account",
            jwt("""{"chatgpt_account_id":"flat-account"}""") to "flat-account",
            jwt("""{"organizations":[{"id":"org-account"}]}""") to "org-account",
            "malformed.jwt" to "access-account",
            jwt("""{"unrelated":true}""") to "access-account",
        )) {
            val f = Fixture { testScheduler.currentTime }
            val normal = f.respond
            f.respond = { if (it.url.encodedPath == "/oauth/token") Reply(tokenBody(access = access, id = id, expiresIn = null)) else normal(it) }
            try {
                f.auth.completeSignIn("one", f.auth.beginSignIn("one"))
                assertEquals(expected, f.saved()?.accountId)
                assertEquals(7_200_000L, f.saved()?.expiresAtMillis)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `missing credentials invalid JWT and malformed token responses fail without exposing bodies`() = runTest {
        for (body in listOf("{\"access_token\":\"secret\"}", tokenBody(id = "malformed"), "not JSON secret")) {
            val f = Fixture { testScheduler.currentTime }
            val normal = f.respond
            f.respond = { if (it.url.encodedPath == "/oauth/token") Reply(body) else normal(it) }
            try {
                val error = assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", f.auth.beginSignIn("one")) }
                // Coroutine stack recovery can chain copies of the already-sanitized exception.
                generateSequence<Throwable>(error) { it.cause }.forEach {
                    assertTrue(it is CodexAuthException)
                    assertFalse(it.message.orEmpty().contains("secret"))
                }
                assertNull(f.saved())
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `credentials refresh exactly at the five minute margin and preserve omitted refresh fields`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        f.store(expiresAt = 300_001)
        f.respond = { Reply("""{"access_token":"rotated-access","expires_in":3600}""") }
        try {
            assertEquals("old-access", f.auth.credentials("one").accessToken)
            assertTrue(f.requests.isEmpty())
            testScheduler.advanceTimeBy(1)
            val refreshed = f.auth.credentials("one")
            assertEquals("rotated-access", refreshed.accessToken)
            assertEquals("old-refresh", refreshed.refreshToken)
            assertEquals("account", refreshed.accountId)
            assertEquals(3_600_001L, refreshed.expiresAtMillis)
            val form = parseQueryString(f.requests.single().bodyText())
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("old-refresh", form["refresh_token"])
            assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", form["client_id"])
            assertEquals("rotated-access", f.saved()?.accessToken)
        } finally {
            f.close()
        }
    }

    @Test
    fun `refresh rotation without new access token does not extend the old access lifetime`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val access = jwt("""{"exp":7200}""")
        f.store(access = access)
        f.respond = { Reply("""{"refresh_token":"rotated-refresh"}""") }
        try {
            val refreshed = f.auth.credentials("one")
            assertEquals(access, refreshed.accessToken)
            assertEquals("rotated-refresh", refreshed.refreshToken)
            assertEquals(200_000L, refreshed.expiresAtMillis)
            assertEquals("rotated-refresh", f.saved()?.refreshToken)
        } finally {
            f.close()
        }
    }

    @Test
    fun `parallel forced refreshes coalesce and persist rotated credentials`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        f.store(expiresAt = 8_000_000)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.respond = {
            started.complete(Unit)
            release.await()
            Reply(tokenBody())
        }
        try {
            val pending = List(8) { async { f.auth.credentials("one", forceRefresh = true) } }
            started.await()
            runCurrent()
            assertEquals(1, f.requests.size)
            release.complete(Unit)
            assertTrue(pending.awaitAll().all { it.accessToken == "new-access" && it.refreshToken == "new-refresh" })
            assertEquals(1, f.requests.size)
            assertEquals("new-refresh", f.saved()?.refreshToken)
        } finally {
            f.close()
        }
    }

    @Test
    fun `network refresh in one instance never holds the other instance state lock`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        f.store("one")
        f.store("two")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        f.respond = {
            if (++calls == 1) {
                started.complete(Unit)
                release.await()
            }
            Reply(tokenBody())
        }
        try {
            val first = async { runCatching { f.auth.credentials("one") } }
            started.await()
            assertEquals("new-access", f.auth.credentials("two").accessToken)
            f.auth.signOut("one")
            release.complete(Unit)
            assertTrue(first.await().exceptionOrNull() is OpenAICompatibleInvalidApiKeyException)
            assertNull(f.saved("one"))
            assertTrue(f.auth.isSignedIn("two"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `permanent refresh failures require sign in and clear stored credentials`() = runTest {
        for ((body, status) in listOf(
            """{"error":"invalid_grant","error_description":"old-refresh secret"}""" to 400,
            """{"error":{"code":"refresh_token_reused"}}""" to 400,
            """{"error":{"code":"refresh_token_expired"}}""" to 400,
            """{"error":{"code":"refresh_token_invalidated"}}""" to 403,
            "not JSON old-refresh secret" to 401,
        )) {
            val f = Fixture { testScheduler.currentTime }
            f.store()
            f.respond = { Reply(body, status) }
            try {
                assertFailsWith<OpenAICompatibleInvalidApiKeyException> { f.auth.credentials("one") }
                assertFalse(f.auth.isSignedIn("one"))
                assertNull(f.saved())
                assertEquals(1, f.requests.size)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `rate limits and transient refresh errors are safe and retain credentials`() = runTest {
        for (status in listOf(429, 500)) {
            val f = Fixture { testScheduler.currentTime }
            f.store()
            f.respond = { Reply("""{"error":"secret old-refresh new-access"}""", status) }
            try {
                val error = assertFailsWith<CodexAuthException> { f.auth.credentials("one") }
                assertContains(error.message.orEmpty(), status.toString())
                assertContains(error.message.orEmpty(), "sign")
                assertFalse(error.message.orEmpty().contains("old-refresh"))
                assertFalse(error.message.orEmpty().contains("secret"))
                assertNull(error.cause)
                assertEquals("old-refresh", f.saved()?.refreshToken)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `challenge is bound to the manager instance generation and one completion`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        try {
            val old = f.auth.beginSignIn("one")
            val other = CodexAuth(f.appSettings, f.client, clock = { testScheduler.currentTime })
            assertFailsWith<CodexAuthException> { f.auth.completeSignIn("two", old) }
            assertFailsWith<CodexAuthException> { other.completeSignIn("one", old) }
            assertEquals(1, f.requests.size)
            val current = f.auth.beginSignIn("one")
            assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", old) }
            f.auth.completeSignIn("one", current)
            val requests = f.requests.size
            assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", current) }
            assertEquals(requests, f.requests.size)
            assertTrue(f.auth.isSignedIn("one"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `sign out during authorization code exchange never restores tokens`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.respond = {
            if (it.url.encodedPath == "/oauth/token") {
                started.complete(Unit)
                release.await()
            }
            normal(it)
        }
        try {
            val authorization = f.auth.beginSignIn("one")
            val pending = async { runCatching { f.auth.completeSignIn("one", authorization) } }
            started.await()
            f.auth.signOut("one")
            release.complete(Unit)
            assertTrue(pending.await().exceptionOrNull() is CodexAuthException)
            assertNull(f.saved())
            assertFalse(f.auth.isSignedIn("one"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `old refresh response cannot overwrite or erase a newer login`() = runTest {
        for (status in listOf(200, 400)) {
            val f = Fixture { testScheduler.currentTime }
            f.store()
            val normal = f.respond
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.respond = { request ->
                if (request.url.encodedPath == "/oauth/token" && parseQueryString(request.bodyText())["grant_type"] == "refresh_token") {
                    started.complete(Unit)
                    release.await()
                    Reply(if (status == 200) tokenBody(access = "stale-access") else """{"error":"invalid_grant"}""", status)
                } else {
                    normal(request)
                }
            }
            try {
                val old = async { runCatching { f.auth.credentials("one") } }
                started.await()
                f.auth.completeSignIn("one", f.auth.beginSignIn("one"))
                release.complete(Unit)
                assertTrue(old.await().isFailure)
                assertEquals("new-access", f.saved()?.accessToken)
                assertTrue(f.auth.isSignedIn("one"))
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `cancelling polling invalidates the challenge and never exchanges a code`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        val started = CompletableDeferred<Unit>()
        f.respond = {
            if (it.url.encodedPath.endsWith("deviceauth/token")) {
                started.complete(Unit)
                awaitCancellation()
            } else {
                normal(it)
            }
        }
        try {
            val authorization = f.auth.beginSignIn("one")
            val pending = launch { f.auth.completeSignIn("one", authorization) }
            started.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", authorization) }
            assertFalse(f.requests.any { it.url.encodedPath == "/oauth/token" })
            assertNull(f.saved())
        } finally {
            f.close()
        }
    }

    @Test
    fun `cancelling refresh propagates cancellation without deleting saved credentials`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        f.store()
        val started = CompletableDeferred<Unit>()
        f.respond = {
            started.complete(Unit)
            awaitCancellation()
        }
        try {
            val pending = launch { f.auth.credentials("one") }
            started.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals("old-refresh", f.saved()?.refreshToken)
        } finally {
            f.close()
        }
    }

    @Test
    fun `ambiguous code exchange failure is never retried or exposed as a cause`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        f.respond = { if (it.url.encodedPath == "/oauth/token") error("one+time/code secret") else normal(it) }
        try {
            val error = assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", f.auth.beginSignIn("one")) }
            assertFalse(error.message.orEmpty().contains("secret"))
            assertNull(error.cause)
            assertEquals(1, f.requests.count { it.url.encodedPath == "/oauth/token" })
            assertNull(f.saved())
        } finally {
            f.close()
        }
    }

    @Test
    fun `persisted tokens restore across managers but corrupt storage never authorizes requests`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        try {
            f.store(expiresAt = 8_000_000)
            val restored = CodexAuth(f.appSettings, f.client, clock = { testScheduler.currentTime })
            assertTrue(restored.isSignedIn("one"))
            assertEquals("old-access", restored.credentials("one").accessToken)
            f.settings.putString("instance_one_codex_auth", "corrupt secret")
            assertFalse(restored.isSignedIn("one"))
            assertFailsWith<OpenAICompatibleInvalidApiKeyException> { restored.credentials("one") }
            assertTrue(f.requests.isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun `old completion cleanup cannot erase a replacement challenge`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var exchanges = 0
        f.respond = {
            if (it.url.encodedPath == "/oauth/token" && ++exchanges == 1) {
                started.complete(Unit)
                release.await()
            }
            normal(it)
        }
        try {
            val first = f.auth.beginSignIn("one")
            val old = async { runCatching { f.auth.completeSignIn("one", first) } }
            started.await()
            val replacement = f.auth.beginSignIn("one")
            release.complete(Unit)
            assertTrue(old.await().isFailure)
            f.auth.completeSignIn("one", replacement)
            assertEquals("new-access", f.saved()?.accessToken)
        } finally {
            f.close()
        }
    }

    @Test
    fun `expired challenges are rejected before polling`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        try {
            val authorization = f.auth.beginSignIn("one")
            testScheduler.advanceTimeBy(900_000)
            assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", authorization) }
            assertEquals(1, f.requests.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun `all device flow stages surface safe rate limit errors without retries`() = runTest {
        for (path in listOf("/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token", "/oauth/token")) {
            val f = Fixture { testScheduler.currentTime }
            val normal = f.respond
            f.respond = { if (it.url.encodedPath == path) Reply("secret server diagnostic", 429) else normal(it) }
            try {
                val error = assertFailsWith<CodexAuthException> { f.auth.completeSignIn("one", f.auth.beginSignIn("one")) }
                assertContains(error.message.orEmpty(), "429")
                assertContains(error.message.orEmpty(), "sign in")
                assertFalse(error.message.orEmpty().contains("secret"))
                assertEquals(1, f.requests.count { it.url.encodedPath == path })
                assertNull(f.saved())
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun `missing expiration falls back to an hour and account changes require fresh sign in`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        val normal = f.respond
        f.respond = { if (it.url.encodedPath == "/oauth/token") Reply(tokenBody(expiresIn = null)) else normal(it) }
        try {
            f.auth.completeSignIn("one", f.auth.beginSignIn("one"))
            assertEquals(3_600_000L, f.saved()?.expiresAtMillis)
            f.respond = { Reply(tokenBody(id = jwt("""{"chatgpt_account_id":"different-account"}"""))) }
            assertFailsWith<OpenAICompatibleInvalidApiKeyException> { f.auth.credentials("one", forceRefresh = true) }
            assertNull(f.saved())
        } finally {
            f.close()
        }
    }

    @Test
    fun `cancellation during the final token write rolls back only that sign in`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        lateinit var pending: Job
        val settings = object : Settings by f.settings {
            override fun putString(key: String, value: String) {
                f.settings.putString(key, value)
                if (key == "instance_one_codex_auth") pending.cancel()
            }
        }
        val auth = CodexAuth(AppSettings(settings), f.client, clock = { testScheduler.currentTime })
        try {
            val authorization = auth.beginSignIn("one")
            pending = launch(start = CoroutineStart.LAZY) { auth.completeSignIn("one", authorization) }
            pending.start()
            pending.join()
            assertTrue(pending.isCancelled)
            assertNull(f.saved())
            assertFalse(auth.isSignedIn("one"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `request generations survive refresh but change after logout and reauthentication`() = runTest {
        val f = Fixture { testScheduler.currentTime }
        try {
            f.store(expiresAt = 8_000_000)
            val before = f.auth.credentials("one")
            assertEquals(f.auth.sessionGeneration("one"), before.sessionGeneration)
            assertEquals(before.sessionGeneration, f.auth.credentials("one", forceRefresh = true).sessionGeneration)
            f.auth.signOut("one")
            f.auth.completeSignIn("one", f.auth.beginSignIn("one"))
            val after = f.auth.credentials("one")
            assertTrue(after.sessionGeneration > before.sessionGeneration)
            assertEquals(f.auth.sessionGeneration("one"), after.sessionGeneration)
            assertFalse(f.settings.getString("instance_one_codex_auth", "").contains("sessionGeneration"))
        } finally {
            f.close()
        }
    }

    private companion object {
        fun jwt(claims: String): String = "header.${Base64.UrlSafe.encode(claims.encodeToByteArray()).trimEnd('=')}.signature"

        fun tokenBody(
            access: String = "new-access",
            id: String? = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"account"}}"""),
            expiresIn: Long? = 3600,
        ): String = buildJsonObject {
            put("access_token", access)
            put("refresh_token", "new-refresh")
            if (id != null) put("id_token", id)
            if (expiresIn != null) put("expires_in", expiresIn)
        }.toString()

        fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

        fun HttpRequestData.jsonFields(): Map<String, String> = (Json.parseToJsonElement(bodyText()) as JsonObject)
            .mapValues { (_, value) -> (value as JsonPrimitive).content }
    }
}
