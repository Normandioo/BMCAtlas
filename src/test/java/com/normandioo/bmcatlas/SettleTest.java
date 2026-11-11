package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettleTest {

    @Test
    void waitsForFiveQuietFramesThenStops() {
        Settle s = new Settle();
        assertTrue(s.wait(0, true, true, 0));    // busy: keeps waiting
        assertTrue(s.wait(0, false, true, 10));
        for (int i = 0; i < Settle.QUIET_FRAMES - 1; i++) assertTrue(s.wait(0, false, false, 20 + i));
        assertFalse(s.wait(0, false, false, 40));
    }

    @Test
    void everyClipWaitsAgainNotOnlyTheFirst() {
        Settle s = new Settle();
        for (int i = 0; i < Settle.QUIET_FRAMES; i++) s.wait(0, true, false, i);   // clip 0 settles
        assertFalse(s.wait(0, false, false, 9));
        assertTrue(s.wait(300, true, true, 10));   // clip 1 starts while busy: it must wait too
        assertTrue(s.wait(300, false, false, 11));
    }

    @Test
    void neverWaitsWithoutAClipStart() {
        assertFalse(new Settle().wait(5, false, true, 0));
    }

    @Test
    void givesUpAfterTheLimit() {
        Settle s = new Settle();
        assertTrue(s.wait(0, true, true, 0));
        assertFalse(s.wait(0, false, true, Settle.MAX_MS));   // busy the whole time: stop at 8 s anyway
    }
}
