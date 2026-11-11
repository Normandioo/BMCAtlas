package com.normandioo.bmcatlas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an export makes: one video, the clips one after the other, each shown with its camera (none: the view on screen).
 * Frame k of the video shows the recording at clip.from + k * speed * 1000 / fps. No Minecraft classes here.
 */
final class ExportPlan {
    private ExportPlan() {}

    /** The video: its file name without extension, and its clips in order. */
    record Pass(String name, List<RecProject.Clip> clips, long frames) {}

    /** The one video of an export (none when there are no clips). base: the recording's folder name. */
    static List<Pass> of(RecProject p, String base) {
        long n = 0;
        for (RecProject.Clip clip : p.clips) n += RecProject.frames(clip, p.fps);
        if (n == 0) return List.of();
        return List.of(new Pass(fileName(base, "video"), List.copyOf(p.clips), n));
    }

    /** "<recording>_<name>", safe for any file system. */
    static String fileName(String base, String name) {
        return RecFormat.safe(base) + "_" + RecFormat.safe(name);
    }

    /** The clip frame k of the video falls in, or null past the end. */
    static RecProject.Clip clipOf(Pass pass, long k, int fps) {
        for (RecProject.Clip c : pass.clips()) {
            long n = RecProject.frames(c, fps);
            if (k < n) return c;
            k -= n;
        }
        return null;
    }

    /** The moment of the recording (ms since its start) of frame k, or -1 past the end. */
    static long timeAt(Pass pass, long k, int fps) {
        if (k < 0) return -1;
        for (RecProject.Clip c : pass.clips()) {
            long n = RecProject.frames(c, fps);
            if (k < n) return c.from + Math.round(k * 1000.0 / fps * c.speed);
            k -= n;
        }
        return -1;
    }

    /** Whether frame k is the first of its clip (the map then waits for its terrain, and the capture waves start afresh). */
    static boolean clipStart(Pass pass, long k, int fps) {
        return k == 0 || clipOf(pass, k, fps) != clipOf(pass, k - 1, fps);
    }

    /** "1:05" of a number of frames. */
    static String duration(long frames, int fps) {
        long s = frames / Math.max(1, fps);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
