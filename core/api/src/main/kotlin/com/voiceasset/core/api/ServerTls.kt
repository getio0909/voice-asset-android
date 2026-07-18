package com.voiceasset.core.api

import com.voiceasset.core.model.CertificateFingerprint
import com.voiceasset.core.model.ServerProfile
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object ServerTls {
    fun buildClient(profile: ServerProfile): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)

        if (profile.customCaPem != null || profile.certificateFingerprint != null) {
            val delegates =
                buildList {
                    add(defaultTrustManager())
                    profile.customCaPem?.let { add(customCaTrustManager(it)) }
                }
            val composite = CompositeTrustManager(delegates)
            val trustManager =
                profile.certificateFingerprint?.let { fingerprint ->
                    CertificateFingerprintTrustManager(composite, fingerprint)
                } ?: composite
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), null)
            builder.sslSocketFactory(context.socketFactory, trustManager)
        }

        return builder.build()
    }
}

// Every delegate comes from TrustManagerFactory; this only accepts a chain one delegate already validated.
@Suppress("CustomX509TrustManager")
private class CompositeTrustManager(
    private val delegates: List<X509TrustManager>,
) : X509TrustManager {
    init {
        require(delegates.isNotEmpty()) { "at least one trust manager is required" }
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        checkTrusted { delegate -> delegate.checkClientTrusted(chain, authType) }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        checkTrusted { delegate -> delegate.checkServerTrusted(chain, authType) }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { trustManager -> trustManager.acceptedIssuers.asList() }.toTypedArray()

    private inline fun checkTrusted(check: (X509TrustManager) -> Unit) {
        var lastFailure: CertificateException? = null
        delegates.forEach { delegate ->
            try {
                check(delegate)
                return
            } catch (exception: CertificateException) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: CertificateException("certificate chain is not trusted")
    }
}

// The platform/custom delegate validates the chain before this class applies a constant-time leaf-certificate pin.
@Suppress("CustomX509TrustManager")
private class CertificateFingerprintTrustManager(
    private val delegate: X509TrustManager,
    fingerprint: CertificateFingerprint,
) : X509TrustManager {
    private val expectedFingerprint = fingerprint.value.hexToBytes()

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        delegate.checkServerTrusted(chain, authType)
        val leaf = chain?.firstOrNull() ?: throw CertificateException("server did not present a certificate")
        val actualFingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(expectedFingerprint, actualFingerprint)) {
            throw CertificateException("server certificate fingerprint does not match the profile")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

private fun defaultTrustManager(): X509TrustManager = trustManagerFactory(null)

private fun customCaTrustManager(pem: String): X509TrustManager {
    val certificates =
        try {
            CertificateFactory
                .getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
                .map { certificate -> certificate as X509Certificate }
        } catch (exception: Exception) {
            throw IllegalArgumentException("custom CA is not valid X.509 PEM data", exception)
        }
    require(certificates.isNotEmpty()) { "custom CA must contain at least one certificate" }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null)
    certificates.forEachIndexed { index, certificate ->
        keyStore.setCertificateEntry("voiceasset-custom-ca-$index", certificate)
    }
    return trustManagerFactory(keyStore)
}

private fun trustManagerFactory(keyStore: KeyStore?): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
        ?: throw IllegalStateException("platform did not provide exactly one X.509 trust manager")
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { octet -> octet.toInt(16).toByte() }.toByteArray()
