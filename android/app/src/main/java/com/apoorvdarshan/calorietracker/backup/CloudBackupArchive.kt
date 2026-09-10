package com.apoorvdarshan.calorietracker.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        "widget_snapshot_v1",
        "lastNotifiedUpdateVersion",
        "cloudBackupFileId",
    )

    private val photoName = Regex("^[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)

    fun safePhotoName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.takeIf { photoName.matches(it) }
    }
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

data class CloudBackupUnpack(
    val document: CloudBackupDocument,
    val photos: Map<String, ByteArray>,
)

object CloudBackupArchive {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun contentHash(values: Map<String, CloudBackupValue>, photos: Map<String, ByteArray>): String {
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
            photos.toSortedMap().forEach { (name, bytes) ->
                append("photo:").append(name).append(':').append(bytes.size).append('\n')
            }
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    fun pack(
        values: Map<String, CloudBackupValue>,
        photos: Map<String, ByteArray>,
        exportedAt: String,
        appVersion: String,
        platform: String = "android",
    ): ByteArray {
        val filtered = values.filterKeys { it !in CloudBackupPolicy.excludedKeys }
        val safePhotos = photos.mapNotNull { (name, bytes) ->
            CloudBackupPolicy.safePhotoName(name)?.let { it to bytes }
        }.toMap()
        val document = CloudBackupDocument(
            format = CloudBackupPolicy.FORMAT,
            format_version = CloudBackupPolicy.VERSION,
            exported_at = exportedAt,
            app_version = appVersion,
            platform = platform,
            content_sha256 = contentHash(filtered, safePhotos),
            payload = CloudBackupPayload(values = filtered),
        )
        val payload = json.encodeToString(document).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(CloudBackupPolicy.PAYLOAD_NAME))
            zip.write(payload)
            zip.closeEntry()
            for ((name, bytes) in safePhotos) {
                zip.putNextEntry(ZipEntry(CloudBackupPolicy.PHOTOS_DIR + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun unpack(bytes: ByteArray): CloudBackupUnpack {
        var payload: ByteArray? = null
        val photos = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = zip.readBytes()
                when {
                    entry.name == CloudBackupPolicy.PAYLOAD_NAME -> payload = data
                    entry.name.startsWith(CloudBackupPolicy.PHOTOS_DIR) -> {
                        CloudBackupPolicy.safePhotoName(entry.name)?.let { photos[it] = data }
                    }
                }
            }
        }
        val raw = payload ?: error("Backup is missing backup.json")
        val document = json.decodeFromString<CloudBackupDocument>(raw.toString(Charsets.UTF_8))
        require(document.format == CloudBackupPolicy.FORMAT) { "Not a Fud AI backup" }
        require(document.format_version <= CloudBackupPolicy.VERSION) {
            "This backup needs a newer Fud AI"
        }
        return CloudBackupUnpack(document, photos)
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
