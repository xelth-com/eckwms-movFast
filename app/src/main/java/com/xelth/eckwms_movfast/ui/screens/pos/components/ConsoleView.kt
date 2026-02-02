package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleView(
    logs: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    scannerEnabled: Boolean = true,
    onScannerToggle: (Boolean) -> Unit = {}
) {
    val selectedLog = remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        if (logs.isEmpty()) {
            Text(
                text = "No scan activity yet...",
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF666666),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        } else {
            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                reverseLayout = true
            ) {
                items(
                    items = logs,
                    key = { it }
                ) { log ->
                    val isSelected = selectedLog.value == log

                    Text(
                        text = log,
                        color = if (isSelected) Color(0xFFFFFF00) else Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier
                            .padding(vertical = 1.dp)
                            .background(
                                if (isSelected) Color(0xFF2A2A2A) else Color.Transparent
                            )
                            .combinedClickable(
                                onClick = {
                                    selectedLog.value = if (isSelected) null else log
                                },
                                onLongClick = {
                                    // Long press to copy or show details
                                }
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
