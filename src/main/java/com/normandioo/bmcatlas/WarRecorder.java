package com.normandioo.bmcatlas;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Records the followed war on this computer and plays it back (format 2, see RecFormat): who holds each city, where every
 * player on the map is (dynmap's readings, plus the exact positions of the players near you four times a second), their faces,
 * and the site data in use. A recording covers only the time this player was on the server: coming back to the same war
 * continues the same folder, and the time away shows as a gap (meta.json "intervals").
 *
 * It records while "Record the war map" is on, or by itself during a live war when "Record live wars automatically" is on. It
 * keeps recording with the map closed (BmcAtlasClient calls tick every game tick). Files are written on a thread of their own,
 * every 5 seconds. Nothing is ever sent anywhere: recordings stay on this computer.
 *
 * While a recording is played back (the bar on the war map), the rest of the mod (colours, score, fronts) sees the recorded
 * cities instead of the live ones, through LiveControl.captured, and nothing is recorded.
 */
final class WarRecorder {
    private WarRecorder() {}

    private static final long FLUSH_MS = 5_000, META_MS = 30_000, FACE_RETRY_MS = 10_000;
    private static final int NEARBY_EVERY_TICKS = 5;   // 4 times a second

    /** A player seen on the map: account, display name, position in blocks. */
    record Seen(String account, String name, double x, double z) {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bmcatlas-recorder");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Session s = WarRecorder.session;
            if (s != null) s.flush(true);
            IO.shutdown();
            try {
                IO.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // leaving anyway
            }
        }, "bmcatlas-recorder-flush"));
    }

    private static void exec(Runnable task) {
        try {
            IO.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();   // the game is closing: do it here
        }
    }

    static Path recordingsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("bmcatlas").resolve("recordings");
    }

    private static String keyOf(SiteTerritories.WarInfo war) {
        MapCatalog.Entry map = SiteTerritories.activeMap();
        return RecFormat.safe(map == null ? "default" : map.key) + "|" + RecFormat.safe(war.id != null ? war.id : war.name);
    }

    // ------------------------------------------------------------------ recording

    /** The recording being written, or null. */
    private static Session session;

    /** One recording folder being written. Touched on the game thread; the files are written on IO. */
    private static final class Session {
        final String key;
        final Path dir;
        final RecFormat.Meta meta;
        final RecTrack.Writer writer;
        final Map<String, Integer> indexOf = new HashMap<>();
        final Map<Integer, RecFormat.PlayerInfo> players;
        final Set<Integer> lastDynmap = new HashSet<>();
        final Map<String, Long> facesPending = new HashMap<>();
        final List<RecFormat.Frame> frames;
        List<SiteTerritories.Captured> lastSeen;
        int territoriesVersion = -1;
        long lastFlush, lastMeta;
        private final StringBuilder citiesOut = new StringBuilder();
        private final ByteArrayOutputStream playersOut = new ByteArrayOutputStream();
        private boolean playersDirty;

        Session(String key, Path dir, RecFormat.Meta meta, RecTrack.Writer writer, Map<Integer, RecFormat.PlayerInfo> players, List<RecFormat.Frame> frames) {
            this.key = key;
            this.dir = dir;
            this.meta = meta;
            this.writer = writer;
            this.players = players;
            this.frames = frames;
            for (var e : players.entrySet()) if (e.getValue().account != null) indexOf.put(e.getValue().account.toLowerCase(Locale.ROOT), e.getKey());
        }

        long since(long now) {
            return Math.max(0, now - meta.start);
        }

        synchronized void addCities(RecFormat.Frame f) {
            citiesOut.append(RecFormat.line(f)).append('\n');
        }

        synchronized void addBlock(byte[] block) {
            playersOut.writeBytes(block);
        }

        /** Hands what piled up to the writer thread (or writes it here, when closing). */
        void flush(boolean now) {
            String cities;
            byte[] block;
            synchronized (this) {
                cities = citiesOut.toString();
                citiesOut.setLength(0);
                block = playersOut.toByteArray();
                playersOut.reset();
            }
            long t = System.currentTimeMillis();
            meta.end = Math.max(meta.end, t);
            if (!meta.intervals.isEmpty()) meta.intervals.get(meta.intervals.size() - 1)[1] = meta.end;
            RecFormat.Meta snapshot = copy(meta);
            Map<Integer, RecFormat.PlayerInfo> playersCopy = playersDirty ? copyPlayers(players) : null;
            playersDirty = false;
            Runnable write = () -> {
                try {
                    Files.createDirectories(dir);
                    if (!cities.isEmpty()) Files.writeString(dir.resolve("cities.jsonl"), cities, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (block.length > 0) {
                        Path bin = dir.resolve("players.bin");
                        if (!Files.exists(bin)) Files.write(bin, RecTrack.header());
                        Files.write(bin, block, StandardOpenOption.APPEND);
                    }
                    if (playersCopy != null) RecFormat.writePlayers(dir, playersCopy);
                    RecFormat.writeMeta(dir, snapshot);
                } catch (IOException | RuntimeException e) {
                    BmcAtlasClient.LOG.warn("Could not write the war recording in {}", dir, e);
                }
            };
            if (now) write.run(); else exec(write);
            lastFlush = t;
            lastMeta = t;
        }
    }

    private static Map<Integer, RecFormat.PlayerInfo> copyPlayers(Map<Integer, RecFormat.PlayerInfo> players) {
        Map<Integer, RecFormat.PlayerInfo> out = new java.util.LinkedHashMap<>();
        for (var e : players.entrySet()) {
            RecFormat.PlayerInfo p = e.getValue(), c = new RecFormat.PlayerInfo();
            c.account = p.account;
            c.name = p.name;
            c.country = p.country;
            c.first = p.first;
            c.last = p.last;
            out.put(e.getKey(), c);
        }
        return out;
    }

    private static RecFormat.Meta copy(RecFormat.Meta m) {
        RecFormat.Meta c = new RecFormat.Meta();
        c.mod = m.mod;
        c.map = m.map;
        c.warId = m.warId;
        c.warName = m.warName;
        c.sides = m.sides;
        c.start = m.start;
        c.end = m.end;
        c.periodMs = m.periodMs;
        c.dynmapWorld = m.dynmapWorld;
        c.serverSync = m.serverSync;
        c.importedFrom = m.importedFrom;
        for (long[] i : m.intervals) c.intervals.add(i.clone());
        return c;
    }

    /** Whether this war should be recorded now: the switch, or a live war with the automatic option. */
    private static boolean wanted(SiteTerritories.WarInfo war) {
        Prefs.Data p = Prefs.get();
        if (war == null || keyOf(war).equals(stoppedByHand)) return false;   // stopped with the button: not even the automatic option restarts it
        return p.recordWar || p.recordLiveWars && war.live && WarStatus.running(war);
    }

    /** Called often (every frame of the map or minimap, and every tick while recording) for the followed war. */
    static void observe(SiteTerritories.Data d, SiteTerritories.WarInfo war) {
        if (replay != null) return;
        if (war == null || !wanted(war)) {
            stopRecording();
            return;
        }
        Session s = sessionFor(d, war);
        if (s == null) return;
        long now = System.currentTimeMillis();
        List<SiteTerritories.Captured> cur = LiveControl.live(d, war);
        if (cur != s.lastSeen) {
            s.lastSeen = cur;
            RecFormat.Frame f = frameOf(cur, s.since(now));
            if (s.frames.isEmpty() || !same(s.frames.get(s.frames.size() - 1).cities, f.cities)) {
                s.frames.add(f);
                s.addCities(f);
            }
        }
        if (SiteTerritories.version() != s.territoriesVersion) {
            s.territoriesVersion = SiteTerritories.version();
            copyTerritories(s, s.since(now));
        }
        if (now - s.lastFlush >= FLUSH_MS) s.flush(false);
    }

    private static RecFormat.Frame frameOf(List<SiteTerritories.Captured> cur, long t) {
        RecFormat.Frame f = new RecFormat.Frame();
        f.t = t;
        for (SiteTerritories.Captured c : cur) {
            RecFormat.City r = new RecFormat.City();
            r.name = c.name;
            r.country = c.country;
            r.state = c.state;
            r.holder = c.holder;
            r.holderKey = c.holderKey;
            r.side = c.liveSide;
            r.x = c.x;
            r.z = c.z;
            f.cities.add(r);
        }
        return f;
    }

    private static boolean same(List<RecFormat.City> a, List<RecFormat.City> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            RecFormat.City x = a.get(i), y = b.get(i);
            if (!Objects.equals(x.name, y.name) || !Objects.equals(x.country, y.country) || !Objects.equals(x.state, y.state)
                    || !Objects.equals(x.holder, y.holder) || !Objects.equals(x.holderKey, y.holderKey) || !Objects.equals(x.side, y.side)) return false;
        }
        return true;
    }

    /** The recording of this war: the one being written, else the newest folder of this map and war continued, else a new one. */
    private static Session sessionFor(SiteTerritories.Data d, SiteTerritories.WarInfo war) {
        String key = keyOf(war);
        if (session != null && session.key.equals(key)) return session;
        stopRecording();
        importOldOnce();
        long now = System.currentTimeMillis();
        MapCatalog.Entry map = SiteTerritories.activeMap();
        String mapKey = map == null ? "default" : map.key;
        String warId = war.id != null ? war.id : war.name;
        Path recordings = recordingsDir();
        Path dir = RecFormat.latest(recordings, mapKey, warId);
        RecFormat.Meta meta = dir == null ? null : RecFormat.readMeta(dir);
        RecTrack.Writer writer = new RecTrack.Writer();
        Map<Integer, RecFormat.PlayerInfo> players = new java.util.LinkedHashMap<>();
        List<RecFormat.Frame> frames = new ArrayList<>();
        if (meta != null && meta.format == RecFormat.FORMAT) {   // the same war again: go on in the same folder
            frames = RecFormat.readCities(dir.resolve("cities.jsonl"));
            players = RecFormat.readPlayers(dir);
            try {
                Path bin = dir.resolve("players.bin");
                if (Files.exists(bin)) {
                    List<RecTrack.Block> blocks = RecTrack.read(Files.readAllBytes(bin));
                    if (blocks != null) {
                        writer = RecTrack.Writer.after(blocks);
                        truncateTo(bin);   // a block cut short by a crash would spoil what is written after it
                    }
                }
            } catch (IOException e) {
                BmcAtlasClient.LOG.warn("Could not read {}", dir, e);
            }
            meta.intervals.add(new long[]{now, now});
        } else {
            meta = new RecFormat.Meta();
            meta.mod = UpdateCheck.installedVersion();
            meta.map = mapKey;
            meta.warId = warId;
            meta.warName = war.name;
            meta.start = now;
            meta.end = now;
            meta.intervals.add(new long[]{now, now});
            if (war.sides != null) for (SiteTerritories.Side side : war.sides) {
                RecFormat.SideInfo si = new RecFormat.SideInfo();
                si.name = side.name;
                si.color = side.color;
                if (d != null) for (int c : Relations.countriesOf(d, side)) si.countries.add(d.ids[c]);
                meta.sides.add(si);
            }
            dir = recordings.resolve(RecFormat.folderName(now, mapKey, warId));
        }
        meta.periodMs = OnlinePlayers.interval();
        meta.dynmapWorld = SiteTerritories.dynmapWorld();
        meta.serverSync = meta.serverSync || ServerSync.active();
        session = new Session(key, dir, meta, writer, players, frames);
        session.playersDirty = true;
        session.flush(false);
        BmcAtlasClient.LOG.info("Recording the war {} in {}", warId, dir);
        return session;
    }

    /** Drops a block cut short at the end of players.bin, so new blocks follow whole ones. */
    private static void truncateTo(Path bin) throws IOException {
        byte[] all = Files.readAllBytes(bin);
        int whole = RecTrack.wholeLength(all);
        if (whole < all.length) {
            byte[] keep = java.util.Arrays.copyOf(all, whole);
            Files.write(bin, keep);
        }
    }

    /** Whether recording may be on at all (the switch or the automatic option): then the war is kept up to date with the map closed. */
    static boolean wantsTicks() {
        Prefs.Data p = Prefs.get();
        return p.recordWar || p.recordLiveWars;
    }

    static boolean recording() {
        return session != null;
    }

    /** A war whose recording was stopped with the button while "Record live wars automatically" is on (so it does not restart). */
    private static String stoppedByHand;

    /** Whether this war is being recorded right now. */
    static boolean recording(SiteTerritories.WarInfo war) {
        Session s = session;
        return s != null && war != null && s.key.equals(keyOf(war));
    }

    /** How long the recording of this sitting has been going (ms), or 0. */
    static long sittingMs() {
        Session s = session;
        if (s == null || s.meta.intervals.isEmpty()) return 0;
        return Math.max(0, System.currentTimeMillis() - s.meta.intervals.get(s.meta.intervals.size() - 1)[0]);
    }

    /** The Start / Stop recording button. */
    static void startStop(SiteTerritories.WarInfo war) {
        Prefs.Data p = Prefs.get();
        if (recording(war)) {
            p.recordWar = false;
            if (p.recordLiveWars) stoppedByHand = keyOf(war);
            stopRecording();
        } else {
            stoppedByHand = null;
            p.recordWar = true;
        }
        Prefs.save();
    }

    /** Ends the recording being written (switched off, the war ended, the player left the server). */
    static void stopRecording() {
        Session s = session;
        if (s == null) return;
        session = null;
        s.flush(false);
    }

    /** Leaving the server. */
    static void onDisconnect() {
        stopRecording();
        stop();
    }

    // ------------------------------------------------------------------ players

    /** What OnlinePlayers read (dynmap, or the players near you when dynmap does not answer): everyone on the map right now. */
    static void dynmapPlayers(List<Seen> seen, long now) {
        Session s = session;
        if (s == null || replay != null) return;
        List<RecTrack.Sample> samples = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (Seen p : seen) {
            int i = indexOf(s, p, now);
            present.add(i);
            samples.add(RecTrack.Sample.at(i, p.x(), p.z()));
        }
        for (int i : s.lastDynmap) if (!present.contains(i)) samples.add(RecTrack.Sample.left(i));   // gone from the map
        s.lastDynmap.clear();
        s.lastDynmap.addAll(present);
        if (!samples.isEmpty()) s.addBlock(s.writer.block(s.since(now), samples));
    }

    /** Every game tick: the exact positions of the players near you (4 times a second), faces still to copy, and the writing. */
    static void tick(Minecraft mc) {
        Session s = session;
        if (s == null) return;
        if (!wantsTicks() || mc.level == null || mc.player == null) {
            stopRecording();
            return;
        }
        if (replay != null) return;   // nothing is recorded while a recording is played back
        long now = System.currentTimeMillis();
        if (++ticks % NEARBY_EVERY_TICKS == 0) {
            List<RecTrack.Sample> samples = new ArrayList<>();
            for (AbstractClientPlayer p : mc.level.players()) {
                String name = p.getName().getString();
                samples.add(RecTrack.Sample.at(indexOf(s, new Seen(name, name, p.getX(), p.getZ()), now), p.getX(), p.getZ()));
            }
            if (!samples.isEmpty()) s.addBlock(s.writer.block(s.since(now), samples));
        }
        if (!s.facesPending.isEmpty() && ticks % 20 == 0) copyFaces(s, now);
        if (now - s.lastFlush >= FLUSH_MS) s.flush(false);
    }

    private static int ticks;

    /** The player's index in the recording's table (added, with their country and face, the first time they are seen). */
    private static int indexOf(Session s, Seen p, long now) {
        String low = p.account().toLowerCase(Locale.ROOT);
        Integer i = s.indexOf.get(low);
        if (i == null) {
            i = s.players.size();
            while (s.players.containsKey(i)) i++;
            RecFormat.PlayerInfo info = new RecFormat.PlayerInfo();
            info.account = p.account();
            info.name = p.name();
            info.country = countryOf(low);
            info.first = now;
            s.players.put(i, info);
            s.indexOf.put(low, i);
            s.facesPending.put(p.account(), 0L);
        }
        RecFormat.PlayerInfo info = s.players.get(i);
        if (info != null) {
            if (info.first == 0) info.first = now;
            info.last = now;
        }
        s.playersDirty = true;
        return i;
    }

    private static String countryOf(String accountLower) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) return null;
        String site = d.ignToSite.get(accountLower);
        Integer ci = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : accountLower);
        return ci == null ? null : d.ids[ci];
    }

    /** Copies the faces already downloaded (the dynmap's 32x32 heads) into the recording; the others are tried again later. */
    private static void copyFaces(Session s, long now) {
        String dyn = SiteTerritories.dynmapUrl();
        if (dyn == null) {
            s.facesPending.clear();
            return;
        }
        for (var it = s.facesPending.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue() < FACE_RETRY_MS) continue;
            String url = dyn + "tiles/faces/32x32/" + e.getKey() + ".png";
            Path cached = RemoteImages.cachedOnDisk(url);
            if (cached == null) {
                RemoteImages.icon(url, 16);   // asks for it, so it is on disk next time
                e.setValue(now);
                continue;
            }
            Path to = s.dir.resolve("faces").resolve(RecFormat.safe(e.getKey()) + ".png");
            exec(() -> {
                try {
                    Files.createDirectories(to.getParent());
                    Files.copy(cached, to, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    BmcAtlasClient.LOG.debug("Could not copy a face into the recording: {}", ex.toString());
                }
            });
            it.remove();
        }
    }

    /** Keeps the site data in use (compressed), so the recording can be shown with the borders of its time. */
    private static void copyTerritories(Session s, long t) {
        Path from = SiteTerritories.cachePath();
        if (from == null) return;
        Path to = s.dir.resolve("territories-" + t + ".json.gz");
        exec(() -> {
            try {
                if (!Files.exists(from)) return;
                Files.createDirectories(to.getParent());
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(to))) {
                    Files.copy(from, out);
                }
            } catch (IOException e) {
                BmcAtlasClient.LOG.debug("Could not keep the site data in the recording: {}", e.toString());
            }
        });
    }

    // ------------------------------------------------------------------ old recordings

    private static boolean imported;

    /** Once per game start: the old recordings (config/bmcatlas/recordings/*.jsonl) become recording folders without players. */
    private static void importOldOnce() {
        if (imported) return;
        imported = true;
        Path old = FabricLoader.getInstance().getConfigDir().resolve("bmcatlas").resolve("recordings");
        if (!Files.isDirectory(old)) return;
        try (var files = Files.newDirectoryStream(old, "*.jsonl")) {
            for (Path f : files) {
                try {
                    Path dir = RecFormat.importOld(f, recordingsDir());
                    if (dir != null) BmcAtlasClient.LOG.info("Imported the old war recording {} into {}", f.getFileName(), dir);
                } catch (IOException | RuntimeException e) {
                    BmcAtlasClient.LOG.warn("Could not import the old war recording {}", f, e);
                }
            }
        } catch (IOException e) {
            BmcAtlasClient.LOG.warn("Could not list {}", old, e);
        }
    }

    // ------------------------------------------------------------------ playback (the bar on the war map)

    private static String loadedKey;
    private static List<RecFormat.Frame> loaded = new ArrayList<>();
    /** The recording being played back, or null when the map is live. */
    private static Replay replay;
    private static String replayKey;
    /** Set when the moment on screen jumped (seek, back in time, back to live): the next colour change has no capture wave. */
    private static boolean jumped;
    private static int lastWaveIndex = -1;
    private static final Map<RecFormat.Frame, List<SiteTerritories.Captured>> LISTS = new java.util.IdentityHashMap<>();

    /** The frames of this war, to count them: the recording being written, else its newest folder. */
    private static List<RecFormat.Frame> frames(SiteTerritories.WarInfo war) {
        String key = keyOf(war);
        Session s = session;
        if (s != null && s.key.equals(key)) return s.frames;
        if (!key.equals(loadedKey)) {
            loadedKey = key;
            importOldOnce();
            Path dir = latestDir(war);
            loaded = dir == null ? new ArrayList<>() : RecFormat.readCities(dir.resolve("cities.jsonl"));
        }
        return loaded;
    }

    private static Path latestDir(SiteTerritories.WarInfo war) {
        MapCatalog.Entry map = SiteTerritories.activeMap();
        return RecFormat.latest(recordingsDir(), map == null ? "default" : map.key, war.id != null ? war.id : war.name);
    }

    /** Opens the newest recording of this war for playback (the one being written is saved first). */
    private static boolean open(SiteTerritories.WarInfo war) {
        String key = keyOf(war);
        if (replay != null && key.equals(replayKey)) return true;
        Session s = session;
        Path dir;
        if (s != null && s.key.equals(key)) {
            s.flush(true);
            dir = s.dir;
        } else {
            importOldOnce();
            dir = latestDir(war);
        }
        Replay r = dir == null ? null : Replay.open(dir);
        if (r == null || r.length() <= 0 && r.frames.isEmpty()) return false;
        replay = r;
        replayKey = key;
        LISTS.clear();
        jumped = true;
        MapClock.showData(r.absTime());
        return true;
    }

    /**
     * Opens a given recording folder for the editor (the whole folder, every session). war is the war it belongs to in the site
     * data, or null when the site no longer has it (then the map shows the players but not the colours). Null when it cannot be read.
     */
    static Replay openDir(SiteTerritories.WarInfo war, Path dir) {
        Session s = session;
        if (s != null && s.dir.equals(dir)) s.flush(true);
        importOldOnce();
        Replay r = Replay.open(dir);
        if (r == null) return null;
        r.useWhole();
        replay = r;
        replayKey = war != null ? keyOf(war) : "editor|" + dir;
        LISTS.clear();
        jumped = true;
        MapClock.showData(r.absTime());
        return r;
    }

    /** Moves the playback forward without checking which war is on screen (the editor owns it). */
    static void advance() {
        if (replay == null) return;
        replay.advance(MapClock.anim());
        MapClock.showData(replay.absTime());
    }

    /** A recorded cities frame as the list the rest of the mod reads (LiveControl.captured). */
    static List<SiteTerritories.Captured> toCaptured(RecFormat.Frame fr) {
        List<SiteTerritories.Captured> out = new ArrayList<>();
        for (RecFormat.City r : fr.cities) {
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
        return List.copyOf(out);
    }

    /** How many moments (changes of who holds the cities) this war has recorded. */
    static int count(SiteTerritories.WarInfo war) {
        return war == null ? 0 : frames(war).size();
    }

    static boolean replaying() {
        return replay != null;
    }

    static boolean playing() {
        return replay != null && replay.playing();
    }

    /** The recording on screen, or null when live. */
    static Replay current() {
        return replay;
    }

    /** The cities frame on screen (0-based), or -1 when live. */
    static int index() {
        return replay == null ? -1 : replay.frameIndex();
    }

    /** The moment on screen (ms since 1970), or 0 when live. */
    static long time() {
        return replay == null ? 0 : replay.absTime();
    }

    /** Shows the moment the cities frame i started (paused). */
    static void show(SiteTerritories.WarInfo war, int i) {
        if (!open(war)) return;
        replay.pause();
        if (i <= 0) seek(replay.from());   // the start of the session played back
        else if (!replay.frames.isEmpty()) seek(replay.frames.get(Math.min(replay.frames.size() - 1, i)).t);
    }

    /** Goes to a moment of the recording (ms since its start). A jump: no capture waves for it. */
    static void seek(long t) {
        if (replay == null) return;
        int before = replay.frameIndex();
        replay.seek(t);
        if (replay.frameIndex() != before) jumped = true;
        MapClock.showData(replay.absTime());
    }

    /** The video export's step: goes to a moment; jump (a clip starting) means no capture wave for that change. */
    static void exportSeek(long t, boolean jump) {
        if (replay == null) return;
        int before = replay.frameIndex();
        replay.seek(t);
        if (jump && replay.frameIndex() != before) jumped = true;
        MapClock.showData(replay.absTime());
    }

    /** Goes to a fraction (0-1) of the recording of this war. */
    static void seekFraction(SiteTerritories.WarInfo war, double f) {
        if (!open(war)) return;
        seek(replay.from() + Math.round((replay.to() - replay.from()) * Math.max(0, Math.min(1, f))));
    }

    static void play(SiteTerritories.WarInfo war) {
        if (!open(war)) return;
        if (replay.time() >= replay.to()) seek(replay.from());
        replay.play(MapClock.anim());
    }

    static void pause() {
        if (replay != null) replay.pause();
    }

    /** One playback speed faster (steps > 0) or slower (steps < 0). */
    static void changeSpeed(int steps) {
        if (replay != null) replay.changeSpeed(steps);
    }

    static void nextChange() {
        if (replay != null) seek(replay.nextChange());
    }

    static void previousChange() {
        if (replay != null) seek(replay.previousChange());
    }

    /** Back to the live map. */
    static void stop() {
        if (replay != null) jumped = true;
        replay = null;
        replayKey = null;
        LISTS.clear();
        MapClock.live();
    }

    /** Called every frame of the map: moves the playback forward. */
    static void tick(SiteTerritories.WarInfo war) {
        if (replay == null) return;
        if (war == null || !keyOf(war).equals(replayKey)) {   // another war on screen
            stop();
            return;
        }
        replay.advance(MapClock.anim());
        MapClock.showData(replay.absTime());
    }

    /**
     * Whether a change of colours on the map now may show a capture wave: not right after a jump, not when going back in time,
     * not when coming back to live. Called by MapScreen when the colours change.
     */
    static boolean waveAllowed() {
        int i = index();
        boolean ok = !jumped && (replay == null || i >= lastWaveIndex);
        jumped = false;
        lastWaveIndex = i;
        return ok;
    }

    /** The recorded cities of the moment on screen, or null when this war is being shown live. */
    static List<SiteTerritories.Captured> replayList(SiteTerritories.WarInfo war) {
        if (replay == null || war == null || !keyOf(war).equals(replayKey)) return null;
        int i = replay.frameIndex();
        if (i < 0) return List.of();
        RecFormat.Frame frame = replay.frames.get(i);
        return LISTS.computeIfAbsent(frame, WarRecorder::toCaptured);
    }
}
