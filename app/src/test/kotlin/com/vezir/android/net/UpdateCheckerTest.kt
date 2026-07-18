package com.vezir.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version-comparison logic for the GitHub-releases update nudge. Pure
 * semver math, no network — the fetch/throttle path is not exercised here.
 */
class UpdateCheckerTest {

    @Test
    fun newerPatchMinorMajor() {
        assertTrue(UpdateChecker.isNewer("v0.9.1", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("v0.10.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("v1.0.0", "0.99.99"))
    }

    @Test
    fun leadingVOptionalOnBothSides() {
        assertTrue(UpdateChecker.isNewer("0.9.1", "v0.9.0"))
        assertTrue(UpdateChecker.isNewer("v0.9.1", "v0.9.0"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v0.9.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "v0.9.0"))
    }

    @Test
    fun olderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v0.8.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("v0.9.0", "0.10.0"))
    }

    @Test
    fun differingComponentLengths() {
        // 0.9 == 0.9.0; 0.9.1 > 0.9
        assertFalse(UpdateChecker.isNewer("0.9", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("0.9.1", "0.9"))
    }

    @Test
    fun prereleaseSuffixIgnoredInCoreCompare() {
        // We only compare the numeric core; suffixes are stripped.
        assertFalse(UpdateChecker.isNewer("v0.9.0-rc1", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("v0.9.1-rc1", "0.9.0"))
    }

    @Test
    fun malformedInputsNeverNag() {
        assertFalse(UpdateChecker.isNewer("garbage", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("v0.9.0", "garbage"))
        assertFalse(UpdateChecker.isNewer("", "0.9.0"))
    }
}
