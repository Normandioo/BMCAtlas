package com.normandioo.bmcatlas;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * The encoder that needs nothing installed: one PNG per frame (frame-000001.png...) in a folder, and a README.txt with the
 * ffmpeg command that joins them into a video. The PNG files are written here directly (no AWT, which must not start inside
 * the game). No Minecraft classes here.
 */
final class PngEncoder implements VideoEncoder {
    private Path dir;
    private int w, h, fps, n;
    /** The PNG files are compressed on several cores at once (each frame is its own file, so order does not matter). */
    private java.util.concurrent.ExecutorService pool;
    private java.util.concurrent.Semaphore slots;
    private volatile IOException failure;

    @Override
    public void start(int w, int h, int fps, Path out) throws IOException {
        this.dir = out;
        this.w = w;
        this.h = h;
        this.fps = fps;
        Files.createDirectories(dir);
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "bmcatlas-png");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);   // the game keeps running smoothly
            return t;
        });
        slots = new java.util.concurrent.Semaphore(threads * 2);   // bounded memory: at most this many frames waiting
    }

    @Override
    public void frame(byte[] rgba) throws IOException {
        if (failure != null) throw failure;
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        Path file = dir.resolve(String.format(Locale.ROOT, "frame-%06d.png", ++n));
        pool.execute(() -> {
            try (OutputStream o = Files.newOutputStream(file)) {
                o.write(png(rgba, w, h));
            } catch (IOException e) {
                failure = e;
            } finally {
                slots.release();
            }
        });
    }

    @Override
    public void finish() throws IOException {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)) throw new IOException("the PNG files took too long");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (failure != null) throw failure;
        String readme = "BMC Atlas: " + n + " frames of " + w + "x" + h + " at " + fps + " fps.\n\n"
                + "ffmpeg was not found, so the frames were saved as PNG files. To join them into a video, run in this folder:\n\n"
                + "ffmpeg -framerate " + fps + " -i frame-%06d.png -c:v libx264 -crf 18 -pix_fmt yuv420p -movflags +faststart video.mp4\n";
        Files.writeString(dir.resolve("README.txt"), readme, StandardCharsets.UTF_8);
    }

    @Override
    public void abort() {
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // left behind: harmless
                }
            });
        } catch (IOException ignored) {
            // left behind: harmless
        }
    }

    @Override
    public Path output() {
        return dir;
    }

    /** A whole PNG file of an RGBA image (8 bits per channel, no interlace), fast compression. */
    static byte[] png(byte[] rgba, int w, int h) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream(rgba.length / 2 + 1024);
        file.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ihdr);
        d.writeInt(w);
        d.writeInt(h);
        d.writeByte(8);   // bits per channel
        d.writeByte(6);   // RGBA
        d.writeByte(0);
        d.writeByte(0);
        d.writeByte(0);
        chunk(file, "IHDR", ihdr.toByteArray());
        ByteArrayOutputStream idat = new ByteArrayOutputStream(rgba.length / 2 + 64);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater, 1 << 16)) {
            int stride = w * 4;
            for (int y = 0; y < h; y++) {
                z.write(0);   // no filter
                z.write(rgba, y * stride, stride);
            }
        } finally {
            deflater.end();
        }
        chunk(file, "IDAT", idat.toByteArray());
        chunk(file, "IEND", new byte[0]);
        return file.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        d.write(t);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
