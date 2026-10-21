package com.vezir.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * v0.15.1: the composite must forward hostname-aware calls.  Android's
 * system trust manager rejects the hostname-less overload once
 * network_security_config.xml has a <domain-config> (the pin-set), so a
 * composite that downgraded to it would fail every public host for users
 * enrolled with a custom CA, and would never enforce the pins.
 */
class CompositeTrustManagerTest {

    private val chain = emptyArray<X509Certificate>()

    /** Mimics Android's RootTrustManager with per-domain config. */
    private class HostnameOnly(private val accept: Boolean) : X509ExtendedTrustManager() {
        val calls = mutableListOf<String>()
        private fun verdict(kind: String) {
            calls += kind
            if (!accept) throw CertificateException("rejected ($kind)")
        }
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String) =
            throw CertificateException("Domain specific configurations require hostname aware")
        override fun checkClientTrusted(c: Array<out X509Certificate>, a: String) =
            throw CertificateException("Domain specific configurations require hostname aware")
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String, s: Socket?) =
            verdict("server-socket")
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String, e: SSLEngine?) =
            verdict("server-engine")
        override fun checkClientTrusted(c: Array<out X509Certificate>, a: String, s: Socket?) =
            verdict("client-socket")
        override fun checkClientTrusted(c: Array<out X509Certificate>, a: String, e: SSLEngine?) =
            verdict("client-engine")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class Plain(private val accept: Boolean) : X509TrustManager {
        var calls = 0
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String) {
            calls++
            if (!accept) throw CertificateException("plain rejected")
        }
        override fun checkClientTrusted(c: Array<out X509Certificate>, a: String) =
            checkServerTrusted(c, a)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `socket call reaches the system manager's hostname-aware overload`() {
        val system = HostnameOnly(accept = true)
        val custom = Plain(accept = false)
        CompositeTrustManager(listOf(system, custom))
            .checkServerTrusted(chain, "ECDHE_ECDSA", null as Socket?)
        assertEquals(listOf("server-socket"), system.calls)
        assertEquals("custom CA not consulted once system accepts", 0, custom.calls)
    }

    @Test
    fun `engine call reaches the system manager's hostname-aware overload`() {
        val system = HostnameOnly(accept = true)
        CompositeTrustManager(listOf(system))
            .checkServerTrusted(chain, "ECDHE_ECDSA", null as SSLEngine?)
        assertEquals(listOf("server-engine"), system.calls)
    }

    @Test
    fun `system rejection (e g pin mismatch) falls through to the custom CA`() {
        val system = HostnameOnly(accept = false)
        val custom = Plain(accept = true)
        CompositeTrustManager(listOf(system, custom))
            .checkServerTrusted(chain, "RSA", null as Socket?)
        assertEquals(listOf("server-socket"), system.calls)
        assertEquals(1, custom.calls)
    }

    @Test
    fun `all delegates rejecting throws the last error`() {
        val composite = CompositeTrustManager(
            listOf(HostnameOnly(accept = false), Plain(accept = false)),
        )
        val ex = runCatching { composite.checkServerTrusted(chain, "RSA", null as Socket?) }
            .exceptionOrNull()
        assertTrue(ex is CertificateException)
        assertEquals("plain rejected", ex!!.message)
    }
}
