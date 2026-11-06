package com.apoorvdarshan.calorietracker.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.ZipException
import javax.net.ssl.SSLHandshakeException

class NetworkFailuresTest {
    @Test
    fun connectivityFailuresAreNetworkFailures() {
        assertTrue(UnknownHostException("drive").isNetworkFailure())
        assertTrue(SocketTimeoutException("timeout").isNetworkFailure())
        assertTrue(ConnectException("refused").isNetworkFailure())
        assertTrue(SSLHandshakeException("tls").isNetworkFailure())
        assertTrue(IOException("wrapped", UnknownHostException("drive")).isNetworkFailure())
    }

    @Test
    fun localFileAndArchiveFailuresAreNot() {
        assertFalse(FileNotFoundException("backup.zip").isNetworkFailure())
        assertFalse(ZipException("corrupt").isNetworkFailure())
        assertFalse(IOException("disk full").isNetworkFailure())
        assertFalse(IllegalStateException("state").isNetworkFailure())
    }
}
