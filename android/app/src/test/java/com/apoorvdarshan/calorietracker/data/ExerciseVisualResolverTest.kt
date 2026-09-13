package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.Gender
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExerciseVisualResolverTest {
    @Test
    fun manifestAcceptsThreeFourAndFiveFrameAtomicGenderSets() {
        (3..5).forEach { frameCount ->
            val frames = ExerciseVisualResolver.parseManifest(
                json = manifestJson(frameCount = frameCount),
                packagedAssetNames = assetNames(frameCount)
            )

            assertSvgVisual(
                visual = ExerciseVisualResolver.resolve(item, Gender.FEMALE, frames),
                expectedGender = "female",
                frameCount = frameCount
            )
            assertSvgVisual(
                visual = ExerciseVisualResolver.resolve(item, Gender.MALE, frames),
                expectedGender = "male",
                frameCount = frameCount
            )
            assertSvgVisual(
                visual = ExerciseVisualResolver.resolve(item, Gender.OTHER, frames),
                expectedGender = "male",
                frameCount = frameCount
            )
        }
    }

    @Test
    fun twoOrSixFrameManifestEntriesFallBackToDatasetJpegs() {
        listOf(2, 6).forEach { frameCount ->
            val frames = ExerciseVisualResolver.parseManifest(
                json = manifestJson(frameCount = frameCount),
                packagedAssetNames = assetNames(frameCount)
            )
            val visual = ExerciseVisualResolver.resolve(item, Gender.MALE, frames)

            assertEquals(ExerciseVisualFormat.JPEG, visual.format)
            assertEquals(item.imagePaths, visual.framePaths)
        }
    }

    @Test
    fun parseManifestWithNullPackagedNamesSkipsAssetVerification() {
        val manifest = manifestJson(frameCount = 4)
        val incompleteAssets = assetNames(4) - "${item.id}_female_2.svg"
        assertTrue(
            ExerciseVisualResolver.parseManifest(manifest, incompleteAssets).isEmpty()
        )

        val frames = ExerciseVisualResolver.parseManifest(manifest, packagedAssetNames = null)
        assertTrue(frames.containsKey(item.id))
        val visual = ExerciseVisualResolver.resolve(item, Gender.MALE, frames)
        assertEquals(ExerciseVisualFormat.SVG, visual.format)
        assertEquals(4, visual.framePaths.size)
    }

    @Test
    fun missingOppositeGenderOrPackagedFrameFallsBackToDatasetJpegs() {
        val missingFemaleManifest = ExerciseVisualResolver.parseManifest(
            json = manifestJson(frameCount = 4, includeFemaleFrames = false),
            packagedAssetNames = assetNames(4)
        )
        val missingPackagedFrame = ExerciseVisualResolver.parseManifest(
            json = manifestJson(frameCount = 4),
            packagedAssetNames = assetNames(4) - "${item.id}_female_2.svg"
        )

        listOf(missingFemaleManifest, missingPackagedFrame).forEach { frames ->
            val visual = ExerciseVisualResolver.resolve(item, Gender.MALE, frames)
            assertEquals(ExerciseVisualFormat.JPEG, visual.format)
            assertEquals(item.imagePaths, visual.framePaths)
        }
    }

    @Test
    fun manifestSupportsVersionedPngFrames() {
        val maleFrames = (0 until 4).map { "${item.id}_male_v2_$it" }
        val femaleFrames = (0 until 4).map { "${item.id}_female_v2_$it" }
        val manifest = Gson().toJson(
            mapOf(
                "schemaVersion" to 1,
                "exercises" to listOf(
                    mapOf(
                        "exerciseId" to item.id,
                        "format" to "png",
                        "frameCount" to 4,
                        "representativeFrameIndex" to 2,
                        "maleFrames" to maleFrames,
                        "femaleFrames" to femaleFrames
                    )
                )
            )
        )
        val frames = ExerciseVisualResolver.parseManifest(
            json = manifest,
            packagedAssetNames = (maleFrames + femaleFrames).map { "$it.png" }
        )

        val visual = ExerciseVisualResolver.resolve(item, Gender.FEMALE, frames)
        assertEquals(ExerciseVisualFormat.PNG, visual.format)
        assertEquals(femaleFrames.map { "$it.png" }, visual.framePaths)
        assertEquals(2, visual.representativeFrameIndex)
    }

    @Test
    fun manifestFrameDigestsAreParsedAndMalformedOnesIgnored() {
        val maleFrames = (0 until 4).map { "${item.id}_male_v2_$it" }
        val femaleFrames = (0 until 4).map { "${item.id}_female_v2_$it" }
        val maleDigests = listOf("0123456789abcdef", "ABCDEF0123456789", "not-a-digest", "fedcba9876543210")
        val manifest = Gson().toJson(
            mapOf(
                "schemaVersion" to 1,
                "exercises" to listOf(
                    mapOf(
                        "exerciseId" to item.id,
                        "format" to "png",
                        "frameCount" to 4,
                        "representativeFrameIndex" to 2,
                        "maleFrames" to maleFrames,
                        "femaleFrames" to femaleFrames,
                        "maleFrameDigests" to maleDigests,
                        "femaleFrameDigests" to listOf("0123456789abcdef") // wrong length → ignored
                    )
                )
            )
        )
        val frames = ExerciseVisualResolver.parseManifest(manifest)

        val male = ExerciseVisualResolver.resolve(item, Gender.MALE, frames)
        assertEquals(listOf("0123456789abcdef", "abcdef0123456789", null, "fedcba9876543210"), male.frameDigests)
        assertEquals("abcdef0123456789", male.digestAt(1))
        assertTrue(male.isAuthored)

        val female = ExerciseVisualResolver.resolve(item, Gender.FEMALE, frames)
        assertEquals(List<String?>(4) { null }, female.frameDigests)
        assertEquals(null, female.digestAt(7))

        assertTrue(ExerciseVisual.jpeg(item.imagePaths).frameDigests.isEmpty())
        assertEquals(false, ExerciseVisual.jpeg(item.imagePaths).isAuthored)
    }

    @Test
    fun sharedV2ManifestAndCorpusSourcesAreComplete() {
        val vectorDirectory = File(repositoryRoot(), "shared/workout-vectors")
        val manifest = File(vectorDirectory, ExerciseVisualResolver.MANIFEST_ASSET_NAME)
        assertTrue("Missing shared exercise visual manifest: ${manifest.absolutePath}", manifest.isFile)

        val frames = ExerciseVisualResolver.parseManifest(
            json = manifest.readText(),
            packagedAssetNames = vectorDirectory.list()?.toSet().orEmpty()
        )
        val femaleVisual = ExerciseVisualResolver.resolve(item, Gender.FEMALE, frames)
        assertEquals(ExerciseVisualFormat.PNG, femaleVisual.format)
        assertEquals(
            (0 until 4).map { "${item.id}_female_v2_$it.png" },
            femaleVisual.framePaths
        )
        assertEquals(2, femaleVisual.representativeFrameIndex)
        assertTrue(
            "Shared manifest must carry a content digest per frame",
            femaleVisual.frameDigests.size == 4 && femaleVisual.frameDigests.all { it != null }
        )
        v2PngAssetNames().forEach { assetName ->
            val asset = File(vectorDirectory, assetName)
            assertTrue("Missing v2 workout illustration: ${asset.absolutePath}", asset.isFile)
        }
    }

    @Test
    fun releaseBuildsNeverMergeTheFrameCorpusIntoAssets() {
        val gradleFile = File(repositoryRoot(), "android/app/build.gradle.kts")
        val script = gradleFile.readText()
        assertTrue(
            "shared/workout-vectors must not be an assets.srcDir; frames ship via WorkoutFrameStore",
            !script.contains("\"../../shared/workout-vectors\"")
        )
        assertTrue(script.contains("isRelease -> \"none\""))
    }

    private fun assertSvgVisual(
        visual: ExerciseVisual,
        expectedGender: String,
        frameCount: Int,
        expectedRepresentativeFrame: Int = 1
    ) {
        assertEquals(ExerciseVisualFormat.SVG, visual.format)
        assertEquals(
            (0 until frameCount).map { frame -> "${item.id}_${expectedGender}_${frame}.svg" },
            visual.framePaths
        )
        assertEquals(expectedRepresentativeFrame, visual.representativeFrameIndex)
    }

    private fun manifestJson(
        frameCount: Int,
        includeMaleFrames: Boolean = true,
        includeFemaleFrames: Boolean = true
    ): String {
        val entry = linkedMapOf<String, Any>(
            "exerciseId" to item.id,
            "frameCount" to frameCount,
            "representativeFrameIndex" to 1
        )
        if (includeMaleFrames) {
            entry["maleFrames"] = frameNames("male", frameCount)
        }
        if (includeFemaleFrames) {
            entry["femaleFrames"] = frameNames("female", frameCount)
        }
        return Gson().toJson(
            mapOf(
                "schemaVersion" to 1,
                "exercises" to listOf(entry)
            )
        )
    }

    private fun frameNames(gender: String, frameCount: Int): List<String> =
        (0 until frameCount).map { frame -> "${item.id}_${gender}_$frame" }

    private fun assetNames(frameCount: Int): Set<String> = buildSet {
        listOf("male", "female").forEach { gender ->
            frameNames(gender, frameCount).forEach { add("$it.svg") }
        }
    }

    private fun v2PngAssetNames(): Set<String> = buildSet {
        listOf("male", "female").forEach { gender ->
            (0 until 4).forEach { frame -> add("${item.id}_${gender}_v2_$frame.png") }
        }
    }

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
    ) { directory -> directory.parentFile }
        .firstOrNull { directory ->
            File(directory, "shared/workout-vectors").isDirectory
        }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    private val item = ExerciseItem(
        id = "Barbell_Full_Squat",
        name = "Barbell Full Squat",
        level = "Intermediate",
        imagePaths = listOf("Barbell_Full_Squat/0.jpg", "Barbell_Full_Squat/1.jpg"),
        force = "Push",
        mechanic = "Compound",
        category = "Strength",
        equipment = "Barbell",
        primaryMuscles = listOf("Quadriceps"),
        secondaryMuscles = listOf("Glutes"),
        instructions = listOf("Squat with control.")
    )
}
