package com.apoorvdarshan.calorietracker.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The archive is not a Fud AI backup, or was written by a newer app version. */
class CloudBackupFormatException(val needsNewerApp: Boolean) : IllegalArgumentException(
    if (needsNewerApp) "This backup needs a newer Fud AI" else "Not a Fud AI backup"
)

object CloudBackupPolicy {
    const val FORMAT = "fudai-cloud-backup"
    const val VERSION = 1
    const val PAYLOAD_NAME = "backup.json"
    const val PHOTOS_DIR = "photos/"
    const val FILE_NAME = "fudai-backup.zip"
    const val MIN_AUTO_BACKUP_INTERVAL_MS = 15 * 60 * 1000L

    val excludedKeys: Set<String> = setOf(
        "healthChangesToken",
        "healthChangesTokenTypes",
        "healthFoodRestoreDone",
        // Copies of other apps' Health Connect workouts; re-imported from Health Connect.
        "importedHealthWorkoutsV1",
        "widget_snapshot_v1",
        "lastNotifiedUpdateVersion",
        "cloudBackupFileId",
        // Local one-shot alarm state — restoring it would skip re-arming on a new device.
        "productHuntLaunchNotificationScheduled",
        "productHuntLaunchNotificationScheduled.2026-09-27",
        "hasSeenProductHuntLaunchPrompt.2026-09-27",
        "productHuntLaunchNotificationScheduled.2026-09-29",
        "hasSeenProductHuntLaunchPrompt.2026-09-29",
        // Whether this install already posted its one "What's new" notification.
        "hasNotifiedWhatsNew.liveVoice",
    )

    /** Longest photo file name a backup carries; real ones are a UUID and an extension. */
    const val MAX_PHOTO_NAME_LENGTH = 128

    private val photoName = Regex("^[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)

    /**
     * [name] if it is a plain photo file name, else null: ASCII letters, digits,
     * '.', '_' and '-' only, a photo extension, at most [MAX_PHOTO_NAME_LENGTH]
     * characters, no leading dot and no "..". A name with a directory part,
     * relative or absolute, is rejected rather than shortened.
     */
    fun safePhotoName(name: String): String? {
        if (name.length > MAX_PHOTO_NAME_LENGTH || name.startsWith('.') || ".." in name) return null
        if (!photoName.matches(name)) return null
        // The pattern already excludes separators; File.name is a last check
        // that no directory part survives.
        val plain = File(name).name
        return if (plain == name) plain else null
    }

    /**
     * The file name a restore writes for archive entry [entryName], or null if
     * the entry must be skipped. Only `photos/<plain photo name>` qualifies, so
     * an entry name can never point a restore outside the photo folder.
     */
    fun restorablePhotoName(entryName: String): String? =
        if (entryName.startsWith(PHOTOS_DIR)) safePhotoName(entryName.substring(PHOTOS_DIR.length)) else null
}

@Serializable
data class CloudBackupDocument(
    val format: String,
    val format_version: Int,
    val exported_at: String,
    val app_version: String,
    val platform: String,
    val content_sha256: String,
    val payload: CloudBackupPayload,
)

@Serializable
data class CloudBackupPayload(
    val values: Map<String, CloudBackupValue> = emptyMap(),
)

@Serializable
data class CloudBackupValue(
    val t: String,
    val b: Boolean? = null,
    val i: Int? = null,
    val s: String? = null,
    val ss: List<String>? = null,
) {
    companion object {
        fun bool(v: Boolean) = CloudBackupValue(t = "b", b = v)
        fun int(v: Int) = CloudBackupValue(t = "i", i = v)
        fun string(v: String) = CloudBackupValue(t = "s", s = v)
        fun stringSet(v: Collection<String>) = CloudBackupValue(t = "ss", ss = v.toList())
    }
}

/**
 * One photo for the archive. Its bytes are read from [open] only while its own
 * zip entry is written, so a backup never holds every photo in memory (#484).
 */
class CloudBackupPhoto(
    val name: String,
    val size: Long,
    val open: () -> InputStream,
) {
    companion object {
        fun fromFile(name: String, file: File) =
            CloudBackupPhoto(name, file.length()) { FileInputStream(file) }
    }
}

/** A validated archive's document, plus the sizes a restore will write to disk. */
class CloudBackupContents(
    val document: CloudBackupDocument,
    val payloadBytes: Long,
    val photoCount: Int,
    val photoBytes: Long,
)

object CloudBackupArchive {
    private const val TEMP_PREFIX = "fudai-backup-"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun contentHash(values: Map<String, CloudBackupValue>, photos: List<CloudBackupPhoto>): String =
        contentHash(values, photos.associate { it.name to it.size })

    // Photos count by name and byte size only, so the hash never needs photo bytes.
    private fun contentHash(values: Map<String, CloudBackupValue>, photoSizes: Map<String, Long>): String {
        val canonical = buildString {
            values.toSortedMap().forEach { (key, value) ->
                append(key).append('=')
                when (value.t) {
                    "b" -> append("b:").append(value.b)
                    "i" -> append("i:").append(value.i)
                    "s" -> append("s:").append(value.s)
                    "ss" -> append("ss:").append(value.ss.orEmpty().sorted().joinToString(","))
                    else -> append(value.t)
                }
                append('\n')
            }
            photoSizes.toSortedMap().forEach { (name, size) ->
                append("photo:").append(name).append(':').append(size).append('\n')
            }
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * Writes the backup zip to [destination]: backup.json first, then one
     * photos/<name> entry per photo, streamed straight from disk.
     */
    fun pack(
        destination: File,
        values: Map<String, CloudBackupValue>,
        photos: List<CloudBackupPhoto>,
        exportedAt: String,
        appVersion: String,
        platform: String = "android",
    ) {
        val filtered = values.filterKeys { it !in CloudBackupPolicy.excludedKeys }
        val safePhotos = photos.mapNotNull { photo ->
            CloudBackupPolicy.safePhotoName(photo.name)?.let { it to photo }
        }.toMap()
        val document = CloudBackupDocument(
            format = CloudBackupPolicy.FORMAT,
            format_version = CloudBackupPolicy.VERSION,
            exported_at = exportedAt,
            app_version = appVersion,
            platform = platform,
            content_sha256 = contentHash(filtered, safePhotos.mapValues { it.value.size }),
            payload = CloudBackupPayload(values = filtered),
        )
        val payload = json.encodeToString(document).toByteArray(Charsets.UTF_8)
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(CloudBackupPolicy.PAYLOAD_NAME))
            zip.write(payload)
            zip.closeEntry()
            for ((name, photo) in safePhotos) {
                // A snapshotted photo that can no longer be read fails the backup.
                // The hash already lists it, so a backup saved without it would
                // count as unchanged and never be retried.
                photo.open().use {
                    zip.putNextEntry(ZipEntry(CloudBackupPolicy.PHOTOS_DIR + name))
                    it.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }

    /** Reads and validates backup.json; see [inspect]. */
    fun readDocument(archive: File): CloudBackupDocument = inspect(archive).document

    /**
     * Reads and validates backup.json and measures the photos [forEachPhoto]
     * will write. Every entry is read to the end so a corrupt archive fails
     * its CRC check here, before local data is replaced.
     */
    fun inspect(archive: File): CloudBackupContents {
        var payload: ByteArray? = null
        var photoCount = 0
        var photoBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            val skip = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == CloudBackupPolicy.PAYLOAD_NAME) {
                    payload = zip.readBytes()
                    continue
                }
                var size = 0L
                while (true) {
                    val read = zip.read(skip)
                    if (read < 0) break
                    size += read
                }
                if (CloudBackupPolicy.restorablePhotoName(entry.name) != null) {
                    photoCount++
                    photoBytes += size
                }
            }
        }
        val raw = payload ?: throw CloudBackupFormatException(needsNewerApp = false)
        val document = json.decodeFromString<CloudBackupDocument>(raw.toString(Charsets.UTF_8))
        if (document.format != CloudBackupPolicy.FORMAT) throw CloudBackupFormatException(needsNewerApp = false)
        if (document.format_version > CloudBackupPolicy.VERSION) throw CloudBackupFormatException(needsNewerApp = true)
        return CloudBackupContents(document, raw.size.toLong(), photoCount, photoBytes)
    }

    /**
     * Hands each restorable photo to [restore] one at a time, under its plain
     * file name; entries [CloudBackupPolicy.restorablePhotoName] rejects are
     * skipped. [restore] must not close the stream.
     */
    fun forEachPhoto(archive: File, restore: (name: String, input: InputStream) -> Unit) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            val entryStream = object : FilterInputStream(zip) {
                override fun close() = Unit
            }
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = CloudBackupPolicy.restorablePhotoName(entry.name) ?: continue
                restore(name, entryStream)
            }
        }
    }

    /** Runs [block] with a new archive file in [dir] and always deletes it afterwards. */
    inline fun <T> withTempArchive(dir: File, block: (File) -> T): T {
        val file = createTempArchive(dir)
        try {
            return block(file)
        } finally {
            file.delete()
        }
    }

    fun createTempArchive(dir: File): File {
        dir.mkdirs()
        return File.createTempFile(TEMP_PREFIX, ".zip", dir)
    }

    /** Removes archives left behind when the process died mid-backup. */
    fun deleteTempArchives(dir: File) {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(TEMP_PREFIX) }
            ?.forEach { it.delete() }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
