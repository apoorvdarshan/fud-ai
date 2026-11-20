package com.apoorvdarshan.calorietracker.data

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException

internal data class FailedMealDraft(
    val text: String? = null,
    val images: List<ByteArray> = emptyList(),
    val progressive: Boolean = false,
    val labelBarcode: String? = null,
    val singlePhoto: Boolean = false
)

/** One user-saved retry slot; raw inputs live outside the cloud/OS backup directories. */
internal class FailedMealDraftStore(directory: File) {
    private val file = AtomicFile(File(directory, "failed_meal_draft"))

    fun exists(): Boolean = try {
        // Unlike checking the base path, openRead also recovers a legacy backup.
        file.openRead().use { true }
    } catch (_: FileNotFoundException) {
        false
    }

    fun write(draft: FailedMealDraft) {
        val output = file.startWrite()
        try {
            FailedMealDraftCodec.write(DataOutputStream(output), draft)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    fun read(): FailedMealDraft = file.openRead().use { FailedMealDraftCodec.read(DataInputStream(it)) }
    fun delete() = file.delete()
}

internal object FailedMealDraftCodec {
    /** Matches the 25 MiB per-photo cap the library import accepts (ContentUriBytes). */
    internal const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    /** Ten accepted photos at the per-photo cap. */
    private const val MAX_TOTAL_BYTES = 10 * MAX_IMAGE_BYTES
    /**
     * Explicit text limit so a long pasted recipe cannot fail silently. The v1
     * format used `writeUTF`, which throws above 65,535 encoded bytes.
     */
    internal const val MAX_TEXT_BYTES = 1024 * 1024
    private const val VERSION = 2

    fun write(out: DataOutputStream, draft: FailedMealDraft) = with(out) {
        require(draft.images.size <= 10 && draft.images.all { it.size in 1..MAX_IMAGE_BYTES })
        require(draft.images.sumOf { it.size } <= MAX_TOTAL_BYTES)
        writeInt(VERSION)
        writeBoundedText(draft.text.orEmpty())
        writeBoundedText(draft.labelBarcode.orEmpty())
        writeBoolean(draft.progressive)
        writeBoolean(draft.singlePhoto)
        writeInt(draft.images.size)
        draft.images.forEach { writeInt(it.size); write(it) }
    }

    fun read(input: DataInputStream): FailedMealDraft = with(input) {
        val text: String?
        val barcode: String?
        when (readInt()) {
            1 -> {
                text = readUTF().ifEmpty { null }
                barcode = readUTF().ifEmpty { null }
            }
            VERSION -> {
                text = readBoundedText(input).ifEmpty { null }
                barcode = readBoundedText(input).ifEmpty { null }
            }
            else -> error("Unknown failed meal draft version")
        }
        val progressive = readBoolean()
        val single = readBoolean()
        val images = readImages(input)
        check(read() == -1)
        FailedMealDraft(text, images, progressive, barcode, single)
    }

    /** Length-prefixed UTF-8 with the same limit on write and read. */
    private fun DataOutputStream.writeBoundedText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun readBoundedText(input: DataInputStream): String {
        val size = input.readInt().also { check(it in 0..MAX_TEXT_BYTES) }
        val bytes = ByteArray(size).also { input.readFully(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readImages(input: DataInputStream): List<ByteArray> = with(input) {
        val count = readInt().also { check(it in 0..10) }
        var total = 0
        List(count) {
            val size = readInt().also { check(it in 1..MAX_IMAGE_BYTES) }
            total += size
            check(total <= MAX_TOTAL_BYTES)
            ByteArray(size).also { readFully(it) }
        }
    }
}
