package com.vezir.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Versioned QR-payload schema produced by `vezir/server/enroll.py`:
 *
 * v1:  {"v":1,"url":"http://...","token":"vzr_..."}
 * v2:  {"v":2,"url":"https://...","token":"vzr_...","ca_pem":"-----BEGIN CERT..."}
 * v3:  v2 + {"alt_urls":["https://100.x.y.z"]}
 *
 * v2 adds the Caddy internal CA root certificate so the app can trust
 * the server's TLS cert without a manual cert install on the device.
 * `ca_pem` is optional even in v2 (some deployments use Let's Encrypt
 * certs that are already in the system trust store).
 *
 * v3 adds `alt_urls`: a list of alternate server URLs for failover
 * when the primary URL is unreachable (e.g., nvpn down, Tailscale up).
 */
@Serializable
data class EnrollmentPayload(
    val v: Int,
    val url: String,
    val token: String,
    val ca_pem: String? = null,
    val alt_urls: List<String>? = null,  // v3: alternate server URLs
) {
    fun isValid(): Boolean =
        v in SUPPORTED_VERSIONS &&
            url.isNotBlank() &&
            (url.startsWith("http://") || url.startsWith("https://")) &&
            token.isNotBlank()

    companion object {
        private val SUPPORTED_VERSIONS = setOf(1, 2, 3)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        /** Parse a scanned QR / pasted JSON payload. Returns null on any error. */
        fun parse(raw: String): EnrollmentPayload? = try {
            val parsed = json.decodeFromString(serializer(), raw.trim())
            if (parsed.isValid()) parsed else null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
