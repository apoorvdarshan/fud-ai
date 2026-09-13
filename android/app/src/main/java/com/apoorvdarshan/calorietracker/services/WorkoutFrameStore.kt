package com.apoorvdarshan.calorietracker.services

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.decode.SvgDecoder
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.apoorvdarshan.calorietracker.BuildConfig
import com.apoorvdarshan.calorietracker.data.ExerciseVisualFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One authored workout frame as referenced by the manifest. Coil request data for [WorkoutFrameFetcher]. */
data class WorkoutFrameRef(
    val name: String,
    val digest: String?,
    val format: ExerciseVisualFormat
) {
    val extension: String get() = if (format == ExerciseVisualFormat.SVG) "svg" else "png"
    val fileName: String get() = "$name.$extension"

    companion object {
        fun from(framePath: String, digest: String?, format: ExerciseVisualFormat): WorkoutFrameRef? {
            val name = WorkoutFrameLocator.frameName(framePath) ?: return null
            return WorkoutFrameRef(name, WorkoutFrameLocator.normalizedDigest(digest), format)
        }
    }
}

/**
 * Delivers authored workout frames without shipping the 1.2 GB corpus in the APK/AAB.
 *
 * Resolution order for a frame:
 * 1. on-device cache (`cacheDir/workout-vectors/v2/<name>.<digest>.png`),
 * 2. bundled asset (debug builds bundle the small sample pack; release bundles none),
 * 3. download from [BuildConfig.WORKOUT_VECTORS_BASE_URL] (`<base>/<name>.png?v=<digest>`),
 *    verified against the manifest digest and stored in the cache.
 *
 * Anything that fails resolves to null and the UI keeps its placeholder, which is the
 * same behaviour the app had before authored frames existed. Failures are remembered
 * briefly so offline users do not retry every frame on every recomposition.
 */
class WorkoutFrameStore private constructor(
    private val context: Context,
    private val baseUrl: String
) {
    private val cacheDirectory: File by lazy {
        File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<File?>>()
    private val recentFailures = ConcurrentHashMap<String, Long>()
    /** Cache file names whose contents have been verified this process; avoids re-hashing per request. */
    private val verifiedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val downloadsSinceTrim = AtomicInteger(0)
    private val http: OkHttpClient by lazy {
        SecureHttpClient.builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Coil image loader that understands [WorkoutFrameRef] requests. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .components {
                add(WorkoutFrameFetcher.Factory(this@WorkoutFrameStore))
                add(SvgDecoder.Factory())
            }
            .build()
    }

    internal class OpenedFrame(
        val imageSource: ImageSource,
        val dataSource: DataSource
    )

    /** Returns a readable source for [ref], downloading it first when needed, or null. */
    internal suspend fun open(ref: WorkoutFrameRef): OpenedFrame? = withContext(Dispatchers.IO) {
        cachedFile(ref)?.let { file ->
            return@withContext OpenedFrame(ImageSource(file.toOkioPath()), DataSource.DISK)
        }
        bundledAsset(ref)?.let { return@withContext it }
        val downloaded = ensureDownloaded(ref) ?: return@withContext null
        OpenedFrame(ImageSource(downloaded.toOkioPath()), DataSource.NETWORK)
    }

    /** True when the frame can be shown without touching the network. */
    fun isAvailableOffline(ref: WorkoutFrameRef): Boolean =
        cachedFile(ref) != null || hasBundledAsset(ref)

    /**
     * Returns the cached frame only if its contents verify against the manifest (size,
     * PNG signature and digest). Anything else is deleted so the next request repairs
     * it through the bundled or network path instead of failing to decode forever.
     */
    fun cachedFile(ref: WorkoutFrameRef): File? {
        val file = File(cacheDirectory, WorkoutFrameLocator.cacheFileName(ref.name, ref.digest, ref.extension))
        if (!file.isFile) return null
        if (file.name in verifiedFiles) return file
        val valid = file.length() in 1..MAX_FRAME_BYTES &&
            runCatching { verifyFrame(ref, file.readBytes()) }.isSuccess
        if (!valid) {
            verifiedFiles.remove(file.name)
            file.delete()
            return null
        }
        verifiedFiles.add(file.name)
        return file
    }

    private fun verifyFrame(ref: WorkoutFrameRef, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) throw IOException("Bad frame size: ${ref.name}")
        if (ref.extension == "png" && !WorkoutFrameLocator.looksLikePng(bytes)) {
            throw IOException("Not a PNG: ${ref.name}")
        }
        if (!WorkoutFrameLocator.matchesDigest(bytes, ref.digest)) {
            throw IOException("Digest mismatch for ${ref.name}")
        }
    }

    /** Reads at most [MAX_FRAME_BYTES]; aborts as soon as the response exceeds the cap. */
    private fun readBounded(source: BufferedSource, name: String): ByteArray {
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            if (buffer.size > MAX_FRAME_BYTES) throw IOException("Frame too large: $name")
        }
        return buffer.readByteArray()
    }

    private fun hasBundledAsset(ref: WorkoutFrameRef): Boolean =
        runCatching { context.assets.open(ref.fileName).close(); true }.getOrDefault(false)

    private fun bundledAsset(ref: WorkoutFrameRef): OpenedFrame? {
        val stream = runCatching { context.assets.open(ref.fileName) }.getOrNull() ?: return null
        return OpenedFrame(ImageSource(stream.source().buffer(), context), DataSource.DISK)
    }

    private suspend fun ensureDownloaded(ref: WorkoutFrameRef): File? {
        val url = WorkoutFrameLocator.remoteUrl(baseUrl, ref.name, ref.digest, ref.extension) ?: return null
        val failedAt = recentFailures[ref.name]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < FAILURE_RETRY_MS) return null

        val deferred = inFlight.getOrPut(ref.name) {
            scope.async { download(ref, url) }
        }
        return try {
            deferred.await()
        } finally {
            inFlight.remove(ref.name, deferred)
        }
    }

    private fun download(ref: WorkoutFrameRef, url: String): File? {
        cachedFile(ref)?.let { return it }
        val target = File(cacheDirectory, WorkoutFrameLocator.cacheFileName(ref.name, ref.digest, ref.extension))
        val partial = File(cacheDirectory, target.name + ".part")
        return try {
            val request = Request.Builder().url(url).header("Accept", "image/png").get().build()
            val bytes = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${ref.name}")
                val body = response.body ?: throw IOException("Empty body for ${ref.name}")
                // Early rejection only; the cap is enforced while streaming because the
                // declared length may be absent (chunked) or wrong.
                if (body.contentLength() > MAX_FRAME_BYTES) throw IOException("Frame too large: ${ref.name}")
                readBounded(body.source(), ref.name)
            }
            verifyFrame(ref, bytes)
            cacheDirectory.mkdirs()
            partial.writeBytes(bytes)
            verifiedFiles.remove(target.name)
            if (!partial.renameTo(target)) {
                // Non-atomic fallback; cachedFile() re-verifies so an interrupted copy self-heals.
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            verifiedFiles.add(target.name)
            removeStaleRevisions(ref, keep = target.name)
            recentFailures.remove(ref.name)
            if (downloadsSinceTrim.incrementAndGet() % TRIM_EVERY_DOWNLOADS == 0) trimCache()
            target
        } catch (error: Exception) {
            partial.delete()
            recentFailures[ref.name] = SystemClock.elapsedRealtime()
            Log.w(TAG, "workout frame download failed: ${ref.name}: ${error.message}")
            null
        }
    }

    private fun removeStaleRevisions(ref: WorkoutFrameRef, keep: String) {
        val prefix = WorkoutFrameLocator.cacheFilePrefix(ref.name)
        cacheDirectory.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name != keep && !it.name.endsWith(".part") }
            ?.forEach {
                verifiedFiles.remove(it.name)
                it.delete()
            }
    }

    /** Keeps the frame cache bounded; frames are re-downloadable so eviction is harmless. */
    private fun trimCache() {
        val files = cacheDirectory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= TRIM_TARGET_BYTES) break
            val size = file.length()
            if (file.delete()) {
                verifiedFiles.remove(file.name)
                total -= size
            }
        }
    }

    /** Deletes every cached frame (Settings → storage management, tests). */
    fun clearCache() {
        verifiedFiles.clear()
        cacheDirectory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "WorkoutFrameStore"
        private const val CACHE_DIRECTORY = "workout-vectors/v2"
        private const val FAILURE_RETRY_MS = 60_000L
        private const val MAX_FRAME_BYTES = 4L * 1024 * 1024
        private const val READ_CHUNK_BYTES = 64L * 1024
        private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        private const val TRIM_TARGET_BYTES = 192L * 1024 * 1024
        private const val TRIM_EVERY_DOWNLOADS = 16

        // Holds the application context only (see get()), never an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: WorkoutFrameStore? = null

        fun get(context: Context): WorkoutFrameStore =
            instance ?: synchronized(this) {
                instance ?: WorkoutFrameStore(
                    context.applicationContext,
                    BuildConfig.WORKOUT_VECTORS_BASE_URL
                ).also { instance = it }
            }
    }
}

/** Coil fetcher that resolves [WorkoutFrameRef] through [WorkoutFrameStore]. */
internal class WorkoutFrameFetcher(
    private val ref: WorkoutFrameRef,
    private val store: WorkoutFrameStore
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val opened = store.open(ref) ?: throw IOException("Workout frame unavailable: ${ref.name}")
        return SourceResult(
            source = opened.imageSource,
            mimeType = if (ref.extension == "svg") "image/svg+xml" else "image/png",
            dataSource = opened.dataSource
        )
    }

    class Factory(private val store: WorkoutFrameStore) : Fetcher.Factory<WorkoutFrameRef> {
        override fun create(data: WorkoutFrameRef, options: Options, imageLoader: ImageLoader): Fetcher =
            WorkoutFrameFetcher(data, store)
    }
}
