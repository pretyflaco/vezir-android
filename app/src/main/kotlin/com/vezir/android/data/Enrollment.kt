package com.vezir.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Versioned QR-payload schema produced by `vezir/server/enroll.py`:
 *
 *     {"v":1,"url":"http://muscle.tail178bd.ts.net:8000","token":"vzr_..."}
 *
 * We accept exactly v=1 in this app build. Bumping `v` server-side without
 * shipping a matching app build will be rejected — by design.
 */
@Serializable
data class EnrollmentPayload(
    val v: Int,
    val url: String,
    val token: String,
) {
    fun isValid(): Boolean =
        v == SUPPORTED_VERSION &&
            url.isNotBlank() &&
            (url.startsWith("http://") || url.startsWith("https://")) &&
            token.isNotBlank()

    companion object {
        const val SUPPORTED_VERSION = 1

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
