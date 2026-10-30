package com.vezir.android.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnrollmentApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postsCodeToExchangeEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"vzr_x"}"""))

        EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/enroll/exchange", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("""{"code":"vzre_abc"}""", request.body.readUtf8())
    }

    @Test
    fun sendsNoBearerOrTeamHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"vzr_x"}"""))

        EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-Team-Id"))
    }

    @Test
    fun successReturnsBearerAndOptionalCa() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"vzr_x","github":"alice","label":"phone","ca_pem":"pem"}""",
            ),
        )

        val result = EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        assertTrue(result is EnrollmentApi.Result.Ok)
        val ok = result as EnrollmentApi.Result.Ok
        assertEquals("vzr_x", ok.data.token)
        assertEquals("pem", ok.data.ca_pem)
    }

    @Test
    fun doesNotRetryNetworkFailure() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"token":"vzr_should_not_be_used"}"""))

        val result = EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        assertTrue(result is EnrollmentApi.Result.NetworkError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun notFoundMeansServerUpgradeRequired() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))

        val result = EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        assertTrue(result is EnrollmentApi.Result.ServerUpgradeRequired)
    }

    @Test
    fun rejectedCodeReturnsHttpErrorDetail() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"invalid enrollment code"}"""),
        )

        val result = EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        assertTrue(result is EnrollmentApi.Result.HttpError)
        val error = result as EnrollmentApi.Result.HttpError
        assertEquals(401, error.code)
        assertEquals("invalid enrollment code", error.message)
    }

    @Test
    fun malformedSuccessIsInvalidResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))

        val result = EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        assertTrue(result is EnrollmentApi.Result.InvalidResponse)
    }

    @Test
    fun blankSuccessTokenIsInvalidResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":" "}"""))

        val result = EnrollmentApi(server.url("/").toString()).exchange("vzre_abc")

        assertTrue(result is EnrollmentApi.Result.InvalidResponse)
    }
}
