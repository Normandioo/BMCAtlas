package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class RecTrackTest {

    private static byte[] file(RecTrack.Writer w, Object... blocks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(RecTrack.header());
        for (int i = 0; i < blocks.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<RecTrack.Sample> s = (List<RecTrack.Sample>) blocks[i + 1];
            out.writeBytes(w.block((Long) blocks[i], s));
        }
        return out.toByteArray();
    }

    @Test
    void varintsAndZigzagRoundTrip() {
        for (long v : new long[]{0, 1, -1, 63, -64, 64, 1_000_000, -1_000_000, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertEquals(v, RecTrack.unzigzag(RecTrack.zigzag(v)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RecTrack.varint(out, RecTrack.zigzag(v));
            assertEquals(RecTrack.zigzag(v), RecTrack.readVarint(out.toByteArray(), new int[]{0}));
        }
    }

    @Test
    void positionsComeBackInEighthsOfABlock() {
        byte[] data = file(new RecTrack.Writer(),
                0L, List.of(RecTrack.Sample.at(0, 100.5, -2000.125), RecTrack.Sample.at(3, -7.3, 12)),
                2000L, List.of(RecTrack.Sample.at(0, 101, -1999), RecTrack.Sample.left(3)));
        List<RecTrack.Block> blocks = RecTrack.read(data);
        assertEquals(2, blocks.size());
        assertEquals(0, blocks.get(0).t());
        assertEquals(2000, blocks.get(1).t());
        RecTrack.Sample a = blocks.get(0).samples().get(0), b = blocks.get(0).samples().get(1);
        assertEquals(100.5, a.x());
        assertEquals(-2000.125, a.z());
        assertEquals(-7.25, b.x());   // -7.3 rounds to the nearest eighth
        assertEquals(12, b.z());
        assertEquals(101, blocks.get(1).samples().get(0).x());
        assertTrue(blocks.get(1).samples().get(1).gone());
    }

    @Test
    void aBlockCutShortIsDropped() {
        byte[] data = file(new RecTrack.Writer(),
                0L, List.of(RecTrack.Sample.at(0, 10, 10)),
                1000L, List.of(RecTrack.Sample.at(0, 5000, 5000), RecTrack.Sample.at(1, -5000, 5000)));
        int whole = RecTrack.wholeLength(data);
        assertEquals(data.length, whole);
        for (int cut = data.length - 1; cut > 5; cut--) {
            byte[] part = Arrays.copyOf(data, cut);
            List<RecTrack.Block> blocks = RecTrack.read(part);
            assertTrue(blocks.size() <= 2);
            if (blocks.size() == 1) assertEquals(10, blocks.get(0).samples().get(0).x());
            assertTrue(RecTrack.wholeLength(part) <= cut);
        }
        assertNull(RecTrack.read(new byte[]{'n', 'o', 'p', 'e', 1}));
    }

    @Test
    void aWriterGoesOnAfterTheBlocksInAFile() {
        RecTrack.Writer first = new RecTrack.Writer();
        byte[] start = file(first, 0L, List.of(RecTrack.Sample.at(0, 300, 400)), 500L, List.of(RecTrack.Sample.at(1, -8, 8)));
        RecTrack.Writer again = RecTrack.Writer.after(RecTrack.read(start));
        byte[] more = again.block(90_000, List.of(RecTrack.Sample.at(0, 301, 401)));
        byte[] all = Arrays.copyOf(start, start.length + more.length);
        System.arraycopy(more, 0, all, start.length, more.length);
        List<RecTrack.Block> blocks = RecTrack.read(all);
        assertEquals(3, blocks.size());
        assertEquals(90_000, blocks.get(2).t());
        assertEquals(301, blocks.get(2).samples().get(0).x());
        assertEquals(401, blocks.get(2).samples().get(0).z());
    }

    @Test
    void whereAPlayerWasInterpolatesAndRespectsHolesTeleportsAndLeaving() {
        List<RecTrack.Block> blocks = List.of(
                new RecTrack.Block(0, List.of(RecTrack.Sample.at(0, 0, 0))),
                new RecTrack.Block(2000, List.of(RecTrack.Sample.at(0, 20, 10))),
                new RecTrack.Block(4000, List.of(RecTrack.Sample.at(0, 1000, 10))),   // teleport
                new RecTrack.Block(6000, List.of(RecTrack.Sample.left(0))),
                new RecTrack.Block(30000, List.of(RecTrack.Sample.at(0, 50, 50))),   // back after a hole
                new RecTrack.Block(32000, List.of(RecTrack.Sample.at(0, 60, 50))));
        RecTrack.Track track = new RecTrack.Track(blocks, 2000);
        assertEquals(6000, track.holeMs);
        assertNull(track.at(0, -1));
        assertArrayEquals(new double[]{10, 5}, track.at(0, 1000));
        assertArrayEquals(new double[]{20, 10}, track.at(0, 3000));   // teleport: stays at the earlier point
        assertArrayEquals(new double[]{1000, 10}, track.at(0, 4000));
        assertNull(track.at(0, 7000));   // left the map
        assertArrayEquals(new double[]{55, 50}, track.at(0, 31000));
        assertArrayEquals(new double[]{60, 50}, track.at(0, 36000));   // still within the hole limit after the last sample
        assertNull(track.at(0, 40000));
        assertNull(track.at(7, 1000));   // never seen
    }

    @Test
    void aLongGapBetweenSamplesIsAHole() {
        List<RecTrack.Block> blocks = List.of(
                new RecTrack.Block(0, List.of(RecTrack.Sample.at(2, 0, 0))),
                new RecTrack.Block(20000, List.of(RecTrack.Sample.at(2, 100, 0))));
        RecTrack.Track track = new RecTrack.Track(blocks, 2000);
        assertArrayEquals(new double[]{0, 0}, track.at(2, 5000));
        assertNull(track.at(2, 10000));
        assertArrayEquals(new double[]{100, 0}, track.at(2, 20000));
    }
}
