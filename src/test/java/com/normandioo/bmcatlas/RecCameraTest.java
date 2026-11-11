package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecCameraTest {

    @TempDir
    Path tmp;

    private static RecCamera camera() {
        RecCamera c = new RecCamera();
        c.id = "c1";
        c.name = "Camera 1";
        c.setKey(10_000, 0, 0, 100);
        c.setKey(20_000, 100, -100, 300);
        return c;
    }

    @Test
    void framingBetweenBeforeAndAfterKeyframes() {
        RecCamera c = camera();
        assertArrayEquals(new double[]{0, 0, 100}, c.keyed(0), 1e-9);          // before the first: the first
        assertArrayEquals(new double[]{0, 0, 100}, c.keyed(10_000), 1e-9);
        assertArrayEquals(new double[]{50, -50, 200}, c.keyed(15_000), 1e-9);  // halfway (ease in/out is 0.5 there)
        double[] early = c.keyed(12_000);
        assertTrue(early[0] < 20, "eased: slow at the start, " + early[0]);
        assertArrayEquals(new double[]{100, -100, 300}, c.keyed(99_000), 1e-9); // after the last: the last
    }

    @Test
    void keyframesAreSetMovedAndRemoved() {
        RecCamera c = camera();
        c.setKey(10_100, 5, 5, 120);   // within 250 ms: the same keyframe
        assertEquals(2, c.keys.size());
        assertEquals(5, c.keys.get(0).cx);
        c.setKey(5_000, 1, 1, 50);
        assertEquals(3, c.keys.size());
        assertEquals(5_000, c.keys.get(0).t);
        RecCamera.Key k = c.keyAt(20_000);
        assertNotNull(k);
        c.moveKey(k, 1_000);
        assertEquals(1_000, c.keys.get(0).t);
        assertTrue(c.removeKey(c.keys.get(0)));
        assertTrue(c.removeKey(c.keys.get(0)));
        assertFalse(c.removeKey(c.keys.get(0)));   // the last one stays
    }

    @Test
    void followingAPlayerAveragesAndKeepsTheWidth() {
        RecCamera c = camera();
        c.follow = "player:Steve";
        double[] f = c.at(15_000, (acc, t) -> acc.equals("Steve") ? new double[]{t / 1000.0, 7} : null, null);
        assertEquals(15, f[0], 1e-9);   // the average around 15 s of x = t/1000
        assertEquals(7, f[1], 1e-9);
        assertEquals(200, f[2], 1e-9);
        double[] gone = c.at(15_000, (acc, t) -> null, null);   // not on the map: the keyframes
        assertArrayEquals(c.keyed(15_000), gone, 1e-9);
    }

    @Test
    void followingTheHighlightsMovesBeforeEachOne() {
        List<double[]> places = List.of(new double[]{10_000, 0, 0}, new double[]{40_000, 600, 0});
        assertArrayEquals(new double[]{0, 0}, RecCamera.follow(places, 0), 1e-9);
        assertArrayEquals(new double[]{0, 0}, RecCamera.follow(places, 30_000), 1e-9);   // stays on the first
        assertArrayEquals(new double[]{300, 0}, RecCamera.follow(places, 37_000), 1e-9);  // halfway through the move
        assertArrayEquals(new double[]{600, 0}, RecCamera.follow(places, 50_000), 1e-9);
    }

    @Test
    void outputSizeForEachAspectIsEvenAndFits() {
        RecCamera c = new RecCamera();
        int w = 1921, h = 1081;
        for (String a : RecCamera.ASPECTS) {
            c.aspect = a;
            int[] s = c.outputSize(w, h);
            assertEquals(0, s[0] % 2, a);
            assertEquals(0, s[1] % 2, a);
            assertTrue(s[0] <= w && s[1] <= h, a);
        }
        c.aspect = "16:9";
        assertArrayEquals(new int[]{1920, 1080}, c.outputSize(1920, 1200));
        c.aspect = "9:16";
        assertArrayEquals(new int[]{608, 1080}, c.outputSize(1920, 1080));
        c.aspect = "1:1";
        assertArrayEquals(new int[]{1080, 1080}, c.outputSize(1920, 1080));
        c.aspect = "window";
        assertArrayEquals(new int[]{1920, 1080}, c.outputSize(1921, 1081));
        c.nextAspect();
        assertEquals("16:9", c.aspect);
    }

    @Test
    void widthInBlocksToZoomAndItsLimits() {
        assertEquals(1920 / 480.0, RecCamera.scaleFor(480, 1920), 1e-9);
        assertEquals(1920 / 8.0, RecCamera.clampWidth(10, 1920), 1e-9);       // closer than 8 px per block
        assertEquals(1920 / 0.03, RecCamera.clampWidth(1e9, 1920), 1e-6);     // further than 0.03
        assertEquals(500, RecCamera.clampWidth(500, 1920), 1e-9);
    }

    @Test
    void terrainShareFromKnownChunks() {
        RecCamera c = new RecCamera();
        c.aspect = "1:1";
        c.setKey(0, 0, 0, 64);   // chunks -2..1 by -2..1: 16 chunks
        assertEquals(1.0, c.terrain(1, (x, z) -> true), 1e-9);
        assertEquals(0.5, c.terrain(1, (x, z) -> x >= 0), 1e-9);
    }

    @Test
    void camerasAreKeptInTheProject() throws IOException {
        RecProject p = new RecProject();
        RecCamera c = camera();
        c.follow = "highlight";
        c.aspect = "9:16";
        p.cameras.add(c);
        p.cameras.add(c.copy("c2", "Camera 2"));
        p.save(tmp);
        RecProject back = RecProject.load(tmp);
        assertEquals(2, back.cameras.size());
        assertEquals("highlight", back.cameras.get(0).follow);
        assertEquals("9:16", back.cameras.get(0).aspect);
        assertEquals(2, back.cameras.get(1).keys.size());
        assertEquals(300, back.cameras.get(1).keys.get(1).width);
        assertEquals("Camera 2", back.cameras.get(1).name);
    }
}
