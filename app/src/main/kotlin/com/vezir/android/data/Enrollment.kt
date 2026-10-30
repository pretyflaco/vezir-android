package com.vezir.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Versioned QR-payload schema produced by `vezir/server/enroll.py`:
 *
 * v1:  {"v":1,"url":"http://...","token":"vzr_..."}
 * v2:  {"v":2,"url":"https://...","token":"vzr_...","ca_pem":"-----BEGIN CERT..."}
 * v3:  {"v":3,"url":"https://...","enroll_code":"vzre_..."}
 *      or historical v2 + {"alt_urls":["https://100.x.y.z"]}
 *
 * v2 adds the Caddy internal CA root certificate so the app can trust
 * the server's TLS cert without a manual cert install on the device.
 * `ca_pem` is optional even in v2 (some deployments use Let's Encrypt
 * certs that are already in the system trust store).
 *
 * Current v3 payloads carry a single-use enrollment code. Historical v3
 * payloads instead carry a bearer and `alt_urls` for URL failover; both
 * shapes remain accepted, but never together.
 */
@Serializable
data class EnrollmentPayload(
    val v: Int,
    val url: String,
    val token: String? = null,
    val enroll_code: String? = null,
    val ca_pem: String? = null,
    val alt_urls: List<String>? = null,
) {
    fun isValid(): Boolean =
        v in SUPPORTED_VERSIONS &&
            url.isHttpUrl() &&
            (v == 3 || token != null) &&
            ((token == null) != (enroll_code == null)) &&
            (token == null || token.isNotBlank()) &&
            (enroll_code == null || enroll_code.isNotBlank()) &&
            alt_urls.orEmpty().all { it.isHttpUrl() }

    private fun String.isHttpUrl(): Boolean = isNotBlank() && toHttpUrlOrNull() != null

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
