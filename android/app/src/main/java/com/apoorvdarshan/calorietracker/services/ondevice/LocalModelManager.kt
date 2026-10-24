package com.apoorvdarshan.calorietracker.services.ondevice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.apoorvdarshan.calorietracker.R
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient

sealed interface LocalModelInstallStatus {
    data object NotInstalled : LocalModelInstallStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LocalModelInstallStatus
    data object Installed : LocalModelInstallStatus
    data class Failed(val message: String) : LocalModelInstallStatus
}

data class LocalModelState(
    val descriptor: LocalModelDescriptor,
    val eligible: Boolean,
    val status: LocalModelInstallStatus,
    val ineligibility: LocalModelIneligibility? = null,
    /** Bytes an interrupted download kept so the next one resumes; Delete discards them. */
    val keptBytes: Long = 0L
) {
    val executable: Boolean get() = eligible && status == LocalModelInstallStatus.Installed
}

/** Owns verified model artifacts in no-backup app storage. */
class LocalModelManager(
    context: Context,
    client: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val downloader = ModelDownloader(client)
    private val modelDir = File(appContext.noBackupFilesDir, "local-models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<LocalModelId, Job>()
    private val activeCalls = ConcurrentHashMap<LocalModelId, Call>()
    private val deleting = ConcurrentHashMap.newKeySet<LocalModelId>()
    private val releaseHooks = ConcurrentHashMap<LocalModelId, suspend () -> Unit>()
    private val totalMemoryBytes: Long =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem }
    private val isLowRamDevice: Boolean =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    private val supportedAbis = Build.SUPPORTED_ABIS.toSet()

    private val _states = MutableStateFlow(
        LocalModelCatalog.all.associate { descriptor ->
            val ineligibility = LocalModelEligibility.ineligibility(
                descriptor,
                totalMemoryBytes,
                supportedAbis,
                isLowRamDevice
            )
            val status = installedStatus(descriptor)
            descriptor.id to LocalModelState(
                descriptor = descriptor,
                eligible = ineligibility == null,
                status = status,
                ineligibility = ineligibility,
                keptBytes = if (status == LocalModelInstallStatus.Installed) 0L else {
                    ModelDownloader.keptBytes(partialFile(descriptor), descriptor)
                }
            )
        }
    )
    val states: StateFlow<Map<LocalModelId, LocalModelState>> = _states.asStateFlow()

    init {
        // A partial that still matches its pinned artifact resumes on the next download, even
        // after a restart; every other partial left in the directory is stale.
        val resumable = LocalModelCatalog.all
            .filter { state(it.id).status != LocalModelInstallStatus.Installed }
            .map { descriptor -> partialFile(descriptor) to descriptor }
            .filter { (part, descriptor) -> ModelDownloader.isResumable(part, descriptor) }
            .flatMap { (part, _) -> listOf(part.name, ModelDownloader.sourceFile(part).name) }
            .toSet()
        modelDir.listFiles { file ->
            (file.name.endsWith(".part") || file.name.endsWith(".part.source")) && file.name !in resumable
        }?.forEach { it.delete() }
    }

    fun state(id: LocalModelId): LocalModelState = checkNotNull(_states.value[id])

    fun isExecutable(id: LocalModelId): Boolean = state(id).executable

    fun modelFile(id: LocalModelId): File = File(modelDir, LocalModelCatalog.descriptor(id).fileName)

    /** Disk space used by downloaded (or partially downloaded) models (Settings → Storage). */
    fun storedBytes(): Long = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun registerReleaseHook(id: LocalModelId, hook: suspend () -> Unit) {
        releaseHooks[id] = hook
    }

    fun download(id: LocalModelId) {
        synchronized(jobs) {
            if (id in deleting) return
            val current = state(id)
            if (!current.eligible || current.status is LocalModelInstallStatus.Downloading || current.executable) return
            update(id, LocalModelInstallStatus.Downloading(0L, current.descriptor.expectedBytes))
            val job = scope.launch(start = CoroutineStart.LAZY) { downloadVerified(current.descriptor) }
            jobs[id] = job
            job.start()
        }
    }

    suspend fun delete(id: LocalModelId) {
        val (ownsDelete, downloadJob) = synchronized(jobs) {
            val owns = deleting.add(id)
            owns to if (owns) jobs.remove(id)?.also { it.cancel() } else null
        }
        if (!ownsDelete) return
        try {
            activeCalls.remove(id)?.cancel()
            downloadJob?.join()
            releaseHooks[id]?.invoke()
            withContext(Dispatchers.IO) {
                val descriptor = LocalModelCatalog.descriptor(id)
                modelFile(id).delete()
                ModelDownloader.discardPartial(partialFile(descriptor))
                markerFile(descriptor).delete()
            }
            update(id, LocalModelInstallStatus.NotInstalled)
        } finally {
            deleting.remove(id)
        }
    }

    private suspend fun downloadVerified(descriptor: LocalModelDescriptor) {
        val id = descriptor.id
        val part = partialFile(descriptor)
        try {
            val available = StatFs(modelDir.absolutePath).availableBytes
            val reserve = maxOf(256L * 1024L * 1024L, descriptor.expectedBytes / 10L)
            // Bytes kept from an interrupted attempt already occupy their share of the space.
            val kept = ModelDownloader.keptBytes(part, descriptor)
            if (available < descriptor.expectedBytes - kept + reserve) {
                throw LocalModelInstallError(
                    "Not enough free storage for ${descriptor.displayName}.",
                    R.string.local_model_error_storage,
                    descriptor.displayName
                )
            }
            downloader.download(
                descriptor = descriptor,
                part = part,
                onCall = { call -> activeCalls[id] = call },
                onProgress = { bytes ->
                    update(id, LocalModelInstallStatus.Downloading(bytes, descriptor.expectedBytes))
                }
            )

            val destination = modelFile(id)
            destination.delete()
            if (!part.renameTo(destination)) {
                throw LocalModelInstallError("The verified model could not be installed.", R.string.local_model_error_install)
            }
            ModelDownloader.sourceFile(part).delete()
            val marker = markerFile(descriptor)
            val markerPart = File(marker.parentFile, "${marker.name}.part")
            markerPart.writeText(descriptor.sha256)
            marker.delete()
            if (!markerPart.renameTo(marker)) {
                throw LocalModelInstallError("The verification marker could not be installed.", R.string.local_model_error_install)
            }
            update(id, LocalModelInstallStatus.Installed)
        } catch (cancelled: CancellationException) {
            // Only the user's Cancel/Delete cancels a download, so its partial goes too.
            ModelDownloader.discardPartial(part)
            update(id, LocalModelInstallStatus.NotInstalled)
            throw cancelled
        } catch (error: Throwable) {
            if (!currentCoroutineContext().isActive) {
                ModelDownloader.discardPartial(part)
                update(id, LocalModelInstallStatus.NotInstalled)
                throw CancellationException("Model download cancelled.").also { it.initCause(error) }
            }
            // The partial stays so the next Download resumes it, and Delete can discard it. The
            // downloader already deleted it if the bytes failed verification.
            update(
                id,
                LocalModelInstallStatus.Failed(installFailureMessage(error)),
                keptBytes = ModelDownloader.keptBytes(part, descriptor)
            )
        } finally {
            activeCalls.remove(id)
            jobs.remove(id)
        }
    }

    /** What the Settings screen shows for a failed install, in the user's language. */
    private fun installFailureMessage(error: Throwable): String = when (error) {
        is LocalModelInstallError -> appContext.getString(error.messageRes, *error.args)
        is LocalModelInstallIOException -> appContext.getString(error.messageRes, *error.args)
        is ModelStorageException -> appContext.getString(R.string.local_model_error_write)
        is IOException -> appContext.getString(R.string.local_model_error_network)
        else -> appContext.getString(R.string.local_model_error_generic)
    }

    private fun installedStatus(descriptor: LocalModelDescriptor): LocalModelInstallStatus {
        val file = modelFile(descriptor.id)
        val marker = markerFile(descriptor)
        val installed = file.isFile && file.length() == descriptor.expectedBytes &&
            marker.isFile && runCatching { marker.readText().trim() }.getOrNull()
                .equals(descriptor.sha256, ignoreCase = true)
        return if (installed) LocalModelInstallStatus.Installed else LocalModelInstallStatus.NotInstalled
    }

    private fun partialFile(descriptor: LocalModelDescriptor) =
        File(modelDir, "${descriptor.fileName}.part")

    private fun markerFile(descriptor: LocalModelDescriptor) =
        File(modelDir, "${descriptor.fileName}.sha256")

    private fun update(id: LocalModelId, status: LocalModelInstallStatus, keptBytes: Long = 0L) {
        _states.value = _states.value.toMutableMap().apply {
            val old = checkNotNull(this[id])
            this[id] = old.copy(status = status, keptBytes = keptBytes)
        }
    }
}
