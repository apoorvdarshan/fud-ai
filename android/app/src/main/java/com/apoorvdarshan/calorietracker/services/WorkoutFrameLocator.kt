package com.apoorvdarshan.calorietracker.services

import java.security.MessageDigest

/**
 * Pure naming rules shared by [WorkoutFrameStore] and its unit tests: how an authored
 * workout frame maps to a bundled asset name, an on-device cache filename, an optional
 * debug download URL, and an integrity check.
 *
 * Frames are addressed by their manifest name (`<exercise>_<gender>_v2_<n>`) plus an
 * optional content digest (hex prefix of the PNG's SHA-256, from the manifest). The
 * digest is part of both the debug download URL (`?v=`) and the cache filename, so a
 * repaired frame published under the same name invalidates stale caches automatically.
 */
internal object WorkoutFrameLocator {
    private val safeName = Regex("^[A-Za-z0-9_-]+$")
    private val safeDigest = Regex("^[0-9a-f]{8,64}$")
    private const val NO_DIGEST = "nodigest"

    /** Flat `<name>.png` / `<name>.svg` filename → manifest frame name, or null for anything else. */
    fun frameName(framePath: String): String? {
        if (framePath.contains('/') || framePath.contains('\\')) return null
        val name = framePath.removeSuffix(".png").removeSuffix(".svg")
        return name.takeIf(safeName::matches)
    }

    fun normalizedDigest(digest: String?): String? =
        digest?.lowercase()?.takeIf(safeDigest::matches)

    fun cacheFileName(name: String, digest: String?, extension: String): String =
        "$name.${normalizedDigest(digest) ?: NO_DIGEST}.$extension"

    /** Prefix that identifies every cached revision of one frame (for stale-revision cleanup). */
    fun cacheFilePrefix(name: String): String = "$name."

    fun remoteUrl(baseUrl: String, name: String, digest: String?, extension: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || !(base.startsWith("https://") || base.startsWith("http://"))) return null
        val query = normalizedDigest(digest)?.let { "?v=$it" }.orEmpty()
        return "$base/$name.$extension$query"
    }

    /** True when [bytes] match [digest] (or no digest was supplied). */
    fun matchesDigest(bytes: ByteArray, digest: String?): Boolean {
        val expected = normalizedDigest(digest) ?: return true
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return actual.startsWith(expected)
    }

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun looksLikePng(bytes: ByteArray): Boolean =
        bytes.size > pngSignature.size &&
            pngSignature.indices.all { bytes[it] == pngSignature[it] }
}
