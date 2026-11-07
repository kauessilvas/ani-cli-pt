package dev.aniclipt.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimesOnlineFallbackTest {
    @Test fun matchesOnlyTheSameTitleDespiteAudioAndYearLabels() {
        assertTrue(fallbackTitleMatches("Black Clover", "Black Clover Dublado"))
        assertTrue(fallbackTitleMatches("Black Clover", "Black Clover 2017"))
        assertFalse(fallbackTitleMatches("Black Clover", "Black Clover 2nd Season"))
        assertFalse(fallbackTitleMatches("High School DxD", "High School of the Dead"))
    }
}
