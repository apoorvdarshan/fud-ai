package com.apoorvdarshan.calorietracker.services.ondevice

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Streams one pinned model artifact into a `.part` file that survives failed attempts and app
 * restarts. Every attempt resumes with an HTTP Range request from the bytes already on disk,
 * transient network failures are retried with backoff, and the SHA-256 always covers the whole file.
 */
internal class ModelDownloader(
    client: OkHttpClient,
    private val retryDelaysMillis: LongArray = DEFAULT_RETRY_DELAYS_MILLIS,
    private val openOutput: (File) -> FileOutputStream = { FileOutputStream(it, true) }
) {
    // The shared client caps a whole call at 75 s, which a multi-gigabyte body cannot meet.
    // Its read timeout still detects a stalled connection.
    private val client = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

    private class Transfer(var offset: Long, val digest: MessageDigest)

    /**
     * Returns once [part] holds the complete artifact and its hash matched. A partial that failed
     * verification is deleted; any other failure leaves it on disk for the next attempt.
     */
    suspend fun download(
        descriptor: LocalModelDescriptor,
        part: File,
        onCall: (Call) -> Unit,
        onProgress: (Long) -> Unit
    ) {
        if (!isResumable(part, descriptor)) discardPartial(part)
        if (!part.exists()) {
            sourceFile(part).writeText(sourceIdentity(descriptor))
            if (!part.createNewFile()) {
                throw LocalModelInstallError("The model download could not be started.", R.string.local_model_error_start)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val transfer = Transfer(hashExisting(part, digest), digest)
        onProgress(transfer.offset)

        // A complete partial left by an earlier run never reaches the network: it is only verified.
        var failures = 0
        while (transfer.offset < descriptor.expectedBytes) {
            val before = transfer.offset
            try {
                transfer(descriptor, part, transfer, onCall, onProgress)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                // Only consecutive attempts that received nothing use up the retry budget.
                if (transfer.offset > before) failures = 0
                if (failures >= retryDelaysMillis.size) throw error
                delay(retryDelaysMillis[failures++])
            }
        }

        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(descriptor.sha256, ignoreCase = true)) {
            discardPartial(part)
            throw LocalModelInstallError("The model download failed integrity verification.", R.string.local_model_error_integrity)
        }
    }

    private suspend fun transfer(
        descriptor: LocalModelDescriptor,
        part: File,
        transfer: Transfer,
        onCall: (Call) -> Unit,
        onProgress: (Long) -> Unit
    ) {
        val expected = descriptor.expectedBytes
        // A write that failed midway can leave bytes the digest never saw; drop them first.
        if (part.length() != transfer.offset) writeLocally { truncate(part, transfer.offset) }
        val request = Request.Builder()
            .url(descriptor.downloadUrl)
            .apply { if (transfer.offset > 0L) header("Range", "bytes=${transfer.offset}-") }
            .get()
            .build()
        val call = client.newCall(request)
        onCall(call)
        currentCoroutineContext().ensureActive()
        readRemotely { call.execute() }.use { response ->
            when (response.code) {
                206 -> if (!continuesAt(response, transfer.offset, expected)) {
                    restart(part, transfer, onProgress)
                    throw LocalModelInstallIOException("The model server returned an unexpected byte range.", R.string.local_model_error_server)
                }
                200 -> {
                    // The server ignored the Range header, so the body starts at byte zero.
                    if (transfer.offset > 0L) restart(part, transfer, onProgress)
                    val declaredLength = response.body.contentLength()
                    if (declaredLength >= 0L && declaredLength != expected) {
                        discardPartial(part)
                        throw LocalModelInstallError("The model download size did not match the published artifact.", R.string.local_model_error_size)
                    }
                }
                // Only reachable with a partial shorter than the artifact, which the server no
                // longer accepts as a resume point.
                416 -> {
                    restart(part, transfer, onProgress)
                    throw LocalModelInstallIOException("The model server rejected the resume offset.", R.string.local_model_error_server)
                }
                408, 429, in 500..599 -> throw LocalModelInstallIOException("Download failed (HTTP ${response.code}).", R.string.local_model_error_http, response.code)
                else -> throw LocalModelInstallError("Download failed (HTTP ${response.code}).", R.string.local_model_error_http, response.code)
            }
            var lastPublished = transfer.offset
            writeLocally { openOutput(part) }.use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = readRemotely { input.read(buffer) }
                        if (count < 0) break
                        if (count > expected - transfer.offset) {
                            discardPartial(part)
                            throw LocalModelInstallError("The model download size did not match the published artifact.", R.string.local_model_error_size)
                        }
                        writeLocally { output.write(buffer, 0, count) }
                        transfer.digest.update(buffer, 0, count)
                        transfer.offset += count
                        if (transfer.offset - lastPublished >= PROGRESS_STEP_BYTES) {
                            lastPublished = transfer.offset
                            onProgress(transfer.offset)
                        }
                    }
                }
                writeLocally { output.fd.sync() }
            }
            onProgress(transfer.offset)
            if (transfer.offset < expected) throw LocalModelInstallIOException("The model download was incomplete.", R.string.local_model_error_incomplete)
        }
    }

    /**
     * Only network failures are worth another attempt. A write that fails (for example because
     * the disk is full) would fail the same way after every reconnect, so it ends the download.
     */
    private inline fun <T> writeLocally(block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw ModelStorageException(error)
        }

    /**
     * Connecting to the server and reading its body are the network, whatever the stack reports:
     * a dropped response surfaces as an `EOFException` or `ProtocolException`, which by type alone
     * look like file errors. Marking them here lets Settings tell them from local file failures.
     */
    private inline fun <T> readRemotely(block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw ModelNetworkException(error)
        }

    private fun restart(part: File, transfer: Transfer, onProgress: (Long) -> Unit) {
        writeLocally { truncate(part, 0L) }
        transfer.offset = 0L
        transfer.digest.reset()
        onProgress(0L)
    }

    /** SHA-256 cannot be resumed across processes, so the kept bytes are hashed again. */
    private suspend fun hashExisting(part: File, digest: MessageDigest): Long {
        var hashed = 0L
        part.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                hashed += count
            }
        }
        return hashed
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
        private const val PROGRESS_STEP_BYTES = 1L * 1024L * 1024L
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

        /** Records which artifact a partial belongs to, so a changed descriptor never resumes it. */
        fun sourceFile(part: File): File = File(part.parentFile, "${part.name}.source")

        fun isResumable(part: File, descriptor: LocalModelDescriptor): Boolean =
            part.isFile && part.length() <= descriptor.expectedBytes &&
                runCatching { sourceFile(part).readText() }.getOrNull() == sourceIdentity(descriptor)

        /** Length of a partial the next download would resume, or zero when there is none. */
        fun keptBytes(part: File, descriptor: LocalModelDescriptor): Long =
            if (isResumable(part, descriptor)) part.length() else 0L

        fun discardPartial(part: File) {
            part.delete()
            sourceFile(part).delete()
        }

        private fun sourceIdentity(descriptor: LocalModelDescriptor): String =
            "${descriptor.sha256}\n${descriptor.expectedBytes}\n${descriptor.downloadUrl}"

        private fun truncate(part: File, length: Long) {
            RandomAccessFile(part, "rw").use { it.setLength(length) }
        }

        /** A 206 is appended only when it starts exactly where the partial ends. */
        private fun continuesAt(response: Response, offset: Long, expected: Long): Boolean {
            val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty().trim())
                ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val end = match.groupValues[2].toLongOrNull() ?: return false
            val total = match.groupValues[3]
            return start == offset && end in start until expected &&
                (total == "*" || total.toLongOrNull() == expected)
        }
    }
}

/** Writing the partial failed on this device; the bytes written before it stay for a resume. */
internal class ModelStorageException(cause: IOException) : IllegalStateException(cause.message, cause)

/**
 * The model server could not be reached or stopped answering mid-response. Still an [IOException],
 * so the downloader retries it; once the retries are spent Settings shows the connection hint.
 */
internal class ModelNetworkException(cause: IOException) : IOException(cause.message, cause)

/**
 * An install failure the Settings screen shows as [messageRes] (formatted with [args]).
 * The English message is for logs and tests.
 */
internal class LocalModelInstallError(
    message: String,
    @StringRes val messageRes: Int,
    vararg val args: Any
) : IllegalStateException(message)

/** The transient variant that keeps the downloader's IOException retry path. */
internal class LocalModelInstallIOException(
    message: String,
    @StringRes val messageRes: Int,
    vararg val args: Any
) : IOException(message)
