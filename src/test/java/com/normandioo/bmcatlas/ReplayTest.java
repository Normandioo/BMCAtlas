package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayTest {

    @TempDir
    Path tmp;

    private static final long START = 1_000_000;

    private static RecFormat.Frame frame(long t) {
        RecFormat.Frame f = new RecFormat.Frame();
        f.t = t;
        return f;
    }

    /** A recording of the given length, with sessions given as [from, to] pairs in ms since its start (none: one session). */
    private static Replay replay(long length, long[][] sessions, long... frameTimes) {
        RecFormat.Meta meta = new RecFormat.Meta();
        meta.start = START;
        meta.end = START + length;
        meta.periodMs = 2000;
        for (long[] s : sessions) meta.intervals.add(new long[]{START + s[0], START + s[1]});
        List<RecFormat.Frame> frames = new ArrayList<>();
        for (long t : frameTimes) frames.add(frame(t));
        return new Replay(null, meta, frames, Map.of(), List.of());
    }

    private static Replay replay(long length, long... frameTimes) {
        return replay(length, new long[][]{{0, length}}, frameTimes);
    }

    @Test
    void framesInForceAndChanges() {
        Replay r = replay(100_000, 0, 10_000, 50_000);
        assertEquals(0, r.from());
        assertEquals(100_000, r.to());
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
        assertEquals(START + 100_000, r.absTime());
    }

    @Test
    void onlyTheMostRecentSessionIsPlayedBack() {
        Replay r = replay(100_000, new long[][]{{0, 40_000}, {70_000, 95_000}}, 0, 10_000, 80_000);
        assertEquals(70_000, r.from());
        assertEquals(100_000, r.to());   // the last session runs to the end of what was written
        assertEquals(70_000, r.time());   // it opens at the start of the session
        assertEquals(1, r.frameIndex());   // the cities as they were when the session began
        r.seek(0);
        assertEquals(70_000, r.time());
        assertEquals(80_000, r.nextChange());
        r.seek(75_000);
        assertEquals(70_000, r.previousChange());   // never back into the older session
        r.seek(100_000);
        r.play(0);
        assertEquals(70_000, r.time());   // play at the end starts the session over
    }

    @Test
    void aSessionJustStartedIsSkippedForTheOneBefore() {
        Replay r = replay(100_000, new long[][]{{0, 60_000}, {98_000, 100_000}}, 0);
        assertEquals(0, r.from());
        assertEquals(60_000, r.to());
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
    void slowerThanRealTime() {
        Replay r = replay(10_000, 0);
        r.setSpeed(0.25);
        r.play(0);
        for (int i = 1; i <= 4; i++) r.advance(i * 250L);   // a second of animation
        assertEquals(250, r.time());
    }

    @Test
    void speedsGoUpAndDownAndStopAtTheEnds() {
        Replay r = replay(1000, 0);
        assertEquals("1x", r.speedText());
        r.changeSpeed(-1);
        assertEquals("0.5x", r.speedText());
        r.changeSpeed(-1);
        assertEquals("0.25x", r.speedText());
        r.changeSpeed(-1);
        assertEquals(0.25, r.speed());
        for (int i = 0; i < 20; i++) r.changeSpeed(1);
        assertEquals("300x", r.speedText());
        r.changeSpeed(-1);
        assertEquals("60x", r.speedText());
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
        assertNull(r.face("Alex"));
    }
}
