package com.normandioo.bmcatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files of a war recording (format 2): one folder per recording, <game folder>/bmcatlas/recordings/<yyyyMMdd-HHmmss>_<map>_<war>/
 *   meta.json                 what was recorded (map, war, sides, times, sampling period, recorded intervals)
 *   cities.jsonl              one line each time who holds a city changed: {t, cities:[{name,country,state,holder,holderKey,side,x,z}]},
 *                             t in ms since meta.start
 *   players.bin               the players' track (RecTrack)
 *   players.json              player index -> {account, name, country, first, last}
 *   faces/<account>.png       each player's face, as it was when recorded
 *   territories-<t>.json.gz   the site data in use, at the start and each time it changed
 * The old recordings (config/bmcatlas/recordings/<map>_<war>.jsonl, cities only) are imported into this format.
 * No Minecraft classes here.
 */
final class RecFormat {
    private RecFormat() {}

    static final int FORMAT = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson LINE = new Gson();
    private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** meta.json */
    static final class Meta {
        int format = FORMAT;
        String mod;
        String map;
        String warId;
        String warName;
        List<SideInfo> sides = new ArrayList<>();
        /** First and last moment recorded (ms since 1970). */
        long start, end;
        /** How often players were sampled (ms): the dynmap's reading period. */
        long periodMs;
        String dynmapWorld;
        /** Whether the server's war plugin was telling who holds what (ServerSync) when the recording started. */
        boolean serverSync;
        /** The stretches the player was there for, [from, to] (ms): the gaps between them were not seen. */
        List<long[]> intervals = new ArrayList<>();
        /** For a recording imported from the old format: the old file's name. */
        String importedFrom;
    }

    static final class SideInfo {
        String name, color;
        List<String> countries = new ArrayList<>();
    }

    /** One city in a frame of cities.jsonl (the same fields as the old format). */
    static final class City {
        String name, country, state, holder, holderKey;
        Integer side;
        int x, z;
    }

    /** One line of cities.jsonl: the cities held by someone other than their owner (or contested), from time t on. */
    static final class Frame {
        long t;
        List<City> cities = new ArrayList<>();
    }

    /** One entry of players.json. */
    static final class PlayerInfo {
        String account, name, country;
        long first, last;
    }

    /** The folder name of a recording that starts now: <yyyyMMdd-HHmmss>_<map>_<war>, safe for any file system. */
    static String folderName(long startMs, String map, String warId) {
        return (FOLDER_TIME.format(Instant.ofEpochMilli(startMs)) + "_" + safe(map) + "_" + safe(warId));
    }

    static String safe(String s) {
        return (s == null || s.isBlank() ? "default" : s).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ---- meta.json

    static Meta readMeta(Path dir) {
        Path f = dir.resolve("meta.json");
        try {
            if (!Files.exists(f)) return null;
            Meta m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Meta.class);
            if (m == null) return null;
            if (m.intervals == null) m.intervals = new ArrayList<>();
            if (m.sides == null) m.sides = new ArrayList<>();
            return m;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void writeMeta(Path dir, Meta m) throws IOException {
        writeAtomic(dir.resolve("meta.json"), GSON.toJson(m));
    }

    // ---- cities.jsonl

    static String line(Frame f) {
        return LINE.toJson(f);
    }

    /** Every whole line of a cities.jsonl, in order; a line cut short by a crash is skipped. */
    static List<Frame> readCities(Path file) {
        List<Frame> out = new ArrayList<>();
        try {
            if (!Files.exists(file)) return out;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Frame f = LINE.fromJson(line, Frame.class);
                    if (f != null && f.cities != null) out.add(f);
                } catch (RuntimeException e) {
                    // a line cut short: skip it
                }
            }
        } catch (IOException e) {
            BmcAtlasClient.LOG.warn("Could not read {}", file, e);
        }
        return out;
    }

    // ---- players.json

    static Map<Integer, PlayerInfo> readPlayers(Path dir) {
        Path f = dir.resolve("players.json");
        try {
            if (!Files.exists(f)) return new LinkedHashMap<>();
            Map<Integer, PlayerInfo> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<Integer, PlayerInfo>>() {}.getType());
            return m == null ? new LinkedHashMap<>() : m;
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    static void writePlayers(Path dir, Map<Integer, PlayerInfo> players) throws IOException {
        writeAtomic(dir.resolve("players.json"), GSON.toJson(players));
    }

    // ---- the recordings folder

    /** Every recording folder (with a meta.json) in the recordings folder, newest name first. */
    static List<Path> list(Path recordings) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(recordings)) return out;
        try (var dirs = Files.newDirectoryStream(recordings, Files::isDirectory)) {
            for (Path d : dirs) if (Files.exists(d.resolve("meta.json"))) out.add(d);
        } catch (IOException e) {
            BmcAtlasClient.LOG.warn("Could not list {}", recordings, e);
        }
        out.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        return out;
    }

    /** The newest recording of this map and war, or null. */
    static Path latest(Path recordings, String map, String warId) {
        for (Path d : list(recordings)) {
            Meta m = readMeta(d);
            if (m != null && safe(map).equals(safe(m.map)) && safe(warId).equals(safe(m.warId))) return d;
        }
        return null;
    }

    /**
     * Imports an old recording (<map>_<war>.jsonl, cities only) as a new folder without players. The old file is kept, renamed to
     * .jsonl.imported, so it is never imported twice and nothing is lost. Returns the new folder, or null when the file has no frames.
     */
    static Path importOld(Path oldFile, Path recordings) throws IOException {
        List<Frame> frames = readCities(oldFile);
        if (frames.isEmpty()) return null;
        String base = oldFile.getFileName().toString().replaceFirst("\\.jsonl$", "");
        int cut = base.indexOf('_');
        Meta m = new Meta();
        m.map = cut > 0 ? base.substring(0, cut) : "default";
        m.warId = cut > 0 ? base.substring(cut + 1) : base;
        m.warName = m.warId;
        m.start = frames.get(0).t;
        m.end = frames.get(frames.size() - 1).t;
        m.intervals.add(new long[]{m.start, m.end});
        m.importedFrom = oldFile.getFileName().toString();
        Path dir = recordings.resolve(folderName(m.start, m.map, m.warId));
        Files.createDirectories(dir);
        StringBuilder lines = new StringBuilder();
        for (Frame f : frames) {   // the old times were absolute; here they count from the start
            f.t -= m.start;
            lines.append(line(f)).append('\n');
        }
        writeAtomic(dir.resolve("cities.jsonl"), lines.toString());
        writeMeta(dir, m);
        Files.move(oldFile, oldFile.resolveSibling(oldFile.getFileName() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
        return dir;
    }

    private static void writeAtomic(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
