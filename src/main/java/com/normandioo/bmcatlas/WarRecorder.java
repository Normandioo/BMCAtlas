package com.normandioo.bmcatlas;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records the map of a war over time and plays it back. While recording is on (Settings, or the bar on the war
 * map), every change in who holds the cities of the followed war is appended, with the time, to
 * config/bmcatlas/recordings/&lt;map&gt;_&lt;war&gt;.jsonl (one small line per change). During playback the rest of the mod
 * (colors, score, fronts) sees the recorded state instead of the live one, through LiveControl.captured.
 */
final class WarRecorder {
    private WarRecorder() {}

    /** One city in a recorded frame. */
    static final class Rec {
        String name, country, state, holder, holderKey;
        Integer side;
        int x, z;
    }

    /** The state of the war at one moment. */
    static final class Frame {
        long t;
        List<Rec> cities = new ArrayList<>();
        transient List<SiteTerritories.Captured> list;
    }

    private static final Gson GSON = new Gson();
    private static String key;                       // map + war of the frames in memory
    private static List<Frame> frames = new ArrayList<>();
    private static List<SiteTerritories.Captured> lastSeen;
    private static int replay = -1;
    private static String replayKey;
    private static boolean playing;
    private static long stepAt;

    private static String keyOf(SiteTerritories.WarInfo war) {
        MapCatalog.Entry map = SiteTerritories.activeMap();
        String id = war.id != null ? war.id : war.name != null ? war.name : "war";
        return ((map == null ? "default" : map.key) + "_" + id).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path file(String k) {
        return FabricLoader.getInstance().getConfigDir().resolve("bmcatlas").resolve("recordings").resolve(k + ".jsonl");
    }

    /** Makes the frames of this war the ones in memory (reads its file the first time). */
    private static void ensure(SiteTerritories.WarInfo war) {
        String k = keyOf(war);
        if (k.equals(key)) return;
        key = k;
        frames = new ArrayList<>();
        lastSeen = null;
        Path f = file(k);
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Frame fr = GSON.fromJson(line, Frame.class);
                    if (fr != null && fr.cities != null) frames.add(fr);
                } catch (RuntimeException e) {
                    // a line cut short by a crash: skip it
                }
            }
        } catch (IOException e) {
            BmcAtlasClient.LOG.warn("Could not read the war recording {}", f, e);
        }
    }

    private static boolean same(List<Rec> a, List<Rec> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Rec x = a.get(i), y = b.get(i);
            if (!Objects.equals(x.name, y.name) || !Objects.equals(x.country, y.country) || !Objects.equals(x.state, y.state)
                    || !Objects.equals(x.holder, y.holder) || !Objects.equals(x.holderKey, y.holderKey) || !Objects.equals(x.side, y.side)) return false;
        }
        return true;
    }

    /** Called often for the followed war: adds a frame when something changed. */
    static void observe(SiteTerritories.Data d, SiteTerritories.WarInfo war) {
        if (!Prefs.get().recordWar || war == null || replay >= 0) return;
        List<SiteTerritories.Captured> cur = LiveControl.live(d, war);
        ensure(war);
        if (cur == lastSeen) return;
        lastSeen = cur;
        List<Rec> recs = new ArrayList<>();
        for (SiteTerritories.Captured c : cur) {
            Rec r = new Rec();
            r.name = c.name;
            r.country = c.country;
            r.state = c.state;
            r.holder = c.holder;
            r.holderKey = c.holderKey;
            r.side = c.liveSide;
            r.x = c.x;
            r.z = c.z;
            recs.add(r);
        }
        if (!frames.isEmpty() && same(frames.get(frames.size() - 1).cities, recs)) return;
        Frame f = new Frame();
        f.t = System.currentTimeMillis();
        f.cities = recs;
        frames.add(f);
        try {
            Path p = file(key);
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(f) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            BmcAtlasClient.LOG.warn("Could not write the war recording", e);
        }
    }

    /** How many frames this war has recorded. */
    static int count(SiteTerritories.WarInfo war) {
        if (war == null) return 0;
        ensure(war);
        return frames.size();
    }

    static boolean replaying() {
        return replay >= 0;
    }

    static boolean playing() {
        return playing;
    }

    /** Frame on screen (0-based), or -1 when live. */
    static int index() {
        return replay;
    }

    static long time() {
        return replay >= 0 && replay < frames.size() ? frames.get(replay).t : 0;
    }

    static void show(SiteTerritories.WarInfo war, int index) {
        ensure(war);
        if (frames.isEmpty()) return;
        replay = Math.max(0, Math.min(frames.size() - 1, index));
        replayKey = key;
    }

    static void play(SiteTerritories.WarInfo war) {
        ensure(war);
        if (frames.size() < 2) return;
        if (replay < 0 || replay >= frames.size() - 1) show(war, 0);
        playing = true;
        stepAt = System.currentTimeMillis();
    }

    static void pause() {
        playing = false;
    }

    /** Back to the live map. */
    static void stop() {
        replay = -1;
        playing = false;
        replayKey = null;
    }

    /** Called every frame of the map: moves the playback forward. */
    static void tick(SiteTerritories.WarInfo war) {
        if (replay < 0) return;
        if (war == null || !keyOf(war).equals(replayKey)) {     // another war on screen
            stop();
            return;
        }
        if (!playing) return;
        long now = System.currentTimeMillis();
        if (now - stepAt < 800) return;
        stepAt = now;
        if (replay >= frames.size() - 1) playing = false;
        else replay++;
    }

    /** The recorded cities of the frame on screen, or null when this war is being shown live. */
    static List<SiteTerritories.Captured> replayList(SiteTerritories.WarInfo war) {
        if (replay < 0 || war == null || replayKey == null || !replayKey.equals(keyOf(war)) || !replayKey.equals(key) || replay >= frames.size()) return null;
        Frame f = frames.get(replay);
        if (f.list == null) {
            List<SiteTerritories.Captured> out = new ArrayList<>();
            for (Rec r : f.cities) {
                SiteTerritories.Captured c = new SiteTerritories.Captured();
                c.name = r.name;
                c.country = r.country;
                c.state = r.state;
                c.holder = r.holder;
                c.holderKey = r.holderKey;
                c.liveSide = r.side;
                c.x = r.x;
                c.z = r.z;
                out.add(c);
            }
            f.list = List.copyOf(out);
        }
        return f.list;
    }
}
