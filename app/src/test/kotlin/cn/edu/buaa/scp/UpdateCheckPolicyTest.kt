package cn.edu.buaa.scp

import cn.edu.buaa.scp.ui.UpdateUi
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckPolicyTest {
    @Test fun firstCheckAndNewBuildAreImmediatelyDue() {
        assertTrue(UpdateUi.autoCheckDue(null, 0, 1000))
        assertTrue(UpdateUi.autoCheckDue("0.8.0", 1000, 1001))
    }

    @Test fun currentBuildChecksAgainAfterFifteenMinutes() {
        assertFalse(UpdateUi.autoCheckDue(BuildConfig.VERSION_NAME, 1000, 1000 + 15 * 60 * 1000 - 1))
        assertTrue(UpdateUi.autoCheckDue(BuildConfig.VERSION_NAME, 1000, 1000 + 15 * 60 * 1000))
    }

    @Test fun clockMovingBackwardDoesNotSuppressChecks() {
        assertTrue(UpdateUi.autoCheckDue(BuildConfig.VERSION_NAME, 2000, 1000))
    }
}
