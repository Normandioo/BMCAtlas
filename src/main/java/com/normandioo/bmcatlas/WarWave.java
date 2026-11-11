package com.normandioo.bmcatlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The capture wave of the war map: when a province goes to another side's colour, the new colour spreads across it from the
 * border with the side that took it (or from its city, when they share no border).
 *
 * The map's texture already has the new colour; the wave only covers what it has not reached yet with the old one, as a tint of
 * the same strength the map uses (so the terrain still shows through), fading into the new colour across a soft edge.
 *
 * The front moves at the same speed in blocks everywhere. When a province is taken next to one whose wave is still running, its
 * wave waits for that front to reach their shared border and goes on from there, so a run of captures reads as one movement.
 * No Minecraft classes here.
 */
final class WarWave {
    private WarWave() {}

    /** How fast the front moves (blocks per ms), unless that makes a wave shorter than MIN_MS or longer than MAX_MS. */
    static final double SPEED = 0.12;
    static final long MIN_MS = 1_200, MAX_MS = 6_000;
    /** The soft edge between the new and the old colour: this share of the province's depth, within these limits (blocks). */
    static final double BAND_SHARE = 0.25, BAND_MIN = 16, BAND_MAX = 160;
    private static final long QUIET = 10_000;      // two changes within this: straight swap, no wave
    private static final int MAX = 12;             // waves at once
    private static final int MAX_CELLS = 80;       // grid resolution, per side

    static final class Wave {
        int prov, cell, x0, z0, w, h, oldRgb, newRgb;
        /** Distance from where the wave starts, in blocks; -1 outside the province. */
        double[] dist;
        double maxDist, speed, band;
        long start, duration;
        /** The border cells it starts from: {cell index, neighbouring province, a block of that province (x, z)}. */
        transient List<int[]> origins = new ArrayList<>();

        /** How far the front is at time t (blocks): 0 before the start, maxDist + band at the end. */
        double front(long t) {
            return Math.max(0, Math.min(maxDist + band, (t - start) * speed));
        }

        /** The distance (blocks) of the cell holding block (x, z), or -1 when outside this wave. */
        double distAt(int x, int z) {
            int i = Math.floorDiv(x - x0, cell), j = Math.floorDiv(z - z0, cell);
            if (i < 0 || j < 0 || i >= w || j >= h) return -1;
            return dist[j * w + i];
        }

        /** When the front has gone past block (x, z) (fully the new colour there). */
        long passes(int x, int z) {
            double d = distAt(x, z);
            if (d < 0) d = maxDist;
            return start + (long) Math.ceil((d + band) / speed);
        }
    }

    private static final Map<Integer, Wave> WAVES = new HashMap<>();
    private static final Map<Integer, Long> LAST_CHANGE = new HashMap<>();

    static void clear() {
        WAVES.clear();
        LAST_CHANGE.clear();
    }

    /** The waves to draw at time t (including those waiting for their turn: still all old colour). */
    static Collection<Wave> active(long now) {
        WAVES.values().removeIf(w -> now - w.start >= w.duration);
        return WAVES.values();
    }

    /**
     * Whether a cell at distance dist (blocks) has flipped to the new colour when the front is at front. Each cell flips at once,
     * somewhere in the band behind the front (a fixed draw per cell), so the front is ragged and eats its way in, cell by cell.
     */
    static boolean taken(int prov, int at, double dist, double front, double band) {
        return front > dist + jitter(prov, at) * band;
    }

    /** A fixed number in [0, 1) for each cell of each province (the same every frame). */
    static double jitter(int prov, int at) {
        int h = at * 0x9E3779B1 + prov * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFF) / 65536.0;
    }

    /** The edge's width for a province this deep (blocks). */
    static double band(double maxDist) {
        return Math.max(BAND_MIN, Math.min(BAND_MAX, maxDist * BAND_SHARE));
    }

    /** The speed (blocks per ms) of a wave this deep, kept between MIN_MS and MAX_MS long. */
    static double speed(double depth) {
        double s = SPEED;
        if (depth / s > MAX_MS) s = depth / MAX_MS;
        if (depth / s < MIN_MS) s = depth / MIN_MS;
        return Math.max(1e-4, s);
    }

    private static int colorOf(SiteTerritories.Data d, Map<Integer, Integer> prov, int[] country, int p) {
        Integer c = prov.get(p);
        return c != null ? c : country[d.provCountry[p]];
    }

    static boolean isSide(int rgb) {
        return rgb != Relations.GREY && rgb != Relations.CONTESTED && rgb != Relations.UNCONTROLLED && rgb != Relations.NEUTRAL;
    }

    /** Compares the colours before and after a reading and starts the waves of the provinces that changed side. */
    static void changes(SiteTerritories.Data d, Map<Integer, Integer> oldProv, int[] oldCountry,
                        Map<Integer, Integer> newProv, int[] newCountry, long now) {
        if (oldCountry.length != newCountry.length) return;
        Set<Integer> touched = new HashSet<>(oldProv.keySet());
        touched.addAll(newProv.keySet());
        List<Wave> fresh = new ArrayList<>();
        for (int p : touched) {
            if (p < 0 || p >= d.n) continue;
            int before = colorOf(d, oldProv, oldCountry, p), after = colorOf(d, newProv, newCountry, p);
            if (before == after) continue;
            Long last = LAST_CHANGE.put(p, now);
            if (last != null && now - last < QUIET) {       // back and forth: no wave
                WAVES.remove(p);
                continue;
            }
            if (!isSide(after) || WAVES.size() + fresh.size() >= MAX) continue;
            Wave w = build(d, p, before, after, newProv, newCountry);
            if (w != null) fresh.add(w);
        }
        for (Wave w : fresh) WAVES.put(w.prov, w);
        // Taken together with a neighbour (or next to one still moving): each wave starts when the wave before it reaches their
        // border. Going over them a few times settles chains of any order.
        for (int round = 0; round < Math.min(4, fresh.size()); round++) {
            for (Wave w : fresh) resolve(w, now);
        }
    }

    /** Lays out the grid of a province and finds the border cells the wave starts from. Distances come later (resolve). */
    private static Wave build(SiteTerritories.Data d, int p, int oldRgb, int conqueror, Map<Integer, Integer> prov, int[] country) {
        int[] b = d.bounds[p];
        int spanX = b[2] - b[0] + 1, spanZ = b[3] - b[1] + 1;
        Wave w = new Wave();
        w.prov = p;
        w.cell = Math.max(1, (int) Math.ceil(Math.max(spanX, spanZ) / (double) MAX_CELLS));
        w.x0 = b[0];
        w.z0 = b[1];
        w.w = (int) Math.ceil(spanX / (double) w.cell);
        w.h = (int) Math.ceil(spanZ / (double) w.cell);
        w.oldRgb = oldRgb;
        w.newRgb = conqueror;
        w.dist = new double[w.w * w.h];
        Arrays.fill(w.dist, -1);

        boolean inside = false;
        for (int j = 0; j < w.h; j++) {
            for (int i = 0; i < w.w; i++) {
                if (SiteTerritories.provinceAt(d, centerX(w, i), centerZ(w, j)) == p) {
                    w.dist[j * w.w + i] = Double.MAX_VALUE;   // inside, not reached yet
                    inside = true;
                }
            }
        }
        if (!inside) return null;

        int[] ni = {-1, 1, 0, 0}, nj = {0, 0, -1, 1};
        for (int j = 0; j < w.h; j++) {
            for (int i = 0; i < w.w; i++) {
                int at = j * w.w + i;
                if (w.dist[at] < 0) continue;
                for (int k = 0; k < 4; k++) {
                    int oi = i + ni[k], oj = j + nj[k];
                    if (oi >= 0 && oj >= 0 && oi < w.w && oj < w.h && w.dist[oj * w.w + oi] >= 0) continue;
                    int qx = centerX(w, oi), qz = centerZ(w, oj);
                    int q = SiteTerritories.provinceAt(d, qx, qz);
                    if (q >= 0 && q != p && colorOf(d, prov, country, q) == conqueror) {
                        w.origins.add(new int[]{at, q, qx, qz});
                        break;
                    }
                }
            }
        }
        if (w.origins.isEmpty()) w.origins.add(new int[]{nearestCell(d, w, p), -1, 0, 0});   // no border (by sea, an island): from the city
        return w;
    }

    /**
     * Works out when the wave starts and how far each cell is: every border cell starts when the neighbouring province is fully
     * the new colour there (now, or when a wave still running there passes it); the distance of a cell is the shortest way from
     * any border cell, counting the later ones as already that far behind.
     */
    private static void resolve(Wave w, long now) {
        // Border cells next to the taker's land that is already settled start now. Only a province with no such border waits
        // for a neighbour's wave still on its way (taken in the same reading, or a moment before) and goes on from it: so waves
        // flow outwards from settled land and never wait on each other in a circle.
        int n = w.origins.size();
        long[] reach = new long[n];
        boolean anySettled = false;
        for (int o = 0; o < n; o++) {
            int[] or = w.origins.get(o);
            Wave before = or[1] >= 0 ? WAVES.get(or[1]) : null;
            if (before == null || before == w || before.newRgb != w.newRgb) {
                reach[o] = now;
                anySettled = true;
            } else if (before.duration <= 0) {
                reach[o] = Long.MAX_VALUE;   // that wave is not worked out yet (next round)
            } else {
                reach[o] = Math.max(now, before.passes(or[2], or[3]));
                if (reach[o] == now) anySettled = true;   // it has already gone past there
            }
        }
        long start = Long.MAX_VALUE;
        for (int o = 0; o < n; o++) {
            if (anySettled && reach[o] != now) reach[o] = Long.MAX_VALUE;   // settled land leads: ignore the waiting borders
            start = Math.min(start, reach[o]);
        }
        if (start == Long.MAX_VALUE) {   // only neighbours not worked out yet (or a circle with no settled land): start now
            start = now;
            Arrays.fill(reach, now);
        }
        double depthGuess = Math.max(w.w, w.h) * (double) w.cell;
        double speed = speed(depthGuess);
        double[] dist = new double[w.dist.length];
        for (int at = 0; at < dist.length; at++) dist[at] = w.dist[at] < 0 ? -1 : Double.MAX_VALUE;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int o = 0; o < n; o++) {
            if (reach[o] == Long.MAX_VALUE) continue;
            int at = w.origins.get(o)[0];
            double d0 = (reach[o] - start) * speed;   // a later start counts as being that far behind
            if (d0 < dist[at]) {
                dist[at] = d0;
                open.add(new double[]{d0, at});
            }
        }
        int[] ni = {-1, 1, 0, 0}, nj = {0, 0, -1, 1};
        while (!open.isEmpty()) {
            double[] e = open.poll();
            int at = (int) e[1];
            if (e[0] > dist[at]) continue;
            int i = at % w.w, j = at / w.w;
            for (int k = 0; k < 4; k++) {
                int oi = i + ni[k], oj = j + nj[k];
                if (oi < 0 || oj < 0 || oi >= w.w || oj >= w.h) continue;
                int to = oj * w.w + oi;
                double nd = dist[at] + w.cell;
                if (dist[to] < 0 || nd >= dist[to]) continue;
                dist[to] = nd;
                open.add(new double[]{nd, to});
            }
        }
        double max = 0;
        for (double v : dist) if (v >= 0 && v < Double.MAX_VALUE) max = Math.max(max, v);
        for (int at = 0; at < dist.length; at++) if (dist[at] == Double.MAX_VALUE) dist[at] = max;   // loose bits: at the end
        w.dist = dist;
        w.maxDist = Math.max(w.cell, max);
        w.speed = speed(w.maxDist);
        w.band = band(w.maxDist);
        w.start = start;
        w.duration = (long) Math.ceil((w.maxDist + w.band) / w.speed);
    }

    private static int centerX(Wave w, int i) {
        return w.x0 + i * w.cell + w.cell / 2;
    }

    private static int centerZ(Wave w, int j) {
        return w.z0 + j * w.cell + w.cell / 2;
    }

    /** The province's cell nearest one of its cities (or its middle, when it has none). */
    private static int nearestCell(SiteTerritories.Data d, Wave w, int p) {
        int tx = w.x0 + w.w * w.cell / 2, tz = w.z0 + w.h * w.cell / 2;
        for (SiteTerritories.Locality l : d.localities) {
            if (SiteTerritories.provinceAt(d, l.x, l.z) == p) {
                tx = l.x;
                tz = l.z;
                break;
            }
        }
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int at = 0; at < w.dist.length; at++) {
            if (w.dist[at] < 0) continue;
            long dx = centerX(w, at % w.w) - tx, dz = centerZ(w, at / w.w) - tz, dd = dx * dx + dz * dz;
            if (dd < bestD) {
                bestD = dd;
                best = at;
            }
        }
        return best;
    }
}
