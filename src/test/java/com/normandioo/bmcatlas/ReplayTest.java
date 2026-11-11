package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class ReplayTest {

    @TempDir
    Path tmp;

    private static RecFormat.Frame frame(long t) {
        RecFormat.Frame f = new RecFormat.Frame();
        f.t = t;
        return f;
    }

    private static Replay replay(long length, long... frameTimes) {
        RecFormat.Meta meta = new RecFormat.Meta();
        meta.start = 1_000_000;
        meta.end = meta.start + length;
        meta.periodMs = 2000;
        meta.intervals.add(new long[]{meta.start, meta.start + length / 2});
        meta.intervals.add(new long[]{meta.start + length * 3 / 4, meta.end});
        List<RecFormat.Frame> frames = new java.util.ArrayList<>();
        for (long t : frameTimes) frames.add(frame(t));
        return new Replay(null, meta, frames, Map.of(), List.of());
    }

    @Test
    void framesInForceAndChanges() {
        Replay r = replay(100_000, 0, 10_000, 50_000);
        assertEquals(100_000, r.length());
        assertEquals(0, r.frameIndex());
        r.seek(9_999);
        assertEquals(0, r.frameIndex());
        r.seek(10_000);
        assertEquals(1, r.frameIndex());
        assertEquals(50_000, r.nextChange());
        r.seek(30_000);
        assertEquals(10_000, r.previousChange());   // well into a change: back to its start
        r.seek(10_500);
        assertEquals(0, r.previousChange());   // just after it: the one before
        r.seek(60_000);
        assertEquals(100_000, r.nextChange());   // none left: the end
        r.seek(-5);
        assertEquals(0, r.time());
        r.seek(500_000);
        assertEquals(100_000, r.time());
        assertEquals(1_100_000, r.absTime());
    }

    @Test
    void playingStopsAtTheEndAndStartsOverFromThere() {
        Replay r = replay(10_000, 0);
        r.setSpeed(60);
        r.play(0);
        r.advance(100);
        assertEquals(6_000, r.time());
        r.advance(200);
        assertEquals(10_000, r.time());
        assertFalse(r.playing());
        r.play(300);
        assertEquals(0, r.time());
        r.advance(60_000);   // the game was frozen a minute: at most a second of animation counts
        assertEquals(10_000, r.time());
    }

    @Test
    void speedsCycle() {
        Replay r = replay(1000, 0);
        assertEquals(1, r.speed());
        for (int i = 1; i < Replay.SPEEDS.length; i++) {
            r.nextSpeed();
            assertEquals(Replay.SPEEDS[i], r.speed());
        }
        r.nextSpeed();
        assertEquals(1, r.speed());
    }

    @Test
    void gapsBetweenIntervalsWereNotSeen() {
        Replay r = replay(100_000, 0);
        assertFalse(r.away(10_000));
        assertTrue(r.away(60_000));
        assertFalse(r.away(90_000));
    }

    @Test
    void opensAFolderWithPlayersAndFaces() throws IOException {
        RecFormat.Meta meta = new RecFormat.Meta();
        meta.start = 5000;
        meta.end = 9000;
        meta.periodMs = 2000;
        RecFormat.writeMeta(tmp, meta);
        Map<Integer, RecFormat.PlayerInfo> players = new LinkedHashMap<>();
        RecFormat.PlayerInfo p = new RecFormat.PlayerInfo();
        p.account = "Steve";
        players.put(0, p);
        RecFormat.writePlayers(tmp, players);
        RecTrack.Writer w = new RecTrack.Writer();
        java.io.ByteArrayOutputStream bin = new java.io.ByteArrayOutputStream();
        bin.writeBytes(RecTrack.header());
        bin.writeBytes(w.block(0, List.of(RecTrack.Sample.at(0, 0, 0))));
        bin.writeBytes(w.block(2000, List.of(RecTrack.Sample.at(0, 20, -20))));
        Files.write(tmp.resolve("players.bin"), bin.toByteArray());
        Files.writeString(tmp.resolve("cities.jsonl"), RecFormat.line(frame(0)) + "\n", StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("faces"));
        Files.write(tmp.resolve("faces").resolve("Steve.png"), new byte[]{1});

        Replay r = Replay.open(tmp);
        assertNotNull(r);
        assertEquals(4000, r.length());
        r.seek(1000);
        List<Replay.Shown> shown = r.shown();
        assertEquals(1, shown.size());
        assertEquals("Steve", shown.get(0).account());
        assertEquals(10, shown.get(0).x());
        assertEquals(-10, shown.get(0).z());
        assertNotNull(r.face("Steve"));
        assertEquals(null, r.face("Alex"));
    }
}
