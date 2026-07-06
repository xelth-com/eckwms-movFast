package com.xelth.eckwms_movfast.voice

/**
 * Voice Commands — local intent registry (P1).
 *
 * A [VoiceCommand] maps spoken keywords/phrases to a grid [action] (the SAME
 * action string a button tap produces, so execution reuses the existing
 * dispatch) plus a human [description] used for console feedback and the
 * "available commands" hint.
 *
 * Matching is local and free (no network). Gemini fallback is P2.
 */
data class VoiceCommand(
    val patterns: List<String>,  // lowercase keywords/phrases (German)
    val action: String,          // grid action, dispatched like a button tap
    val description: String,     // shown in console + the available-commands list
)

object VoiceCommandManager {

    /**
     * mode key → commands. Mode keys match MainScreenViewModel.currentVoiceMode().
     * P1 seeds Trip mode; other modes register theirs later (empty for now →
     * console shows "keine Sprachbefehle in diesem Modus").
     */
    private val registry: Map<String, List<VoiceCommand>> = mapOf(
        "trip" to listOf(
            VoiceCommand(
                patterns = listOf("neue fahrt", "fahrt starten", "starten", "losfahren", "los"),
                action = "trip_start_business",
                description = "Neue Fahrt",
            ),
            VoiceCommand(
                patterns = listOf("stopp", "stop", "fahrt beenden", "beenden", "anhalten", "halt"),
                action = "trip_stop",
                description = "Fahrt stoppen",
            ),
            VoiceCommand(
                patterns = listOf("alle", "alle städte", "alles anzeigen", "filter weg"),
                action = "trip_city_all",
                description = "Alle Städte",
            ),
        ),
        "repair" to listOf(
            VoiceCommand(listOf("scan", "scannen", "scanner"), "act_scan", "Scannen"),
            VoiceCommand(listOf("foto", "photo", "bild"), "act_photo", "Foto"),
            VoiceCommand(listOf("rückgängig", "rueckgaengig", "undo", "zurücknehmen"), "act_undo", "Rückgängig"),
            VoiceCommand(listOf("schließen", "schliessen", "beenden", "exit"), "act_exit", "Schließen"),
        ),
        "receiving" to listOf(
            VoiceCommand(listOf("scan", "scannen"), "act_scan", "Scannen"),
            VoiceCommand(listOf("foto", "photo", "bild"), "act_photo", "Foto"),
            VoiceCommand(listOf("speichern", "sichern", "save"), "act_save_receiving", "Speichern"),
            VoiceCommand(listOf("ok", "fertig", "weiter", "bestätigen", "bestaetigen"), "act_contents_ok", "OK / Weiter"),
            VoiceCommand(listOf("schließen", "schliessen", "beenden", "exit"), "act_exit", "Schließen"),
        ),
        "inventory" to listOf(
            VoiceCommand(listOf("scan", "scannen"), "act_scan", "Scannen"),
            VoiceCommand(listOf("foto", "photo", "bild"), "act_photo", "Foto"),
            VoiceCommand(listOf("senden", "absenden", "submit", "übermitteln", "uebermitteln"), "act_submit_inventory", "Senden"),
            VoiceCommand(listOf("löschen", "loeschen", "leeren", "clear"), "act_clear_inventory", "Löschen"),
            VoiceCommand(listOf("box", "karton", "umschalten"), "act_toggle_box", "Box/Item"),
            VoiceCommand(listOf("schließen", "schliessen", "beenden", "exit"), "act_exit", "Schließen"),
        ),
        "device_check" to listOf(
            VoiceCommand(listOf("scan", "scannen"), "act_scan", "Scannen"),
            VoiceCommand(listOf("foto", "photo", "bild"), "act_photo", "Foto"),
            VoiceCommand(listOf("hochladen", "upload", "senden"), "act_upload_check", "Hochladen"),
            VoiceCommand(listOf("rückgängig", "rueckgaengig", "undo"), "act_undo", "Rückgängig"),
            VoiceCommand(listOf("schließen", "schliessen", "beenden", "exit"), "act_exit", "Schließen"),
        ),
        "restock" to listOf(
            VoiceCommand(listOf("scan", "scannen"), "act_scan", "Scannen"),
            VoiceCommand(listOf("senden", "absenden", "submit"), "act_submit_restock", "Senden"),
            VoiceCommand(listOf("löschen", "loeschen", "leeren", "clear"), "act_clear_restock", "Löschen"),
            VoiceCommand(listOf("schließen", "schliessen", "beenden", "exit"), "act_exit", "Schließen"),
        ),
        // "main" intentionally empty (the menu's labels are self-explanatory).
    )

    fun commandsFor(mode: String): List<VoiceCommand> = registry[mode] ?: emptyList()

    /** A spoken trip declaration ("я поехал в Карлсруэ" / "fahre nach Karlsruhe" /
     *  "zu Doktor Steiner"). [clientNamed] = the phrase names a PERSON/client
     *  (к/zu/to + name) rather than a place — a named client binds WITHOUT the
     *  confirmation question (the naming IS the declaration, owner 2026-07-06). */
    data class TripIntentPhrase(val destination: String, val clientNamed: Boolean)

    // Trip-intent openers. The capture group is the destination/client phrase.
    // Person markers (к / zu(m/r) / to Dr./Doktor/Herr/Frau …) flag clientNamed.
    // NOT start-anchored: STT loves leading filler ("ну я поехал в …", "okay ich
    // fahre nach …") — the opener may appear anywhere, the tail is captured.
    private val tripIntentPatterns = listOf(
        // RU: (я) поехал/еду/выезжаю/поеду/выехал/едем (в|во|на) X
        Regex("(?:^| )(?:я |мы )?(?:поехал[аи]?|еду|едем|выезжаю|выезжаем|выехал[аи]?|поеду|поедем) (?:в|во|на) (.+)$"),
        Regex("(?:^| )(?:я |мы )?(?:поехал[аи]?|еду|едем|выезжаю|выезжаем|выехал[аи]?|поеду|поедем) к (.+)$"),
        // DE: (ich/wir) fahre(n)/fahr (jetzt) nach|zu(m|r) X · fahrt nach X
        Regex("(?:^| )(?:ich |wir )?fahr(?:e|en|t)? (?:jetzt )?nach (.+)$"),
        Regex("(?:^| )(?:ich |wir )?fahr(?:e|en|t)? (?:jetzt )?zu[mr]? (.+)$"),
        // EN: (i'm/we're) driving/going/heading to X
        Regex("(?:^| )(?:i m |im |we re )?(?:driving|going|heading) to (.+)$"),
    )
    // Zero-indexed patterns that carry a PERSON marker (к / zu). Titles inside
    // the captured phrase also flag a named client.
    private val personPatternIdx = setOf(1, 3)
    private val personTitles =
        Regex("^(?:доктор[уа]?|dr|doktor|herr[n]?|frau|госпож[еа]|господин[у]?) ", RegexOption.IGNORE_CASE)

    /** Parse a free-form trip declaration out of [text]; null when it isn't one.
     *  Runs AFTER the fixed-command registry so explicit commands keep priority. */
    fun parseTripIntent(text: String): TripIntentPhrase? {
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return null
        for ((idx, re) in tripIntentPatterns.withIndex()) {
            val m = re.find(normalized) ?: continue
            val phrase = m.groupValues[1].trim()
            if (phrase.isBlank()) continue
            val named = idx in personPatternIdx || personTitles.containsMatchIn(phrase)
            return TripIntentPhrase(destination = phrase, clientNamed = named)
        }
        return null
    }

    /** Human-readable list of what the user can say in [mode] (for console hints). */
    fun availableLabels(mode: String): List<String> = commandsFor(mode).map { it.description }

    /**
     * Match recognized [text] against [mode]'s commands using whole-word /
     * phrase matching (so a destination like "Stoppenberg" is NOT mistaken for
     * the "stop" command). Returns the first matching command, or null.
     */
    fun match(mode: String, text: String): VoiceCommand? {
        val normalized = " " + text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ") + " "
        return commandsFor(mode).firstOrNull { cmd ->
            cmd.patterns.any { p -> normalized.contains(" ${p.trim()} ") }
        }
    }
}
