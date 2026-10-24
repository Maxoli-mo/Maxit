package com.maximiliano.maxit.data

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsConversationStagingTest {
    private fun conversation(id: String = "chat", vararg contents: String) = Conversation(
        id = id,
        messages = contents.mapIndexed { index, content -> Conversation.Message("$id-$index", "assistant", content) },
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun `duplicate replacement IDs are rejected without changing committed keys or cache`() {
        val backing = MapSettings()
        val app = AppSettings(backing)
        val persistence = SettingsConversationPersistence(app)
        val original = conversation("chat", "committed")
        persistence.replaceAll(listOf(original))
        val keys = backing.keys.toSet()

        assertFailsWith<IllegalArgumentException> {
            persistence.replaceAll(listOf(conversation("duplicate", "first"), conversation("duplicate", "second")))
        }
        assertEquals(keys, backing.keys)
        // Same instance must retain reusable committed blobs too.
        persistence.save(original.copy(title = "still owned"), emptyList())
        assertEquals(1, backing.keys.count { it.startsWith("conversation_message_") })
        assertEquals(listOf(original.copy(title = "still owned")), SettingsConversationPersistence(app).loadAll())
    }

    @Test
    fun `duplicate pending imports preserve the committed settings manifest and rejected backup`() {
        val backing = MapSettings()
        val app = AppSettings(backing)
        val persistence = SettingsConversationPersistence(app)
        val original = conversation("chat", "committed")
        persistence.replaceAll(listOf(original))
        val duplicate = conversation("duplicate", "first")
        val pending = ConversationJson.encodeToString(ConversationsData(conversations = listOf(duplicate, duplicate.copy(title = "second"))))
        app.setConversationsJson(pending)
        val keys = backing.keys.toSet()
        assertFailsWith<IllegalArgumentException> { persistence.loadAll() }
        assertEquals(keys, backing.keys)
        assertEquals(pending, app.getConversationsJson())
        app.removeConversationsJson()
        assertEquals(listOf(original), SettingsConversationPersistence(app).loadAll())
    }

    @Test
    fun `failed saves reclaim every staged key before reload and allow a smaller retry under quota`() {
        for (boundary in 1..4) {
            val backing = MapSettings()
            var writes = 0
            var failAt = Int.MAX_VALUE
            var maximumKeys = Int.MAX_VALUE
            val settings = object : Settings by backing {
                override fun putString(key: String, value: String) {
                    writes++
                    check(writes != failAt && (backing.hasKey(key) || backing.keys.size < maximumKeys)) { "injected quota" }
                    backing.putString(key, value)
                }
            }
            val app = AppSettings(settings)
            val persistence = SettingsConversationPersistence(app)
            val original = conversation("chat", "reused", "committed")
            persistence.save(original, emptyList())
            val keys = backing.keys.toSet()
            failAt = writes + boundary
            assertFailsWith<IllegalStateException> {
                persistence.save(conversation("chat", "reused", "changed", "new"), emptyList())
            }
            assertEquals(keys, backing.keys, "boundary $boundary must not leak before reload")
            failAt = Int.MAX_VALUE
            maximumKeys = keys.size + 2 // one changed blob and one envelope fit, no leaked keys do
            val retry = conversation("chat", "reused", "smaller")
            persistence.save(retry, emptyList())
            assertEquals(2, backing.keys.count { it.startsWith("conversation_message_") })
            assertEquals(1, backing.keys.count { it.startsWith("conversation_snapshot_") })
            assertEquals(listOf(retry), SettingsConversationPersistence(app).loadAll())
        }
    }

    @Test
    fun `failed replacements reclaim only new blobs across all prepared conversations`() {
        for (boundary in 1..5) {
            val backing = MapSettings()
            var writes = 0
            var failAt = Int.MAX_VALUE
            val settings = object : Settings by backing {
                override fun putString(key: String, value: String) {
                    writes++
                    check(writes != failAt) { "injected quota" }
                    backing.putString(key, value)
                }
            }
            val app = AppSettings(settings)
            val persistence = SettingsConversationPersistence(app)
            val original = conversation("chat", "reused", "committed")
            persistence.replaceAll(listOf(original))
            val keys = backing.keys.toSet()
            failAt = writes + boundary
            assertFailsWith<IllegalStateException> {
                persistence.replaceAll(listOf(conversation("chat", "reused", "changed"), conversation("new", "new")))
            }
            assertEquals(keys, backing.keys, "boundary $boundary must not leak before reload")
            failAt = Int.MAX_VALUE
            assertEquals(listOf(original), SettingsConversationPersistence(app).loadAll())
            persistence.save(original.copy(title = "retry"), emptyList())
            assertEquals(2, backing.keys.count { it.startsWith("conversation_message_") })
        }
    }

    @Test
    fun `non Exception staging failures clean new blobs and retain the original cause`() {
        for (replace in listOf(false, true)) {
            val backing = MapSettings()
            val failure = object : Throwable("foreign storage failure") {}
            var fail = false
            val settings = object : Settings by backing {
                override fun putString(key: String, value: String) {
                    if (fail && key.startsWith("conversation_snapshot_")) throw failure
                    backing.putString(key, value)
                }
            }
            val app = AppSettings(settings)
            val persistence = SettingsConversationPersistence(app)
            val original = conversation("chat", "reused", "old")
            persistence.replaceAll(listOf(original))
            val keys = backing.keys.toSet()
            fail = true
            val changed = conversation("chat", "reused", "new")
            assertSame(
                failure,
                assertFailsWith<Throwable> {
                    if (replace) persistence.replaceAll(listOf(changed)) else persistence.save(changed, emptyList())
                },
            )
            assertEquals(keys, backing.keys)
            fail = false
            assertEquals(listOf(original), SettingsConversationPersistence(app).loadAll())
        }
    }

    @Test
    fun `cleanup failures after publication do not remove committed or reused blobs`() {
        for (replace in listOf(false, true)) {
            val backing = MapSettings()
            var failCleanup = false
            val settings = object : Settings by backing {
                override fun remove(key: String) {
                    check(!failCleanup) { "injected cleanup failure" }
                    backing.remove(key)
                }
                override val keys: Set<String>
                    get() {
                        check(!failCleanup || !replace) { "injected enumeration failure" }
                        return backing.keys
                    }
            }
            val app = AppSettings(settings)
            val persistence = SettingsConversationPersistence(app)
            persistence.replaceAll(listOf(conversation("chat", "reused", "old")))
            failCleanup = true
            val committed = conversation("chat", "reused", "new")
            if (replace) persistence.replaceAll(listOf(committed)) else persistence.save(committed, emptyList())
            failCleanup = false
            assertEquals(listOf(committed), SettingsConversationPersistence(app).loadAll())
            assertEquals(2, backing.keys.count { it.startsWith("conversation_message_") })
        }
    }

    @Test
    fun `long history checkpoints reuse all unchanged blobs and reclaim only the changed tail`() {
        val backing = MapSettings()
        var messageWrites = 0
        val removedMessages = mutableListOf<String>()
        val settings = object : Settings by backing {
            override fun putString(key: String, value: String) {
                if (key.startsWith("conversation_message_")) messageWrites++
                backing.putString(key, value)
            }
            override fun remove(key: String) {
                if (key.startsWith("conversation_message_")) removedMessages.add(key)
                backing.remove(key)
            }
        }
        val app = AppSettings(settings)
        val persistence = SettingsConversationPersistence(app)
        val original = conversation("chat", *Array(4_000) { "message $it" })
        persistence.save(original, emptyList())
        val originalKeys = backing.keys.filter { it.startsWith("conversation_message_") }.toSet()
        repeat(3) { checkpoint ->
            val updated = original.copy(messages = original.messages.dropLast(1) + original.messages.last().copy(content = "tail $checkpoint"))
            persistence.save(updated, emptyList())
            assertEquals(4_001 + checkpoint, messageWrites)
            assertEquals(checkpoint + 1, removedMessages.size)
            assertEquals(4_000, backing.keys.count { it.startsWith("conversation_message_") })
            assertTrue(originalKeys.intersect(backing.keys).size >= 3_999)
        }
    }
}
