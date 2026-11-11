package com.normandioo.bmcatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The editor's work on a recording (project.json in its folder): the clips that make the video, and what it shows. Cameras
 * (phase 3b) and the export options (phase 4) are added here later. No Minecraft classes here.
 */
final class RecProject {
    static final long BEFORE_MS = 20_000, AFTER_MS = 15_000;
    static final double DEFAULT_MIN_WEIGHT = 1, DEFAULT_FAST = 120;
    /** The speeds a clip can have, slowest first. */
    static final double[] CLIP_SPEEDS = {0.25, 0.5, 1, 2, 4, 8, 16, 30, 60, 120, 240};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A stretch of the recording in the video: from, to (ms since the recording's start), played at speed. */
    static final class Clip {
        long from, to;
        double speed = 1;

        Clip() {}

        Clip(long from, long to, double speed) {
            this.from = from;
            this.to = to;
            this.speed = speed;
        }

        long length() {
            return Math.max(0, to - from);
        }
    }

    /** What the video shows. */
    static final class Show {
        boolean players = true, playerNames = true, cityNames = true, score = true, clock = true, captions = true;
        boolean countryNames = false, front = true, cities = true, limits = true, marks = true, self = true;
    }

    /** A cut of the edited video: from time t on, camera (its id) is the one shown. */
    static final class Cut {
        long t;
        String camera;

        Cut() {}

        Cut(long t, String camera) {
            this.t = t;
            this.camera = camera;
        }
    }

    /** The cuts of the edited video, in time order (none: no edited video, one video per camera). */
    List<Cut> cuts = new ArrayList<>();

    RecCamera cameraById(String id) {
        for (RecCamera c : cameras) if (c.id != null && c.id.equals(id)) return c;
        return null;
    }

    /** The camera shown at time t in the edited video: the last cut at or before t (the first cut's before it), or null. */
    RecCamera cameraAt(long t) {
        Cut best = null;
        for (Cut c : cuts) {
            if (best == null || c.t <= t) best = c;
            if (c.t > t) break;
        }
        return best == null ? null : cameraById(best.camera);
    }

    /** Adds a cut to a camera at t (or changes the cut there, within half a second); the cuts stay in time order. */
    Cut cut(long t, String camera) {
        for (Cut c : cuts) {
            if (Math.abs(c.t - t) <= 500) {
                c.camera = camera;
                return c;
            }
        }
        Cut c = new Cut(Math.max(0, t), camera);
        cuts.add(c);
        sortCuts();
        return c;
    }

    void sortCuts() {
        cuts.sort(Comparator.comparingLong(c -> c.t));
    }

    /** Removes a camera and its cuts. */
    void removeCamera(RecCamera c) {
        cameras.remove(c);
        cuts.removeIf(x -> x.camera == null || x.camera.equals(c.id));
    }

    List<Clip> clips = new ArrayList<>();
    Show show = new Show();
    /** The cameras (phase 3b): one video each at export. None: the editor's view is used. */
    List<RecCamera> cameras = new ArrayList<>();
    /** Frames per second of the export (30 or 60). */
    int fps = 30;

    // ---- project.json

    static RecProject load(Path dir) {
        Path f = dir.resolve("project.json");
        try {
            if (Files.exists(f)) {
                RecProject p = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), RecProject.class);
                if (p != null) {
                    if (p.clips == null) p.clips = new ArrayList<>();
                    if (p.show == null) p.show = new Show();
                    if (p.cameras == null) p.cameras = new ArrayList<>();
                    p.cameras.removeIf(c -> c == null);
                    if (p.cuts == null) p.cuts = new ArrayList<>();
                    p.cuts.removeIf(c -> c == null || p.cameraById(c.camera) == null);
                    p.sortCuts();
                    for (RecCamera c : p.cameras) {
                        if (c.keys == null) c.keys = new ArrayList<>();
                        c.keys.removeIf(k -> k == null || !(k.width > 0));
                        if (c.keys.isEmpty()) c.keys.add(new RecCamera.Key(0, 0, 0, 512));
                    }
                    if (p.fps != 60) p.fps = 30;
                    p.clips.removeIf(c -> c == null || c.to <= c.from || c.speed <= 0);
                    return p;
                }
            }
        } catch (IOException | RuntimeException e) {
            // a broken file: start over (the recording itself is untouched)
        }
        return new RecProject();
    }

    void save(Path dir) throws IOException {
        Path f = dir.resolve("project.json");
        Path tmp = f.resolveSibling("project.json.tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- clips

    /** Clips in time order. */
    void sort() {
        clips.sort(Comparator.comparingLong((Clip c) -> c.from).thenComparingLong(c -> c.to));
    }

    /** Adds a clip, kept inside the recording; returns it, or null when it would be empty. */
    Clip add(long from, long to, double speed, long length) {
        long a = Math.max(0, Math.min(from, to)), b = Math.min(length, Math.max(from, to));
        if (b <= a) return null;
        Clip c = new Clip(a, b, speed);
        clips.add(c);
        sort();
        return c;
    }

    /**
     * "Auto highlights": a clip from 20 s before to 15 s after each highlight of at least minWeight, at 1x, merged where they
     * overlap; with fast > 0, the gaps between them are filled with clips at that speed. Replaces the clips there were.
     */
    void autoHighlights(List<RecHighlights.Highlight> highlights, double minWeight, double fast, long length) {
        List<Clip> slow = new ArrayList<>();
        for (RecHighlights.Highlight h : highlights) {
            if (h.weight() < minWeight) continue;
            long a = Math.max(0, h.t() - BEFORE_MS), b = Math.min(length, h.t() + AFTER_MS);
            if (b <= a) continue;
            Clip last = slow.isEmpty() ? null : slow.get(slow.size() - 1);
            if (last != null && a <= last.to) last.to = Math.max(last.to, b);   // they overlap: one clip
            else slow.add(new Clip(a, b, 1));
        }
        slow.sort(Comparator.comparingLong(c -> c.from));
        List<Clip> merged = new ArrayList<>();
        for (Clip c : slow) {   // sorted input could still overlap after sorting: merge again
            Clip last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && c.from <= last.to) last.to = Math.max(last.to, c.to);
            else merged.add(c);
        }
        clips = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0 && fast > 0 && merged.get(i).from > merged.get(i - 1).to) clips.add(new Clip(merged.get(i - 1).to, merged.get(i).from, fast));
            clips.add(merged.get(i));
        }
    }

    // ---- frames of the video

    /** How many video frames a clip makes at fps. */
    static long frames(Clip c, int fps) {
        return (long) Math.ceil(c.length() / c.speed * fps / 1000.0);
    }

    /** How many video frames all the clips make. */
    long totalFrames() {
        long n = 0;
        for (Clip c : clips) n += frames(c, fps);
        return n;
    }
}
