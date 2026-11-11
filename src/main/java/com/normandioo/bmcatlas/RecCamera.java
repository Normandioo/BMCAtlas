package com.normandioo.bmcatlas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * A camera of the replay editor: a named framing of the map, kept in project.json. The time comes from the clips; the framing
 * comes from the cameras, and the export makes one video per camera marked "export". No Minecraft classes here.
 *
 * The framing is kept in blocks (centre and width covered), never as a zoom, so it does not depend on the window or on the
 * interface scale. The height covered is the width divided by the camera's aspect.
 */
final class RecCamera {
    static final String[] ASPECTS = {"window", "16:9", "9:16", "1:1", "4:3"};
    /** The zoom range of the map, in pixels per block at the video's size. */
    static final double MIN_SCALE = 0.03, MAX_SCALE = 8;
    /** Two keyframes closer than this in time are the same one. */
    static final long SAME_KEY_MS = 250;
    /** Following a player: their position is averaged over this long around each moment, so the camera does not shake. */
    static final long FOLLOW_SMOOTH_MS = 3_000;
    /** Following the highlights: the camera stays on one and moves to the next during this long before it. */
    static final long HIGHLIGHT_MOVE_MS = 6_000;
    static final int[] COLORS = {0xFF39A0FF, 0xFFFF6B6B, 0xFF4CD964, 0xFFFFC83D, 0xFFB06CFF, 0xFF2ED1C4};

    /** One keyframe: from time t (ms since the recording's start), the centre (blocks) and the width covered (blocks). */
    static final class Key {
        long t;
        double cx, cz, width;

        Key() {}

        Key(long t, double cx, double cz, double width) {
            this.t = t;
            this.cx = cx;
            this.cz = cz;
            this.width = width;
        }
    }

    String id, name;
    int color = COLORS[0];
    String aspect = "16:9";
    List<Key> keys = new ArrayList<>();
    /** "none", "player:<account>" or "highlight". */
    String follow = "none";
    boolean export = true;
    /** Optional: the stretch this camera is for (ms since the start); outside it, it makes no frames. */
    Long from, to;

    /** Width / height of the video: the window's for "window". */
    double ratio(double windowRatio) {
        if (aspect == null) return windowRatio;
        return switch (aspect) {
            case "16:9" -> 16 / 9.0;
            case "9:16" -> 9 / 16.0;
            case "1:1" -> 1;
            case "4:3" -> 4 / 3.0;
            default -> windowRatio;
        };
    }

    /** The next aspect in ASPECTS. */
    void nextAspect() {
        int i = 0;
        while (i < ASPECTS.length && !ASPECTS[i].equals(aspect)) i++;
        aspect = ASPECTS[(i + 1) % ASPECTS.length];
    }

    String followedAccount() {
        return follow != null && follow.startsWith("player:") ? follow.substring("player:".length()) : null;
    }

    boolean active(long t) {
        return (from == null || t >= from) && (to == null || t <= to);
    }

    // ---- keyframes

    private void sortKeys() {
        keys.sort(Comparator.comparingLong(k -> k.t));
    }

    /** Sets the framing at time t: changes the keyframe there, or adds one. */
    Key setKey(long t, double cx, double cz, double width) {
        for (Key k : keys) {
            if (Math.abs(k.t - t) <= SAME_KEY_MS) {
                k.cx = cx;
                k.cz = cz;
                k.width = width;
                return k;
            }
        }
        Key k = new Key(t, cx, cz, width);
        keys.add(k);
        sortKeys();
        return k;
    }

    /** The keyframe at time t (within SAME_KEY_MS), or null. */
    Key keyAt(long t) {
        for (Key k : keys) if (Math.abs(k.t - t) <= SAME_KEY_MS) return k;
        return null;
    }

    void moveKey(Key k, long t) {
        k.t = Math.max(0, t);
        sortKeys();
    }

    /** Removes a keyframe; the last one stays (a camera always has a framing). */
    boolean removeKey(Key k) {
        if (keys.size() <= 1) return false;
        return keys.remove(k);
    }

    private static double ease(double f) {
        f = Math.max(0, Math.min(1, f));
        return f * f * (3 - 2 * f);
    }

    /**
     * The framing a fraction f (0-1) of the way from keyframe a to b, zoomed like the mouse wheel zooms the map: the width changes
     * by the same ratio in equal times (so zooming in does not speed up or slow down), and the centre moves with it so that one
     * point of the map stays put on screen, the one both framings are scaled around. With the same width it is a plain pan.
     */
    static double[] between(Key a, Key b, double f) {
        double wa = Math.max(1e-6, a.width), wb = Math.max(1e-6, b.width);
        double w = wa * Math.pow(wb / wa, f);
        double g = Math.abs(wb - wa) < 1e-6 * Math.max(wa, wb) ? f : (w - wa) / (wb - wa);   // how far the centre has moved
        return new double[]{a.cx + (b.cx - a.cx) * g, a.cz + (b.cz - a.cz) * g, w};
    }

    /** The keyframes' framing at time t: {cx, cz, width}, eased between the two around it; the nearest one before or after. */
    double[] keyed(long t) {
        if (keys.isEmpty()) return new double[]{0, 0, 256};
        Key a = keys.get(0);
        if (t <= a.t) return new double[]{a.cx, a.cz, a.width};
        for (int i = 1; i < keys.size(); i++) {
            Key b = keys.get(i);
            if (t <= b.t) return between(a, b, ease((t - a.t) / (double) Math.max(1, b.t - a.t)));
            a = b;
        }
        return new double[]{a.cx, a.cz, a.width};
    }

    /**
     * The framing at time t: {cx, cz, width}. Following a player, the centre is where they were (averaged over FOLLOW_SMOOTH_MS),
     * or the keyframes' when they were not on the map; following the highlights, it moves from one to the next. The width always
     * comes from the keyframes.
     * @param playerAt where a recorded account was at a moment (null when not on the map); may be null
     * @param places the highlights with a place, {t, x, z}, in time order; may be null
     */
    double[] at(long t, java.util.function.BiFunction<String, Long, double[]> playerAt, List<double[]> places) {
        double[] k = keyed(t);
        String account = followedAccount();
        if (account != null && playerAt != null) {
            double sx = 0, sz = 0;
            int n = 0;
            for (long dt = -FOLLOW_SMOOTH_MS / 2; dt <= FOLLOW_SMOOTH_MS / 2; dt += 250) {
                double[] p = playerAt.apply(account, t + dt);
                if (p == null) continue;
                sx += p[0];
                sz += p[1];
                n++;
            }
            if (n > 0) return new double[]{sx / n, sz / n, k[2]};
        }
        if ("highlight".equals(follow) && places != null && !places.isEmpty()) {
            double[] c = follow(places, t);
            return new double[]{c[0], c[1], k[2]};
        }
        return k;
    }

    /** Following the highlights: on the last one until HIGHLIGHT_MOVE_MS before the next, then moving there (eased). */
    static double[] follow(List<double[]> places, long t) {
        double[] prev = places.get(0);
        if (t <= prev[0]) return new double[]{prev[1], prev[2]};
        for (int i = 1; i < places.size(); i++) {
            double[] next = places.get(i);
            if (t < next[0]) {
                double start = Math.max(prev[0], next[0] - HIGHLIGHT_MOVE_MS);
                double f = t <= start ? 0 : ease((t - start) / Math.max(1, next[0] - start));
                return new double[]{prev[1] + (next[1] - prev[1]) * f, prev[2] + (next[2] - prev[2]) * f};
            }
            prev = next;
        }
        return new double[]{prev[1], prev[2]};
    }

    // ---- the video

    /** The video's size in pixels for a window of w x h: the window's for "window", else the largest that fits, both even. */
    int[] outputSize(int w, int h) {
        double r = ratio(w / (double) Math.max(1, h));
        int ow = w, oh = (int) Math.round(w / r);
        if (oh > h) {
            oh = h;
            ow = (int) Math.round(h * r);
        }
        return new int[]{Math.max(2, ow & ~1), Math.max(2, oh & ~1)};
    }

    /** The zoom (pixels per block) kept inside the map's range: the editor view and the export both use it. */
    static double clampScale(double scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /**
     * The share (0-1) of the area this camera covers that has terrain, looking at each keyframe's framing: chunks known / chunks
     * covered. Up to 48 x 48 chunks are looked at per keyframe (spread evenly over larger areas).
     */
    double terrain(double windowRatio, BiPredicate<Integer, Integer> chunkKnown) {
        long known = 0, all = 0;
        double r = ratio(windowRatio);
        for (Key k : keys) {
            double hw = k.width / 2, hh = k.width / r / 2;
            int c0 = (int) Math.floor((k.cx - hw) / 16), c1 = (int) Math.floor((k.cx + hw - 1e-6) / 16);   // the right and bottom edges are outside
            int r0 = (int) Math.floor((k.cz - hh) / 16), r1 = (int) Math.floor((k.cz + hh - 1e-6) / 16);
            int stepX = Math.max(1, (c1 - c0 + 1) / 48), stepZ = Math.max(1, (r1 - r0 + 1) / 48);
            for (int cx = c0; cx <= c1; cx += stepX) {
                for (int cz = r0; cz <= r1; cz += stepZ) {
                    all++;
                    if (chunkKnown.test(cx, cz)) known++;
                }
            }
        }
        return all == 0 ? 1 : known / (double) all;
    }

    /** A copy with a new id and name (keyframes copied). */
    RecCamera copy(String newId, String newName) {
        RecCamera c = new RecCamera();
        c.id = newId;
        c.name = newName;
        c.color = color;
        c.aspect = aspect;
        c.follow = follow;
        c.export = export;
        c.from = from;
        c.to = to;
        for (Key k : keys) c.keys.add(new Key(k.t, k.cx, k.cz, k.width));
        return c;
    }

    /** The player's position at a moment from a replay's track, for at(...). */
    static java.util.function.BiFunction<String, Long, double[]> playersOf(Replay r) {
        java.util.Map<String, Integer> index = new java.util.HashMap<>();
        for (var e : r.players.entrySet()) if (e.getValue().account != null) index.put(e.getValue().account.toLowerCase(java.util.Locale.ROOT), e.getKey());
        return (account, t) -> {
            Integer i = index.get(account.toLowerCase(java.util.Locale.ROOT));
            return i == null ? null : r.track.at(i, t);
        };
    }

    /** The highlights with a place, {t, x, z}, for at(...). */
    static List<double[]> placesOf(List<RecHighlights.Highlight> hs) {
        List<double[]> out = new ArrayList<>();
        for (RecHighlights.Highlight h : hs) {
            if (h.city() == null && h.type() != RecHighlights.Type.BATTLE) continue;
            out.add(new double[]{h.t(), h.x(), h.z()});
        }
        return out;
    }
}
