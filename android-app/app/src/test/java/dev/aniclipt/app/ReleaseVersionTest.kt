package dev.aniclipt.app

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun comparesNumbersInsteadOfText() {
        assertTrue(compareReleaseVersions("0.1.10", "0.1.9") > 0)
        assertTrue(compareReleaseVersions("0.2.0", "1.0.0") < 0)
    }
    @Test fun updateRequiresSamePackageNewerVersionAndSameSignature() {
        assertTrue(isCompatibleUpdate("dev.app", "dev.app", 8, 9, setOf("chave"), setOf("chave")))
        assertFalse(isCompatibleUpdate("dev.app", "dev.outro", 8, 9, setOf("chave"), setOf("chave")))
        assertFalse(isCompatibleUpdate("dev.app", "dev.app", 8, 8, setOf("chave"), setOf("chave")))
        assertFalse(isCompatibleUpdate("dev.app", "dev.app", 8, 9, setOf("chave"), setOf("outra")))
        assertFalse(isCompatibleUpdate("dev.app", "dev.app", 8, 9, emptySet<String>(), emptySet<String>()))
    }
    @Test fun acceptsTagPrefixAndMissingZeroComponents() {
        assertEquals(0, compareReleaseVersions("v1.2", "1.2.0"))
    }
}
