package com.apoorvdarshan.calorietracker.services.ondevice

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private val artifact = Random(510).nextBytes(200_000)
    private val split = 70_000
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var part: File

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        part = File(temp.root, "model.bin.part")
    }

    @After fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test fun disconnectMidBodyResumesWithRangeAndVerifies() {
        server.enqueue(truncatedFullResponse(artifact, split))
        server.enqueue(rangeResponse(artifact, split))

        val progress = download(retryDelays = longArrayOf(0))

        assertArrayEquals(artifact, part.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertEquals(2, server.requestCount)
        assertEquals(artifact.size.toLong(), progress.last())
    }

    @Test fun sharedClientCallTimeoutDoesNotCapALongDownload() {
        // The app's shared client limits a whole call to 75 s; scaled down, the same cap must not
        // abort a body that keeps streaming past it.
        val shared = client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(artifact))
                .throttleBody(50_000, 250, TimeUnit.MILLISECONDS)
        )

        runBlocking {
            ModelDownloader(shared, retryDelaysMillis = longArrayOf())
                .download(descriptor(artifact), part, onCall = {}, onProgress = {})
        }

        assertArrayEquals(artifact, part.readBytes())
    }

    @Test fun partialFromAnEarlierRunResumesAndReportsKeptBytes() {
        leavePartial(artifact.copyOf(split))
        server.enqueue(rangeResponse(artifact, split))

        val progress = download()

        assertArrayEquals(artifact, part.readBytes())
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertEquals(split.toLong(), progress.first())
        assertEquals(artifact.size.toLong(), progress.last())
    }

    @Test fun serverIgnoringRangeRestartsCleanlyFromZero() {
        // Wrong kept bytes prove the 200 body replaces the partial instead of being appended.
        leavePartial(ByteArray(split) { 0x5a })
        server.enqueue(MockResponse().setBody(Buffer().write(artifact)))

        val progress = download()

        assertArrayEquals(artifact, part.readBytes())
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertTrue(progress.contains(0L))
        // The interrupted run's request plus one: the full 200 body is used, not requested again.
        assertEquals(2, server.requestCount)
    }

    @Test fun oversizedPartialIsDiscardedBeforeRequesting() {
        leavePartial(artifact.copyOf(split))
        part.appendBytes(ByteArray(artifact.size))
        server.enqueue(MockResponse().setBody(Buffer().write(artifact)))

        download()

        assertArrayEquals(artifact, part.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun partialOfADifferentArtifactIsDiscarded() {
        val previous = Random(566).nextBytes(artifact.size)
        leavePartial(previous.copyOf(split), descriptor(previous))
        server.enqueue(MockResponse().setBody(Buffer().write(artifact)))

        download()

        assertArrayEquals(artifact, part.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun corruptedPartialFailsVerificationAndIsDeleted() {
        leavePartial(ByteArray(split) { 0x5a })
        server.enqueue(rangeResponse(artifact, split))

        val error = downloadFailure()

        assertTrue(error is IllegalStateException)
        assertTrue(error.message.orEmpty().contains("integrity verification"))
        assertFalse(part.exists())
        assertFalse(ModelDownloader.sourceFile(part).exists())
    }

    @Test fun hashMismatchFailsAndCleansUp() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(artifact.size) { 0x01 })))

        val error = downloadFailure()

        assertTrue(error.message.orEmpty().contains("integrity verification"))
        assertFalse(part.exists())
        assertFalse(ModelDownloader.sourceFile(part).exists())
    }

    @Test fun completePartialIsVerifiedWithoutTheNetwork() {
        leavePartial(artifact.copyOf(split))
        part.appendBytes(artifact.copyOfRange(split, artifact.size))
        val before = server.requestCount

        val progress = download()

        assertArrayEquals(artifact, part.readBytes())
        assertEquals(before, server.requestCount)
        assertEquals(listOf(artifact.size.toLong()), progress)
    }

    @Test fun unexpectedContentRangeRestartsFromZero() {
        leavePartial(artifact.copyOf(split))
        server.enqueue(rangeResponse(artifact, split, start = 0))
        server.enqueue(MockResponse().setBody(Buffer().write(artifact)))

        download(retryDelays = longArrayOf(0))

        assertArrayEquals(artifact, part.readBytes())
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun rangeNotSatisfiableRestartsFromZero() {
        leavePartial(artifact.copyOf(split))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(Buffer().write(artifact)))

        download(retryDelays = longArrayOf(0))

        assertArrayEquals(artifact, part.readBytes())
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun retriesStopAfterConsecutiveFailuresWithoutProgressAndKeepThePartial() {
        server.enqueue(truncatedFullResponse(artifact, split))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val error = downloadFailure(retryDelays = longArrayOf(0, 0))

        assertTrue(error is IOException)
        // The first attempt made progress, so it did not spend any of the two retries.
        assertEquals(3, server.requestCount)
        assertEquals(split.toLong(), part.length())
        assertTrue(ModelDownloader.isResumable(part, descriptor(artifact)))
    }

    @Test fun keptBytesReportThePartialUntilItIsDiscarded() {
        // Settings offers Delete for exactly these bytes, so a failed download never strands them.
        assertEquals(0L, ModelDownloader.keptBytes(part, descriptor(artifact)))

        leavePartial(artifact.copyOf(split))

        assertEquals(split.toLong(), ModelDownloader.keptBytes(part, descriptor(artifact)))
        assertEquals(0L, ModelDownloader.keptBytes(part, descriptor(Random(566).nextBytes(artifact.size))))
        ModelDownloader.discardPartial(part)
        assertEquals(0L, ModelDownloader.keptBytes(part, descriptor(artifact)))
        assertFalse(ModelDownloader.sourceFile(part).exists())
    }

    @Test fun permanentHttpFailureIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = downloadFailure(retryDelays = longArrayOf(0, 0, 0))

        assertTrue(error is IllegalStateException)
        assertEquals(1, server.requestCount)
    }

    @Test fun localWriteFailureIsNotRetriedAsANetworkError() {
        // A full disk fails every write the same way, so reconnecting would only repeat the request.
        leavePartial(artifact.copyOf(split))
        val before = server.requestCount
        server.enqueue(rangeResponse(artifact, split))
        server.enqueue(rangeResponse(artifact, split))
        val downloader = ModelDownloader(client, retryDelaysMillis = longArrayOf(0, 0, 0)) { file ->
            object : FileOutputStream(file, true) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw IOException("write failed: ENOSPC (No space left on device)")
                }
            }
        }

        val error = try {
            runBlocking { downloader.download(descriptor(artifact), part, onCall = {}, onProgress = {}) }
            null
        } catch (failure: Throwable) {
            failure
        }

        assertTrue(error is ModelStorageException)
        assertTrue(error?.message.orEmpty().contains("ENOSPC"))
        assertEquals("bytes=$split-", server.takeRequest().getHeader("Range"))
        assertEquals(before + 1, server.requestCount)
        // The bytes already written stay, so freeing space and tapping Download resumes them.
        assertArrayEquals(artifact.copyOf(split), part.readBytes())
        assertTrue(ModelDownloader.isResumable(part, descriptor(artifact)))
    }

    @Test fun responseDroppedBeforeAnyByteIsReportedAsANetworkFailure() {
        // OkHttp reports the cut body as a ProtocolException or EOFException, which by type alone
        // look like file errors; the downloader marks them as the connection problem they are.
        repeat(3) { server.enqueue(truncatedFullResponse(artifact, 0)) }

        val error = downloadFailure(retryDelays = longArrayOf(0, 0))

        assertTrue(error is ModelNetworkException)
        assertTrue(error.cause is IOException)
        assertEquals(3, server.requestCount)
        assertTrue(ModelDownloader.isResumable(part, descriptor(artifact)))
    }

    @Test fun partialThatCannotBeOpenedIsAStorageFailure() {
        server.enqueue(MockResponse().setBody(Buffer().write(artifact)))
        val downloader = ModelDownloader(client, retryDelaysMillis = longArrayOf(0, 0, 0)) { file ->
            throw FileNotFoundException("$file: open failed: EACCES (Permission denied)")
        }

        val error = runCatching {
            runBlocking { downloader.download(descriptor(artifact), part, onCall = {}, onProgress = {}) }
        }.exceptionOrNull()

        assertTrue(error is ModelStorageException)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringBackoffIsPrompt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val downloader = ModelDownloader(client, retryDelaysMillis = longArrayOf(60_000))
        val job = launch(Dispatchers.IO) {
            downloader.download(descriptor(artifact), part, onCall = {}, onProgress = {})
        }
        server.takeRequest(5, TimeUnit.SECONDS)

        withTimeout(5_000) {
            job.cancel()
            job.join()
        }

        assertTrue(job.isCancelled)
    }

    /** Leaves [bytes] on disk the way an interrupted earlier run would. */
    private fun leavePartial(bytes: ByteArray, descriptor: LocalModelDescriptor = descriptor(artifact)) {
        server.enqueue(truncatedFullResponse(ByteArray(descriptor.expectedBytes.toInt()).also {
            bytes.copyInto(it)
        }, bytes.size))
        try {
            runBlocking {
                ModelDownloader(client, retryDelaysMillis = longArrayOf())
                    .download(descriptor, part, onCall = {}, onProgress = {})
            }
            fail("The interrupted download should fail")
        } catch (expected: IOException) {
        }
        assertArrayEquals(bytes, part.readBytes())
        server.takeRequest()
    }

    private fun download(retryDelays: LongArray = longArrayOf()): List<Long> {
        val progress = mutableListOf<Long>()
        runBlocking {
            ModelDownloader(client, retryDelays)
                .download(descriptor(artifact), part, onCall = {}, onProgress = { progress += it })
        }
        return progress
    }

    private fun downloadFailure(retryDelays: LongArray = longArrayOf()): Throwable {
        try {
            download(retryDelays)
        } catch (error: Throwable) {
            return error
        }
        throw AssertionError("Expected the download to fail")
    }

    private fun descriptor(bytes: ByteArray) = LocalModelDescriptor(
        id = LocalModelId.WHISPER_BASE,
        displayName = "Test model",
        fileName = "model.bin",
        downloadUrl = server.url("/model.bin").toString(),
        expectedBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        licenseName = "MIT",
        licenseUrl = "https://example.invalid/license",
        sourceUrl = "https://example.invalid/source",
        minimumMemoryClassGb = null,
        supportedAbis = setOf("arm64-v8a")
    )

    /** Declares the whole artifact but drops the connection after [sent] bytes. */
    private fun truncatedFullResponse(bytes: ByteArray, sent: Int) = MockResponse()
        .setBody(Buffer().write(bytes, 0, sent))
        .setHeader("Content-Length", bytes.size)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)

    private fun rangeResponse(bytes: ByteArray, from: Int, start: Int = from) = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
        .setBody(Buffer().write(bytes, from, bytes.size - from))
}
