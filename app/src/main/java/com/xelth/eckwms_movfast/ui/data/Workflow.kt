package com.xelth.eckwms_movfast.ui.data

import kotlinx.serialization.Serializable

@Serializable
data class Workflow(
    val workflowName: String,
    val version: String,
    val steps: List<WorkflowStep>
)

@Serializable
data class WorkflowStep(
    val stepId: String,
    val action: String, // e.g., "scanBarcode", "captureImage", "showUI"
    val ui: UIConfig,
    val variable: String? = null, // Variable to store the result in
    val upload: UploadConfig? = null,
    val loop: LoopConfig? = null
)

@Serializable
data class UIConfig(
    val title: String,
    val instruction: String
)

@Serializable
data class UploadConfig(
    val reason: String,
    val relatedTo: String // Variable name to associate the upload with
)

@Serializable
data class LoopConfig(
    val condition: String, // e.g., "ask_user", "user_ends_session", "for_each"
    val prompt: String? = null,
    val endButtonLabel: String? = null,
    val variable: String? = null // For for_each loops
)
