// app/src/main/java/com/xelth/eckwms_movfast/ui/screens/ApiTestHelpDialog.kt

package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Dialog that explains scanner API tests
 */
@Composable
fun ApiTestHelpDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Scanner API Tests Help",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "These tests check if the scanner hardware and API are functioning correctly.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Run All Tests
                Text(
                    text = "Run All Tests",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Executes all available scanner API tests to check overall functionality. Results are displayed with pass/fail status for each test.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Test Barcode Types
                Text(
                    text = "Test Barcode Types",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Verifies if the scanner can recognize and toggle support for different barcode types (QR Code, Code 128, EAN-13, etc.).",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Test Settings
                Text(
                    text = "Test Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Checks if scanner settings like flash mode, aimer mode, output method, and notification types can be successfully configured.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Test Image API
                Text(
                    text = "Test Image API",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Verifies if the scanner can capture and process barcode images. This tests if the movFast device's camera and decoder functions are working properly.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Understanding Results
                Text(
                    text = "Understanding Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Green checkmarks (✓) indicate passed tests\n" +
                            "• Red X marks (✗) indicate failed tests\n" +
                            "• The percentage at the bottom shows overall test success rate\n" +
                            "• Expand individual test results to see detailed messages",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Troubleshooting
                Text(
                    text = "Troubleshooting",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "If tests fail, try these steps:\n" +
                            "1. Restart the application\n" +
                            "2. Check if scanner service is running\n" +
                            "3. Verify device permissions\n" +
                            "4. Ensure scanner hardware is not in use by another app\n" +
                            "5. Contact technical support if issues persist",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}