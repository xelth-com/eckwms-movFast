package com.xelth.eckwms_movfast.ui.data

/**
 * Data model for AI Interaction responses from the server
 * Represents questions, confirmations, or other AI-driven UI prompts
 */
data class AiInteraction(
    val id: String? = null,     // Unique interaction ID for tracking responses (optional for debug mode)
    val type: String,           // Type of interaction: "question", "confirmation", "info", etc.
    val message: String,        // The message/question to display to the user
    val options: List<String>? = null,  // Optional list of options for user selection
    val data: Map<String, Any>? = null,  // Additional metadata for the interaction
    val barcode: String? = null // The barcode that triggered this interaction (for context)
)
