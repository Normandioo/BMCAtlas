package com.normandioo.bmcatlas;

/**
 * Whether a video export must wait before a clip: until the terrain and images stay loaded for a few frames, or 8 s pass.
 * Each clip waits once (keyed by its first frame), so a settled clip is not waited for again. No Minecraft classes here.
 */
final class Settle {
    static final int QUIET_FRAMES = 5;
    static final long MAX_MS = 8_000;

    private long since = -1;
    private int quiet;
    private long doneKey = -1;

    /** Asked once per frame: true while this frame must wait. start: the frame is the first of a clip (key). */
    boolean wait(long key, boolean start, boolean busy, long now) {
        if (key == doneKey) return false;
        if (since < 0) {
            if (!start) return false;
            since = now;
            quiet = 0;
        }
        quiet = busy ? 0 : quiet + 1;
        if (quiet < QUIET_FRAMES && now - since < MAX_MS) return true;
        since = -1;
        doneKey = key;
        return false;
    }
}
