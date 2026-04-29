package com.vezir.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.vezir.android.data.EnrollmentPayload

/**
 * Camera-backed QR scanner. On a successful Vezir-enrollment payload
 * decode, [onScanned] is fired exactly once with the parsed payload and
 * the screen finishes.
 *
 * Falls back to "permission required" message when CAMERA isn't granted;
 * the user can either grant or tap Cancel to return to the manual-paste
 * Setup flow.
 */
@Composable
fun QrScanScreen(
    onScanned: (EnrollmentPayload) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf<String?>(null) }
    val firedOnce = remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            status = "Camera permission denied. Use manual paste instead."
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scan QR", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Open /admin/enroll on the Vezir server in a browser, generate the QR, " +
                "and point your camera at it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission) {
                CameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    onPayload = { raw ->
                        if (firedOnce.value) return@CameraPreview
                        val parsed = EnrollmentPayload.parse(raw)
                        if (parsed == null) {
                            status = "Scanned a QR, but it's not a Vezir enrollment payload."
                        } else {
                            firedOnce.value = true
                            onScanned(parsed)
                        }
                    },
                    onError = { msg -> status = msg },
                )
            } else {
                Text(
                    "Camera permission required.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (status != null) {
            Text(
                status!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cancel and use manual paste") }
    }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onPayload: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { LifecycleCameraController(context) }
    val barcodeScanner = remember {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(opts)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            // Wire ML Kit barcode analyzer into CameraX. The
            // androidx.camera:camera-mlkit-vision artifact provides
            // MlKitAnalyzer which adapts CameraX ImageAnalysis frames
            // for ML Kit detectors without us writing the bridge.
            controller.setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(ctx),
                MlKitAnalyzer(
                    listOf(barcodeScanner),
                    COORDINATE_SYSTEM_VIEW_REFERENCED,
                    ContextCompat.getMainExecutor(ctx),
                ) { result ->
                    val barcodes = result.getValue(barcodeScanner) ?: return@MlKitAnalyzer
                    for (b in barcodes) {
                        val raw = b.rawValue ?: continue
                        if (raw.isNotBlank()) {
                            onPayload(raw)
                            return@MlKitAnalyzer
                        }
                    }
                },
            )
            try {
                controller.bindToLifecycle(lifecycleOwner)
                view.controller = controller
            } catch (e: Exception) {
                Log.e("VezirQrScan", "bindToLifecycle failed", e)
                onError("Camera bind failed: ${e.message}")
            }
            view
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            // Release the controller and the ML Kit detector. Without
            // these the camera stays bound until process death.
            controller.unbind()
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            barcodeScanner.close()
        }
    }
}
