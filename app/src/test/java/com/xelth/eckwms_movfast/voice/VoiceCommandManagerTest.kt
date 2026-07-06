package com.xelth.eckwms_movfast.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parseTripIntent — the spoken trip declaration ("я поехал в Карлсруэ") that
 * arms a pending trip intent (TRIP_PURPOSE.md §10). Pure string parsing.
 */
class VoiceCommandManagerTest {

    @Test
    fun `russian destination declaration parses`() {
        val p = VoiceCommandManager.parseTripIntent("я поехал в Карлсруэ")
        assertEquals("карлсруэ", p?.destination)
        assertFalse(p!!.clientNamed)
    }

    @Test
    fun `russian client declaration flags clientNamed`() {
        val p = VoiceCommandManager.parseTripIntent("я поехал к доктору Штайнеру")
        assertTrue(p != null && p.clientNamed)
        assertEquals("доктору штайнеру", p?.destination)
    }

    @Test
    fun `german nach declaration parses`() {
        val p = VoiceCommandManager.parseTripIntent("ich fahre nach Karlsruhe")
        assertEquals("karlsruhe", p?.destination)
        assertFalse(p!!.clientNamed)
    }

    @Test
    fun `german zu declaration flags clientNamed`() {
        val p = VoiceCommandManager.parseTripIntent("fahre zu Doktor Steiner")
        assertTrue(p != null && p.clientNamed)
    }

    @Test
    fun `title inside phrase flags clientNamed even with nach`() {
        val p = VoiceCommandManager.parseTripIntent("я еду на Herr Müller")
        assertTrue(p != null && p.clientNamed)
    }

    @Test
    fun `unrelated speech does not parse`() {
        assertNull(VoiceCommandManager.parseTripIntent("scan den karton bitte"))
        assertNull(VoiceCommandManager.parseTripIntent("fahrt beenden"))
        assertNull(VoiceCommandManager.parseTripIntent(""))
    }

    @Test
    fun `registry stop command still wins over intent parse order`() {
        // The caller checks the registry FIRST; "fahrt beenden" must be a
        // registry hit in trip mode and NOT a trip declaration.
        assertTrue(VoiceCommandManager.match("trip", "fahrt beenden") != null)
        assertNull(VoiceCommandManager.parseTripIntent("fahrt beenden"))
    }
}
