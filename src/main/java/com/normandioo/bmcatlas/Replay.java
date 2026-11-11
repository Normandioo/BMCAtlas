package com.normandioo.bmcatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A recording opened for playback: its cities, its players' track, and which moment is on screen. No Minecraft classes here.
 *
 * Times inside are ms since the recording's start (meta.start), like in the files. The moment on screen moves with the animation
 * clock (MapClock.anim) times the speed, so playback keeps its pace however fast frames come.
 *
 * A recording folder can hold several sessions (each time the player came back to the same war, meta.intervals). The playback
 * covers only the most recent one, from() to to(); the older ones stay in the folder for the editor.
 */
final class Replay {
    /** The playback speeds offered, slowest first. */
    static final double[] SPEEDS = {0.25, 0.5, 1, 2, 5, 10, 60, 300};
    /** A last session shorter than this (just joined) is skipped for the one before it. */
    static final long MIN_SESSION_MS = 5_000;

    /** One player on screen at the moment shown. */
    record Shown(int index, String account, String name, double x, double z) {}

    final Path dir;
    final RecFormat.Meta meta;
    final List<RecFormat.Frame> frames;
    final Map<Integer, RecFormat.PlayerInfo> players;
    final RecTrack.Track track;
    private final long length;
    private long from, to;
    private double t;
    private double speed = 1;
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
        long a = 0, b = len;
        for (int i = meta.intervals.size() - 1; i >= 0; i--) {   // the most recent session long enough to watch
            long[] iv = meta.intervals.get(i);
            long ia = Math.max(0, Math.min(len, iv[0] - meta.start)), ib = Math.max(ia, Math.min(len, iv[1] - meta.start));
            a = ia;
            b = i == meta.intervals.size() - 1 ? len : ib;   // the last session runs to the end of what was written
            if (b - a >= MIN_SESSION_MS) break;
        }
        this.from = a;
        this.to = Math.max(a, b);
        this.t = from;
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

    /** How long the whole folder is (ms). */
    long length() {
        return length;
    }

    /** Where the session played back starts and ends (ms since the start). */
    long from() {
        return from;
    }

    long to() {
        return to;
    }

    /** The editor plays the whole folder, every session, with the time away in between. */
    void useWhole() {
        from = 0;
        to = length;
        seek(time());
    }

    /** The sessions of the folder, [from, to] in ms since the start (one covering everything when none were kept). */
    List<long[]> sessions() {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < meta.intervals.size(); i++) {
            long[] iv = meta.intervals.get(i);
            long a = Math.max(0, Math.min(length, iv[0] - meta.start));
            long b = i == meta.intervals.size() - 1 ? length : Math.max(a, Math.min(length, iv[1] - meta.start));
            out.add(new long[]{a, b});
        }
        if (out.isEmpty()) out.add(new long[]{0, length});
        return out;
    }

    /** Whether the moment t was recorded (inside a session). */
    boolean recorded(long time) {
        for (long[] s : sessions()) if (time >= s[0] && time <= s[1]) return true;
        return false;
    }

    /** The moment on screen (ms since the start). */
    long time() {
        return (long) t;
    }

    /** The moment on screen (ms since 1970). */
    long absTime() {
        return meta.start + time();
    }

    double speed() {
        return speed;
    }

    /** The speed as shown on the bar: 0.25x, 0.5x, 1x, 60x... */
    String speedText() {
        return (speed == Math.rint(speed) ? String.valueOf((long) speed) : String.format(Locale.ROOT, "%s", speed)) + "x";
    }

    boolean playing() {
        return playing;
    }

    /** Goes to a moment (kept inside the session played back). */
    void seek(long to) {
        t = Math.max(from, Math.min(this.to, to));
    }

    void play(long animNow) {
        if (t >= to) t = from;
        playing = true;
        lastAnim = animNow;
    }

    void pause() {
        playing = false;
    }

    /** One step faster (steps > 0) or slower (steps < 0) in SPEEDS, stopping at the ends. */
    void changeSpeed(int steps) {
        int i = 0;
        while (i < SPEEDS.length - 1 && SPEEDS[i] < speed) i++;
        speed = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, i + steps))];
    }

    void setSpeed(double s) {
        speed = Math.max(0.01, s);
    }

    /** Moves the moment on screen by the animation time passed since the last call, times the speed; stops at the end. */
    void advance(long animNow) {
        if (!playing) return;
        long dt = Math.max(0, Math.min(1000, animNow - lastAnim));   // a long pause (game frozen) does not jump ahead
        lastAnim = animNow;
        t = Math.min(to, t + dt * speed);
        if (t >= to) playing = false;
    }

    /** The cities frame in force at the moment on screen: the last one at or before it (the first one before it starts). */
    int frameIndex() {
        return frameAt(time());
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
        long now = time();
        for (RecFormat.Frame f : frames) if (f.t > now && f.t <= to) return f.t;
        return to;
    }

    /** Where the cities changed last before the moment on screen, or the start. */
    long previousChange() {
        long now = time();
        int at = frameIndex();
        if (at < 0) return from;
        long best = frames.get(at).t < now - 1000 ? frames.get(at).t   // well into a change: back to its start
                : at > 0 ? frames.get(at - 1).t : from;
        return Math.max(from, best);
    }

    /** The players on the map at the moment on screen. */
    List<Shown> shown() {
        List<Shown> out = new ArrayList<>();
        long now = time();
        for (int i : track.players()) {
            double[] p = track.at(i, now);
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
