import java.nio.file.Files
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
// Frames are hard-linked into the generated asset directory when the filesystem
// allows it (falling back to a copy), so a clean build does not duplicate 1.2 GB.
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
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val mode: Property<String>

    /** The raw -PworkoutVectors override; release tasks refuse anything but all/absent. */
    @get:Input
    @get:Optional
    abstract val requestedMode: Property<String>

    @get:Input
    abstract val release: Property<Boolean>

    /** Frame count the complete corpus must contain; release builds fail when it is short. */
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
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        var frames = 0
        sourceFiles.files.forEach { source ->
            require(source.isFile) { "workout vector source missing: $source" }
            val target = File(output, source.name)
            val linked = runCatching { Files.createLink(target.toPath(), source.toPath()) }.isSuccess
            if (!linked) source.copyTo(target, overwrite = true)
            if (source.extension == "png") frames++
        }
        if (mode.get() == "all" && frames != expectedFrameCount.get()) {
            throw GradleException(
                "shared/workout-vectors contains $frames v2 frames, expected " +
                    "${expectedFrameCount.get()}; run scripts/sync_workout_visual_assets.py --check"
            )
        }
        logger.lifecycle("workout vectors (${mode.get()}): bundled manifest + $frames frame(s)")
    }
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
            sourceFiles.from(workoutVectorsManifest)
            when (mode) {
                "sample" -> sourceFiles.from(workoutVectorSampleFiles())
                "all" -> sourceFiles.from(fileTree(workoutVectorsDirectory) { include("*_v2_*.png") })
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
