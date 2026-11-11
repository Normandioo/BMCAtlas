package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExportPlanTest {

    @Test
    void oneVideoWithTheClipsInOrder() {
        RecProject p = new RecProject();
        RecProject.Clip a = new RecProject.Clip(0, 10_000, 1, null);          // 300 frames at 30 fps
        RecProject.Clip b = new RecProject.Clip(60_000, 660_000, 60, null);   // 10 min at 60x: 300 frames
        p.clips.addAll(List.of(a, b));
        List<ExportPlan.Pass> passes = ExportPlan.of(p, "rec");
        assertEquals(1, passes.size());
        assertEquals("rec_video", passes.get(0).name());
        assertEquals(600, passes.get(0).frames());
        ExportPlan.Pass pass = passes.get(0);
        assertSame(a, ExportPlan.clipOf(pass, 0, 30));
        assertSame(a, ExportPlan.clipOf(pass, 299, 30));
        assertSame(b, ExportPlan.clipOf(pass, 300, 30));
        assertEquals(null, ExportPlan.clipOf(pass, 600, 30));
    }

    @Test
    void framesToTimes() {
        ExportPlan.Pass pass = ExportPlan.of(twoClips(), "rec").get(0);
        assertEquals(0, ExportPlan.timeAt(pass, 0, 30));
        assertEquals(1000, ExportPlan.timeAt(pass, 30, 30));
        assertEquals(60_000, ExportPlan.timeAt(pass, 300, 30));
        assertEquals(62_000, ExportPlan.timeAt(pass, 301, 30));   // 33 ms of video at 60x is two seconds of recording
        assertEquals(-1, ExportPlan.timeAt(pass, 600, 30));
        assertEquals("0:20", ExportPlan.duration(600, 30));
    }

    @Test
    void aClipStartsAtItsFirstFrameOnly() {
        ExportPlan.Pass pass = ExportPlan.of(twoClips(), "rec").get(0);
        assertTrue(ExportPlan.clipStart(pass, 0, 30));
        assertFalse(ExportPlan.clipStart(pass, 1, 30));
        assertTrue(ExportPlan.clipStart(pass, 300, 30));
        assertFalse(ExportPlan.clipStart(pass, 301, 30));
    }

    @Test
    void nothingToExportWithoutClips() {
        assertTrue(ExportPlan.of(new RecProject(), "rec").isEmpty());
    }

    private static RecProject twoClips() {
        RecProject p = new RecProject();
        p.clips.add(new RecProject.Clip(0, 10_000, 1, null));
        p.clips.add(new RecProject.Clip(60_000, 660_000, 60, null));
        return p;
    }
}
