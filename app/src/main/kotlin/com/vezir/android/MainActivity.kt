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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.net.Uri
import com.vezir.android.data.Prefs
import com.vezir.android.ui.ImportScreen
import com.vezir.android.ui.QrScanScreen
import com.vezir.android.ui.RecordScreen
import com.vezir.android.ui.SetupScreen
import com.vezir.android.ui.UploadScreen
import com.vezir.android.ui.theme.VezirTheme

/**
 * Single-activity host. v1 has five screens (Setup, QrScan, Record,
 * Import, Upload). State transitions are local to AppRoot; persistence
 * lives in EncryptedSharedPreferences (Prefs).
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
    object Setup : Screen()
    object QrScan : Screen()
    object Record : Screen()
    object Import : Screen()
    data class Upload(val uri: Uri, val fileName: String, val title: String?) : Screen()
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }

    var screen by remember {
        mutableStateOf<Screen>(if (prefs.isConfigured()) Screen.Record else Screen.Setup)
    }

    LaunchedEffect(Unit) {
        if (prefs.isConfigured() && screen is Screen.Setup) screen = Screen.Record
    }

    when (val s = screen) {
        Screen.Setup -> SetupScreen(
            prefs = prefs,
            onConfigured = { screen = Screen.Record },
            onScanQr = { screen = Screen.QrScan },
        )
        Screen.QrScan -> QrScanScreen(
            onScanned = { payload ->
                prefs.serverUrl = payload.url
                prefs.token = payload.token
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
            onUpload = { uri, name, title -> screen = Screen.Upload(uri, name, title) },
            onImport = { screen = Screen.Import },
        )
        Screen.Import -> ImportScreen(
            onCancel = { screen = Screen.Record },
            onImported = { uri, name -> screen = Screen.Upload(uri, name, null) },
        )
        is Screen.Upload -> UploadScreen(
            prefs = prefs,
            contentUri = s.uri,
            fileName = s.fileName,
            title = s.title,
            onDismiss = { screen = Screen.Record },
        )
    }
}
