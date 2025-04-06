// app/src/main/java/com/xelth/eckwms_movfast/ui/screens/ScannedImageScreen.kt
// Улучшенный экран просмотра изображения штрихкода с обработкой API-ограничений
package com.xelth.eckwms_movfast.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.scanners.getLastDecodedImage

private const val TAG = "ScannedImageScreen"
private const val MIN_SCALE = 1.0f
private const val MAX_SCALE = 5.0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedImageScreen(
    scannerManager: ScannerManager,
    onNavigateBack: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isGeneratedImage by remember { mutableStateOf(false) }

    // Наблюдаем за последними отсканированными данными для контекста
    val latestScanResult by scannerManager.scanResult.observeAsState()

    // Загрузка изображения при отображении экрана
    LaunchedEffect(Unit) {
        try {
            Log.d(TAG, "Attempting to load the last decoded image")
            bitmap = scannerManager.getLastDecodedImage()

            if (bitmap == null) {
                Log.d(TAG, "No image available")
                errorMessage = "Изображение не найдено. Отсканируйте штрих-код, чтобы увидеть его визуализацию."
            } else {
                Log.d(TAG, "Image loaded successfully: ${bitmap?.width}x${bitmap?.height}")
                // Проверяем, является ли это сгенерированным изображением
                isGeneratedImage = bitmap!!.width == 800 && bitmap!!.height == 400
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            errorMessage = "Ошибка загрузки изображения: ${e.message}"
        }
    }

    // Состояние для трансформации (масштабирование и смещение)
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        // Обновляем масштаб с ограничениями
        scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)

        // Обновляем смещение
        offset += panChange * scale
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Просмотр изображения") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("← Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Информация о последнем отсканированном штрих-коде
            if (!latestScanResult.isNullOrEmpty()) {
                Text(
                    text = "Штрих-код: $latestScanResult",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Предупреждение о сгенерированном изображении
            if (isGeneratedImage) {
                Text(
                    text = "Примечание: API сканера не поддерживает получение реального изображения, " +
                            "отображается визуализация штрих-кода.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Контроллеры масштабирования
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { scale = (scale - 0.5f).coerceAtLeast(MIN_SCALE) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("−", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    valueRange = MIN_SCALE..MAX_SCALE,
                    modifier = Modifier.weight(2f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { scale = (scale + 0.5f).coerceAtMost(MAX_SCALE) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
            }

            Text(
                text = "Масштаб: ${String.format("%.1f", scale)}x",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Отсканированное изображение",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .transformable(transformableState)
                    )
                } else if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    Text(
                        text = "Загрузка изображения...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    offset = Offset.Zero
                    scale = 1f
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сбросить масштаб")
            }

            if (bitmap != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Размер изображения: ${bitmap!!.width}x${bitmap!!.height}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}