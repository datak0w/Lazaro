package io.lazaro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextCleanerTest {

    @Test
    fun stripsBoldAsterisks() {
        val out = SpokenTextCleaner.forSpeech("Hola **mundo** listo")
        assertEquals("Hola mundo listo", out)
        assertFalse(out.contains("*"))
    }

    @Test
    fun stripsLinksAndHeaders() {
        val out = SpokenTextCleaner.forSpeech("## Título\nVe a [casa](https://x.com) ya")
        assertFalse(out.contains("#"))
        assertFalse(out.contains("http"))
        assertEquals("Título Ve a casa ya", out)
    }

    @Test
    fun leftoverAsterisksRemoved() {
        val out = SpokenTextCleaner.forSpeech("**aviso** y *más*")
        assertEquals("aviso y más", out)
    }

    @Test
    fun doesNotTruncateLongReplies() {
        val long = (1..10).joinToString(" ") { "Esta es la frase número $it del mensaje largo." }
        val out = SpokenTextCleaner.forSpeech(long)
        assertTrue(out.length > 220)
        assertTrue(out.contains("frase número 10"))
    }

    @Test
    fun chunksLongTextOnSentenceBoundaries() {
        val long = (1..20).joinToString(" ") { "Frase número $it con algo de texto." }
        val chunks = SpokenTextCleaner.chunkForTts(long, maxChunkChars = 80)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 90 })
        assertTrue(chunks.joinToString(" ").contains("Frase número 20"))
    }
}
