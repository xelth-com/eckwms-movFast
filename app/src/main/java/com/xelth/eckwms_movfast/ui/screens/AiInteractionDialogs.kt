package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xelth.eckwms_movfast.ui.data.AiInteraction

/**
 * Main overlay that renders the appropriate UI component based on AI interaction type
 */
@Composable
fun AiInteractionOverlay(
    aiInteraction: AiInteraction?,
    onDismiss: () -> Unit,
    onResponse: (String) -> Unit
) {
    aiInteraction?.let { interaction ->
        when (interaction.type.lowercase()) {
            "question", "confirmation" -> {
                // Blocking dialog for questions and confirmations
                AiQuestionDialog(
                    interaction = interaction,
                    onDismiss = onDismiss,
                    onResponse = onResponse
                )
            }
            "info", "warning", "error", "success" -> {
                // Non-blocking banner for informational messages
                AiInfoBanner(
                    interaction = interaction,
                    onDismiss = onDismiss,
                    onAction = onResponse
                )
            }
            else -> {
                // Default to info banner for unknown types
                AiInfoBanner(
                    interaction = interaction,
                    onDismiss = onDismiss,
                    onAction = onResponse
                )
            }
        }
    }
}

/**
 * Blocking dialog for AI questions and confirmations
 */
@Composable
fun AiQuestionDialog(
    interaction: AiInteraction,
    onDismiss: () -> Unit,
    onResponse: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = if (interaction.type.lowercase() == "confirmation") "Confirmation Required" else "Question",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Message
                Text(
                    text = interaction.message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Options/Buttons
                val options = interaction.options ?: listOf("Yes", "No")

                if (options.size <= 2) {
                    // Horizontal layout for 2 or fewer options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        options.forEachIndexed { index, option ->
                            Button(
                                onClick = { onResponse(option) },
                                modifier = Modifier.weight(1f),
                                colors = if (index == 0) {
                                    ButtonDefaults.buttonColors()
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                }
                            ) {
                                Text(option)
                            }
                        }
                    }
                } else {
                    // Vertical layout for 3+ options
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            Button(
                                onClick = { onResponse(option) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(option)
                            }
                        }
                    }
                }

                // Cancel button
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Non-blocking banner for informational AI messages
 */
@Composable
fun AiInfoBanner(
    interaction: AiInteraction,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(interaction) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (interaction.type.lowercase()) {
                    "warning" -> MaterialTheme.colorScheme.tertiaryContainer
                    "error" -> MaterialTheme.colorScheme.errorContainer
                    "success" -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Icon(
                    imageVector = when (interaction.type.lowercase()) {
                        "warning", "error" -> Icons.Default.Warning
                        else -> Icons.Default.Info
                    },
                    contentDescription = interaction.type,
                    modifier = Modifier.size(32.dp),
                    tint = when (interaction.type.lowercase()) {
                        "warning" -> MaterialTheme.colorScheme.onTertiaryContainer
                        "error" -> MaterialTheme.colorScheme.error
                        "success" -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Message
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = interaction.type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = when (interaction.type.lowercase()) {
                            "error" -> MaterialTheme.colorScheme.error
                            "success" -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = interaction.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Action buttons if options are provided
                    interaction.options?.let { options ->
                        if (options.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                options.forEach { option ->
                                    OutlinedButton(
                                        onClick = {
                                            onAction(option)
                                            visible = false
                                        },
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text(
                                            text = option,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Dismiss button
                IconButton(
                    onClick = {
                        visible = false
                        onDismiss()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
