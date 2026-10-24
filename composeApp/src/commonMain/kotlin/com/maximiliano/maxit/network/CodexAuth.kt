@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)

package com.maximiliano.maxit.network

import com.maximiliano.maxit.data.AppSettings
import com.maximiliano.maxit.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.io.encoding.Base64
import kotlin.time.Clock

private const val CODEX_ISSUER = "https://auth.openai.com"
private const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val DEVICE_LIFETIME_MILLIS = 15 * 60 * 1000L
private const val REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L

@Serializable
class CodexTokens(
    val accessToken: String,
    val refreshToken: String = "",
    val accountId: String,
    val expiresAtMillis: Long = 0,
    @Transient internal val sessionGeneration: Long = 0,
) {
    override fun toString(): String = "CodexTokens(<redacted>)"
}

class CodexDeviceAuthorization(
    val userCode: String,
    val expiresAtMillis: Long = 0,
    internal val deviceAuthId: String = "",
    internal val intervalMillis: Long = 5_000,
    internal val instanceId: String = "",
    internal val generation: Long = 0,
    internal val owner: Any? = null,
) {
    val verificationUri: String = "$CODEX_ISSUER/codex/device"

    override fun toString(): String = "CodexDeviceAuthorization(<redacted>)"
}

class CodexAuthException(message: String) : Exception(message)

class CodexAuth(
    private val appSettings: AppSettings,
    private val client: HttpClient = httpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val guard = AtomicInt(0)
    private val instances = mutableMapOf<String, InstanceState>()

    private class InstanceState {
        val refreshMutex = Mutex()
        var generation = 0L
        var revision = 0L
        var authorization: CodexDeviceAuthorization? = null
        var completing = false
        var signedOut = false
    }

    // Sign-out is synchronous: its epoch change and storage removal must exclude token commits.
    private inline fun <T> locked(block: () -> T): T {
        while (!guard.compareAndSet(0, 1)) { }
        return try {
            block()
        } finally {
            guard.store(0)
        }
    }

    fun isSignedIn(instanceId: String): Boolean = locked { readTokens(instanceId) != null }

    fun sessionGeneration(instanceId: String): Long = locked { state(instanceId).generation }

    suspend fun beginSignIn(instanceId: String): CodexDeviceAuthorization = safe {
        currentCoroutineContext().ensureActive()
        val generation = locked {
            invalidate(instanceId)
            state(instanceId).generation
        }
        val expiresAt = clock() + DEVICE_LIFETIME_MILLIS
        val response = postJson("/api/accounts/deviceauth/usercode", mapOf("client_id" to CODEX_CLIENT_ID))
        requireSuccess(response, "device authorization")
        val body = responseJson(response)
        val authorization = CodexDeviceAuthorization(
            userCode = body.text("user_code") ?: body.text("usercode") ?: invalidResponse(),
            expiresAtMillis = expiresAt,
            deviceAuthId = body.text("device_auth_id") ?: invalidResponse(),
            intervalMillis = (body.number("interval")?.takeIf { it > 0 } ?: 5)
                .coerceAtMost(DEVICE_LIFETIME_MILLIS / 1000) * 1000,
            instanceId = instanceId,
            generation = generation,
            owner = this,
        )
        val context = currentCoroutineContext()
        locked {
            context.ensureActive()
            if (state(instanceId).generation != generation) replacedSignIn()
            state(instanceId).authorization = authorization
        }
        authorization
    }

    suspend fun completeSignIn(instanceId: String, authorization: CodexDeviceAuthorization): Unit = safe {
        locked {
            requireCurrent(instanceId, authorization)
            val state = state(instanceId)
            if (state.completing) throw CodexAuthException("This ChatGPT sign-in is already being completed. Wait, or start sign-in again.")
            state.completing = true
        }
        try {
            val remaining = remaining(authorization)
            withTimeoutOrNull(remaining) {
                var grant: JsonObject
                while (true) {
                    checkAuthorization(instanceId, authorization)
                    val response = postJson(
                        "/api/accounts/deviceauth/token",
                        mapOf("device_auth_id" to authorization.deviceAuthId, "user_code" to authorization.userCode),
                    )
                    checkAuthorization(instanceId, authorization)
                    if (response.status.isSuccess()) {
                        grant = responseJson(response)
                        break
                    }
                    if (response.status.value != 403 && response.status.value != 404) {
                        requireSuccess(response, "device authorization")
                    }
                    delay(minOf(authorization.intervalMillis, remaining(authorization)))
                }
                val response = postForm(
                    mapOf(
                        "grant_type" to "authorization_code",
                        "client_id" to CODEX_CLIENT_ID,
                        "code" to (grant.text("authorization_code") ?: invalidResponse()),
                        "code_verifier" to (grant.text("code_verifier") ?: invalidResponse()),
                        "redirect_uri" to "$CODEX_ISSUER/deviceauth/callback",
                    ),
                )
                checkAuthorization(instanceId, authorization)
                requireSuccess(response, "token exchange")
                val tokens = tokens(responseJson(response))
                val context = currentCoroutineContext()
                locked {
                    context.ensureActive()
                    requireCurrent(instanceId, authorization)
                    remaining(authorization)
                    saveTokens(instanceId, tokens)
                    context.ensureActive()
                }
            } ?: expiredSignIn()
        } catch (e: Exception) {
            locked {
                val state = state(instanceId)
                if (state.authorization === authorization && state.generation == authorization.generation && !state.signedOut) {
                    invalidate(instanceId)
                }
            }
            throw e
        } finally {
            locked {
                val state = state(instanceId)
                if (state.authorization === authorization) {
                    state.authorization = null
                    state.completing = false
                }
            }
        }
    }

    fun signOut(instanceId: String) = locked { invalidate(instanceId) }

    suspend fun credentials(instanceId: String, forceRefresh: Boolean = false): CodexTokens = safe {
        currentCoroutineContext().ensureActive()
        val (instance, generation, revision) = locked {
            val instance = state(instanceId)
            if (readTokens(instanceId) == null) throw OpenAICompatibleInvalidApiKeyException()
            Triple(instance, instance.generation, instance.revision)
        }
        instance.refreshMutex.withLock {
            val previous = locked {
                if (instance.generation != generation) throw OpenAICompatibleInvalidApiKeyException()
                val current = readTokens(instanceId) ?: throw OpenAICompatibleInvalidApiKeyException()
                // Reuse a refresh completed while this caller waited, including forced refreshes.
                if (instance.revision != revision ||
                    (!forceRefresh && current.expiresAtMillis - clock() > REFRESH_MARGIN_MILLIS)
                ) {
                    return@withLock current
                }
                current
            }

            val response = postForm(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to CODEX_CLIENT_ID,
                    "refresh_token" to previous.refreshToken,
                ),
            )
            currentCoroutineContext().ensureActive()
            if (!response.status.isSuccess()) {
                val error = try {
                    responseJson(response)
                } catch (_: CodexAuthException) {
                    null
                }
                val code = (
                    (error?.get("error") as? JsonObject)?.text("code")
                        ?: error?.text("error") ?: error?.text("code")
                    )?.lowercase()
                if (response.status.value == 401 || (response.status.value == 400 && code == "invalid_grant") || code in setOf(
                        "refresh_token_expired",
                        "refresh_token_reused",
                        "refresh_token_invalidated",
                    )
                ) {
                    locked { if (instance.generation == generation) invalidate(instanceId) }
                    throw OpenAICompatibleInvalidApiKeyException()
                }
                requireSuccess(response, "token refresh")
            }
            val refreshed = tokens(responseJson(response), previous)
            val context = currentCoroutineContext()
            locked {
                context.ensureActive()
                if (instance.generation != generation) throw OpenAICompatibleInvalidApiKeyException()
                if (refreshed.accountId != previous.accountId) {
                    invalidate(instanceId)
                    throw OpenAICompatibleInvalidApiKeyException()
                }
                saveTokens(instanceId, refreshed)
            }
            refreshed
        }
    }

    private fun state(instanceId: String): InstanceState = instances.getOrPut(instanceId) { InstanceState() }

    private fun invalidate(instanceId: String) {
        val state = state(instanceId)
        state.generation++
        state.revision++
        state.authorization = null
        state.completing = false
        state.signedOut = true
        try {
            appSettings.settings.remove(key(instanceId))
        } catch (_: Exception) {
            throw CodexAuthException("ChatGPT credentials could not be cleared securely. Sign in again before continuing.")
        }
    }

    private fun readTokens(instanceId: String): CodexTokens? = try {
        if (state(instanceId).signedOut) {
            null
        } else {
            appSettings.settings.getStringOrNull(key(instanceId))?.let { blob ->
                json.decodeFromString<CodexTokens>(blob).takeIf {
                    it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() && it.accountId.isNotBlank() && it.expiresAtMillis > 0
                }?.let { CodexTokens(it.accessToken, it.refreshToken, it.accountId, it.expiresAtMillis, state(instanceId).generation) }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun saveTokens(instanceId: String, tokens: CodexTokens) {
        try {
            appSettings.settings.putString(key(instanceId), json.encodeToString(tokens))
            state(instanceId).revision++
            state(instanceId).signedOut = false
        } catch (_: Exception) {
            throw CodexAuthException("ChatGPT credentials could not be saved securely. Try signing in again.")
        }
    }

    private fun key(instanceId: String): String = "instance_${instanceId}_codex_auth"

    private fun requireCurrent(instanceId: String, authorization: CodexDeviceAuthorization) {
        if (authorization.owner !== this || authorization.instanceId != instanceId ||
            state(instanceId).authorization !== authorization || state(instanceId).generation != authorization.generation
        ) {
            replacedSignIn()
        }
    }

    private suspend fun checkAuthorization(instanceId: String, authorization: CodexDeviceAuthorization) {
        currentCoroutineContext().ensureActive()
        locked { requireCurrent(instanceId, authorization) }
        remaining(authorization)
    }

    private fun remaining(authorization: CodexDeviceAuthorization): Long = (authorization.expiresAtMillis - clock()).takeIf { it > 0 } ?: expiredSignIn()

    private suspend fun postJson(path: String, fields: Map<String, String>): HttpResponse = client.post("$CODEX_ISSUER$path") {
        expectSuccess = false
        contentType(ContentType.Application.Json)
        setBody(JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString())
    }

    private suspend fun postForm(fields: Map<String, String>): HttpResponse = client.post("$CODEX_ISSUER/oauth/token") {
        expectSuccess = false
        setBody(FormDataContent(Parameters.build { fields.forEach { (key, value) -> append(key, value) } }))
    }

    private fun requireSuccess(response: HttpResponse, stage: String) {
        if (response.status.isSuccess()) return
        val status = response.status.value
        val message = when (status) {
            429 -> "ChatGPT $stage is rate limited (HTTP 429). Wait, then sign in again."
            401, 403 -> "ChatGPT denied $stage (HTTP $status). Start sign-in again."
            404 -> "ChatGPT $stage is unavailable (HTTP 404). Try browser sign-in in Codex instead."
            else -> "ChatGPT $stage failed (HTTP $status). Try signing in again later."
        }
        throw CodexAuthException(message)
    }

    private suspend fun responseJson(response: HttpResponse): JsonObject = try {
        json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: invalidResponse()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        invalidResponse()
    }

    private fun tokens(body: JsonObject, previous: CodexTokens? = null): CodexTokens {
        val access = body.text("access_token") ?: previous?.accessToken ?: invalidResponse()
        val refresh = body.text("refresh_token") ?: previous?.refreshToken ?: invalidResponse()
        val accountId = accountId(body.text("id_token")) ?: accountId(access) ?: previous?.accountId ?: invalidResponse()
        val now = clock()
        val expiresIn = body.number("expires_in")?.takeIf { it > 0 && it <= (Long.MAX_VALUE - now) / 1000 }
        val jwtExpiry = claims(access)?.number("exp")?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
        val expiresAt = when {
            expiresIn != null -> now + expiresIn * 1000
            body.text("access_token") == null && previous != null -> previous.expiresAtMillis
            jwtExpiry != null -> jwtExpiry * 1000
            else -> now + 3_600_000
        }
        return CodexTokens(access, refresh, accountId, expiresAt, previous?.sessionGeneration ?: 0)
    }

    private fun accountId(token: String?): String? {
        val claims = claims(token) ?: return null
        return claims.text("chatgpt_account_id")
            ?: (claims["https://api.openai.com/auth"] as? JsonObject)?.text("chatgpt_account_id")
            ?: ((claims["organizations"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("id")
    }

    private fun claims(token: String?): JsonObject? = try {
        val part = token?.split('.')?.takeIf { it.size == 3 }?.get(1)
        if (part == null) {
            null
        } else {
            val padded = part.padEnd((part.length + 3) / 4 * 4, '=')
            json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()) as? JsonObject
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }

    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun invalidResponse(): Nothing = throw CodexAuthException("ChatGPT returned an invalid sign-in response. Start sign-in again.")

    private fun expiredSignIn(): Nothing = throw CodexAuthException("ChatGPT device authorization expired after 15 minutes. Start sign-in again.")

    private fun replacedSignIn(): Nothing = throw CodexAuthException("This ChatGPT sign-in was cancelled or replaced. Start sign-in again.")

    private suspend fun <T> safe(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CodexAuthException) {
        throw e
    } catch (e: OpenAICompatibleInvalidApiKeyException) {
        throw e
    } catch (_: Exception) {
        throw CodexAuthException("Could not complete ChatGPT authentication. Check your connection and sign in again.")
    }
}
