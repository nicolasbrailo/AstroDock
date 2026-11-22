package com.nicobrailo.astrodock.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun hearsThePhrase() {
        assertTrue(WakeWord.heard("hey portal"))
        // The grammar's "[unk]" stands for whatever else was said
        assertTrue(WakeWord.heard("[unk] hey portal"))
        assertTrue(WakeWord.heard("hey portal [unk]"))
        assertTrue(WakeWord.heard("  Hey   Portal "))
    }

    @Test
    fun needsBothWordsInOrder() {
        assertFalse(WakeWord.heard(""))
        assertFalse(WakeWord.heard("[unk]"))
        assertFalse(WakeWord.heard("hey"))
        assertFalse(WakeWord.heard("portal"))
        assertFalse(WakeWord.heard("portal hey"))
        assertFalse(WakeWord.heard("hey [unk] portal"))
        assertFalse(WakeWord.heard("hey portals"))
    }

    @Test
    fun grammarIsTheJsonVoskWants() {
        assertEquals("""["hey portal", "[unk]"]""", WakeWord.GRAMMAR)
    }
}
