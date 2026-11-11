package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecProjectTest {

    @TempDir
    Path tmp;

    private static RecHighlights.Highlight h(long t, double weight) {
        return new RecHighlights.Highlight(t, RecHighlights.Type.CAPTURE, "X", 0, 0, List.of(), weight, "x");
    }

    @Test
    void framesOfTheVideoForSpeedsAndFps() {
        RecProject p = new RecProject();
        p.clips.add(new RecProject.Clip(0, 10_000, 1));        // 10 s of video
        p.clips.add(new RecProject.Clip(20_000, 620_000, 60));  // 10 min at 60x: 10 s of video
        assertEquals(300, RecProject.frames(p.clips.get(0), 30));
        assertEquals(300, RecProject.frames(p.clips.get(1), 30));
        assertEquals(600, p.totalFrames());
        assertEquals(0, p.timeAt(0));
        assertEquals(1000, p.timeAt(30));                // one second in
        assertEquals(20_000, p.timeAt(300));             // the second clip starts
        assertEquals(20_000 + 60_000, p.timeAt(330));    // a second of video at 60x is a minute
        assertEquals(-1, p.timeAt(600));
        p.fps = 60;
        assertEquals(1200, p.totalFrames());
        assertEquals(500, p.timeAt(30));
    }

    @Test
    void autoHighlightsMakeMergedClipsAndFastGaps() {
        RecProject p = new RecProject();
        List<RecHighlights.Highlight> hs = List.of(h(10_000, 5), h(30_000, 5), h(200_000, 0.3), h(300_000, 2), h(990_000, 2));
        p.autoHighlights(hs, 1, 120, 1_000_000);
        // 10 s and 30 s overlap: one clip 0..45 s; 200 s is too light; 300 s alone; 990 s cut at the end
        assertEquals(5, p.clips.size());
        assertEquals(0, p.clips.get(0).from);
        assertEquals(45_000, p.clips.get(0).to);
        assertEquals(1, p.clips.get(0).speed);
        assertEquals(45_000, p.clips.get(1).from);   // fast stretch up to the next one
        assertEquals(280_000, p.clips.get(1).to);
        assertEquals(120, p.clips.get(1).speed);
        assertEquals(280_000, p.clips.get(2).from);
        assertEquals(315_000, p.clips.get(2).to);
        assertEquals(970_000, p.clips.get(4).from);
        assertEquals(1_000_000, p.clips.get(4).to);

        p.autoHighlights(hs, 1, 0, 1_000_000);   // no fast stretches
        assertEquals(3, p.clips.size());
        assertTrue(p.clips.stream().allMatch(c -> c.speed == 1));
    }

    @Test
    void addKeepsClipsInsideAndInOrder() {
        RecProject p = new RecProject();
        p.add(50_000, 40_000, 1, 100_000);   // reversed marks
        p.add(90_000, 130_000, 2, 100_000);  // past the end
        p.add(0, 10_000, 1, 100_000);
        assertNull(p.add(5_000, 5_000, 1, 100_000));
        assertEquals(List.of(0L, 40_000L, 90_000L), p.clips.stream().map(c -> c.from).toList());
        assertEquals(50_000, p.clips.get(1).to);
        assertEquals(100_000, p.clips.get(2).to);
    }

    @Test
    void projectJsonRoundTripAndBrokenFiles() throws IOException {
        RecProject p = new RecProject();
        p.add(1000, 5000, 0.5, 10_000);
        p.fps = 60;
        p.show.playerNames = false;
        p.save(tmp);
        RecProject back = RecProject.load(tmp);
        assertEquals(1, back.clips.size());
        assertEquals(0.5, back.clips.get(0).speed);
        assertEquals(60, back.fps);
        assertEquals(false, back.show.playerNames);

        Files.writeString(tmp.resolve("project.json"), "{\"clips\":[{\"from\":5,\"to\":2}], \"fps\": 24", StandardCharsets.UTF_8);
        RecProject broken = RecProject.load(tmp);
        assertEquals(0, broken.clips.size());
        assertEquals(30, broken.fps);
        Files.writeString(tmp.resolve("project.json"), "{\"clips\":[{\"from\":5,\"to\":2}], \"fps\": 24}", StandardCharsets.UTF_8);
        RecProject bad = RecProject.load(tmp);
        assertEquals(0, bad.clips.size());   // a clip ending before it starts is dropped
        assertEquals(30, bad.fps);            // only 30 or 60
    }
}
