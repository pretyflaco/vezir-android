package com.vezir.android.net

import android.util.Log
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Creates OkHttpClient builders that trust both the system CAs and a
 * custom CA cert embedded in the enrollment QR payload (v2).
 *
 * When `ca_pem` is null or invalid, callers fall back to a plain
 * `OkHttpClient.Builder()` (system trust only), which matches the
 * pre-0.1.6 behavior.
 */
object CaTrustManager {

    private const val TAG = "CaTrustManager"

    /**
     * Return an [OkHttpClient.Builder] configured to trust both the
     * system CAs and the given PEM-encoded CA certificate, or `null` if
     * the PEM is invalid / unparseable.
     */
    fun builderWithCa(pem: String): OkHttpClient.Builder? {
        val cert = parsePem(pem)
        if (cert == null) {
            Log.w(TAG, "could not parse CA PEM; falling back to system trust")
            return null
        }
        return try {
            val customKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("vezir-ca", cert)
            }
            val customTmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm(),
            ).apply { init(customKs) }

            val systemTmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm(),
            ).apply { init(null as KeyStore?) }

            val composite = CompositeTrustManager(
                delegates = systemTmf.trustManagers.filterIsInstance<X509TrustManager>() +
                    customTmf.trustManagers.filterIsInstance<X509TrustManager>(),
            )

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(composite), null)
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, composite)
        } catch (e: Exception) {
            Log.e(TAG, "failed to build custom TLS trust", e)
            null
        }
    }

    private fun parsePem(pem: String): X509Certificate? = try {
        val cf = CertificateFactory.getInstance("X.509")
        cf.generateCertificate(
            ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8)),
        ) as X509Certificate
    } catch (e: Exception) {
        Log.w(TAG, "PEM parse failed: ${e.message}")
        null
    }
}

/**
 * Tries each delegate [X509TrustManager] in order. Throws only if ALL
 * delegates reject the certificate chain. This is the standard OkHttp
 * pattern for combining system + custom CAs.
 */
private class CompositeTrustManager(
    private val delegates: List<X509TrustManager>,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        var lastEx: CertificateException? = null
        for (tm in delegates) {
            try { tm.checkClientTrusted(chain, authType); return }
            catch (e: CertificateException) { lastEx = e }
        }
        throw lastEx ?: CertificateException("no trust managers configured")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        var lastEx: CertificateException? = null
        for (tm in delegates) {
            try { tm.checkServerTrusted(chain, authType); return }
            catch (e: CertificateException) { lastEx = e }
        }
        throw lastEx ?: CertificateException("no trust managers configured")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
}
