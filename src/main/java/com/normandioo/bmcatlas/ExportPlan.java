package com.normandioo.bmcatlas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an export makes (video plan, section 9.1): one pass per camera marked "export" (or one with the editor's view when the
 * project has none), each through every clip, cut to the camera's own stretch. Frame k of a pass shows the recording at
 * clip.from + k * speed * 1000 / fps. No Minecraft classes here.
 */
final class ExportPlan {
    private ExportPlan() {}

    /** One output file: the camera (null: the editor's view), its file name without extension, and its clips. */
    record Pass(RecCamera camera, String name, List<RecProject.Clip> clips, long frames) {}

    /** The passes of an export. base: the recording's folder name. */
    static List<Pass> of(RecProject p, String base) {
        List<Pass> out = new ArrayList<>();
        List<RecCamera> cams = new ArrayList<>();
        for (RecCamera c : p.cameras) if (c.export) cams.add(c);
        if (p.cameras.isEmpty()) cams.add(null);   // no camera at all: the view on screen
        for (RecCamera c : cams) {
            List<RecProject.Clip> clips = new ArrayList<>();
            for (RecProject.Clip clip : p.clips) {
                long a = clip.from, b = clip.to;
                if (c != null && c.from != null) a = Math.max(a, c.from);
                if (c != null && c.to != null) b = Math.min(b, c.to);
                if (b > a) clips.add(new RecProject.Clip(a, b, clip.speed));
            }
            long n = 0;
            for (RecProject.Clip clip : clips) n += RecProject.frames(clip, p.fps);
            if (n > 0) out.add(new Pass(c, fileName(base, c == null ? "view" : c.name), clips, n));
        }
        return out;
    }

    /** "<recording>_<camera>", safe for any file system. */
    static String fileName(String base, String camera) {
        return RecFormat.safe(base) + "_" + RecFormat.safe(camera);
    }

    /** The moment of the recording (ms since its start) of frame k of a pass, or -1 past its end. */
    static long timeAt(Pass pass, long k, int fps) {
        if (k < 0) return -1;
        for (RecProject.Clip c : pass.clips()) {
            long n = RecProject.frames(c, fps);
            if (k < n) return c.from + Math.round(k * 1000.0 / fps * c.speed);
            k -= n;
        }
        return -1;
    }

    /** Whether frame k starts a clip (the map then waits for its terrain, and the capture waves start afresh). */
    static boolean clipStart(Pass pass, long k, int fps) {
        for (RecProject.Clip c : pass.clips()) {
            if (k == 0) return true;
            long n = RecProject.frames(c, fps);
            if (k < n) return false;
            k -= n;
        }
        return false;
    }

    /** "1:05" of a number of frames. */
    static String duration(long frames, int fps) {
        long s = frames / Math.max(1, fps);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
