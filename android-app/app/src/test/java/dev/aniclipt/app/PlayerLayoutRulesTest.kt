package dev.aniclipt.app

import org.junit.Assert.*
import org.junit.Test

class PlayerLayoutRulesTest {
    @Test fun liftsBottomSubtitlesOnlyForShortLandscapeWithControls() {
        assertTrue(shouldLiftSubtitle("bottom", true, true, 360))
        assertFalse(shouldLiftSubtitle("bottom", true, false, 360))
        assertFalse(shouldLiftSubtitle("bottom", false, true, 360))
        assertFalse(shouldLiftSubtitle("bottom", true, true, 430))
    }
    @Test fun preservesExplicitTopAndCenterPreferences() {
        assertFalse(shouldLiftSubtitle("top", true, true, 360))
        assertFalse(shouldLiftSubtitle("center", true, true, 360))
    }
}
