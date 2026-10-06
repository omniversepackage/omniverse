package com.yodesla.omniverse.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit 73 M4: Restore / Replace rewrite EVERY profile's data, so they ask for the parental PIN
 * whenever one is set — the Kids-only rule profile admin uses is not enough here, because a kid
 * holding the remote on an adult profile can still wipe the whole device.
 */
class BackupRestorePinGateTest {
    @Test
    fun restoreNeedsThePinOnEveryProfileWhenOneIsSet() {
        assertTrue(
            backupRestoreNeedsPin(pinEnabled = true),
            "an adult profile is not a safe profile when the action rewrites everyone's data",
        )
    }

    @Test
    fun restoreRunsFreeOnAPinlessDevice() {
        assertFalse(backupRestoreNeedsPin(pinEnabled = false), "with no PIN set there is nothing to ask for")
    }
}
