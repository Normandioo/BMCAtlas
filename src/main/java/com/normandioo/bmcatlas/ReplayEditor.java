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
    /** The camera selected (index in project.cameras), and its keyframe selected on the timeline. */
    int selCam = -1;
    RecCamera.Key selKey;
    /** The cut selected on the timeline (edited video), or null. */
    RecProject.Cut selCut;
    private java.util.function.BiFunction<String, Long, double[]> playerAt;
    private List<double[]> places;

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

    // ---- cameras

    RecCamera camera() {
        return selCam >= 0 && selCam < project.cameras.size() ? project.cameras.get(selCam) : null;
    }

    void selectCamera(RecCamera c) {
        selCam = project.cameras.indexOf(c);
        selKey = null;
    }

    /** Where a camera frames the map at time t: {cx, cz, width} (following a player or the highlights when set). */
    double[] frame(RecCamera c, long t) {
        if (playerAt == null) {
            playerAt = RecCamera.playersOf(replay);
            places = RecCamera.placesOf(highlights);
        }
        return c.at(t, playerAt, places);
    }

    /** "New camera": framing the view now on screen, from the moment on screen. */
    /** The number of the first camera id not used yet ("cam1", "cam2"...). */
    private int freeCameraNumber() {
        int n = 1;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (RecCamera c : project.cameras) ids.add(c.id);
        while (ids.contains("cam" + n)) n++;
        return n;
    }

    RecCamera newCamera(double cx, double cz, double width) {
        int n = freeCameraNumber();
        RecCamera c = new RecCamera();
        c.id = "cam" + n;
        c.name = "Camera " + n;
        c.color = RecCamera.COLORS[(n - 1) % RecCamera.COLORS.length];
        c.setKey(replay.time(), cx, cz, width);
        project.cameras.add(c);
        selectCamera(c);
        save();
        return c;
    }

    void duplicateCamera() {
        RecCamera c = camera();
        if (c == null) return;
        int n = freeCameraNumber();
        RecCamera copy = c.copy("cam" + n, c.name + " copy");
        copy.color = RecCamera.COLORS[(n - 1) % RecCamera.COLORS.length];
        project.cameras.add(copy);
        selectCamera(copy);
        save();
    }

    void deleteCamera() {
        RecCamera c = camera();
        if (c == null) return;
        project.removeCamera(c);
        selCut = null;
        selCam = -1;
        selKey = null;
        save();
    }

    /** Tab / the arrows: the next (or previous) camera becomes the selected one. */
    void nextCamera(int dir) {
        int n = project.cameras.size();
        if (n == 0) return;
        int i = selCam < 0 ? (dir > 0 ? 0 : n - 1) : Math.floorMod(selCam + dir, n);
        selectCamera(project.cameras.get(i));
    }

    /** 1-9: the camera of that number. */
    void pickCamera(int i) {
        if (i >= 0 && i < project.cameras.size()) selectCamera(project.cameras.get(i));
    }

    /** C: from the moment on screen on, the edited video shows the selected camera. */
    void cutHere() {
        RecCamera c = camera();
        if (c == null) return;
        selCut = project.cut(replay.time(), c.id);
        selClip = -1;
        selKey = null;
        save();
    }

    void deleteCut() {
        if (selCut == null) return;
        project.cuts.remove(selCut);
        selCut = null;
        save();
    }

    /** Next "Follow" for the selected camera: none, the highlights, then the player last clicked on the map (if any). */
    void nextFollow(String pickedAccount) {
        RecCamera c = camera();
        if (c == null) return;
        if (c.follow == null || "none".equals(c.follow)) c.follow = "highlight";
        else if ("highlight".equals(c.follow) && pickedAccount != null) c.follow = "player:" + pickedAccount;
        else c.follow = "none";
        save();
    }

    /** K: a keyframe on the selected camera at the moment on screen, with the framing it has there. */
    void keyHere() {
        RecCamera c = camera();
        if (c == null) return;
        double[] f = c.keyed(replay.time());
        selKey = c.setKey(replay.time(), f[0], f[1], f[2]);
        save();
    }

    void deleteKey() {
        RecCamera c = camera();
        if (c == null || selKey == null) return;
        if (c.removeKey(selKey)) selKey = null;
        save();
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
