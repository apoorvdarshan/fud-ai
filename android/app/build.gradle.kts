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

// Public CDN prefix for on-demand workout frames (R2 bucket behind a custom domain;
// see shared/workout-vectors/README.md). Debug builds may point at a local corpus
// server via local.properties: workout.vectors.base.url=http://10.0.2.2:8765
val workoutVectorsDefaultBaseUrl = "https://assets.fud-ai.app/workout-vectors/v2"
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
// (WorkoutFrameStore). Debug builds also bundle the small sample pack listed in
// shared/workout-vectors/sample-pack.txt so animations work offline.
//
//   ./gradlew assembleDebug -PworkoutVectors=sample   (default for debug builds)
//   ./gradlew assembleDebug -PworkoutVectors=all      (whole corpus, local QA only)
//   ./gradlew assembleDebug -PworkoutVectors=none     (manifest only, release parity)
//
// Release builds refuse anything other than the manifest so the 1.2 GB corpus
// can never end up in a store binary again.
// ---------------------------------------------------------------------------
val workoutVectorsDirectory = rootProject.file("../shared/workout-vectors")
val workoutVectorsManifest = File(workoutVectorsDirectory, "exercise-visual-manifest.json")
val workoutVectorsSampleList = File(workoutVectorsDirectory, "sample-pack.txt")
val workoutVectorsModeProperty = providers.gradleProperty("workoutVectors")

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

    /** The raw -PworkoutVectors override; release tasks refuse anything but none/absent. */
    @get:Input
    @get:Optional
    abstract val requestedMode: Property<String>

    @get:Input
    abstract val release: Property<Boolean>

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
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        var frames = 0
        sourceFiles.files.forEach { source ->
            require(source.isFile) { "workout vector source missing: $source" }
            source.copyTo(File(output, source.name), overwrite = true)
            if (source.extension == "png") frames++
        }
        logger.lifecycle("workout vectors (${mode.get()}): bundled manifest + $frames frame(s)")
    }
}

androidComponents {
    onVariants { variant ->
        val isRelease = variant.buildType == "release"
        val requested = workoutVectorsModeProperty.orNull
        // Gradle configures every variant even for `assembleDebug`, so the release variant
        // must not throw here when a debug corpus override is present. Release always uses
        // manifest-only inputs and only rejects the override if its own asset task runs.
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
