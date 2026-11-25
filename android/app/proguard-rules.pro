# R8/ProGuard rules for the release build (app/build.gradle.kts:isMinifyEnabled = true).
# Default rules from proguard-android-optimize.txt are layered in first.

# ─── kotlinx.serialization ────────────────────────────────────────────────────
# All @Serializable data classes (UserProfile, FoodEntry, WeightEntry,
# ChatMessage, WidgetSnapshot, FoodAnalysis, etc.) are reflected at runtime to
# locate their generated KSerializer$$serializer companion. Strip those and the
# DataStore + widget-snapshot JSON layer breaks at runtime with NoSuchMethodError
# on `serializer()`.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# All app-package serializable classes + their generated $$serializer.
-keep,includedescriptorclasses class com.apoorvdarshan.calorietracker.**$$serializer { *; }
-keepclassmembers class com.apoorvdarshan.calorietracker.** {
    *** Companion;
}
-keepclasseswithmembers class com.apoorvdarshan.calorietracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Enum @Serializable lookups need their members preserved for KSerializer.
-keepclassmembers enum com.apoorvdarshan.calorietracker.** { *; }

# ─── Glance widgets ───────────────────────────────────────────────────────────
# CalorieAppWidget / ProteinAppWidget + their *Receiver classes are loaded by
# the OS via reflection from AndroidManifest. Default Android rules cover the
# Receiver subclasses, but Glance also reflects on the GlanceAppWidget subclass
# itself.
-keep class com.apoorvdarshan.calorietracker.widget.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# ─── WorkManager + Room (transitive Glance dependency) ───────────────────────
# Glance widget updates run through WorkManager, which in turn uses Room for its
# WorkDatabase. R8 strips both unless we keep the @Database class and the Worker
# subclasses — without these the app crashes on Application.onCreate with
# "Failed to create an instance of androidx.work.impl.WorkDatabase" the first
# time AppContainer wires the snapshot writer.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers @androidx.room.Database class * { *; }
-keep class androidx.work.impl.** { *; }
-keep class androidx.work.WorkerParameters { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-dontwarn androidx.work.**
-dontwarn androidx.room.paging.**

# ─── Embedded LiteRT-LM ─────────────────────────────────────────────────────
# LiteRT-LM's JNI bridge is registered against Kotlin/Java class and method names.
# The current AAR does not ship consumer rules, so keep its API intact in release builds.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Health Connect ───────────────────────────────────────────────────────────
# The androidx.health.connect.client AAR ships consumer rules that should
# survive R8, but defensively keep WeightRecord / NutritionRecord field access.
-keep class androidx.health.connect.client.records.** { *; }

# ─── ML Kit barcode scanner ───────────────────────────────────────────────────
# ML Kit discovers these registrars by class name at runtime. Release minification
# can strip their no-arg constructors, which crashes the barcode scanner on open.
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { *; }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { *; }
-keep class com.google.mlkit.vision.barcode.internal.BarcodeRegistrar { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

# ─── Crash reporting ──────────────────────────────────────────────────────────
# Keep line numbers so Play Console crash reports stay readable, but rename
# the original source file name so we don't leak internal file structure.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# play-core-ktx (in-app review) references this compile-only annotation that no
# runtime dependency ships; R8 in full mode fails the release build without the
# suppression. Surfaced when play-services-ads 24.4 shifted basement versions.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# ─── Workouts exercise library (Gson) ─────────────────────────────────────────
# Gson parses assets/exercises.json into ExerciseRecord by FIELD NAME via
# reflection (no @SerializedName annotations). R8 renaming those fields made
# release builds parse every record to nulls — the live-3.0 empty-Workouts bug.
# Signature is required so TypeToken<List<ExerciseRecord>> survives shrinking.
-keepattributes Signature
-keepclassmembers class com.apoorvdarshan.calorietracker.data.ExerciseRecord {
  <init>();
  <fields>;
}

# R8 full mode strips the generic signature from the anonymous
# TypeToken<List<ExerciseRecord>> subclass unless TypeToken subtypes are kept;
# Gson then has no element type and the parse dies into runCatching -> zero
# exercises. These are the canonical rules Gson 2.11+ ships as consumer rules.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keep class com.k2fsa.sherpa.onnx.** { *; }
