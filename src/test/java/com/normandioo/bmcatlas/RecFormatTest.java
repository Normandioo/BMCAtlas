package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecFormatTest {

    @TempDir
    Path tmp;

    @Test
    void folderNamesAreSafe() {
        String name = RecFormat.folderName(0, "main map", "war/1:x");
        assertTrue(name.matches("\\d{8}-\\d{6}_main_map_war_1_x"), name);
        assertEquals("default", RecFormat.safe(null));
        assertEquals("default", RecFormat.safe(" "));
    }

    @Test
    void metaAndPlayersRoundTrip() throws IOException {
        RecFormat.Meta m = new RecFormat.Meta();
        m.map = "gaia";
        m.warId = "TestWar";
        m.start = 1000;
        m.end = 5000;
        m.periodMs = 2000;
        m.intervals.add(new long[]{1000, 3000});
        m.intervals.add(new long[]{4000, 5000});
        RecFormat.SideInfo side = new RecFormat.SideInfo();
        side.name = "Red";
        side.countries.add("synor");
        m.sides.add(side);
        RecFormat.writeMeta(tmp, m);
        RecFormat.Meta back = RecFormat.readMeta(tmp);
        assertNotNull(back);
        assertEquals(RecFormat.FORMAT, back.format);
        assertEquals("TestWar", back.warId);
        assertEquals(2, back.intervals.size());
        assertEquals(4000, back.intervals.get(1)[0]);
        assertEquals("synor", back.sides.get(0).countries.get(0));

        Map<Integer, RecFormat.PlayerInfo> players = new LinkedHashMap<>();
        RecFormat.PlayerInfo p = new RecFormat.PlayerInfo();
        p.account = "Steve";
        p.country = "synor";
        p.first = 1;
        p.last = 2;
        players.put(5, p);
        RecFormat.writePlayers(tmp, players);
        Map<Integer, RecFormat.PlayerInfo> read = RecFormat.readPlayers(tmp);
        assertEquals("Steve", read.get(5).account);
        assertEquals(2, read.get(5).last);
        assertNull(RecFormat.readMeta(tmp.resolve("missing")));
    }

    @Test
    void aCitiesLineCutShortIsSkipped() throws IOException {
        RecFormat.Frame f = new RecFormat.Frame();
        f.t = 42;
        RecFormat.City c = new RecFormat.City();
        c.name = "Alpha";
        c.side = 1;
        f.cities.add(c);
        String line = RecFormat.line(f);
        Path file = tmp.resolve("cities.jsonl");
        Files.writeString(file, line + "\n" + line + "\n" + line.substring(0, line.length() / 2), StandardCharsets.UTF_8);
        List<RecFormat.Frame> frames = RecFormat.readCities(file);
        assertEquals(2, frames.size());
        assertEquals("Alpha", frames.get(1).cities.get(0).name);
        assertEquals(1, frames.get(1).cities.get(0).side);
    }

    @Test
    void theNewestRecordingOfAWarIsFound() throws IOException {
        for (String[] r : new String[][]{{"20261101-100000_gaia_A", "gaia", "A"}, {"20261105-100000_gaia_A", "gaia", "A"}, {"20261109-100000_gaia_B", "gaia", "B"}}) {
            RecFormat.Meta m = new RecFormat.Meta();
            m.map = r[1];
            m.warId = r[2];
            RecFormat.writeMeta(tmp.resolve(r[0]), m);
        }
        Files.createDirectories(tmp.resolve("20261110-100000_not_a_recording"));
        assertEquals(3, RecFormat.list(tmp).size());
        assertEquals("20261105-100000_gaia_A", RecFormat.latest(tmp, "gaia", "A").getFileName().toString());
        assertNull(RecFormat.latest(tmp, "gaia", "C"));
    }

    @Test
    void anOldRecordingIsImportedOnceWithTimesFromItsStart() throws IOException {
        Path old = tmp.resolve("old");
        Files.createDirectories(old);
        Path file = old.resolve("gaia_Test_War.jsonl");
        Files.writeString(file, "{\"t\":1700000000000,\"cities\":[{\"name\":\"Alpha\",\"x\":1,\"z\":2}]}\n"
                + "{\"t\":1700000060000,\"cities\":[]}\n", StandardCharsets.UTF_8);
        Path recordings = tmp.resolve("recordings");
        Path dir = RecFormat.importOld(file, recordings);
        assertNotNull(dir);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(old.resolve("gaia_Test_War.jsonl.imported")));
        RecFormat.Meta m = RecFormat.readMeta(dir);
        assertEquals("gaia", m.map);
        assertEquals("Test_War", m.warId);
        assertEquals(1700000000000L, m.start);
        assertEquals("gaia_Test_War.jsonl", m.importedFrom);
        List<RecFormat.Frame> frames = RecFormat.readCities(dir.resolve("cities.jsonl"));
        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).t);
        assertEquals(60000, frames.get(1).t);
        assertEquals(dir, RecFormat.latest(recordings, "gaia", "Test_War"));
    }
}
