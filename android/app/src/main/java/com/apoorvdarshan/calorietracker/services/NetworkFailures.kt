package com.apoorvdarshan.calorietracker.services

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * True when this failure, or a cause within a few hops, is a connectivity problem (DNS, connect,
 * socket, TLS, timeout) rather than a local file or archive error. Lets user-facing messages say
 * "check your connection" only when that is what happened.
 */
fun Throwable.isNetworkFailure(): Boolean =
    generateSequence(this) { it.cause }.take(5).any {
        it is UnknownHostException || it is SocketTimeoutException || it is SocketException || it is SSLException
    }
