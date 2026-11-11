package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        p.clips.add(new RecProject.Clip(0, 10_000, 1, null));        // 10 s of video
        p.clips.add(new RecProject.Clip(20_000, 620_000, 60, null));  // 10 min at 60x: 10 s of video
        assertEquals(300, RecProject.frames(p.clips.get(0), 30));
        assertEquals(300, RecProject.frames(p.clips.get(1), 30));
        assertEquals(600, p.totalFrames());
        p.fps = 60;
        assertEquals(1200, p.totalFrames());
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
        p.add(50_000, 40_000, 1, 100_000, null);   // reversed marks
        p.add(90_000, 130_000, 2, 100_000, null);  // past the end
        p.add(0, 10_000, 1, 100_000, null);
        assertNull(p.add(5_000, 5_000, 1, 100_000, null));
        assertEquals(List.of(0L, 40_000L, 90_000L), p.clips.stream().map(c -> c.from).toList());
        assertEquals(50_000, p.clips.get(1).to);
        assertEquals(100_000, p.clips.get(2).to);
    }

    @Test
    void clipsNeverOverlap() {
        RecProject p = new RecProject();
        p.add(10_000, 20_000, 1, 100_000, "a");
        assertNull(p.add(15_000, 30_000, 1, 100_000, "b"));   // another camera may not take the same moment
        assertNull(p.add(5_000, 11_000, 1, 100_000, null));
        assertEquals(20_000, p.add(20_000, 30_000, 1, 100_000, "b").from);   // touching is fine
    }

    @Test
    void aClipStopsAtItsNeighbours() {
        RecProject p = new RecProject();
        RecProject.Clip a = p.add(0, 10_000, 1, 100_000, null);
        RecProject.Clip b = p.add(20_000, 30_000, 1, 100_000, null);
        long[] room = p.room(b, 100_000);
        assertEquals(10_000, room[0]);
        assertEquals(100_000, room[1]);
        room = p.room(a, 100_000);
        assertEquals(0, room[0]);
        assertEquals(20_000, room[1]);
    }

    @Test
    void cutSplitsAClipInTwoWithTheSameCamera() {
        RecProject p = new RecProject();
        RecProject.Clip c = p.add(0, 10_000, 2, 100_000, "cam1");
        assertFalse(p.split(c, 200));   // too close to the start
        assertTrue(p.split(c, 4_000));
        assertEquals(List.of(0L, 4_000L), p.clips.stream().map(x -> x.from).toList());
        assertEquals(4_000, p.clips.get(0).to);
        assertEquals("cam1", p.clips.get(1).camera);
        assertEquals(2, p.clips.get(1).speed);
    }

    @Test
    void removingACameraSendsItsClipsBackToTheView() {
        RecProject p = new RecProject();
        RecCamera cam = new RecCamera();
        cam.id = "cam1";
        p.cameras.add(cam);
        p.add(0, 10_000, 1, 100_000, "cam1");
        p.removeCamera(cam);
        assertNull(p.clips.get(0).camera);
    }

    @Test
    void projectJsonRoundTripAndBrokenFiles() throws IOException {
        RecProject p = new RecProject();
        p.add(1000, 5000, 0.5, 10_000, null);
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
