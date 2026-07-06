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
    fun `leading filler words do not break the parse`() {
        // STT loves prefixes — the opener is matched anywhere, not just at ^.
        assertEquals("эшборн", VoiceCommandManager.parseTripIntent("ну я поехал в Эшборн")?.destination)
        assertEquals("karlsruhe", VoiceCommandManager.parseTripIntent("okay ich fahre jetzt nach Karlsruhe")?.destination)
        assertEquals("эшборн", VoiceCommandManager.parseTripIntent("так мы едем в Эшборн")?.destination)
    }

    @Test
    fun `more verbs and prepositions parse`() {
        assertEquals("эшборн", VoiceCommandManager.parseTripIntent("выехал в Эшборн")?.destination)
        assertEquals("франкфурт", VoiceCommandManager.parseTripIntent("поедем во франкфурт")?.destination)
        assertEquals("mannheim", VoiceCommandManager.parseTripIntent("wir fahren nach Mannheim")?.destination)
    }

    @Test
    fun `registry stop command still wins over intent parse order`() {
        // The caller checks the registry FIRST; "fahrt beenden" must be a
        // registry hit in trip mode and NOT a trip declaration.
        assertTrue(VoiceCommandManager.match("trip", "fahrt beenden") != null)
        assertNull(VoiceCommandManager.parseTripIntent("fahrt beenden"))
    }
}
