package com.normandioo.bmcatlas;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Turns the exported frames into a file (video plan, section 9.2): FfmpegEncoder (an .mp4 through the player's own ffmpeg) or
 * PngEncoder (numbered PNG files, with no dependency). One encoder per output file; it runs on the export's encoding thread.
 * No Minecraft classes here.
 */
interface VideoEncoder {
    /** Opens the output: frames will be w x h pixels (both even), fps per second. */
    void start(int w, int h, int fps, Path out) throws IOException;

    /** One frame: w * h * 4 bytes, RGBA, top row first. */
    void frame(byte[] rgba) throws IOException;

    /** Closes the output once every frame was given. */
    void finish() throws IOException;

    /** Stops and removes what was written. */
    void abort();

    /** What the output is, for the list at the end ("the .mp4", "a folder of PNG frames"). */
    Path output();

    /**
     * The rectangle x0, y0, ow, oh of an image w x h (ints 0xAABBGGRR, as NativeImage keeps them, top row first) as RGBA bytes,
     * fully opaque.
     */
    static byte[] crop(int[] abgr, int w, int h, int x0, int y0, int ow, int oh) {
        byte[] out = new byte[ow * oh * 4];
        int o = 0;
        for (int y = 0; y < oh; y++) {
            int row = (y0 + y) * w + x0;
            for (int x = 0; x < ow; x++) {
                int v = abgr[row + x];
                out[o++] = (byte) v;
                out[o++] = (byte) (v >> 8);
                out[o++] = (byte) (v >> 16);
                out[o++] = (byte) 0xFF;
            }
        }
        return out;
    }
}
