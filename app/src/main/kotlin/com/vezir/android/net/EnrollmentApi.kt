package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class EnrollmentApi(
    private val baseUrl: String,
    caPem: String? = null,
) {
    private val client = HttpClients.build(caPem, refreshOn401 = false)
        .newBuilder()
        .retryOnConnectionFailure(false)
        .build()

    @Serializable
    data class ExchangeResponse(
        val token: String = "",
        val ca_pem: String? = null,
    )

    sealed class Result {
        data class Ok(val data: ExchangeResponse) : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val cause: IOException) : Result()
        object ServerUpgradeRequired : Result()
        object InvalidResponse : Result()
    }

    suspend fun exchange(code: String): Result = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code).toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/enroll/exchange")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 404 -> Result.ServerUpgradeRequired
                    !response.isSuccessful -> Result.HttpError(
                        response.code,
                        errorDetail(body, response.message),
                    )
                    else -> parseSuccess(body)
                }
            }
        } catch (e: IOException) {
            Result.NetworkError(e)
        }
    }

    private fun parseSuccess(body: String): Result = try {
        val response = json.decodeFromString(ExchangeResponse.serializer(), body)
        if (response.token.isBlank()) {
            Result.InvalidResponse
        } else {
            Result.Ok(response.copy(ca_pem = response.ca_pem?.ifBlank { null }))
        }
    } catch (_: SerializationException) {
        Result.InvalidResponse
    } catch (_: IllegalArgumentException) {
        Result.InvalidResponse
    }

    private fun errorDetail(body: String, fallback: String): String =
        try {
            JSONObject(body).optString("detail", fallback)
        } catch (_: Exception) {
            fallback
        }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

internal suspend fun exchangeAndSaveEnrollment(
    url: String,
    token: String,
    caPem: String?,
    enrollCode: String?,
    persist: (String, String, String?) -> Unit,
    exchange: suspend (String, String?, String) -> EnrollmentApi.Result = { baseUrl, ca, code ->
        EnrollmentApi(baseUrl, ca).exchange(code)
    },
): EnrollmentApi.Result {
    if (enrollCode == null) {
        persist(url, token, caPem)
        return EnrollmentApi.Result.Ok(EnrollmentApi.ExchangeResponse(token, caPem))
    }
    return when (val result = exchange(url, caPem, enrollCode)) {
        is EnrollmentApi.Result.Ok -> {
            val resolved = result.data.copy(ca_pem = result.data.ca_pem ?: caPem)
            persist(url, resolved.token, resolved.ca_pem)
            EnrollmentApi.Result.Ok(resolved)
        }
        else -> result
    }
}

fun enrollmentErrorMessage(result: EnrollmentApi.Result): String = when (result) {
    is EnrollmentApi.Result.Ok -> "Enrollment succeeded."
    is EnrollmentApi.Result.HttpError ->
        "Enrollment failed (${result.code}): ${result.message}"
    is EnrollmentApi.Result.NetworkError ->
        "Network error: ${result.cause.message}. The code was not retried; " +
            "request a new QR if the server consumed it."
    EnrollmentApi.Result.ServerUpgradeRequired ->
        "Enrollment codes need Vezir server 0.27.0 or newer. " +
            "Upgrade the server or use a legacy QR."
    EnrollmentApi.Result.InvalidResponse ->
        "The server returned an invalid enrollment response."
}
