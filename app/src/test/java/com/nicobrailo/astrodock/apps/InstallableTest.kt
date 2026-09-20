package com.nicobrailo.astrodock.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallableTest {
    private val wanted = listOf(
        "AstroDock-debug.apk", "app-debug.apk",
        "AstroDock.apk", "AstroDock-release.apk", "app-release.apk",
    )

    @Test
    fun picksTheBestNameTheReleaseHas() {
        assertEquals(
            "AstroDock-debug.apk",
            pickApkAsset(listOf("AstroDock.apk", "AstroDock-debug.apk"), wanted),
        )
        // The two tools/build-apks.sh makes, debug first
        assertEquals(
            "AstroDock-debug.apk",
            pickApkAsset(listOf("AstroDock-debug.apk", "AstroDock-release.apk"), wanted),
        )
        // What the releases hold today, with no debug build published
        assertEquals("AstroDock.apk", pickApkAsset(listOf("AstroDock.apk"), wanted))
        assertEquals("app-release.apk", pickApkAsset(listOf("app-release.apk"), wanted))
    }

    @Test
    fun fallsBackToAnyApk() {
        assertEquals(
            "AstroDock-v2.apk",
            pickApkAsset(listOf("checksums.txt", "AstroDock-v2.apk", "AstroDock-v2-arm64.apk"), wanted),
        )
    }

    @Test
    fun findsNothingInAReleaseWithoutOne() {
        assertNull(pickApkAsset(emptyList(), wanted))
        assertNull(pickApkAsset(listOf("source.zip", "notes.md"), wanted))
    }

    @Test
    fun recognisesTheInstalledBuildByItsDigest() {
        val hash = "addb74244121d8e145ae90408b413bf8af7efef45175e69401f1676d5320c32d"
        assertTrue(sameBuild("sha256:$hash", hash)!!)
        // GitHub writes the hex lower case, but nothing says it has to
        assertTrue(sameBuild("sha256:${hash.uppercase()}", hash)!!)
        assertFalse(sameBuild("sha256:${hash.dropLast(1)}0", hash)!!)
    }

    @Test
    fun hasNothingToSayWithoutADigest() {
        val hash = "addb74244121d8e145ae90408b413bf8af7efef45175e69401f1676d5320c32d"
        // A release from before GitHub published digests
        assertNull(sameBuild(null, hash))
        assertNull(sameBuild("", hash))
        assertNull(sameBuild("sha256:", hash))
        // Some other algorithm, which we can't check the APK against
        assertNull(sameBuild("sha512:$hash", hash))
    }
}
