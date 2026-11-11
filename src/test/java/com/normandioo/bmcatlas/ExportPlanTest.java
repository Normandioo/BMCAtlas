package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExportPlanTest {

    private static RecProject project() {
        RecProject p = new RecProject();
        p.clips.add(new RecProject.Clip(0, 10_000, 1));         // 300 frames at 30 fps
        p.clips.add(new RecProject.Clip(60_000, 660_000, 60));  // 10 min at 60x: 300 frames
        return p;
    }

    @Test
    void noCameraMeansOnePassWithTheView() {
        List<ExportPlan.Pass> passes = ExportPlan.of(project(), "rec");
        assertEquals(1, passes.size());
        assertNull(passes.get(0).camera());
        assertEquals("rec_view", passes.get(0).name());
        assertEquals(600, passes.get(0).frames());
    }

    @Test
    void onePassPerCameraMarkedForExportCutToItsStretch() {
        RecProject p = project();
        RecCamera a = new RecCamera();
        a.name = "Wide";
        a.setKey(0, 0, 0, 1000);
        RecCamera b = a.copy("b", "Close");
        b.from = 5_000L;      // half of the first clip
        b.to = 360_000L;      // half of the second
        RecCamera off = a.copy("c", "Off");
        off.export = false;
        p.cameras.addAll(List.of(a, b, off));
        List<ExportPlan.Pass> passes = ExportPlan.of(p, "rec");
        assertEquals(2, passes.size());
        assertEquals("rec_Wide", passes.get(0).name());
        assertEquals(600, passes.get(0).frames());
        assertEquals("rec_Close", passes.get(1).name());
        assertEquals(150 + 150, passes.get(1).frames());
        assertEquals(5_000, ExportPlan.timeAt(passes.get(1), 0, 30));
        assertEquals(60_000, ExportPlan.timeAt(passes.get(1), 150, 30));
    }

    @Test
    void framesToTimesAndClipStarts() {
        ExportPlan.Pass pass = ExportPlan.of(project(), "rec").get(0);
        assertEquals(0, ExportPlan.timeAt(pass, 0, 30));
        assertEquals(1000, ExportPlan.timeAt(pass, 30, 30));
        assertEquals(60_000, ExportPlan.timeAt(pass, 300, 30));
        assertEquals(60_000 + 2000, ExportPlan.timeAt(pass, 301, 30));   // 33.3 ms of video at 60x
        assertEquals(-1, ExportPlan.timeAt(pass, 600, 30));
        assertTrue(ExportPlan.clipStart(pass, 0, 30));
        assertFalse(ExportPlan.clipStart(pass, 1, 30));
        assertTrue(ExportPlan.clipStart(pass, 300, 30));
        assertEquals("0:20", ExportPlan.duration(600, 30));
    }

    @Test
    void nothingToExportWithoutClips() {
        RecProject p = new RecProject();
        assertTrue(ExportPlan.of(p, "rec").isEmpty());
        RecProject q = project();
        RecCamera c = new RecCamera();
        c.name = "Late";
        c.from = 900_000L;   // after every clip
        q.cameras.add(c);
        assertTrue(ExportPlan.of(q, "rec").isEmpty());
    }
}
