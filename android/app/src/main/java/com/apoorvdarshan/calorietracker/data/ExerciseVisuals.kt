package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.Gender
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

enum class ExerciseVisualFormat {
    JPEG,
    SVG,
    PNG
}

/**
 * Frames for one exercise visual.
 *
 * - [ExerciseVisualFormat.JPEG]: [framePaths] are bundled asset paths or user photo
 *   filenames, loaded directly.
 * - [ExerciseVisualFormat.PNG] / [ExerciseVisualFormat.SVG]: authored frames. [framePaths]
 *   are flat filenames (`<name>.png`) bundled as assets in every build; they are resolved
 *   through [com.apoorvdarshan.calorietracker.services.WorkoutFrameStore]
 *   (on-device cache → bundled asset → optional debug-only download). [frameDigests]
 *   carries the manifest's per-frame content digest used to verify cached/downloaded bytes.
 */
data class ExerciseVisual(
    val framePaths: List<String>,
    val format: ExerciseVisualFormat,
    val representativeFrameIndex: Int,
    val frameDigests: List<String?> = emptyList()
) {
    val isAuthored: Boolean get() = format != ExerciseVisualFormat.JPEG

    fun digestAt(index: Int): String? = frameDigests.getOrNull(index)

    companion object {
        fun jpeg(paths: List<String>): ExerciseVisual = ExerciseVisual(
            framePaths = paths,
            format = ExerciseVisualFormat.JPEG,
            representativeFrameIndex = 0
        )
    }
}

/** Complete gender-specific authored frame sets described by the shared manifest. */
internal data class GenderedExerciseFrames(
    val male: List<String>,
    val female: List<String>,
    val format: ExerciseVisualFormat,
    val representativeFrameIndex: Int,
    val maleDigests: List<String?> = emptyList(),
    val femaleDigests: List<String?> = emptyList()
) {
    fun forGender(gender: Gender): List<String> = when (gender) {
        Gender.FEMALE -> female
        // OTHER intentionally uses the male visual convention so its result is deterministic.
        Gender.MALE, Gender.OTHER -> male
    }

    fun digestsForGender(gender: Gender): List<String?> = when (gender) {
        Gender.FEMALE -> femaleDigests
        Gender.MALE, Gender.OTHER -> maleDigests
    }
}

internal object ExerciseVisualResolver {
    const val MANIFEST_ASSET_NAME = "exercise-visual-manifest.json"

    private data class ManifestDocument(
        @SerializedName("schemaVersion")
        val schemaVersion: Int? = null,
        @SerializedName("exercises")
        val exercises: List<ManifestRecord>? = null
    )

    private data class ManifestRecord(
        @SerializedName("exerciseId")
        val exerciseId: String? = null,
        @SerializedName("frameCount")
        val frameCount: Int? = null,
        @SerializedName("representativeFrameIndex")
        val representativeFrameIndex: Int? = null,
        @SerializedName("format")
        val format: String? = null,
        @SerializedName("maleFrames")
        val maleFrames: List<String>? = null,
        @SerializedName("femaleFrames")
        val femaleFrames: List<String>? = null,
        @SerializedName("maleFrameDigests")
        val maleFrameDigests: List<String>? = null,
        @SerializedName("femaleFrameDigests")
        val femaleFrameDigests: List<String>? = null
    )

    private val digestPattern = Regex("^[0-9a-f]{8,64}$")

    /**
     * Parses the shared manifest into atomic male/female sets (3–5 contiguous frames).
     * When [packagedAssetNames] is set (unit tests / corpus audits), every referenced file
     * must exist in that collection. Runtime passes null: the manifest is the source of
     * truth and corpus completeness is enforced at build time (sync script + Gradle asset
     * task), so listing ~7,000 assets on every launch would be wasted I/O.
     */
    fun parseManifest(
        json: String,
        packagedAssetNames: Collection<String>? = null
    ): Map<String, GenderedExerciseFrames> {
        val document = runCatching { Gson().fromJson(json, ManifestDocument::class.java) }
            .getOrNull()
            ?: return emptyMap()
        if (document.schemaVersion != 1) return emptyMap()

        val entries = mutableMapOf<String, GenderedExerciseFrames>()
        val duplicateIDs = mutableSetOf<String>()
        for (record in document.exercises.orEmpty()) {
            val frames = parseRecord(record, packagedAssetNames) ?: continue
            val (exerciseID, gendered) = frames
            if (entries.containsKey(exerciseID)) {
                duplicateIDs += exerciseID
            } else {
                entries[exerciseID] = gendered
            }
        }
        duplicateIDs.forEach { entries.remove(it) }
        return entries
    }

    private fun parseRecord(
        record: ManifestRecord,
        packagedAssetNames: Collection<String>?
    ): Pair<String, GenderedExerciseFrames>? {
        val exerciseID = record.exerciseId?.takeIf { it.isNotBlank() } ?: return null
        val frameCount = record.frameCount?.takeIf { it in 3..5 } ?: return null
        val representative = record.representativeFrameIndex
            ?.takeIf { it in 0 until frameCount }
            ?: return null
        val format = when (record.format?.lowercase()) {
            null, "svg" -> ExerciseVisualFormat.SVG
            "png" -> ExerciseVisualFormat.PNG
            else -> return null
        }
        val extension = format.name.lowercase()
        val maleNames = record.maleFrames
            ?.takeIf { validFrameNames(it, exerciseID, "male", frameCount) }
            ?: return null
        val femaleNames = record.femaleFrames
            ?.takeIf { validFrameNames(it, exerciseID, "female", frameCount) }
            ?: return null
        val malePaths = maleNames.map { "$it.$extension" }
        val femalePaths = femaleNames.map { "$it.$extension" }
        if (packagedAssetNames != null &&
            !(malePaths + femalePaths).all(packagedAssetNames::contains)
        ) {
            return null
        }
        return exerciseID to GenderedExerciseFrames(
            male = malePaths,
            female = femalePaths,
            format = format,
            representativeFrameIndex = representative,
            maleDigests = validDigests(record.maleFrameDigests, frameCount),
            femaleDigests = validDigests(record.femaleFrameDigests, frameCount)
        )
    }

    /** Digests are optional metadata; a malformed list is ignored rather than rejecting the set. */
    private fun validDigests(digests: List<String>?, frameCount: Int): List<String?> {
        if (digests == null || digests.size != frameCount) return List(frameCount) { null }
        return digests.map { digest -> digest.lowercase().takeIf(digestPattern::matches) }
    }

    private fun validFrameNames(
        names: List<String>,
        exerciseID: String,
        gender: String,
        frameCount: Int
    ): Boolean {
        val safeName = Regex("^[A-Za-z0-9_-]+$")
        val prefix = "${exerciseID}_${gender}_"
        return names.size == frameCount &&
            names.distinct().size == frameCount &&
            names.withIndex().all { (index, name) ->
                name.startsWith(prefix) && safeName.matches(name) && name.endsWith("_$index")
            }
    }

    fun resolve(
        item: ExerciseItem,
        gender: Gender,
        authoredFrames: Map<String, GenderedExerciseFrames>
    ): ExerciseVisual {
        val authoredSet = authoredFrames[resolveVisualKey(item, authoredFrames)]
        val frames = authoredSet?.forGender(gender).orEmpty()
        if (authoredSet == null || frames.isEmpty()) {
            return ExerciseVisual.jpeg(item.imagePaths)
        }
        return ExerciseVisual(
            framePaths = frames,
            format = authoredSet.format,
            representativeFrameIndex = authoredSet.representativeFrameIndex,
            frameDigests = authoredSet.digestsForGender(gender)
        )
    }

    private fun resolveVisualKey(
        item: ExerciseItem,
        authoredFrames: Map<String, GenderedExerciseFrames>
    ): String {
        if (authoredFrames.containsKey(item.id)) return item.id
        return exerciseIdFromImagePaths(item.imagePaths)
            ?.takeIf { authoredFrames.containsKey(it) }
            ?: item.id
    }

    private fun exerciseIdFromImagePaths(imagePaths: List<String>): String? {
        for (imagePath in imagePaths) {
            val filename = imagePath.substringAfterLast('/').substringBeforeLast('.')
            val separator = filename.lastIndexOf('_')
            if (separator <= 0) continue
            val suffix = filename.substring(separator + 1)
            if (suffix.toIntOrNull() != null) {
                return filename.substring(0, separator)
            }
        }
        return null
    }
}
