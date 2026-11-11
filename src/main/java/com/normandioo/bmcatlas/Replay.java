package com.normandioo.bmcatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A recording opened for playback: its cities, its players' track, and which moment is on screen. No Minecraft classes here.
 *
 * Times inside are ms since the recording's start (meta.start), like in the files. The moment on screen moves with the animation
 * clock (MapClock.anim) times the speed, so playback keeps its pace however fast frames come.
 */
final class Replay {
    /** The playback speeds offered, in order. */
    static final int[] SPEEDS = {1, 10, 60, 300};

    /** One player on screen at the moment shown. */
    record Shown(int index, String account, String name, double x, double z) {}

    final Path dir;
    final RecFormat.Meta meta;
    final List<RecFormat.Frame> frames;
    final Map<Integer, RecFormat.PlayerInfo> players;
    final RecTrack.Track track;
    private final long length;
    private long t;
    private int speed = 1;
    private boolean playing;
    private long lastAnim;

    Replay(Path dir, RecFormat.Meta meta, List<RecFormat.Frame> frames, Map<Integer, RecFormat.PlayerInfo> players, List<RecTrack.Block> blocks) {
        this.dir = dir;
        this.meta = meta;
        this.frames = frames;
        this.players = players;
        this.track = new RecTrack.Track(blocks, meta.periodMs > 0 ? meta.periodMs : 3000);
        long len = Math.max(0, meta.end - meta.start);
        if (!frames.isEmpty()) len = Math.max(len, frames.get(frames.size() - 1).t);
        if (!blocks.isEmpty()) len = Math.max(len, blocks.get(blocks.size() - 1).t());
        this.length = len;
    }

    /** Reads a recording folder; null when it has no meta.json. */
    static Replay open(Path dir) {
        RecFormat.Meta meta = RecFormat.readMeta(dir);
        if (meta == null) return null;
        List<RecTrack.Block> blocks = List.of();
        try {
            Path bin = dir.resolve("players.bin");
            if (Files.exists(bin)) {
                List<RecTrack.Block> read = RecTrack.read(Files.readAllBytes(bin));
                if (read != null) blocks = read;
            }
        } catch (IOException e) {
            // no players then
        }
        return new Replay(dir, meta, RecFormat.readCities(dir.resolve("cities.jsonl")), RecFormat.readPlayers(dir), blocks);
    }

    /** How long the recording is (ms). */
    long length() {
        return length;
    }

    /** The moment on screen (ms since the start). */
    long time() {
        return t;
    }

    /** The moment on screen (ms since 1970). */
    long absTime() {
        return meta.start + t;
    }

    int speed() {
        return speed;
    }

    boolean playing() {
        return playing;
    }

    /** Goes to a moment (clamped to the recording). */
    void seek(long to) {
        t = Math.max(0, Math.min(length, to));
    }

    void play(long animNow) {
        if (t >= length) t = 0;
        playing = true;
        lastAnim = animNow;
    }

    void pause() {
        playing = false;
    }

    /** The next speed of SPEEDS (back to the first after the last). */
    void nextSpeed() {
        int i = 0;
        while (i < SPEEDS.length && SPEEDS[i] != speed) i++;
        speed = SPEEDS[(i + 1) % SPEEDS.length];
    }

    void setSpeed(int s) {
        speed = Math.max(1, s);
    }

    /** Moves the moment on screen by the animation time passed since the last call, times the speed; stops at the end. */
    void advance(long animNow) {
        if (!playing) return;
        long dt = Math.max(0, Math.min(1000, animNow - lastAnim));   // a long pause (game frozen) does not jump ahead
        lastAnim = animNow;
        t = Math.min(length, t + dt * speed);
        if (t >= length) playing = false;
    }

    /** The cities frame in force at the moment on screen: the last one at or before it (the first one before it starts). */
    int frameIndex() {
        return frameAt(t);
    }

    int frameAt(long time) {
        if (frames.isEmpty()) return -1;
        int lo = 0, hi = frames.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (frames.get(mid).t <= time) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Where the cities change next after the moment on screen, or the end. */
    long nextChange() {
        for (RecFormat.Frame f : frames) if (f.t > t) return f.t;
        return length;
    }

    /** Where the cities changed last before the moment on screen, or the start. */
    long previousChange() {
        int at = frameIndex();
        if (at < 0) return 0;
        if (frames.get(at).t < t - 1000) return frames.get(at).t;   // well into a change: back to its start
        return at > 0 ? frames.get(at - 1).t : 0;
    }

    /** Whether the moment on screen falls in a stretch the player was away (not recorded). */
    boolean away(long time) {
        if (meta.intervals.isEmpty()) return false;
        long abs = meta.start + time;
        for (long[] i : meta.intervals) if (abs >= i[0] && abs <= i[1]) return false;
        return true;
    }

    /** The players on the map at the moment on screen. */
    List<Shown> shown() {
        List<Shown> out = new ArrayList<>();
        for (int i : track.players()) {
            double[] p = track.at(i, t);
            if (p == null) continue;
            RecFormat.PlayerInfo info = players.get(i);
            String account = info == null || info.account == null ? "#" + i : info.account;
            out.add(new Shown(i, account, info == null || info.name == null ? account : info.name, p[0], p[1]));
        }
        return out;
    }

    /** The face kept with the recording for this player, or null. */
    Path face(String account) {
        Path f = dir.resolve("faces").resolve(RecFormat.safe(account) + ".png");
        return Files.exists(f) ? f : null;
    }
}
