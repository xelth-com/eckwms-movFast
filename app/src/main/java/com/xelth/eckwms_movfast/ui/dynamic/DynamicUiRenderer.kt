package com.xelth.eckwms_movfast.ui.dynamic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONObject
import org.json.JSONArray

@Composable
fun DynamicUiRenderer(
    layoutJson: String,
    onAction: (String, Map<String, String>) -> Unit,
    stateValues: Map<String, Any> = emptyMap(),
    onValueChange: (String, Any) -> Unit = { _, _ -> }
) {
    val parseResult = try {
        val root = JSONObject(layoutJson)
        val sections = root.optJSONArray("sections")
        if (sections != null) {
            ParseResult.Sections(sections)
        } else {
            val components = root.optJSONArray("components")
            ParseResult.Components(components)
        }
    } catch (e: Exception) {
        ParseResult.Error(e.message ?: "Unknown error")
    }

    when (parseResult) {
        is ParseResult.Sections -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val sections = parseResult.data
                items(sections.length()) { index ->
                    val section = sections.getJSONObject(index)
                    RenderSection(section, onAction, stateValues, onValueChange)
                }
            }
        }
        is ParseResult.Components -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val components = parseResult.data
                if (components != null) {
                    items(components.length()) { index ->
                        val component = components.getJSONObject(index)
                        RenderComponent(component, onAction, stateValues, onValueChange)
                    }
                }
            }
        }
        is ParseResult.Error -> {
            Text(
                "UI Render Error: ${parseResult.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private sealed class ParseResult {
    data class Sections(val data: JSONArray) : ParseResult()
    data class Components(val data: JSONArray?) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

@Composable
fun RenderSection(
    json: JSONObject,
    onAction: (String, Map<String, String>) -> Unit,
    stateValues: Map<String, Any>,
    onValueChange: (String, Any) -> Unit
) {
    // Handle non-section types at top level (spacing, button)
    val type = json.optString("type")
    if (type != "section") {
        RenderComponent(json, onAction, stateValues, onValueChange)
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val title = json.optString("title")
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = Color.Gray.copy(alpha = 0.3f)
                )
            }

            val components = json.optJSONArray("components")
            if (components != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (i in 0 until components.length()) {
                        RenderComponent(components.getJSONObject(i), onAction, stateValues, onValueChange)
                    }
                }
            }
        }
    }
}

@Composable
fun RenderComponent(
    json: JSONObject,
    onAction: (String, Map<String, String>) -> Unit,
    stateValues: Map<String, Any>,
    onValueChange: (String, Any) -> Unit
) {
    val type = json.optString("type")

    when (type) {
        "text" -> {
            val style = when(json.optString("style")) {
                "h1" -> MaterialTheme.typography.headlineMedium
                "h2" -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.bodyLarge
            }
            Text(
                text = json.optString("content", ""),
                style = style,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White
            )
        }

        "info_row" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val action = json.optString("action", "")
                        if (action.isNotEmpty()) onAction(action, emptyMap())
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    json.optString("label"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                val variable = json.optString("variable")
                val value = stateValues[variable]?.toString() ?: "Not Set"
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (value == "Not Set") Color.Gray else Color.White
                )
            }
        }

        "boolean_toggle" -> {
            val key = json.optString("key")
            val currentVal = stateValues[key] as? Boolean ?: json.optBoolean("default", false)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    json.optString("label"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                Switch(
                    checked = currentVal,
                    onCheckedChange = { onValueChange(key, it) }
                )
            }
        }

        "single_choice" -> {
            val key = json.optString("key")
            val currentVal = stateValues[key]?.toString() ?: json.optString("default", "")
            val options = json.optJSONArray("options")

            Column {
                Text(
                    json.optString("label"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (options != null) {
                        for (i in 0 until options.length()) {
                            val option = options.getString(i)
                            val isSelected = option == currentVal
                            FilterChip(
                                selected = isSelected,
                                onClick = { onValueChange(key, option) },
                                label = { Text(option) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        "button" -> {
            Button(
                onClick = {
                    val action = json.optString("action", "")
                    val params = mutableMapOf<String, String>()
                    onAction(action, params)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (json.optBoolean("primary", false))
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(json.optString("label", "Button"))
            }
        }

        "text_input" -> {
            val key = json.optString("key")
            val currentVal = stateValues[key]?.toString() ?: ""
            val placeholder = json.optString("placeholder", "")
            var textValue by remember(key, currentVal) { mutableStateOf(currentVal) }

            Column {
                Text(
                    json.optString("label"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        onValueChange(key, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder, color = Color.Gray.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    )
                )
            }
        }

        "date_input" -> {
            val key = json.optString("key")
            val defaultVal = json.optString("default", "")
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val initial = if (defaultVal == "today") today else defaultVal
            val currentVal = stateValues[key]?.toString() ?: initial
            var dateValue by remember(key) { mutableStateOf(currentVal) }

            // Auto-set default on first render
            LaunchedEffect(key) {
                if (stateValues[key] == null) {
                    onValueChange(key, initial)
                }
            }

            Column {
                Text(
                    json.optString("label"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = dateValue,
                        onValueChange = {
                            dateValue = it
                            onValueChange(key, it)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    FilterChip(
                        selected = dateValue == today,
                        onClick = {
                            dateValue = today
                            onValueChange(key, today)
                        },
                        label = { Text("Today") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }

        "dropdown_select" -> {
            val key = json.optString("key")
            val label = json.optString("label")
            val currentVal = stateValues[key]?.toString() ?: ""
            val optionsJson = json.optJSONArray("options")

            val options = remember(optionsJson?.toString()) {
                val list = mutableListOf<Pair<String, String>>()
                if (optionsJson != null) {
                    for (i in 0 until optionsJson.length()) {
                        val item = optionsJson.optJSONObject(i)
                        if (item != null) {
                            list.add(item.optString("label") to item.optString("value"))
                        } else {
                            val str = optionsJson.optString(i)
                            list.add(str to str)
                        }
                    }
                }
                list
            }

            var expanded by remember { mutableStateOf(false) }
            val displayLabel = options.find { it.second == currentVal }?.first ?: "Select..."

            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayLabel,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 300.dp)
                    ) {
                        options.forEach { (optLabel, optValue) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = optLabel,
                                        fontWeight = if (optValue == currentVal) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onValueChange(key, optValue)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        "card" -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val title = json.optString("title")
                    if (title.isNotEmpty()) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(json.optString("content", ""))
                }
            }
        }

        "spacing" -> {
            Spacer(modifier = Modifier.height(json.optInt("height", 16).dp))
        }
    }
}
