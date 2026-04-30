package com.vezir.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Setup / enrollment screen.
 *
 * Visual hierarchy after the M5.1 layout pass:
 *
 *   1. Brand header (full lockup) — anchors the brand and gives the
 *      screen breathing room at the top.
 *   2. Primary CTA: 'Scan enrollment QR' (filled coral). The expected
 *      happy path for new devices.
 *   3. Manual paste fallback as collapsible secondary block.
 *   4. Test connection / Verify token in a single horizontal row so
 *      the screen doesn't grow another two button rows tall.
 *   5. 'Save and continue' filled coral CTA.
 */
@Composable
fun SetupScreen(
    prefs: Prefs,
    onConfigured: () -> Unit,
    onScanQr: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.serverUrl ?: "") }
    var token by remember { mutableStateOf(prefs.token ?: "") }
    var qrJson by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(prefs.serverUrl != null) }

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        prefs.serverUrl?.let { url = it }
        prefs.token?.let { token = it }
    }

    ScreenScaffold {
        BrandHeader(subtitle = "set up this device")

        Text(
            "Point this device at your Vezir server. Scan the QR rendered " +
                "by /admin/enroll, or paste the token your operator issued.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !busy,
        ) { Text("Scan enrollment QR") }

        TextButton(
            onClick = { showManual = !showManual },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showManual) "Hide manual entry" else "Or enter manually")
        }

        if (showManual) {
            HorizontalDivider()

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

            OutlinedTextField(
                value = qrJson,
                onValueChange = { qrJson = it },
                label = { Text("Or paste enrollment JSON") },
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
                        status = "Loaded URL and token from JSON."
                    }
                },
                enabled = qrJson.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply pasted JSON") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Pinging /health..."
                            val res = VezirApi(url, null).health()
                            status = when (res) {
                                VezirApi.Result.Ok ->
                                    "Server reachable."
                                is VezirApi.Result.HttpError ->
                                    "Server replied ${res.code} ${res.message}."
                                is VezirApi.Result.NetworkError ->
                                    "Network error: ${res.cause.message}."
                            }
                            busy = false
                        }
                    },
                    enabled = url.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Validating token via /api/sessions..."
                            val res = VezirApi(url, token).checkToken()
                            status = when (res) {
                                VezirApi.Result.Ok ->
                                    "Token accepted."
                                is VezirApi.Result.HttpError ->
                                    "Token rejected: ${res.code} ${res.message}."
                                is VezirApi.Result.NetworkError ->
                                    "Network error: ${res.cause.message}."
                            }
                            busy = false
                        }
                    },
                    enabled = url.isNotBlank() && token.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Verify token") }
            }
        }

        Button(
            onClick = {
                prefs.serverUrl = url
                prefs.token = token
                status = "Saved."
                onConfigured()
            },
            enabled = url.isNotBlank() && token.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save and continue") }

        if (status != null) {
            MonoStatus(status!!)
        }
    }
}
