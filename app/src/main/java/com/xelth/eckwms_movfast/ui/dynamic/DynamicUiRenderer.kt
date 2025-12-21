package com.xelth.eckwms_movfast.ui.dynamic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.json.JSONArray

@Composable
fun DynamicUiRenderer(
    layoutJson: String,
    onAction: (String, Map<String, String>) -> Unit
) {
    // Parse JSON outside of composable scope
    val parseResult = try {
        val root = JSONObject(layoutJson)
        val components = root.optJSONArray("components")
        ParseResult.Success(components)
    } catch (e: Exception) {
        ParseResult.Error(e.message ?: "Unknown error")
    }

    // Render based on parse result
    when (parseResult) {
        is ParseResult.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val components = parseResult.components
                if (components != null) {
                    items(components.length()) { index ->
                        val component = components.getJSONObject(index)
                        RenderComponent(component, onAction)
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
    data class Success(val components: JSONArray?) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

@Composable
fun RenderComponent(json: JSONObject, onAction: (String, Map<String, String>) -> Unit) {
    when (json.optString("type")) {
        "text" -> {
            val style = when(json.optString("style")) {
                "h1" -> MaterialTheme.typography.headlineMedium
                "h2" -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.bodyLarge
            }
            Text(
                text = json.optString("content", ""),
                style = style,
                modifier = Modifier.fillMaxWidth()
            )
        }
        "button" -> {
            Button(
                onClick = {
                    val action = json.optString("action", "")
                    val params = mutableMapOf<String, String>()
                    // Parse simple params if needed
                    onAction(action, params)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (json.optBoolean("primary", true))
                    ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(json.optString("label", "Button"))
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
