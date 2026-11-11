package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegEncoderTest {

    @TempDir
    Path tmp;

    @Test
    void commandLine() {
        List<String> a = FfmpegEncoder.args("ffmpeg", 1920, 1080, 30, Path.of("out.mp4"));
        assertEquals("ffmpeg", a.get(0));
        assertEquals("1920x1080", a.get(a.indexOf("-s") + 1));
        assertEquals("30", a.get(a.indexOf("-r") + 1));
        assertEquals("-", a.get(a.indexOf("-i") + 1));
        assertEquals("rgba", a.get(a.indexOf("-pix_fmt") + 1));
        assertEquals("libx264", a.get(a.indexOf("-c:v") + 1));
        assertTrue(a.contains("-an"));
        assertEquals("out.mp4", a.get(a.size() - 1));
    }

    @Test
    void whereFfmpegIsLookedFor() {
        List<String> win = FfmpegEncoder.candidates("D:\\tools\\ffmpeg.exe", "C:\\bin;\"C:\\Program Files\\x\";", true);
        assertEquals("D:\\tools\\ffmpeg.exe", win.get(0));   // the settings first
        assertTrue(win.get(1).endsWith("ffmpeg.exe") && win.get(1).startsWith("C:\\bin"));
        assertTrue(win.contains("C:\\ffmpeg\\bin\\ffmpeg.exe"));
        List<String> nix = FfmpegEncoder.candidates(null, "/usr/bin:/home/me/bin", false);
        assertTrue(nix.get(0).endsWith("ffmpeg"));
        assertTrue(nix.contains("/opt/homebrew/bin/ffmpeg"));
        assertEquals(nix.size(), nix.stream().distinct().count());   // /usr/bin/ffmpeg only once
    }

    @Test
    void fileNamesPerCamera() {
        assertEquals("20261110-120000_gaia_TestWar_Main_camera", ExportPlan.fileName("20261110-120000_gaia_TestWar", "Main camera"));
        assertEquals("rec_a_b", ExportPlan.fileName("rec", "a/b"));
    }

    @Test
    void cropTurnsNativeImagePixelsIntoRgba() {
        // 3 x 2 image, 0xAABBGGRR
        int[] px = {0xFF000001, 0xFF000002, 0xFF000003, 0xFF030201, 0x00102030, 0xFF000006};
        byte[] rgba = VideoEncoder.crop(px, 3, 2, 1, 1, 2, 1);
        assertArrayEquals(new byte[]{0x30, 0x20, 0x10, (byte) 0xFF, 6, 0, 0, (byte) 0xFF}, rgba);
    }

    @Test
    void pngFramesAreRealPngs() throws IOException {
        byte[] rgba = new byte[4 * 3 * 4];
        for (int i = 0; i < 12; i++) {
            rgba[i * 4] = (byte) (i * 20);
            rgba[i * 4 + 1] = 7;
            rgba[i * 4 + 2] = (byte) 200;
            rgba[i * 4 + 3] = (byte) 255;
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(PngEncoder.png(rgba, 4, 3)));
        assertEquals(4, img.getWidth());
        assertEquals(3, img.getHeight());
        assertEquals(0xFF000000 | (100 << 16) | (7 << 8) | 200, img.getRGB(1, 1));   // pixel 5: red 100

        PngEncoder enc = new PngEncoder();
        Path dir = tmp.resolve("frames");
        enc.start(4, 3, 30, dir);
        enc.frame(rgba);
        enc.frame(rgba);
        enc.finish();
        assertTrue(Files.exists(dir.resolve("frame-000002.png")));
        assertTrue(Files.readString(dir.resolve("README.txt")).contains("-framerate 30"));
        enc.abort();
        assertTrue(!Files.exists(dir));
    }

    /** An encoder that only counts, for the worker. */
    private static final class Counting implements VideoEncoder {
        final List<Integer> sizes = new ArrayList<>();
        boolean finished, aborted;

        public void start(int w, int h, int fps, Path out) {}

        public void frame(byte[] rgba) {
            sizes.add(rgba.length);
        }

        public void finish() {
            finished = true;
        }

        public void abort() {
            aborted = true;
        }

        public Path output() {
            return null;
        }
    }

    @Test
    void theWorkerCropsEncodesAndFinishes() throws InterruptedException {
        Counting enc = new Counting();
        EncodeWorker w = new EncodeWorker(enc);
        w.start(2, 2, 30, null);
        for (int i = 0; i < 3; i++) {
            while (!w.room(0)) Thread.sleep(1);
            w.add(new EncodeWorker.Frame(new int[16], 4, 4, 1, 1, 2, 2));
        }
        w.end();
        for (int i = 0; i < 500 && !w.done(); i++) Thread.sleep(5);
        assertTrue(w.done());
        assertNull(w.error());
        assertEquals(List.of(16, 16, 16), enc.sizes);
        assertTrue(enc.finished);
        assertEquals(3, w.written());

        Counting bad = new Counting();
        EncodeWorker w2 = new EncodeWorker(bad);
        w2.start(2, 2, 30, null);
        w2.add(new EncodeWorker.Frame(new int[16], 4, 4, 0, 0, 4, 4));   // not the size the output was opened with
        for (int i = 0; i < 500 && !w2.done(); i++) Thread.sleep(5);
        assertTrue(w2.error() != null);
        assertTrue(bad.aborted);
    }
}
