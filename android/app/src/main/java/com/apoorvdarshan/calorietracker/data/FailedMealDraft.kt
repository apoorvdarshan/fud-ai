package com.apoorvdarshan.calorietracker.data

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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

    fun exists(): Boolean = file.exists()

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
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

    fun write(out: DataOutputStream, draft: FailedMealDraft) = with(out) {
        require(draft.images.size <= 10 && draft.images.all { it.size in 1..MAX_IMAGE_BYTES })
        require(draft.images.sumOf { it.size } <= MAX_TOTAL_BYTES)
        writeInt(1)
        writeUTF(draft.text.orEmpty())
        writeUTF(draft.labelBarcode.orEmpty())
        writeBoolean(draft.progressive)
        writeBoolean(draft.singlePhoto)
        writeInt(draft.images.size)
        draft.images.forEach { writeInt(it.size); write(it) }
    }

    fun read(input: DataInputStream): FailedMealDraft = with(input) {
        check(readInt() == 1)
        val text = readUTF().ifEmpty { null }
        val barcode = readUTF().ifEmpty { null }
        val progressive = readBoolean()
        val single = readBoolean()
        val count = readInt().also { check(it in 0..10) }
        var total = 0
        val images = List(count) {
            val size = readInt().also { check(it in 1..MAX_IMAGE_BYTES) }
            total += size
            check(total <= MAX_TOTAL_BYTES)
            ByteArray(size).also { readFully(it) }
        }
        check(read() == -1)
        FailedMealDraft(text, images, progressive, barcode, single)
    }
}
