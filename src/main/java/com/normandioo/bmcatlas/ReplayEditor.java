package com.normandioo.bmcatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The editor's state for one recording: the playback (Replay), the project (clips, project.json), the highlights, what is
 * selected. MapEditor draws it and turns clicks and keys into calls here.
 */
final class ReplayEditor {
    final Path dir;
    final Replay replay;
    final RecProject project;
    final List<RecHighlights.Highlight> highlights;
    /** The war in the site data, or null when the site no longer has it. */
    final SiteTerritories.WarInfo war;
    final String title;
    int selClip = -1;
    long markIn = -1, markOut = -1;

    private ReplayEditor(Path dir, Replay replay, SiteTerritories.WarInfo war) {
        this.dir = dir;
        this.replay = replay;
        this.war = war;
        this.project = RecProject.load(dir);
        this.title = replay.meta.warName != null ? replay.meta.warName : replay.meta.warId != null ? replay.meta.warId : dir.getFileName().toString();
        this.highlights = findHighlights(replay, war);
    }

    /** Opens a recording folder in the editor, or null when it cannot be read. Selects its war on the map when the site has it. */
    static ReplayEditor open(Path dir) {
        RecFormat.Meta meta = RecFormat.readMeta(dir);
        if (meta == null) return null;
        SiteTerritories.WarInfo war = null;
        SiteTerritories.Data d = SiteTerritories.data();
        if (d != null && meta.warId != null) {
            List<SiteTerritories.WarInfo> wars = Relations.wars(d);
            for (int i = 0; i < wars.size(); i++) {
                SiteTerritories.WarInfo w = wars.get(i);
                if (w != null && (meta.warId.equals(w.id) || w.id == null && meta.warId.equals(w.name))) {
                    war = w;
                    MapPanels.warSel = i;
                    MapPanels.mode = MapPanels.MODE_WAR;
                    break;
                }
            }
        }
        Replay r = WarRecorder.openDir(war, dir);
        return r == null ? null : new ReplayEditor(dir, r, war);
    }

    /** The highlights, with the cities' values and capitals from the site data when the war is known. */
    private static List<RecHighlights.Highlight> findHighlights(Replay r, SiteTerritories.WarInfo war) {
        SiteTerritories.Data d = SiteTerritories.data();
        RecHighlights.Cities cities = RecHighlights.Cities.NONE;
        java.util.function.IntFunction<Set<String>> capitulated = null;
        if (d != null) {
            Map<String, Double> values = WarScore.values(d);
            Map<String, String> kinds = new HashMap<>();
            for (SiteTerritories.Locality l : d.localities) kinds.put(WarScore.key(l.name, l.country), l.kind);
            cities = new RecHighlights.Cities() {
                public double value(RecFormat.City c) {
                    return values.getOrDefault(WarScore.key(c.name, c.country), 1.0);
                }

                public boolean capital(RecFormat.City c) {
                    return WarScore.isCapital(kinds.get(WarScore.key(c.name, c.country)));
                }
            };
            if (war != null && war.sides != null && war.sides.size() >= 2) {
                capitulated = i -> {
                    try {
                        List<SiteTerritories.Captured> list = WarRecorder.toCaptured(r.frames.get(i));
                        WarFront f = WarFront.solve(d, war, WarScore.compute(d, war, list));
                        Set<String> down = new HashSet<>();
                        for (WarFront.Score s : f.scores()) if (s.capitulated()) down.add(s.nation());
                        return down;
                    } catch (RuntimeException e) {
                        return null;   // the site data changed too much since: no capitulations for this frame
                    }
                };
            }
        }
        return RecHighlights.find(r, cities, capitulated);
    }

    void save() {
        try {
            project.save(dir);
        } catch (IOException e) {
            BmcAtlasClient.LOG.warn("Could not save the editor project in {}", dir, e);
        }
    }

    RecProject.Clip selected() {
        return selClip >= 0 && selClip < project.clips.size() ? project.clips.get(selClip) : null;
    }

    void select(RecProject.Clip c) {
        selClip = project.clips.indexOf(c);
    }

    // ---- playback

    void seek(long t) {
        WarRecorder.seek(t);
    }

    void togglePlay() {
        if (replay.playing()) replay.pause();
        else {
            if (replay.time() >= replay.to()) WarRecorder.seek(0);
            replay.play(MapClock.anim());
        }
    }

    /** The next (dir > 0) or previous highlight from the moment on screen; centres the map on it when it has a place. */
    RecHighlights.Highlight jumpHighlight(int dirn) {
        long now = replay.time();
        RecHighlights.Highlight best = null;
        for (RecHighlights.Highlight h : highlights) {
            if (dirn > 0 && h.t() > now + 500 && (best == null || h.t() < best.t())) best = h;
            if (dirn < 0 && h.t() < now - 500 && (best == null || h.t() > best.t())) best = h;
        }
        if (best != null) seek(best.t());
        return best;
    }

    // ---- clips

    /** I / O: marks where the next clip starts and ends. */
    void markIn() {
        markIn = replay.time();
        if (markOut >= 0 && markOut <= markIn) markOut = -1;
    }

    void markOut() {
        markOut = replay.time();
        if (markIn >= 0 && markIn >= markOut) markIn = -1;
    }

    /** Enter / "Add clip": a clip between the marks, or 30 s from the moment on screen. */
    void addClip() {
        long a = markIn >= 0 ? markIn : replay.time(), b = markOut >= 0 ? markOut : a + 30_000;
        RecProject.Clip c = project.add(a, b, 1, replay.length());
        if (c == null) return;
        markIn = markOut = -1;
        select(c);
        save();
    }

    void deleteClip() {
        RecProject.Clip c = selected();
        if (c == null) return;
        project.clips.remove(c);
        selClip = -1;
        save();
    }

    void autoHighlights() {
        project.autoHighlights(highlights, RecProject.DEFAULT_MIN_WEIGHT, RecProject.DEFAULT_FAST, replay.length());
        selClip = -1;
        save();
    }

    /** One step faster or slower for the selected clip, in the playback speeds. */
    void clipSpeed(int steps) {
        RecProject.Clip c = selected();
        if (c == null) return;
        double[] s = RecProject.CLIP_SPEEDS;
        int i = 0;
        while (i < s.length - 1 && s[i] < c.speed) i++;
        c.speed = s[Math.max(0, Math.min(s.length - 1, i + steps))];
        save();
    }

    /** "1x", "0.5x", "120x". */
    static String speedText(double s) {
        return (s == Math.rint(s) ? String.valueOf((long) s) : String.format(Locale.ROOT, "%s", s)) + "x";
    }

    /** "m:ss" or "h:mm:ss" of a time in ms. */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Whether the recording keeps players (players.bin with samples). */
    static boolean hasPlayers(Path dir) {
        try {
            Path bin = dir.resolve("players.bin");
            return Files.exists(bin) && Files.size(bin) > 5;
        } catch (IOException e) {
            return false;
        }
    }
}
