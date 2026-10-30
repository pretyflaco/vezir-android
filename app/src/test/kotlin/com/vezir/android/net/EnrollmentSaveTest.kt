package com.vezir.android.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentSaveTest {
    @Test
    fun codeExchangePersistsBearerBeforeReturningSuccess() = runBlocking {
        val events = mutableListOf<String>()
        var saved: Triple<String, String, String?>? = null

        val result = exchangeAndSaveEnrollment(
            url = "https://example.com",
            token = "",
            caPem = "qr-ca",
            enrollCode = "vzre_abc",
            persist = { url, token, ca ->
                events += "persist"
                saved = Triple(url, token, ca)
            },
            exchange = { url, ca, code ->
                assertEquals("https://example.com", url)
                assertEquals("qr-ca", ca)
                assertEquals("vzre_abc", code)
                events += "exchange"
                EnrollmentApi.Result.Ok(EnrollmentApi.ExchangeResponse(token = "vzr_returned"))
            },
        )

        assertTrue(result is EnrollmentApi.Result.Ok)
        assertEquals(listOf("exchange", "persist"), events)
        assertEquals(Triple("https://example.com", "vzr_returned", "qr-ca"), saved)
    }

    @Test
    fun everyExchangeErrorLeavesPersistenceUntouched() = runBlocking {
        val errors = listOf(
            EnrollmentApi.Result.HttpError(401, "invalid enrollment code"),
            EnrollmentApi.Result.ServerUpgradeRequired,
            EnrollmentApi.Result.NetworkError(IOException("lost response")),
            EnrollmentApi.Result.InvalidResponse,
        )

        errors.forEach { error ->
            var persisted = false
            val result = exchangeAndSaveEnrollment(
                url = "https://example.com",
                token = "",
                caPem = null,
                enrollCode = "vzre_abc",
                persist = { _, _, _ -> persisted = true },
                exchange = { _, _, _ -> error },
            )

            assertSame(error, result)
            assertFalse(persisted)
        }
    }

    @Test
    fun legacySavePersistsWithoutExchange() = runBlocking {
        var saved: Triple<String, String, String?>? = null

        val result = exchangeAndSaveEnrollment(
            url = "https://example.com",
            token = "vzr_legacy",
            caPem = "legacy-ca",
            enrollCode = null,
            persist = { url, token, ca -> saved = Triple(url, token, ca) },
            exchange = { _, _, _ -> error("legacy save must not exchange a code") },
        )

        assertTrue(result is EnrollmentApi.Result.Ok)
        assertEquals(Triple("https://example.com", "vzr_legacy", "legacy-ca"), saved)
    }
}
