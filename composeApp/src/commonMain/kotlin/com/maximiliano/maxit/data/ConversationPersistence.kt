package com.maximiliano.maxit.data

import app.cash.sqldelight.db.SqlDriver
import com.maximiliano.maxit.TerminalLine
import com.maximiliano.maxit.db.KaiDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal val ConversationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private const val MAX_MESSAGE_JSON_BYTES = 1_000_000L
private const val PAYLOAD_CHUNK_CHARS = 128_000

private fun payloadChunks(value: String): List<String> = buildList {
    var start = 0
    while (start < value.length) {
        var end = minOf(start + PAYLOAD_CHUNK_CHARS, value.length)
        // SQLite encodes each chunk separately; never split a UTF-16 surrogate pair.
        if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        add(value.substring(start, end))
        start = end
    }
}

internal fun requireUniqueConversationIds(conversations: List<Conversation>) {
    val ids = mutableSetOf<String>()
    require(conversations.all { ids.add(it.id) }) { "Duplicate conversation IDs; the original data has been preserved." }
}

interface ConversationPersistence {
    fun loadAll(): List<Conversation>
    fun replaceAll(conversations: List<Conversation>)
    fun save(conversation: Conversation, snapshot: List<Conversation>)
    fun delete(id: String, snapshot: List<Conversation>)
    fun saveShellTranscript(conversation: Conversation, snapshot: List<Conversation>)
}

expect fun createConversationSqlDriver(): SqlDriver?

fun createConversationPersistence(appSettings: AppSettings): ConversationPersistence {
    val driver = createConversationSqlDriver()
    return if (driver != null) SqlConversationPersistence(KaiDatabase(driver), appSettings) else SettingsConversationPersistence(appSettings)
}

class SqlConversationPersistence(
    private val database: KaiDatabase,
    private val appSettings: AppSettings,
) : ConversationPersistence {
    private val queries get() = database.conversationQueries
    private val savedMessages = mutableMapOf<String, List<Conversation.Message>>()

    override fun loadAll(): List<Conversation> {
        importPendingJson()
        val messages = queries.selectAllMessages(MAX_MESSAGE_JSON_BYTES).executeAsList().groupBy({ it.conversationId }) { row ->
            val header = runCatching { ConversationJson.parseToJsonElement(row.messageJson) as? JsonObject }.getOrNull()
            val chunks = header?.get("payloadChunks")?.jsonPrimitive?.intOrNull
            val legacyLength = header?.get("legacyLength")?.jsonPrimitive?.intOrNull
            val json = when {
                chunks != null -> {
                    val parts = queries.selectPayloadChunks(row.conversationId, row.orderIndex).executeAsList()
                    check(parts.size == chunks) { "Stored conversation payload is incomplete; the original data has been preserved." }
                    parts.joinToString("")
                }

                legacyLength != null -> buildString {
                    for (start in 0 until legacyLength step PAYLOAD_CHUNK_CHARS) {
                        append(queries.selectLegacyMessageChunk((start + 1).toString(), PAYLOAD_CHUNK_CHARS.toString(), row.conversationId, row.orderIndex).executeAsOne())
                    }
                }

                else -> row.messageJson
            }
            try {
                ConversationJson.decodeFromString<Conversation.Message>(json)
            } catch (e: Exception) {
                throw IllegalStateException("A stored conversation message could not be read. The original data has been preserved.", e)
            }
        }
        return queries.selectAllConversations().executeAsList().map { row ->
            Conversation(
                id = row.id,
                messages = messages[row.id].orEmpty(),
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                title = row.title,
                type = row.type,
                shellTranscript = ConversationJson.decodeFromString<List<TerminalLine>>(row.shellTranscriptJson),
            ).also { savedMessages[it.id] = it.messages }
        }
    }

    override fun replaceAll(conversations: List<Conversation>) {
        requireUniqueConversationIds(conversations)
        database.transaction {
            queries.deleteAllPayloadChunks()
            queries.deleteAllMessages()
            queries.deleteAllConversations()
            conversations.forEach { write(it, emptyList()) }
        }
        savedMessages.clear()
        conversations.forEach { savedMessages[it.id] = it.messages }
    }

    override fun save(conversation: Conversation, snapshot: List<Conversation>) {
        database.transaction { write(conversation, savedMessages[conversation.id].orEmpty()) }
        savedMessages[conversation.id] = conversation.messages
    }

    override fun delete(id: String, snapshot: List<Conversation>) {
        database.transaction {
            queries.deletePayloadChunks(id, 0)
            queries.deleteMessages(id)
            queries.deleteConversation(id)
        }
        savedMessages.remove(id)
    }

    override fun saveShellTranscript(conversation: Conversation, snapshot: List<Conversation>) {
        queries.updateShellTranscript(ConversationJson.encodeToString(conversation.shellTranscript), conversation.id)
    }

    private fun importPendingJson() {
        val pending = appSettings.getConversationsJson() ?: return
        // Blank is an explicit clear from settings import. Malformed nonblank data must never clear the database.
        val conversations = if (pending.isBlank()) emptyList() else ConversationJson.decodeFromString<ConversationsData>(pending).conversations
        replaceAll(conversations)
        appSettings.removeConversationsJson()
    }

    private fun write(conversation: Conversation, previous: List<Conversation.Message>) {
        queries.upsertConversation(conversation.id, conversation.title, conversation.type, conversation.createdAt, conversation.updatedAt, ConversationJson.encodeToString(conversation.shellTranscript))
        conversation.messages.forEachIndexed { index, message ->
            if (previous.getOrNull(index) != message) {
                queries.deleteMessagePayload(conversation.id, index.toLong())
                val json = ConversationJson.encodeToString(message)
                if (json.length.toLong() * 3 <= MAX_MESSAGE_JSON_BYTES) {
                    queries.insertMessage(conversation.id, index.toLong(), json)
                } else {
                    val chunks = payloadChunks(json)
                    chunks.forEachIndexed { chunkIndex, content ->
                        queries.insertPayloadChunk(conversation.id, index.toLong(), chunkIndex.toLong(), content)
                    }
                    queries.insertMessage(conversation.id, index.toLong(), """{"payloadChunks":${chunks.size}}""")
                }
            }
        }
        queries.deleteMessagesFrom(conversation.id, conversation.messages.size.toLong())
        queries.deletePayloadChunks(conversation.id, conversation.messages.size.toLong())
    }
}

/** Immutable message blobs + an atomic manifest swap; quota failure leaves the old manifest readable. */
@OptIn(ExperimentalUuidApi::class)
class SettingsConversationPersistence(private val appSettings: AppSettings) : ConversationPersistence {
    @Serializable
    private data class Entry(val id: String, val key: String)

    @Serializable
    private data class Envelope(val conversation: Conversation, val messageKeys: List<String>)

    private val settings get() = appSettings.settings
    private val indexKey = "conversation_store_v3"
    private val loaded = mutableMapOf<String, Pair<Conversation, Envelope>>()

    private fun index(): List<Entry> = settings.getStringOrNull(indexKey)?.let { ConversationJson.decodeFromString(it) } ?: emptyList()

    override fun loadAll(): List<Conversation> {
        appSettings.getConversationsJson()?.let { pending ->
            val conversations = if (pending.isBlank()) emptyList() else ConversationJson.decodeFromString<ConversationsData>(pending).conversations
            replaceAll(conversations)
            appSettings.removeConversationsJson()
        }
        val entries = index()
        loaded.clear()
        return entries.map { entry ->
            val envelope = ConversationJson.decodeFromString<Envelope>((settings.getStringOrNull(entry.key) ?: error("Stored conversation snapshot is missing")))
            val conversation = envelope.conversation.copy(messages = envelope.messageKeys.map { ConversationJson.decodeFromString<Conversation.Message>((settings.getStringOrNull(it) ?: error("Stored conversation message is missing"))) })
            loaded[conversation.id] = conversation to envelope
            conversation
        }.also { collectGarbage() }
    }

    private fun collectGarbage() {
        val reachable = loaded.values.flatMap { it.second.messageKeys }.toSet() + index().map { it.key }
        settings.keys.filter { (it.startsWith("conversation_message_") || it.startsWith("conversation_snapshot_")) && it !in reachable }.forEach { runCatching { settings.remove(it) } }
    }

    override fun replaceAll(conversations: List<Conversation>) {
        requireUniqueConversationIds(conversations)
        val stagedKeys = mutableListOf<String>()
        val prepared = try {
            val prepared = conversations.map { prepare(it, stagedKeys) }
            settings.putString(indexKey, ConversationJson.encodeToString(prepared.map { it.first }))
            prepared
        } catch (e: Throwable) {
            stagedKeys.forEach { runCatching { settings.remove(it) } }
            throw e
        }
        loaded.clear()
        prepared.forEachIndexed { index, (_, envelope) -> loaded[conversations[index].id] = conversations[index] to envelope }
        // Publication has succeeded; a cleanup failure must not roll back committed blobs.
        runCatching { collectGarbage() }
    }

    override fun save(conversation: Conversation, snapshot: List<Conversation>) {
        val old = index()
        val previous = loaded[conversation.id]?.second
        val stagedKeys = mutableListOf<String>()
        val (_, envelope) = try {
            val prepared = prepare(conversation, stagedKeys)
            val next = if (old.any { it.id == conversation.id }) old.map { if (it.id == conversation.id) prepared.first else it } else old + prepared.first
            settings.putString(indexKey, ConversationJson.encodeToString(next))
            prepared
        } catch (e: Throwable) {
            stagedKeys.forEach { runCatching { settings.remove(it) } }
            throw e
        }
        loaded[conversation.id] = conversation to envelope
        // Reclaim changed blobs only after the manifest commits; failed writes retain the old version.
        old.filter { it.id == conversation.id }.forEach { runCatching { settings.remove(it.key) } }
        val retainedKeys = envelope.messageKeys.toSet()
        previous?.messageKeys?.filter { it !in retainedKeys }?.forEach { runCatching { settings.remove(it) } }
    }

    override fun delete(id: String, snapshot: List<Conversation>) {
        val old = index()
        settings.putString(indexKey, ConversationJson.encodeToString(old.filterNot { it.id == id }))
        val envelope = loaded.remove(id)?.second
        old.filter { it.id == id }.forEach { runCatching { settings.remove(it.key) } }
        envelope?.messageKeys?.forEach { runCatching { settings.remove(it) } }
    }

    override fun saveShellTranscript(conversation: Conversation, snapshot: List<Conversation>) = save(conversation, snapshot)

    private fun prepare(conversation: Conversation, stagedKeys: MutableList<String>): Pair<Entry, Envelope> {
        val previous = loaded[conversation.id]
        val keys = conversation.messages.mapIndexed { index, message ->
            if (previous?.first?.messages?.getOrNull(index) == message) {
                previous.second.messageKeys[index]
            } else {
                val key = "conversation_message_${Uuid.random()}"
                stagedKeys.add(key)
                settings.putString(key, ConversationJson.encodeToString(message))
                key
            }
        }
        val envelope = Envelope(conversation.copy(messages = emptyList()), keys)
        val key = "conversation_snapshot_${Uuid.random()}"
        stagedKeys.add(key)
        settings.putString(key, ConversationJson.encodeToString(envelope))
        return Entry(conversation.id, key) to envelope
    }
}
