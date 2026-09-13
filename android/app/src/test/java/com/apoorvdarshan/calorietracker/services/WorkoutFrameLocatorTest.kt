package com.apoorvdarshan.calorietracker.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class WorkoutFrameLocatorTest {
    private val name = "Barbell_Full_Squat_female_v2_2"

    @Test
    fun frameNameStripsExtensionAndRejectsUnsafeNames() {
        assertEquals(name, WorkoutFrameLocator.frameName("$name.png"))
        assertEquals("Ab_Roller_male_v2_0", WorkoutFrameLocator.frameName("Ab_Roller_male_v2_0.svg"))
        assertNull(WorkoutFrameLocator.frameName("nested/$name.png"))
        assertNull(WorkoutFrameLocator.frameName("../etc/passwd.png"))
        assertNull(WorkoutFrameLocator.frameName("Barbell_Full_Squat/0.jpg"))
        assertNull(WorkoutFrameLocator.frameName("has space.png"))
        assertNull(WorkoutFrameLocator.frameName(""))
    }

    @Test
    fun remoteUrlUsesDigestAsCacheBuster() {
        assertEquals(
            "https://assets.fud-ai.app/workout-vectors/v2/$name.png?v=0123456789abcdef",
            WorkoutFrameLocator.remoteUrl("https://assets.fud-ai.app/workout-vectors/v2/", name, "0123456789ABCDEF", "png")
        )
        assertEquals(
            "http://10.0.2.2:8765/$name.png",
            WorkoutFrameLocator.remoteUrl("http://10.0.2.2:8765", name, null, "png")
        )
        assertNull(WorkoutFrameLocator.remoteUrl("", name, "0123456789abcdef", "png"))
        assertNull(WorkoutFrameLocator.remoteUrl("ftp://example.com", name, null, "png"))
    }

    @Test
    fun cacheFileNamesAreRevisioned() {
        assertEquals("$name.0123456789abcdef.png", WorkoutFrameLocator.cacheFileName(name, "0123456789abcdef", "png"))
        assertEquals("$name.nodigest.png", WorkoutFrameLocator.cacheFileName(name, "zz", "png"))
        assertTrue(WorkoutFrameLocator.cacheFileName(name, "abcdef01", "png").startsWith(WorkoutFrameLocator.cacheFilePrefix(name)))
    }

    @Test
    fun digestVerificationUsesSha256Prefix() {
        val bytes = "frame-bytes".toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertTrue(WorkoutFrameLocator.matchesDigest(bytes, sha256.substring(0, 16)))
        assertTrue(WorkoutFrameLocator.matchesDigest(bytes, sha256))
        assertTrue(WorkoutFrameLocator.matchesDigest(bytes, null))
        assertFalse(WorkoutFrameLocator.matchesDigest(bytes, "0000000000000000"))
    }

    @Test
    fun pngSignatureCheck() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        assertTrue(WorkoutFrameLocator.looksLikePng(png))
        assertFalse(WorkoutFrameLocator.looksLikePng("<svg/>".toByteArray()))
        assertFalse(WorkoutFrameLocator.looksLikePng(ByteArray(0)))
    }
}
