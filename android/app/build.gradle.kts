import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing config is read from android/keystore.properties (gitignored).
// When the file is absent (fresh checkout, CI without secrets), assembleRelease
// still works but emits an unsigned APK. Generate one with:
//   keytool -genkey -v -keystore fudai-release.jks -keyalg RSA -keysize 2048 \
//           -validity 10000 -alias fudai
// then create keystore.properties with storeFile / storePassword / keyAlias / keyPassword.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

// Workout frames ship inside the APK/AAB (see shared/workout-vectors/README.md), so no
// CDN is required or contacted. Release builds hard-code an empty base URL, which
// disables WorkoutFrameStore downloads entirely. Debug builds may opt into a
// download fallback (e.g. when built with -PworkoutVectors=sample) by pointing at a
// local corpus server in local.properties: workout.vectors.base.url=http://10.0.2.2:8765
val workoutVectorsDefaultBaseUrl = ""
val debugWorkoutVectorsBaseUrl = localProperties.getProperty("workout.vectors.base.url")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: workoutVectorsDefaultBaseUrl

android {
    namespace = "com.apoorvdarshan.calorietracker"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.apoorvdarshan.calorietracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "7.0"
        // Release uses localized @string/app_name; debug overrides to "Fud AI Debug".
        manifestPlaceholders["launcherAppName"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val oauthProps = Properties().apply {
            val file = rootProject.file("oauth.properties")
            if (file.exists()) load(file.inputStream())
        }
        val webClientId = oauthProps.getProperty("cloud.backup.web.client.id")
            ?: localProperties.getProperty("cloud.backup.web.client.id")
            ?: ""
        buildConfigField(
            "String",
            "CLOUD_BACKUP_WEB_CLIENT_ID",
            "\"${webClientId.replace("\"", "\\\"")}\""
        )
        // Empty: frames are bundled; WorkoutFrameStore never downloads in release.
        buildConfigField("String", "WORKOUT_VECTORS_BASE_URL", "\"$workoutVectorsDefaultBaseUrl\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config if keystore.properties exists. Without
            // it, gradle emits app-release-unsigned.apk and you sign manually with
            // apksigner before uploading to the Play Console.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // Suffix the package + version so the debug build installs side-by-side
            // with the production app. Launcher label matches iOS Debug: "Fud AI Debug".
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Literal placeholder so locale app_name strings can't override the label.
            manifestPlaceholders["launcherAppName"] = "Fud AI Debug"
            buildConfigField("String", "WORKOUT_VECTORS_BASE_URL", "\"$debugWorkoutVectorsBaseUrl\"")
        }
        create("debug2") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug2"
            versionNameSuffix = "-debug2"
            manifestPlaceholders["launcherAppName"] = "Fud AI Debug 2"
            buildConfigField("String", "WORKOUT_VECTORS_BASE_URL", "\"$debugWorkoutVectorsBaseUrl\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // AdsConfig gates real vs test ad units on BuildConfig.DEBUG.
        buildConfig = true
    }

    lint {
        // The default resources intentionally provide English fallback copy while
        // translated locales are updated incrementally. Keep all other release
        // checks enabled; only the fallback-policy warning is excluded.
        disable += "MissingTranslation"
    }

    // Workouts: mirror iOS exercises.json. The authored workout frames in
    // shared/workout-vectors (~1.2 GB) are added by the workout-vector asset task
    // below rather than as a srcDir, so only the manifest + `*_v2_*.png` frames are
    // packaged (not the README, SVG pilot, or sample list that live next to them).
    sourceSets {
        getByName("main") {
            assets.srcDirs(
                "src/main/assets",
                "../../ios/calorietracker/Resources/FreeExerciseDB/dist",
                "../../local-models/legal"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Workout vector frames
//
// Every build bundles exercise-visual-manifest.json plus the complete authored
// frame corpus (shared/workout-vectors/*_v2_*.png, ~7,000 files / ~1.2 GB) as flat
// assets, so WorkoutFrameStore resolves frames offline with no CDN dependency.
// Known ship blocker: the resulting Play base module is far above Play's 200 MB
// cap; the owner accepts this for now (see shared/workout-vectors/README.md).
//
// Debug builds may trade offline coverage for build speed:
//
//   ./gradlew assembleDebug -PworkoutVectors=all      (default: whole corpus)
//   ./gradlew assembleDebug -PworkoutVectors=sample   (sample-pack.txt only, ~15 MB)
//   ./gradlew assembleDebug -PworkoutVectors=none     (manifest only)
//
// Release builds always bundle the whole corpus and refuse the overrides above so
// a store binary can never ship with missing frames by accident.
//
// Frames are copied (never hard-linked or symlinked) into the generated asset
// directory: the task outputs must not share inodes with shared/workout-vectors, so
// Gradle cleaning or rewriting its outputs can never touch the canonical corpus, and
// a missing/altered output is detected and regenerated by the normal up-to-date check.
// The prepared set is then verified against the manifest's frame sequences, not just
// counted, so a frame the manifest names can never be silently absent from the build.
// ---------------------------------------------------------------------------
val workoutVectorsDirectory = rootProject.file("../shared/workout-vectors")
val workoutVectorsManifest = File(workoutVectorsDirectory, "exercise-visual-manifest.json")
val workoutVectorsSampleList = File(workoutVectorsDirectory, "sample-pack.txt")
val workoutVectorsModeProperty = providers.gradleProperty("workoutVectors")
// 875 illustrated exercises x 2 genders x 4 frames (scripts/sync_workout_visual_assets.py).
val workoutVectorsExpectedFrameCount = 875 * 2 * 4

fun workoutVectorSampleFiles(): List<File> {
    val ids = workoutVectorsSampleList.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    return ids.flatMap { id ->
        listOf("male", "female").flatMap { gender ->
            (0 until 4).map { frame -> File(workoutVectorsDirectory, "${id}_${gender}_v2_$frame.png") }
        }
    }
}

abstract class PrepareWorkoutVectorAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val frameFiles: ConfigurableFileCollection

    @get:Input
    abstract val mode: Property<String>

    /** The raw -PworkoutVectors override; release tasks refuse anything but all/absent. */
    @get:Input
    @get:Optional
    abstract val requestedMode: Property<String>

    @get:Input
    abstract val release: Property<Boolean>

    /** Frame count the manifest must name (and mode `all` must bundle). */
    @get:Input
    abstract val expectedFrameCount: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val requested = requestedMode.orNull
        if (release.get() && requested != null && requested != "all") {
            throw GradleException(
                "workoutVectors=$requested is not allowed for release builds; " +
                    "store binaries must bundle the complete workout frame corpus."
            )
        }
        val manifest = manifestFile.get().asFile
        val manifestFrames = manifestFrameFileNames(manifest)
        if (manifestFrames.size != expectedFrameCount.get()) {
            throw GradleException(
                "${manifest.name} names ${manifestFrames.size} v2 frames, expected " +
                    "${expectedFrameCount.get()}; run scripts/sync_workout_visual_assets.py"
            )
        }

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        copyAsset(manifest, output)
        val bundled = sortedSetOf<String>()
        frameFiles.files.forEach { source ->
            require(bundled.add(source.name)) { "duplicate workout vector asset name: ${source.name}" }
            copyAsset(source, output)
        }

        val missing = manifestFrames - bundled
        val unexpected = bundled - manifestFrames
        val problems = mutableListOf<String>()
        when (mode.get()) {
            "all" -> {
                if (missing.isNotEmpty()) problems += "missing ${missing.size} manifest frame(s): ${summarize(missing)}"
            }
            "sample" -> {
                if (bundled.isEmpty()) problems += "sample pack selected no frames"
            }
            "none" -> {
                if (bundled.isNotEmpty()) problems += "mode none must not bundle frames"
            }
        }
        if (unexpected.isNotEmpty()) {
            problems += "${unexpected.size} frame(s) not in the manifest: ${summarize(unexpected)}"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "shared/workout-vectors does not match ${manifest.name} (${problems.joinToString("; ")}); " +
                    "run scripts/sync_workout_visual_assets.py --check"
            )
        }
        logger.lifecycle("workout vectors (${mode.get()}): bundled manifest + ${bundled.size} frame(s)")
    }

    /** A real copy: outputs must never share an inode with the canonical corpus. */
    private fun copyAsset(source: File, output: File) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
            "workout vector source missing or not a regular file: $source"
        }
        val target = File(output, source.name)
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        require(target.length() == source.length()) { "short copy of workout vector asset: $target" }
    }

    /** Every `<name>.png` the manifest's male/female sequences reference. */
    private fun manifestFrameFileNames(manifest: File): Set<String> {
        val document = JsonSlurper().parse(manifest) as? Map<*, *>
            ?: throw GradleException("${manifest.name} is not a JSON object")
        val exercises = document["exercises"] as? List<*>
            ?: throw GradleException("${manifest.name} has no exercises array")
        val names = sortedSetOf<String>()
        exercises.forEach { exercise ->
            val entry = exercise as? Map<*, *> ?: throw GradleException("${manifest.name}: exercise entry is not an object")
            val format = entry["format"] as? String ?: "png"
            listOf("maleFrames", "femaleFrames").forEach { key ->
                val frames = entry[key] as? List<*> ?: throw GradleException("${manifest.name}: ${entry["exerciseId"]} lacks $key")
                frames.forEach { frame ->
                    val name = frame as? String ?: throw GradleException("${manifest.name}: non-string frame in ${entry["exerciseId"]}")
                    if (!names.add("$name.$format")) throw GradleException("${manifest.name}: duplicate frame $name")
                }
            }
        }
        return names
    }

    private fun summarize(names: Set<String>): String =
        names.take(8).joinToString(", ") + if (names.size > 8) ", ... (+${names.size - 8} more)" else ""
}

androidComponents {
    onVariants { variant ->
        val isRelease = variant.buildType == "release"
        val requested = workoutVectorsModeProperty.orNull
        // Gradle configures every variant even for `assembleDebug`, so the release variant
        // must not throw here when a debug override is present. Release always bundles the
        // whole corpus and only rejects the override if its own asset task runs.
        val mode = when {
            isRelease -> "all"
            requested == null -> "all"
            requested in setOf("none", "sample", "all") -> requested
            else -> throw GradleException("Unknown workoutVectors mode '$requested' (none|sample|all)")
        }
        val taskName = "prepare${variant.name.replaceFirstChar { it.uppercase() }}WorkoutVectorAssets"
        val task = tasks.register<PrepareWorkoutVectorAssetsTask>(taskName) {
            this.mode.set(mode)
            requestedMode.set(workoutVectorsModeProperty)
            release.set(isRelease)
            expectedFrameCount.set(workoutVectorsExpectedFrameCount)
            manifestFile.set(workoutVectorsManifest)
            when (mode) {
                "sample" -> frameFiles.from(workoutVectorSampleFiles())
                "all" -> frameFiles.from(fileTree(workoutVectorsDirectory) { include("*_v2_*.png") })
            }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            task,
            PrepareWorkoutVectorAssetsTask::outputDirectory
        )
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.play.review.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.gson)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.app.update)
    implementation(libs.play.services.auth)
    implementation(libs.vico.compose.m3)
    implementation(libs.litert.lm.android)
    implementation(libs.whisper.android)

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
