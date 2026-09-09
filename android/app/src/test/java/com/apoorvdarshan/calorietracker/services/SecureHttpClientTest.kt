package com.apoorvdarshan.calorietracker.services

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import javax.net.ssl.SSLPeerUnverifiedException

class SecureHttpClientTest {
    private fun certificate(bits: Int, hostname: String = "localhost"): HeldCertificate =
        HeldCertificate.Builder()
            .keyPair(KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair())
            .commonName(hostname).addSubjectAlternativeName(hostname).build()

    private fun withTls(bits: Int, hostname: String = "localhost", test: (MockWebServer, okhttp3.OkHttpClient) -> Unit) {
        val cert = certificate(bits, hostname)
        val serverKeys = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientKeys = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverKeys.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val client = SecureHttpClient.builder()
                .sslSocketFactory(clientKeys.sslSocketFactory(), clientKeys.trustManager).build()
            try { test(server, client) }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun acceptsStrongTrustedHttps() = withTls(2048) { server, client ->
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            assertEquals("ok", it.body!!.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsWeakKeyBeforeSendingCredentials() = withTls(1024) { server, client ->
        try {
            client.newCall(Request.Builder().url(server.url("/"))
                .header("Authorization", "Bearer test-only-placeholder").build()).execute().close()
            fail("Weak certificate must be rejected")
        } catch (error: SSLPeerUnverifiedException) {
            assertTrue(error.message.orEmpty().contains("minimum strength"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun preservesHostnameVerification() = withTls(2048, "different.invalid") { server, client ->
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            fail("Wrong hostname must be rejected")
        } catch (_: SSLPeerUnverifiedException) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun retainsExplicitLocalHttpSupport() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("local"))
            server.start()
            val client = SecureHttpClient.builder().build()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    assertEquals("local", it.body!!.string())
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}
