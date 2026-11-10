package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckTest {

    @Test
    void readsTheVersionFromReleaseTagsAndJarVersions() {
        assertEquals("1.2.5", UpdateCheck.versionIn("v1.2.5-build.160"));
        assertEquals("1.2.4", UpdateCheck.versionIn("1.2.4+mc26.x"));
        assertEquals("1.2.4", UpdateCheck.versionIn("1.2.4"));
        assertNull(UpdateCheck.versionIn("no version here"));
        assertNull(UpdateCheck.versionIn(null));
    }

    @Test
    void comparesNumbersNotText() {
        assertTrue(UpdateCheck.newer("1.2.10", "1.2.9"));
        assertTrue(UpdateCheck.newer("1.3.0", "1.2.9"));
        assertTrue(UpdateCheck.newer("2.0.0", "1.9.9"));
        assertFalse(UpdateCheck.newer("1.2.4", "1.2.4"));
        assertFalse(UpdateCheck.newer("1.2.3", "1.2.4"));
    }
}
