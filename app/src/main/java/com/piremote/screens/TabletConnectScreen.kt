package com.piremote.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.piremote.*
import com.piremote.theme.*
import java.util.concurrent.Executors

/**
 * Two-pane tablet connect screen: QR scanner on the left, URL input + options on the right.
 */
@Composable
fun TabletConnectScreen(
    vm: ChatViewModel,
    url: String,
    input: String,
    messages: List<ChatMessage>,
    assist: String,
    status: ConnectionStatus,
    urlHistory: Set<String>,
    sessions: List<RemoteSession> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Key on url so the field populates once the saved URL loads from DataStore
    // (async, after first compose). Typing mutates only `t`, never `url`.
    var t by remember(url) { mutableStateOf(url) }
    var showScanner by remember { mutableStateOf(true) } // default on for tablets

    Row(modifier = modifier.fillMaxSize()) {
        // ── Left: QR Scanner ─────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Black)
        ) {
            if (showScanner) {
                TabletQrScannerPane(
                    initialUrl = t,
                    onConnected = { scanned: String ->
                        val wsUrl = if (scanned.startsWith("piremote://")) scanned.replaceFirst("piremote://", "ws://") else scanned
                        t = wsUrl
                        vm.setServerUrl(wsUrl)
                        vm.connect()
                    },
                    onClose = { showScanner = false },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Scanner dismissed — show placeholder
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("⊡", color = textMuted, fontSize = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Scanner closed",
                        color = textMuted,
                        fontFamily = piMono,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to reopen",
                        color = accent,
                        fontFamily = piMono,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(role = Role.Button, onClickLabel = "reopen QR scanner") { showScanner = true },
                    )
                }
            }

            // Update affordance in bottom-right
            UpdateAffordance(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }

        // Divider
        Box(modifier = Modifier.fillMaxHeight().width(0.5.dp).background(border))

        // ── Right: Connection Panel ──────────────────────────
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(bg)
                .verticalScroll(rememberScrollState())
        ) {
            // Header + Options menu + URL input + Connect + status + Quick
            // Start — shared with the phone connect screen (ConnectPanel.kt).
            ConnectPanel(
                vm = vm,
                urlText = t,
                onUrlTextChange = { t = it },
                status = status,
                urlHistory = urlHistory,
                subtitle = "Control your Pi agent from your tablet",
                onScanRequest = { showScanner = true },
                contentPadding = 10.dp,
            )

            // Fixed spacer: weight() does not work inside a scrollable column.
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * QR scanner adapted for the left pane of the tablet connect screen.
 * No header bar — the panel header provides the title.
 */
@Composable
fun TabletQrScannerPane(
    initialUrl: String,
    onConnected: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current

    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    // See QrScanner.kt for the rationale behind these three. Same camera/scan
    // lifecycle bugs were duplicated here in the tablet pane.
    val scanned = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var hasCamera by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA)
    }

    fun processQr(raw: String) {
        val wsUrl = com.piremote.scan.parseScannedUrl(raw) ?: return
        if (scanned.compareAndSet(false, true)) onConnected(wsUrl)
    }

    Column(modifier = modifier) {
        // Top bar with close button
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close scanner", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("⊡ Scan QR to connect", color = Color.White, fontSize = 14.sp, fontFamily = piMono)
        }

        if (hasCamera) {
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        val preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                        val providerFuture = ProcessCameraProvider.getInstance(context)
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
                                            .addOnCompleteListener { proxy.close() }
                                    } else proxy.close()
                                } }
                            val sel = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycle, sel, pr, an)
                        }, androidx.core.content.ContextCompat.getMainExecutor(context))
                        preview
                    },
                    modifier = Modifier.fillMaxSize()
                )
                DisposableEffect(Unit) {
                    onDispose {
                        cameraProvider.value?.unbindAll()
                        analysisExecutor.shutdown()
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(24.dp).align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⊅ Camera permission needed", color = Color.Gray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera access")
                    }
                }
            }
        }

        // Manual URL fallback at the bottom
        Column(modifier = Modifier.padding(12.dp).background(Color.Black.copy(alpha = 0.7f))) {
            Text("Or enter URL manually", color = Color.Gray, fontSize = 11.sp, fontFamily = piMono)
            var manualUrl by remember { mutableStateOf(initialUrl) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualUrl,
                    onValueChange = { manualUrl = it },
                    placeholder = { Text("ws://IP:port", color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                    ),
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = piMono, fontSize = 12.sp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = { com.piremote.scan.parseScannedUrl(manualUrl)?.let { onConnected(it) } },
                    enabled = manualUrl.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Connect", fontSize = 12.sp)
                }
            }
        }
    }
}
