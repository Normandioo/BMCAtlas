package com.normandioo.bmcatlas;

import java.util.function.LongSupplier;

/**
 * The map's two clocks. No Minecraft classes here.
 *
 *   data(): which moment of the war is on screen. Live it is now; during a replay it is the recorded moment, which moves at the
 *           chosen speed (1x, 60x...).
 *   anim(): the clock of the animations (capture wave, pulses, front chevrons). It always moves at presentation speed: real time
 *           on screen, and 1000 / fps per frame when a video is exported. So a capture wave lasts 2.8 s on screen even at 60x.
 *
 * Things that are not about the map (toasts, search, the age of the site data, timings) keep System.currentTimeMillis().
 */
final class MapClock {
    private MapClock() {}

    /** The real clock; tests replace it. */
    static LongSupplier wall = System::currentTimeMillis;

    private static long data = Long.MIN_VALUE, anim = Long.MIN_VALUE;

    static long data() {
        return data != Long.MIN_VALUE ? data : wall.getAsLong();
    }

    static long anim() {
        return anim != Long.MIN_VALUE ? anim : wall.getAsLong();
    }

    /** Whether the map shows a recorded moment instead of now. */
    static boolean replay() {
        return data != Long.MIN_VALUE;
    }

    /** The recorded moment on screen (ms since 1970). */
    static void showData(long t) {
        data = t;
    }

    /** Back to now. */
    static void live() {
        data = Long.MIN_VALUE;
    }

    /** Sets the animation clock by hand (video export), or Long.MIN_VALUE to follow the real clock again. */
    static void setAnim(long t) {
        anim = t;
    }
}
