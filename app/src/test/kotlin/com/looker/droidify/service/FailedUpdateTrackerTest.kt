package com.looker.droidify.service

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailedUpdateTrackerTest {

    @BeforeTest
    fun setup() {
        FailedUpdateTracker.clear()
    }

    @Test
    fun returnsFalseWhenNoFailureRecorded() {
        assertFalse(FailedUpdateTracker.shouldSkipAutoUpdate("com.example.app", 100L))
    }

    @Test
    fun skipsAutoUpdateAfterFailureForSameVersion() {
        val pkg = "com.example.app"
        FailedUpdateTracker.recordFailure(pkg, 100L)
        assertTrue(FailedUpdateTracker.shouldSkipAutoUpdate(pkg, 100L))
    }

    @Test
    fun doesNotSkipWhenNewVersionCodeAvailable() {
        val pkg = "com.example.app"
        FailedUpdateTracker.recordFailure(pkg, 100L)
        // A newer release version 101L should not be skipped
        assertFalse(FailedUpdateTracker.shouldSkipAutoUpdate(pkg, 101L))
        // And it should have reset the record for the package
        assertFalse(FailedUpdateTracker.shouldSkipAutoUpdate(pkg, 100L))
    }

    @Test
    fun recordSuccessClearsFailure() {
        val pkg = "com.example.app"
        FailedUpdateTracker.recordFailure(pkg, 100L)
        assertTrue(FailedUpdateTracker.shouldSkipAutoUpdate(pkg, 100L))

        FailedUpdateTracker.recordSuccess(pkg)
        assertFalse(FailedUpdateTracker.shouldSkipAutoUpdate(pkg, 100L))
    }
}
