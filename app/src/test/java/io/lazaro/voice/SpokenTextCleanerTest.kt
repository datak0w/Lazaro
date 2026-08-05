package io.lazaro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
