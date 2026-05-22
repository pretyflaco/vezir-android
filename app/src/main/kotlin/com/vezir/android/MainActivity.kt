package com.vezir.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.net.Uri
import com.vezir.android.data.Prefs
import com.vezir.android.ui.ImportScreen
import com.vezir.android.ui.LabelScreen
import com.vezir.android.ui.QrScanScreen
import com.vezir.android.ui.RecordScreen
import com.vezir.android.ui.SetupScreen
import com.vezir.android.ui.SplashScreen
import com.vezir.android.ui.UploadScreen
import com.vezir.android.ui.theme.VezirTheme

/**
 * Single-activity host. App has six screens (Splash, Setup, QrScan,
 * Record, Import, Upload). State transitions are local to AppRoot;
 * persistence lives in EncryptedSharedPreferences (Prefs).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() must be called BEFORE super.onCreate() so
        // the platform draws the brand mark from Theme.Vezir.Splash before
        // the Activity content takes over.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VezirTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

private sealed class Screen {
    object Splash : Screen()
    object Setup : Screen()
    object QrScan : Screen()
    object Record : Screen()
    object Import : Screen()
    data class Upload(
        val uri: Uri,
        val fileName: String,
        val title: String?,
        val summaryPreset: String?,
        val autoLabel: Boolean,
        val sync: Boolean,
    ) : Screen()
    data class Label(val sessionId: String) : Screen()
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }

    // Always start on the brand splash; after its delay, route to
    // Setup or Record depending on whether the device is enrolled.
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }

    when (val s = screen) {
        Screen.Splash -> SplashScreen(
            onDone = {
                screen = if (prefs.isConfigured()) Screen.Record else Screen.Setup
            },
        )
        Screen.Setup -> SetupScreen(
            prefs = prefs,
            onConfigured = { screen = Screen.Record },
            onScanQr = { screen = Screen.QrScan },
        )
        Screen.QrScan -> QrScanScreen(
            onScanned = { payload ->
                prefs.serverUrl = payload.url
                prefs.token = payload.token
                prefs.caPem = payload.ca_pem  // v2: store CA cert for OkHttp trust
                screen = Screen.Setup
            },
            onCancel = { screen = Screen.Setup },
        )
        Screen.Record -> RecordScreen(
            prefs = prefs,
            onSignOut = {
                prefs.clear()
                screen = Screen.Setup
            },
            onUpload = { uri, name, title, preset, autoLabel, sync ->
                screen = Screen.Upload(uri, name, title, preset, autoLabel, sync)
            },
            onImport = { screen = Screen.Import },
        )
        Screen.Import -> ImportScreen(
            onCancel = { screen = Screen.Record },
            // Imports re-use the user's stickied preset + toggles
            // (matches Record flow).
            onImported = { uri, name ->
                screen = Screen.Upload(
                    uri, name, null,
                    prefs.summaryPreset, prefs.autoLabel, prefs.sync,
                )
            },
        )
        is Screen.Upload -> UploadScreen(
            prefs = prefs,
            contentUri = s.uri,
            fileName = s.fileName,
            title = s.title,
            summaryPreset = s.summaryPreset,
            autoLabel = s.autoLabel,
            sync = s.sync,
            onDismiss = { screen = Screen.Record },
            onLabel = { sessionId -> screen = Screen.Label(sessionId) },
        )
        is Screen.Label -> LabelScreen(
            prefs = prefs,
            sessionId = s.sessionId,
            onDone = {
                // Return to upload screen to resume polling (syncing → done).
                screen = Screen.Record
            },
            onCancel = { screen = Screen.Record },
        )
    }
}
