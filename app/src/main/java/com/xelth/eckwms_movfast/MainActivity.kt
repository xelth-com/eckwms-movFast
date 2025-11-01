package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.screens.DebugInfoPanel
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanState

class MainActivity : ComponentActivity() {

    private val viewModel: ScanRecoveryViewModel by viewModels {
        ScanRecoveryViewModel.Companion.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            EckwmsmovFastTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        // Main content
                        MainContent(
                            viewModel = viewModel,
                            onOpenScannerSettings = {
                                val intent = Intent(this@MainActivity, ScannerActivity::class.java)
                                startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainContent(
    viewModel: ScanRecoveryViewModel,
    onOpenScannerSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ECKWMS Scanner",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onOpenScannerSettings,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                "Scanner Hardware Settings",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}