package com.normandioo.bmcatlas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;


/**
 * The moments worth watching in a recording, found from its files alone. No Minecraft classes here.
 *
 *   capture      a city goes to an enemy side                 weight: the city's value (a capital counts triple)
 *   retaken      a city goes back to its owner                weight: the same
 *   contested    a city becomes contested                     weight: low
 *   capital      a capital falls                              weight: high
 *   capitulation a nation capitulates (WarFront 7.9)              weight: the highest
 *   battle       3+ players of 2+ sides within 64 blocks for 10 s or more   weight: by the number of players
 *   start, end   the first and last moment recorded           weight: 0 (markers only)
 *
 * A change that happened while the player was away shows up as the difference between the last frame before the gap and the
 * first one after it. It is not a highlight: nobody saw it happen.
 */
final class RecHighlights {
    private RecHighlights() {}

    static final double BATTLE_RADIUS = 64;
    static final long BATTLE_MIN_MS = 10_000, BATTLE_STEP_MS = 2_000;
    /** A frame within this long after the player came back holds what changed while away. */
    static final long ARRIVAL_MS = 3_000;

    enum Type { START, END, CAPTURE, RETAKEN, CONTESTED, CAPITAL, CAPITULATION, BATTLE }

    /** One highlight: when (ms since the start), what, where, which sides (indexes in meta.sides), how much it matters, a short text. */
    record Highlight(long t, Type type, String city, double x, double z, List<Integer> sides, double weight, String text) {}

    /** What the game knows about a city that the recording does not: its value and whether it is a capital. */
    interface Cities {
        double value(RecFormat.City c);

        boolean capital(RecFormat.City c);

        Cities NONE = new Cities() {
            public double value(RecFormat.City c) {
                return 1;
            }

            public boolean capital(RecFormat.City c) {
                return false;
            }
        };
    }

    /**
     * Every highlight of a recording, in time order.
     * @param capitulatedAt the nations capitulated at a cities frame (index in frames), or null to skip capitulations
     */
    static List<Highlight> find(Replay r, Cities cities, IntFunction<Set<String>> capitulatedAt) {
        List<Highlight> out = new ArrayList<>();
        out.add(new Highlight(0, Type.START, null, 0, 0, List.of(), 0, "Start"));
        cityChanges(r, cities, capitulatedAt, out);
        battles(r, out);
        out.add(new Highlight(r.length(), Type.END, null, 0, 0, List.of(), 0, "End"));
        out.sort(Comparator.comparingLong(Highlight::t));
        return out;
    }

    /** The caption for the moment on screen: the newest capture-like highlight in the last span ms, or null. */
    static String captionAt(List<Highlight> all, long now, long span) {
        Highlight best = null;
        for (Highlight h : all) {
            boolean capture = h.type() == Type.CAPTURE || h.type() == Type.CAPITAL || h.type() == Type.RETAKEN || h.type() == Type.CAPITULATION;
            if (capture && h.t() <= now && h.t() > now - span && (best == null || h.t() > best.t())) best = h;
        }
        return best == null ? null : best.text();
    }

    /** Whether the change in a frame at time t was seen: inside a recorded stretch, and not the first look after coming back. */
    static boolean witnessed(RecFormat.Meta meta, long t) {
        if (meta.intervals.isEmpty()) return true;
        long abs = meta.start + t;
        for (long[] iv : meta.intervals) {
            if (abs >= iv[0] && abs <= iv[1] + ARRIVAL_MS) return abs - iv[0] > ARRIVAL_MS;
        }
        return false;
    }

    private static String key(RecFormat.City c) {
        return (c.name == null ? "" : c.name.toLowerCase(Locale.ROOT)) + "|" + (c.country == null ? "" : c.country.toLowerCase(Locale.ROOT));
    }

    private static String sideName(RecFormat.Meta meta, Integer side) {
        return side != null && side >= 0 && side < meta.sides.size() && meta.sides.get(side).name != null ? meta.sides.get(side).name : "A side";
    }

    private static void cityChanges(Replay r, Cities cities, IntFunction<Set<String>> capitulatedAt, List<Highlight> out) {
        Map<String, RecFormat.City> before = new HashMap<>();
        Set<String> lastDown = Set.of();
        for (int i = 0; i < r.frames.size(); i++) {
            RecFormat.Frame f = r.frames.get(i);
            Map<String, RecFormat.City> now = new HashMap<>();
            for (RecFormat.City c : f.cities) now.put(key(c), c);
            boolean seen = i > 0 && witnessed(r.meta, f.t);
            if (seen) {
                Set<String> all = new HashSet<>(before.keySet());
                all.addAll(now.keySet());
                for (String k : all) {
                    RecFormat.City a = before.get(k), b = now.get(k);
                    if (b == null) {   // back to its owner
                        double w = cities.value(a) * (cities.capital(a) ? 3 : 1);
                        out.add(new Highlight(f.t, Type.RETAKEN, a.name, a.x, a.z, List.of(), w, a.name + " retaken"));
                        continue;
                    }
                    boolean contested = "contested".equals(b.state);
                    if (contested && (a == null || !"contested".equals(a.state))) {
                        out.add(new Highlight(f.t, Type.CONTESTED, b.name, b.x, b.z, List.of(), 0.3, b.name + " contested"));
                        continue;
                    }
                    if (contested || b.side == null) continue;
                    if (a != null && Objects.equals(a.side, b.side) && !"contested".equals(a.state)) continue;
                    boolean capital = cities.capital(b);
                    double w = cities.value(b) * (capital ? 3 : 1);
                    String who = sideName(r.meta, b.side);
                    out.add(new Highlight(f.t, capital ? Type.CAPITAL : Type.CAPTURE, b.name, b.x, b.z, List.of(b.side),
                            capital ? Math.max(w, 10) : w, who + " captured " + b.name + (capital ? " (capital)" : "")));
                }
            }
            if (capitulatedAt != null) {
                Set<String> down = capitulatedAt.apply(i);
                if (down != null) {
                    for (String nation : down) {
                        if (seen && !lastDown.contains(nation)) {
                            out.add(new Highlight(f.t, Type.CAPITULATION, null, 0, 0, List.of(), 1000, nation + " capitulated"));
                        }
                    }
                    lastDown = down;
                }
            }
            before = now;
        }
    }

    /** The side (index in meta.sides) of each recorded player, from their country; -1 when in none. */
    private static Map<Integer, Integer> sides(Replay r) {
        Map<String, Integer> sideOfCountry = new HashMap<>();
        for (int s = 0; s < r.meta.sides.size(); s++) {
            for (String c : r.meta.sides.get(s).countries) sideOfCountry.put(c.toLowerCase(Locale.ROOT), s);
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (var e : r.players.entrySet()) {
            String c = e.getValue().country;
            out.put(e.getKey(), c == null ? -1 : sideOfCountry.getOrDefault(c.toLowerCase(Locale.ROOT), -1));
        }
        return out;
    }

    private static void battles(Replay r, List<Highlight> out) {
        Map<Integer, Integer> sideOf = sides(r);
        if (sideOf.isEmpty()) return;
        long runStart = -1, runEnd = -1;
        double cx = 0, cz = 0;
        int most = 0;
        Set<Integer> runSides = new HashSet<>();
        for (long t = 0; t <= r.length() + BATTLE_STEP_MS; t += BATTLE_STEP_MS) {
            double[] spot = t <= r.length() ? battleAt(r, sideOf, t) : null;   // {x, z, players, sides bitmask}
            boolean near = spot != null && (runStart < 0 || Math.hypot(spot[0] - cx, spot[1] - cz) <= BATTLE_RADIUS * 2);
            if (near) {
                if (runStart < 0) {
                    runStart = t;
                    most = 0;
                    runSides.clear();
                }
                runEnd = t;
                cx = spot[0];
                cz = spot[1];
                most = Math.max(most, (int) spot[2]);
                long mask = (long) spot[3];
                for (int s = 0; s < 63; s++) if ((mask >> s & 1) == 1) runSides.add(s);
                continue;
            }
            if (runStart >= 0 && runEnd - runStart >= BATTLE_MIN_MS && witnessed(r.meta, runStart + ARRIVAL_MS + 1)) {
                out.add(new Highlight(runStart, Type.BATTLE, null, cx, cz, List.copyOf(runSides), most * 0.5, "Battle: " + most + " players"));
            }
            runStart = -1;
            if (spot != null) t -= BATTLE_STEP_MS;   // a battle somewhere else: look at this moment again as a new start
        }
    }

    /** The biggest group of 3+ players of 2+ sides within BATTLE_RADIUS of one of them at time t, or null. */
    static double[] battleAt(Replay r, Map<Integer, Integer> sideOf, long t) {
        List<double[]> pos = new ArrayList<>();   // x, z, side
        for (int i : r.track.players()) {
            Integer side = sideOf.get(i);
            if (side == null || side < 0) continue;
            double[] p = r.track.at(i, t);
            if (p != null) pos.add(new double[]{p[0], p[1], side});
        }
        double[] best = null;
        for (double[] a : pos) {
            int n = 0;
            long mask = 0;
            double sx = 0, sz = 0;
            for (double[] b : pos) {
                if (Math.hypot(a[0] - b[0], a[1] - b[1]) > BATTLE_RADIUS) continue;
                n++;
                mask |= 1L << (int) b[2];
                sx += b[0];
                sz += b[1];
            }
            if (n >= 3 && Long.bitCount(mask) >= 2 && (best == null || n > best[2])) best = new double[]{sx / n, sz / n, n, mask};
        }
        return best;
    }
}
