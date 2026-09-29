package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

@Composable
fun CameraQrScannerView(
    modifier: Modifier = Modifier,
    isScanningActive: Boolean = true,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var camera by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (isScanningActive) {
                                    val qrText = QrCodeDecoder.decodeImageProxy(imageProxy)
                                    if (qrText != null) {
                                        onQrDetected(qrText)
                                    }
                                }
                                imageProxy.close()
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Animated Scanner Overlay
        ScannerOverlay(
            modifier = Modifier.fillMaxSize()
        )

        // Torch toggle button
        camera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    IconButton(
                        onClick = {
                            isTorchOn = !isTorchOn
                            cam.cameraControl.enableTorch(isTorchOn)
                        }
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (isTorchOn) "Turn Off Flash" else "Turn On Flash",
                            tint = if (isTorchOn) Color(0xFFFBBF24) else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScannerOverlay(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanner_laser")
    val laserFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = 0.99f } // Required for BlendMode.Clear cutout
    ) {
        val width = size.width
        val height = size.height
        val boxSize = minOf(width, height) * 0.72f
        val left = (width - boxSize) / 2f
        val top = (height - boxSize) / 2f
        val right = left + boxSize
        val bottom = top + boxSize
        val cornerRadius = 16.dp.toPx()

        // 1. Semi-transparent black background
        drawRect(
            color = Color.Black.copy(alpha = 0.65f),
            size = size
        )

        // 2. Cutout transparent viewfinder hole
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(boxSize, boxSize),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            blendMode = BlendMode.Clear
        )

        // 3. Four corner brackets
        val bracketColor = Color(0xFF0284C7)
        val cornerLength = 32.dp.toPx()
        val strokeWidth = 5.dp.toPx()

        // Top Left
        drawLine(bracketColor, Offset(left, top + cornerLength), Offset(left, top + cornerRadius), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(left + cornerRadius, top), Offset(left + cornerLength, top), strokeWidth, StrokeCap.Round)

        // Top Right
        drawLine(bracketColor, Offset(right, top + cornerLength), Offset(right, top + cornerRadius), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(right - cornerRadius, top), Offset(right - cornerLength, top), strokeWidth, StrokeCap.Round)

        // Bottom Left
        drawLine(bracketColor, Offset(left, bottom - cornerLength), Offset(left, bottom - cornerRadius), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(left + cornerRadius, bottom), Offset(left + cornerLength, bottom), strokeWidth, StrokeCap.Round)

        // Bottom Right
        drawLine(bracketColor, Offset(right, bottom - cornerLength), Offset(right, bottom - cornerRadius), strokeWidth, StrokeCap.Round)
        drawLine(bracketColor, Offset(right - cornerRadius, bottom), Offset(right - cornerLength, bottom), strokeWidth, StrokeCap.Round)

        // 4. Animated Laser Scan Line
        val laserY = top + (boxSize * laserFraction)
        drawLine(
            color = Color(0xFF38BDF8),
            start = Offset(left + 8.dp.toPx(), laserY),
            end = Offset(right - 8.dp.toPx(), laserY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
