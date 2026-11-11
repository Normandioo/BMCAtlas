package com.normandioo.bmcatlas;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The players' track of a recording (players.bin), and where each player was at any moment. No Minecraft classes here.
 *
 * File: the header "BMPT" + version (1 byte), then one block per sampling, appended as it goes:
 *   varint  milliseconds since the previous block (the first: since the recording started)
 *   varint  how many records follow
 *   per record: varint (player index << 1 | gone); unless gone, zigzag varint of the change in X and in Z, in eighths of a
 *   block, from that player's previous position in the file (the first one is from 0, so absolute).
 * A block cut short at the end of the file (the game closed while writing) is dropped when reading.
 */
final class RecTrack {
    private RecTrack() {}

    static final byte[] MAGIC = {'B', 'M', 'P', 'T'};
    static final int VERSION = 1;
    /** Positions are kept in eighths of a block. */
    static final int UNITS = 8;
    /** A jump longer than this between two samples is a teleport: the icon goes straight there. */
    static final double TELEPORT_BLOCKS = 96;

    /** One player in one sampling: index in the recording's player table, position in blocks, or gone (left the map). */
    record Sample(int index, double x, double z, boolean gone) {
        static Sample at(int index, double x, double z) { return new Sample(index, x, z, false); }
        static Sample left(int index) { return new Sample(index, 0, 0, true); }
    }

    /** One sampling read back: its time in ms since the recording started, and its records. */
    record Block(long t, List<Sample> samples) {}

    static byte[] header() {
        return new byte[]{MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], (byte) VERSION};
    }

    /** Turns samplings into bytes, remembering each player's last position (one writer per file). */
    static final class Writer {
        private final Map<Integer, long[]> last = new HashMap<>();
        private long lastT;

        /** A writer that goes on after the blocks already in a file (the same war recorded again in the same folder). */
        static Writer after(List<Block> blocks) {
            Writer w = new Writer();
            for (Block b : blocks) {
                w.lastT = Math.max(w.lastT, b.t());
                for (Sample s : b.samples()) {
                    if (!s.gone()) w.last.put(s.index(), new long[]{Math.round(s.x() * UNITS), Math.round(s.z() * UNITS)});
                }
            }
            return w;
        }

        /** The bytes of one block at time t (ms since the recording started). */
        byte[] block(long t, List<Sample> samples) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 + samples.size() * 6);
            varint(out, Math.max(0, t - lastT));
            lastT = Math.max(lastT, t);
            varint(out, samples.size());
            for (Sample s : samples) {
                varint(out, ((long) s.index() << 1) | (s.gone() ? 1 : 0));
                if (s.gone()) continue;
                long x = Math.round(s.x() * UNITS), z = Math.round(s.z() * UNITS);
                long[] p = last.computeIfAbsent(s.index(), k -> new long[2]);
                varint(out, zigzag(x - p[0]));
                varint(out, zigzag(z - p[1]));
                p[0] = x;
                p[1] = z;
            }
            return out.toByteArray();
        }
    }

    /** Reads a whole players.bin. Null when it is not one; a block cut short at the end is left out. */
    static List<Block> read(byte[] data) {
        return read(data, null);
    }

    /** How many bytes of the file are the header and whole blocks (without a block cut short at the end); 0 when it is not one. */
    static int wholeLength(byte[] data) {
        int[] end = {0};
        return read(data, end) == null ? 0 : end[0];
    }

    private static List<Block> read(byte[] data, int[] end) {
        if (data.length < 5 || data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) return null;
        List<Block> out = new ArrayList<>();
        Map<Integer, long[]> last = new HashMap<>();
        int[] pos = {5};
        long t = 0;
        while (pos[0] < data.length) {
            int start = pos[0];
            try {
                long dt = readVarint(data, pos);
                long n = readVarint(data, pos);
                if (n < 0 || n > 100_000) break;
                List<Sample> samples = new ArrayList<>((int) n);
                Map<Integer, long[]> changed = new HashMap<>();
                for (int i = 0; i < n; i++) {
                    long head = readVarint(data, pos);
                    int index = (int) (head >>> 1);
                    if ((head & 1) == 1) {
                        samples.add(Sample.left(index));
                        continue;
                    }
                    long dx = unzigzag(readVarint(data, pos)), dz = unzigzag(readVarint(data, pos));
                    long[] p = changed.containsKey(index) ? changed.get(index) : last.getOrDefault(index, new long[2]).clone();
                    p[0] += dx;
                    p[1] += dz;
                    changed.put(index, p);
                    samples.add(Sample.at(index, p[0] / (double) UNITS, p[1] / (double) UNITS));
                }
                t += dt;
                last.putAll(changed);   // only a whole block counts
                out.add(new Block(t, List.copyOf(samples)));
            } catch (IndexOutOfBoundsException cut) {
                pos[0] = start;
                break;   // the last block was cut short
            }
        }
        if (end != null) end[0] = pos[0];
        return out;
    }

    // ---- where a player is at a moment

    /** Each player's samples in time order, to ask where they were at any moment. */
    static final class Track {
        private final Map<Integer, List<double[]>> byPlayer = new HashMap<>();   // {t, x, z, gone 0/1}
        /** How long without a sample makes a hole (the icon fades out): three sampling periods. */
        final long holeMs;

        Track(List<Block> blocks, long periodMs) {
            holeMs = Math.max(1, periodMs) * 3;
            for (Block b : blocks) {
                for (Sample s : b.samples()) {
                    byPlayer.computeIfAbsent(s.index(), k -> new ArrayList<>()).add(new double[]{b.t(), s.x(), s.z(), s.gone() ? 1 : 0});
                }
            }
        }

        java.util.Set<Integer> players() {
            return byPlayer.keySet();
        }

        /**
         * Where the player was at time t (ms since the start), or null when they were not on the map: before their first sample,
         * after they left, or in a hole longer than holeMs. Between two samples the position is interpolated, unless the jump is
         * a teleport (longer than TELEPORT_BLOCKS), when it stays at the earlier one until the later sample.
         */
        double[] at(int index, long t) {
            List<double[]> list = byPlayer.get(index);
            if (list == null || list.isEmpty() || t < list.get(0)[0]) return null;
            int lo = 0, hi = list.size() - 1;
            while (lo < hi) {   // last sample at or before t
                int mid = (lo + hi + 1) >>> 1;
                if (list.get(mid)[0] <= t) lo = mid; else hi = mid - 1;
            }
            double[] a = list.get(lo);
            if (a[3] == 1) return null;   // they left
            if (lo + 1 >= list.size()) return t - a[0] <= holeMs ? new double[]{a[1], a[2]} : null;
            double[] b = list.get(lo + 1);
            if (b[0] - a[0] > holeMs) return t - a[0] <= holeMs ? new double[]{a[1], a[2]} : null;   // a hole
            if (b[3] == 1) return new double[]{a[1], a[2]};
            if (Math.hypot(b[1] - a[1], b[2] - a[2]) > TELEPORT_BLOCKS) return new double[]{a[1], a[2]};
            double f = b[0] == a[0] ? 1 : (t - a[0]) / (b[0] - a[0]);
            return new double[]{a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
        }
    }

    // ---- varints

    static long zigzag(long v) {
        return (v << 1) ^ (v >> 63);
    }

    static long unzigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    static long readVarint(byte[] data, int[] pos) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= data.length) throw new IndexOutOfBoundsException("cut");
            int b = data[pos[0]++] & 0xFF;
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 63) throw new IndexOutOfBoundsException("bad varint");
        }
    }
}
