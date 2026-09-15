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

// Public CDN prefix for on-demand workout frames (R2 bucket `fud-ai-assets` behind a
// custom domain; see shared/workout-vectors/README.md). Store builds bundle only the
// manifest and WorkoutFrameStore downloads frames from here. Debug builds may point at
// a local corpus server via local.properties: workout.vectors.base.url=http://10.0.2.2:8765
val workoutVectorsDefaultBaseUrl = "https://assets.fud-ai.app/workout-vectors/v2"
val debugWorkoutVectorsBaseUrl = localProperties.getProperty("workout.vectors.base.url")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: workoutVectorsDefaultBaseUrl

android {
    namespace = "com.apoorvdarshan.calorietracker"
    compileSdk {
        // AndroidX Compose / core 1.19+ and OkHttp 5.5 require compileSdk >= 37.
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.apoorvdarshan.calorietracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 36
        versionName = "7.0.1"
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
        // Public CDN prefix the app fetches workout frames from on demand.
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
    // shared/workout-vectors (~1.2 GB) are deliberately NOT merged here; see the
    // workout-vector asset task below (manifest only in release, sample in debug).
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
// Release/store builds bundle only exercise-visual-manifest.json; frames are
// fetched on demand from WORKOUT_VECTORS_BASE_URL and cached on device
// (WorkoutFrameStore). The authored corpus (shared/workout-vectors/*_v2_*.png,
// ~7,000 files / ~1.2 GB) would push the Play base module far past its 200 MB cap,
// so it must never be packaged into a store binary. Debug builds also bundle the
// small sample pack listed in shared/workout-vectors/sample-pack.txt so a handful
// of exercises animate offline without a CDN.
//
//   ./gradlew assembleDebug -PworkoutVectors=sample   (default for debug builds)
//   ./gradlew assembleDebug -PworkoutVectors=all      (whole corpus, local QA only)
//   ./gradlew assembleDebug -PworkoutVectors=none     (manifest only, release parity)
//
// Release variants always use manifest-only inputs and refuse any override other
// than `none`, so `assembleRelease -PworkoutVectors=all` fails instead of producing
// a 1.2 GB APK/AAB. `-PworkoutVectors=none` is accepted everywhere (it is release
// parity); CI (.github/workflows/quality.yml) runs unit tests and lint with it so it
// never has to copy or fingerprint the sample pack either.
//
// Frames are copied (never hard-linked or symlinked) into the generated asset
// directory: the task outputs must not share inodes with shared/workout-vectors, so
// Gradle cleaning or rewriting its outputs can never touch the canonical corpus, and
// a missing/altered output is detected and regenerated by the normal up-to-date check.
// The prepared set is then verified against the manifest's frame sequences, not just
// counted, so a frame outside the manifest can never sneak into a build.
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

    /** The raw -PworkoutVectors override; release tasks refuse anything but none/absent. */
    @get:Input
    @get:Optional
    abstract val requestedMode: Property<String>

    @get:Input
    abstract val release: Property<Boolean>

    /** Frame count the manifest must name (and the local-QA mode `all` must bundle). */
    @get:Input
    abstract val expectedFrameCount: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val requested = requestedMode.orNull
        if (release.get() && requested != null && requested != "none") {
            throw GradleException(
                "workoutVectors=$requested is not allowed for release builds; " +
                    "store binaries must only bundle the manifest."
            )
        }
        if (release.get() && mode.get() != "none") {
            throw GradleException("release workout vector assets must use mode none, got ${mode.get()}")
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
        // Belt and braces: whatever selected the inputs, a release output may only ever
        // hold the manifest. Checked on the files actually written, not on `mode`.
        if (release.get()) {
            val leaked = output.listFiles().orEmpty().filter { it.name != manifest.name }
            if (leaked.isNotEmpty()) problems += "release output contains ${leaked.size} non-manifest file(s): ${summarize(leaked.map { it.name }.toSortedSet())}"
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
        // must not throw here when a debug corpus override is present. Release always uses
        // manifest-only inputs (the 7,000-file corpus is never even wired into its task
        // graph) and only rejects the override if its own asset task runs, so
        // `assembleRelease -PworkoutVectors=all` fails while `assembleDebug
        // -PworkoutVectors=all` and `lintRelease -PworkoutVectors=none` both work.
        val mode = when {
            isRelease -> "none"
            requested == null -> "sample"
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
