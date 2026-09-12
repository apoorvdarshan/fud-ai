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

val oauthProps = Properties().apply {
    val file = rootProject.file("oauth.properties")
    if (file.exists()) load(file.inputStream())
}
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}
val revenueCatPublicKey = oauthProps.getProperty("revenuecat.public.sdk.key")
    ?: localProps.getProperty("revenuecat.public.sdk.key")
    ?: ""

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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val webClientId = oauthProps.getProperty("cloud.backup.web.client.id")
            ?: localProps.getProperty("cloud.backup.web.client.id")
            ?: ""
        buildConfigField(
            "String",
            "CLOUD_BACKUP_WEB_CLIENT_ID",
            "\"${webClientId.replace("\"", "\\\"")}\""
        )
        buildConfigField(
            "String",
            "REVENUECAT_PUBLIC_SDK_KEY",
            "\"${revenueCatPublicKey.replace("\"", "\\\"")}\""
        )
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
            // with the production app pulled from Play Store. Launcher label stays
            // "Fud AI" (same as release) — distinguish the two by the install order
            // / icon position rather than a separate label.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("debug2") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug2"
            versionNameSuffix = "-debug2"
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

    // Workouts: mirror iOS exercises.json plus gender-aware authored frames from
    // shared/workout-vectors. Upstream Free Exercise DB JPEGs are no longer shipped.
    sourceSets {
        getByName("main") {
            assets.srcDirs(
                "src/main/assets",
                "../../ios/calorietracker/Resources/FreeExerciseDB/dist",
                "../../shared/workout-vectors",
                "../../local-models/legal"
            )
        }
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
    implementation(libs.revenuecat.purchases)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

afterEvaluate {
    tasks.matching {
        it.name.equals("assembleRelease", ignoreCase = true) ||
            it.name.equals("bundleRelease", ignoreCase = true)
    }.configureEach {
        doFirst {
            if (revenueCatPublicKey.isBlank() || revenueCatPublicKey.contains("PLACEHOLDER", ignoreCase = true)) {
                // Hosted billing stays disabled until the public Play SDK key is set
                // (revenuecat.public.sdk.key in oauth.properties / local.properties).
                // Allow local USB release installs; Play/store shipping must set the key.
                logger.warn(
                    "revenuecat.public.sdk.key missing — RevenueCat will not configure in this release build."
                )
            }
        }
    }
}
