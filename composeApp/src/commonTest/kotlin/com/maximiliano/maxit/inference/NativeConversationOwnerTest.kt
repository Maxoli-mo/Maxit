package com.maximiliano.maxit.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class NativeConversationOwnerTest {
    @Test
    fun `replacement creation failure closes old handle once and permits later cleanup`() {
        val handle = Any()
        var closes = 0
        val owner = NativeConversationOwner(handle) { closes++ }
        assertFailsWith<IllegalStateException> {
            owner.replace { error("creation failed") }
        }
        assertNull(owner.value)
        owner.close() // The generation's finally cleanup acknowledges success, not double-close.
        assertEquals(1, closes)
        val next = Any()
        assertSame(next, owner.replace { next })
        owner.close()
        assertEquals(2, closes)
    }

    @Test
    fun `genuine close failure continues to fail cleanup and prevents replacement`() {
        val failure = IllegalStateException("native close failed")
        var closes = 0
        var creates = 0
        val owner = NativeConversationOwner(Any()) {
            closes++
            throw failure
        }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                owner.replace {
                    creates++
                    Any()
                }
            },
        )
        assertNull(owner.value)
        assertSame(failure, assertFailsWith<IllegalStateException> { owner.close() })
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                owner.replace {
                    creates++
                    Any()
                }
            },
        )
        assertEquals(1, closes)
        assertEquals(0, creates)
    }
}
