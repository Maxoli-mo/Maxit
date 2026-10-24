package com.maximiliano.maxit.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalStreamTextPhaseTest {
    @Test
    fun `completion preserves whitespace across tool phases`() {
        val phases = listOf(listOf("  Checking.", "\n", "\n"), listOf("Result", "\n  "))
        val streamed = StringBuilder()
        val completed = phases.joinToString("") { chunks ->
            val filter = LocalStreamText()
            chunks.forEach { streamed.append(filter.append(it)) }
            filter.answer()
        }
        assertEquals("  Checking.\n\nResult\n  ", completed)
        assertEquals(streamed.toString(), completed)
    }

    @Test
    fun `every thinking tag split produces identical streamed and completed text`() {
        val source = "\nBefore <think>private\nreasoning</think> after\n\n"
        for (split in 0..source.length) {
            val filter = LocalStreamText()
            val streamed = filter.append(source.take(split)) + filter.append(source.drop(split))
            assertEquals("\nBefore  after\n\n", streamed, "split=$split")
            assertEquals(streamed, filter.answer(), "split=$split")
        }
    }

    @Test
    fun `unfinished thinking markers never appear only on completion`() {
        for (suffix in listOf("<", "<t", "<thin", "<think>", "<think>unfinished")) {
            val filter = LocalStreamText()
            val streamed = ("Visible\n\n" + suffix).map { filter.append(it.toString()) }.joinToString("")
            assertEquals("Visible\n\n", streamed)
            assertEquals(streamed, filter.answer())
        }
    }
}
