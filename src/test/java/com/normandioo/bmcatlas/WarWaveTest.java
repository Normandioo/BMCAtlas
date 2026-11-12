package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WarWaveTest {

    /** Three provinces in a row, 100 blocks wide each: Alpha's on the left, then two of Beta's. */
    private static final String ROW = """
            {
              "format": 1,
              "generated": "test",
              "countries": {
                "alpha": {"name": "Alpha", "color": "#4C9A52", "info": {}, "population": []},
                "beta": {"name": "Beta", "color": "#B04A4A", "info": {}, "population": []}
              },
              "provinces": [
                {"id": "A1", "country": "alpha", "rings": [[0, 0, 100, 0, 100, 100, 0, 100]]},
                {"id": "B1", "country": "beta", "rings": [[100, 0, 200, 0, 200, 100, 100, 100]]},
                {"id": "B2", "country": "beta", "rings": [[200, 0, 300, 0, 300, 100, 200, 100]]}
              ],
              "localities": [],
              "war": {"wars": [], "alliances": []},
              "network": null
            }
            """;

    private static final int GREEN = 0x4C9A52, RED = 0xB04A4A;

    @AfterEach
    void reset() {
        WarWave.clear();
    }

    private static Map<Integer, WarWave.Wave> waves(long now) {
        Map<Integer, WarWave.Wave> out = new HashMap<>();
        for (WarWave.Wave w : WarWave.active(now)) out.put(w.prov, w);
        return out;
    }

    private static int prov(SiteTerritories.Data d, int x) {
        return SiteTerritories.provinceAt(d, x, 50);
    }

    @Test
    void theOldColourFadesAcrossTheEdgeBehindTheFront() {
        assertEquals(1, WarWave.oldShare(100, 50, 20), 1e-9);    // ahead of the front: all old
        assertEquals(0, WarWave.oldShare(10, 50, 20), 1e-9);     // well behind it: all new
        assertEquals(0.5, WarWave.oldShare(40, 50, 20), 1e-9);   // halfway across the edge
        assertEquals(1, WarWave.oldShare(0, 0, 20), 1e-9);       // before the start nothing has changed yet
        assertEquals(16, WarWave.band(10), 1e-9);
        assertEquals(160, WarWave.band(10_000), 1e-9);
        assertEquals(600.0 / 3_000, WarWave.speed(600), 1e-12);              // 600 blocks: 5 s at SPEED, now 3 s
        assertEquals(10.0 / WarWave.FLOOR_MS, WarWave.speed(10), 1e-12);      // a tiny province: never under FLOOR_MS
        assertEquals(100_000.0 / (WarWave.MAX_MS - WarWave.FASTER_MS), WarWave.speed(100_000), 1e-12); // a huge one: MAX_MS less 2 s
    }

    @Test
    void aCaptureSpreadsFromTheBorderWithTheTaker() {
        SiteTerritories.Data d = SiteTerritories.parse(ROW);
        int b1 = prov(d, 150);
        int[] country = new int[d.ids.length];
        for (int c = 0; c < country.length; c++) country[c] = "alpha".equals(d.ids[c]) ? GREEN : RED;
        WarWave.changes(d, Map.of(), country, Map.of(b1, GREEN), country, 1_000);
        WarWave.Wave w = waves(1_000).get(b1);
        assertEquals(1_000, w.start);
        assertTrue(w.distAt(101, 50) <= w.cell, "starts at the border with Alpha");
        assertTrue(w.distAt(198, 50) >= 90, "ends at the far side");
        assertEquals(w.duration, (long) Math.ceil((w.maxDist + w.band) / w.speed));
        assertTrue(w.front(1_000) == 0 && w.front(1_000 + w.duration) >= w.maxDist + w.band - 1e-9);
    }

    @Test
    void aNeighbourTakenTooWaitsForTheFrontAndGoesOnFromThere() {
        SiteTerritories.Data d = SiteTerritories.parse(ROW);
        int b1 = prov(d, 150), b2 = prov(d, 250);
        int[] country = new int[d.ids.length];
        for (int c = 0; c < country.length; c++) country[c] = "alpha".equals(d.ids[c]) ? GREEN : RED;
        WarWave.changes(d, Map.of(), country, Map.of(b1, GREEN, b2, GREEN), country, 1_000);
        Map<Integer, WarWave.Wave> all = waves(1_000);
        WarWave.Wave first = all.get(b1), second = all.get(b2);
        assertEquals(1_000, first.start);
        // the second starts when the first has passed their shared border, not at once (out of nothing)
        assertEquals(first.passes(199, 50), second.start, 1.0 * first.cell / first.speed + 1);
        assertTrue(second.start > first.start + (long) (80 / first.speed), "second starts at " + second.start);
        assertTrue(second.distAt(201, 50) <= second.cell, "from the shared border");

        // taken later, while the first is still moving: the same
        WarWave.clear();
        WarWave.changes(d, Map.of(), country, Map.of(b1, GREEN), country, 1_000);
        Map<Integer, Integer> after = Map.of(b1, GREEN);
        WarWave.changes(d, after, country, Map.of(b1, GREEN, b2, GREEN), country, 1_200);
        WarWave.Wave a = waves(1_200).get(b1), b = waves(1_200).get(b2);
        assertTrue(b.start >= a.passes(199, 50) - 1 && b.start > 1_200, "waits for the first: " + b.start);
    }
}
