package com.piremote.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun QrScanner(initialUrl: String, onConnected: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Create the scanner once and reuse it — creating a new one per frame
    // (as the old code did) is wasteful and leaks native resources.
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA)
    }

    fun processQr(raw: String) {
        val wsUrl = when {
            raw.startsWith("piremote://") -> raw.replaceFirst("piremote://", "ws://")
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            else -> "ws://$raw"
        }
        onConnected(wsUrl)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(modifier = Modifier.width(8.dp))
            Text("⊡ Scan QR to connect", fontSize = 16.sp)
        }

        if (hasCamera) {
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        val preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                        scope.launch {
                            val provider = ProcessCameraProvider.getInstance(context).get()
                            val pr = Preview.Builder().build().also { p -> p.setSurfaceProvider(preview.surfaceProvider) }
                            val an = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build().also { a -> a.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                                    proxy.image?.let { mediaImg ->
                                        val img = InputImage.fromMediaImage(mediaImg, proxy.imageInfo.rotationDegrees)
                                        scanner.process(img).addOnSuccessListener { codes ->
                                            for (c in codes) c.rawValue?.let { processQr(it) }
                                        }
                                    }
                                    proxy.close()
                                } }
                            val sel = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
                            provider.bindToLifecycle(lifecycle, sel, pr, an)
                        }
                        preview
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Properly unbind camera resources when this composable leaves
                // the composition (e.g., user taps back / QR scan succeeds).
                DisposableEffect(Unit) {
                    onDispose {
                        scope.launch {
                            val provider = ProcessCameraProvider.getInstance(ctx).get()
                            provider.unbindAll()
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(32.dp).align(Alignment.Center)) {
                    Text("⊅ Camera permission needed to scan the QR shown by the Pi host.",
                        color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera access")
                    }
                }
            }
        }

        // Manual URL entry
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Or enter URL manually", color = Color.Gray, fontSize = 12.sp)
            var url by remember { mutableStateOf(initialUrl) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("ws://IP:port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.3f)),
                    textStyle = TextStyle.Default.copy(color = Color.White),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { if (url.isNotBlank()) onConnected(url) }, enabled = url.isNotBlank()) {
                    Text("Connect")
                }
            }
        }
    }
}

