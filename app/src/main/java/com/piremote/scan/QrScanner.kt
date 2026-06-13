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
import java.util.concurrent.Executors

/**
 * Normalize a scanned/typed value to a `ws(s)://` URL, or return null if it
 * cannot be a valid WebSocket endpoint. This is the trust gate for the QR path:
 * without it, a hostile QR (`else -> "ws://$raw"`) silently points the app at an
 * attacker-controlled server that can then drive dialogs, push files, and read
 * everything the user types.
 */
fun parseScannedUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val candidate = when {
        trimmed.startsWith("piremote://") -> trimmed.replaceFirst("piremote://", "ws://")
        trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
        else -> "ws://$trimmed"
    }
    return try {
        val uri = java.net.URI(candidate)
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "ws" || scheme == "wss") && !uri.host.isNullOrBlank()) candidate else null
    } catch (_: Exception) { null }
}

@Composable
fun QrScanner(initialUrl: String, onConnected: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current

    // Create the scanner once and reuse it — creating a new one per frame
    // (as the old code did) is wasteful and leaks native resources.
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    // The analyzer fires for every camera frame; latch so a successful scan only
    // calls onConnected once instead of bursting connect() on every frame.
    val scanned = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // Capture the provider + analysis executor so onDispose can tear them down
    // synchronously (a coroutine launched in onDispose never runs — its scope is
    // being cancelled in the same pass, leaving the camera bound to the Activity).
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

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
        val wsUrl = parseScannedUrl(raw) ?: return   // ignore non-WebSocket QR payloads
        if (scanned.compareAndSet(false, true)) onConnected(wsUrl)
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
                        val providerFuture = ProcessCameraProvider.getInstance(context)
                        // addListener instead of a blocking .get() on the main thread.
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            cameraProvider.value = provider
                            val pr = Preview.Builder().build().also { p -> p.setSurfaceProvider(preview.surfaceProvider) }
                            val an = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build().also { a -> a.setAnalyzer(analysisExecutor) { proxy ->
                                    val mediaImg = proxy.image
                                    if (mediaImg != null) {
                                        val img = InputImage.fromMediaImage(mediaImg, proxy.imageInfo.rotationDegrees)
                                        scanner.process(img)
                                            .addOnSuccessListener { codes ->
                                                for (c in codes) c.rawValue?.let { processQr(it) }
                                            }
                                            // Close only after ML Kit is done with the borrowed
                                            // media.Image buffer — closing synchronously can
                                            // corrupt detection or throw on some devices.
                                            .addOnCompleteListener { proxy.close() }
                                    } else proxy.close()
                                } }
                            val sel = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycle, sel, pr, an)
                        }, ContextCompat.getMainExecutor(context))
                        preview
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Tear down camera + executor synchronously when this composable
                // leaves composition (back press / successful scan). Both calls are
                // main-thread safe; doing it here (not in a launched coroutine) is
                // what actually releases the camera.
                DisposableEffect(Unit) {
                    onDispose {
                        cameraProvider.value?.unbindAll()
                        analysisExecutor.shutdown()
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
                Button(
                    onClick = { parseScannedUrl(url)?.let { onConnected(it) } },
                    enabled = url.isNotBlank()
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

