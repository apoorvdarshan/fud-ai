package com.apoorvdarshan.calorietracker.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File

/**
 * Outcome of reading a persisted JSON list.
 *
 * Keeping [Missing] and [Corrupt] apart is the whole point: collapsing both
 * into `emptyList()` is how one unreadable byte used to erase the diary — the
 * repository read `[]`, appended the new entry and persisted `[entry]` over
 * the original blob.
 */
sealed interface PersistedListDecode<out T> {
    /** The key has never been written — a genuinely empty store. */
    data object Missing : PersistedListDecode<Nothing>

    /**
     * Decoded successfully. [dropped] is non-zero when individual rows were
     * unreadable and skipped; callers must preserve [raw] before overwriting.
     */
    data class Decoded<T>(val items: List<T>, val dropped: Int, val raw: String) : PersistedListDecode<T>

    /** Nothing usable could be decoded. [raw] must be preserved before overwriting. */
    data class Corrupt(val raw: String) : PersistedListDecode<Nothing>

    val itemsOrEmpty: List<T>
        get() = when (this) {
            Missing -> emptyList()
            is Decoded -> items
            is Corrupt -> emptyList()
        }

    /** True when the stored text should be copied aside before it is replaced. */
    val needsPreservation: Boolean
        get() = when (this) {
            Missing -> false
            is Decoded -> dropped > 0
            is Corrupt -> true
        }

    val rawOrNull: String?
        get() = when (this) {
            Missing -> null
            is Decoded -> raw
            is Corrupt -> raw
        }
}

/**
 * Element-wise JSON list decoding: a list with one unreadable row loses that
 * row, not the whole list.
 */
object LenientJsonList {
    fun <T> decode(json: Json, serializer: KSerializer<T>, raw: String?): PersistedListDecode<T> {
        if (raw == null) return PersistedListDecode.Missing
        runCatching { json.decodeFromString(ListSerializer(serializer), raw) }
            .getOrNull()
            ?.let { return PersistedListDecode.Decoded(it, dropped = 0, raw = raw) }

        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return PersistedListDecode.Corrupt(raw)
        val items = array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
        }
        if (items.isEmpty()) return PersistedListDecode.Corrupt(raw)
        return PersistedListDecode.Decoded(items, dropped = array.size - items.size, raw = raw)
    }
}

/**
 * Copies unreadable persisted blobs aside as `<name>.corrupt-<timestamp>` so
 * a decode failure never costs the user their data. Best-effort: returns
 * `null` when the copy could not be written, letting callers fall back to an
 * in-store backup.
 */
class CorruptBlobArchive(private val directory: File) {
    fun preserveText(name: String, raw: String): File? = runCatching {
        val target = uniqueTarget(name)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(raw)
        if (!tmp.renameTo(target)) {
            target.writeText(raw)
            tmp.delete()
        }
        target
    }.getOrNull()

    fun preserveFile(source: File): File? = runCatching {
        if (!source.exists()) return null
        val target = File(source.parentFile ?: directory, backupName(source.name))
        source.copyTo(target, overwrite = false)
        target
    }.getOrNull()

    private fun uniqueTarget(name: String): File {
        var candidate = File(directory, backupName(name))
        var attempt = 0
        while (candidate.exists()) {
            attempt += 1
            candidate = File(directory, backupName(name, suffix = "-$attempt"))
        }
        return candidate
    }

    companion object {
        const val DIRECTORY_NAME = "corrupt-backups"

        fun backupName(name: String, now: Long = System.currentTimeMillis(), suffix: String = ""): String =
            "$name.corrupt-$now$suffix"
    }
}
