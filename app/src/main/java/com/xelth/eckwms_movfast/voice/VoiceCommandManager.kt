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
                action = "trip_open_start",
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
