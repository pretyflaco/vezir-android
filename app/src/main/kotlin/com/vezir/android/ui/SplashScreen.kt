package com.vezir.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.vezir.android.R
import kotlinx.coroutines.delay

/**
 * Full-screen brand splash. Shows the stacked vezir lockup
 * (mark + wordmark) centered on the brand surface for [durationMs]
 * milliseconds, then fires [onDone] so the host can route to the
 * regular app content.
 *
 * The Android 12+ OS splash from `Theme.Vezir.Splash` still fires
 * during cold-start process spin-up (typically <300 ms, OS-controlled).
 * This Composable picks up immediately after that, giving the user a
 * deliberate ~1.5 s of the full lockup before the app content
 * appears.
 */
@Composable
fun SplashScreen(
    onDone: () -> Unit,
    durationMs: Long = 1_500L,
) {
    LaunchedEffect(Unit) {
        delay(durationMs)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.vezir_logo_stacked),
            contentDescription = "vezir",
        )
    }
}
