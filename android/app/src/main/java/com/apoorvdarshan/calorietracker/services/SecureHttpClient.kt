package com.apoorvdarshan.calorietracker.services

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.interfaces.ECKey
import java.security.interfaces.RSAKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/** Shared HTTPS defaults. Platform trust and hostname verification remain enabled. */
internal object SecureHttpClient {
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT))
        .addNetworkInterceptor { chain ->
            // A network interceptor runs after platform TLS validation but before request
            // headers/body are sent, including each redirect and pooled connection use.
            if (chain.request().url.isHttps) {
                val handshake = chain.connection()?.handshake()
                    ?: throw SSLPeerUnverifiedException("Missing HTTPS handshake")
                if (handshake.peerCertificates.isEmpty()) {
                    throw SSLPeerUnverifiedException("Missing HTTPS peer certificate")
                }
                handshake.peerCertificates.forEach { certificate ->
                    val cert = certificate as? X509Certificate
                        ?: throw SSLPeerUnverifiedException("Unsupported peer certificate")
                    val strongKey = when (val key = cert.publicKey) {
                        is RSAKey -> key.modulus.bitLength() >= 2048
                        is ECKey -> key.params.curve.field.fieldSize >= 256
                        else -> key.algorithm == "Ed25519" || key.algorithm == "Ed448"
                    }
                    if (!strongKey) {
                        throw SSLPeerUnverifiedException("HTTPS certificate key is below the minimum strength")
                    }
                    val signature = cert.sigAlgName.uppercase(java.util.Locale.ROOT).replace("-", "")
                    if (signature.contains("MD2") || signature.contains("MD5") || signature.contains("SHA1")) {
                        throw SSLPeerUnverifiedException("HTTPS certificate uses a weak signature hash")
                    }
                }
            }
            // User-selected local HTTP endpoints have no cryptographic handshake.
            chain.proceed(chain.request())
        }
}
