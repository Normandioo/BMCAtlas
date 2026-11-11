package com.normandioo.bmcatlas;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The export's encoding thread (video plan, section 9.2): takes captured frames (as the game gives them), cuts out the video's
 * rectangle, turns them to RGBA and hands them to the encoder. At most QUEUE frames wait: the export asks room() before
 * capturing the next one, so the game never blocks and memory stays bounded. No Minecraft classes here.
 */
final class EncodeWorker {
    static final int QUEUE = 4;

    /** A captured frame: the whole image (ints 0xAABBGGRR, top row first) and the rectangle to keep. */
    record Frame(int[] abgr, int w, int h, int x0, int y0, int ow, int oh) {}

    private static final Object END = new Object(), ABORT = new Object();
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE + 2);
    private final VideoEncoder encoder;
    private volatile Throwable error;
    private volatile boolean done;
    private volatile long written;

    EncodeWorker(VideoEncoder encoder) {
        this.encoder = encoder;
    }

    /** Opens the output and starts the thread. A failure to open shows in error(). */
    void start(int w, int h, int fps, Path out) {
        Thread t = new Thread(() -> run(w, h, fps, out), "bmcatlas-encoder");
        t.setDaemon(true);
        t.start();
    }

    private void run(int w, int h, int fps, Path out) {
        try {
            encoder.start(w, h, fps, out);
            while (true) {
                Object o = queue.take();
                if (o == END) {
                    encoder.finish();
                    break;
                }
                if (o == ABORT) {
                    encoder.abort();
                    break;
                }
                Frame f = (Frame) o;
                if (f.ow() != w || f.oh() != h) throw new IllegalStateException("the frame size changed");
                encoder.frame(VideoEncoder.crop(f.abgr(), f.w(), f.h(), f.x0(), f.y0(), f.ow(), f.oh()));
                written++;
            }
        } catch (Throwable e) {
            error = e;
            encoder.abort();
        } finally {
            done = true;
        }
    }

    /** Whether one more frame may be captured now. */
    boolean room() {
        return queue.size() < QUEUE && error == null;
    }

    void add(Frame f) {
        if (!queue.offer(f)) throw new IllegalStateException("encoder queue full");
    }

    /** No more frames: the output is closed once the waiting ones are written. */
    void end() {
        queue.offer(END);
    }

    /** Stops: the waiting frames are dropped and the output removed. */
    void abort() {
        queue.clear();
        queue.offer(ABORT);
    }

    boolean done() {
        return done;
    }

    Throwable error() {
        return error;
    }

    long written() {
        return written;
    }

    Path output() {
        return encoder.output();
    }
}
