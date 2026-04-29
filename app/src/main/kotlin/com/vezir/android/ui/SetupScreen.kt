package com.vezir.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vezir.android.data.EnrollmentPayload
import com.vezir.android.data.Prefs
import com.vezir.android.net.VezirApi
import kotlinx.coroutines.launch

/**
 * Setup screen.
 *
 *  - Manual paste: server URL + token (M1).
 *  - Paste full QR JSON payload (M1; lets users complete enrollment without
 *    a camera before M5 lands real QR scanning).
 *  - "Test connection" hits `/health`.
 *  - "Verify token" hits `/api/sessions`.
 *  - "Save and continue" stores prefs and advances to Record.
 *
 * QR camera scan is an M5 deliverable; the manual paste path here is the
 * fallback we'll keep even after M5.
 */
@Composable
fun SetupScreen(
    prefs: Prefs,
    onConfigured: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.serverUrl ?: "") }
    var token by remember { mutableStateOf(prefs.token ?: "") }
    var qrJson by remember { mutableStateOf("") }

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Auto-fill from previously saved settings on first composition.
    LaunchedEffect(Unit) {
        prefs.serverUrl?.let { url = it }
        prefs.token?.let { token = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vezir — set up", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(
            "Point this device at your Vezir server and enter the token your operator issued.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("Server URL") },
            placeholder = { Text("http://muscle.tail178bd.ts.net:8000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Token (vzr_...)") },
            placeholder = { Text("vzr_...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Text(
            "Or paste the JSON payload from /admin/enroll:",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = qrJson,
            onValueChange = { qrJson = it },
            label = { Text("Enrollment JSON") },
            placeholder = { Text("{\"v\":1,\"url\":\"...\",\"token\":\"vzr_...\"}") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val parsed = EnrollmentPayload.parse(qrJson)
                if (parsed == null) {
                    status = "Could not parse enrollment JSON. " +
                        "Expected {\"v\":1,\"url\":\"...\",\"token\":\"...\"}."
                } else {
                    url = parsed.url
                    token = parsed.token
                    status = "Loaded URL and token from JSON. Tap Test connection."
                }
            },
            enabled = qrJson.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply pasted JSON") }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    status = "Pinging /health..."
                    val res = VezirApi(url, null).health()
                    status = when (res) {
                        VezirApi.Result.Ok ->
                            "Server reachable. /health OK."
                        is VezirApi.Result.HttpError ->
                            "Server replied ${res.code} ${res.message}. Check the URL."
                        is VezirApi.Result.NetworkError ->
                            "Network error: ${res.cause.message}. " +
                                "Is Tailscale on? Is the host in network_security_config.xml?"
                    }
                    busy = false
                }
            },
            enabled = url.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test connection") }

        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    status = "Validating token via /api/sessions..."
                    val res = VezirApi(url, token).checkToken()
                    status = when (res) {
                        VezirApi.Result.Ok ->
                            "Token accepted. You can save and continue."
                        is VezirApi.Result.HttpError ->
                            "Token rejected: ${res.code} ${res.message}."
                        is VezirApi.Result.NetworkError ->
                            "Network error: ${res.cause.message}."
                    }
                    busy = false
                }
            },
            enabled = url.isNotBlank() && token.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Verify token") }

        Button(
            onClick = {
                prefs.serverUrl = url
                prefs.token = token
                status = "Saved."
                onConfigured()
            },
            enabled = url.isNotBlank() && token.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and continue") }

        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                status!!,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
