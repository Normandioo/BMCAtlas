package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A video export running (video plan, section 9.1): the recording is played in the map's own window at a fixed step, one pass
 * per camera, and each frame is captured between one frame and the next, like F2 does.
 *
 * Per frame k: beforeFrame (start of the map's frame) sets the moment of the recording, the animation clock and the view for k;
 * the map draws it; afterFrame (end of the frame) queues the capture, which runs at the start of the next frame (before the
 * screen is cleared) and hands the pixels to the encoding thread. k only moves on once its pixels arrived and the encoder has
 * room, so how fast the game draws never changes the video. Before each clip the map waits for its terrain and images.
 */
final class VideoExport {
    enum Stage { RUNNING, DONE, FAILED, CANCELLED }

    private static final long SETTLE_MIN_FRAMES = 5, SETTLE_MAX_MS = 8_000, LOADING_MAX_MS = 3_000, CAPTURE_MAX_MS = 10_000;

    final ReplayEditor ed;
    final List<ExportPlan.Pass> passes;
    final int fps;
    final String ffmpeg;
    final Path outDir;
    final List<Path> outputs = new ArrayList<>();
    final long totalFrames;
    Stage stage = Stage.RUNNING;
    String message;

    private final String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
    private final long startedAt = System.currentTimeMillis();
    private final long animBase = MapClock.anim();
    private final double savedX = MapPanels.viewX, savedZ = MapPanels.viewZ, savedScale = MapPanels.scale;
    private final long savedTime;
    private final int fbW, fbH;
    private int pass;
    private long k, framesDone;
    private EncodeWorker worker;
    private int x0, y0, ow, oh;
    /** The capture of the frame just drawn is queued but not yet asked of the game (it is asked at the start of the next frame). */
    private volatile boolean pending;
    private volatile long pendingSince;
    /** Captures asked of the game whose pixels have not arrived yet (the GPU copies them while the next frames are drawn). */
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastArrival;
    private boolean captureThisFrame;
    private long settleFrames, settleSince, loadingSince;
    private boolean settling;

    VideoExport(ReplayEditor ed, String ffmpeg) {
        this.ed = ed;
        this.fps = ed.project.fps;
        this.ffmpeg = ffmpeg;
        this.passes = ExportPlan.of(ed.project, ed.dir.getFileName().toString());
        this.outDir = FabricLoader.getInstance().getGameDir().resolve("bmcatlas").resolve("videos");
        long n = 0;
        for (ExportPlan.Pass p : passes) n += p.frames();
        this.totalFrames = n;
        this.savedTime = ed.replay.time();
        ed.replay.pause();
        Minecraft mc = Minecraft.getInstance();
        fbW = mc.getWindow().getWidth();
        fbH = mc.getWindow().getHeight();
        if (passes.isEmpty()) fail("Nothing to export: add clips first.");
    }

    boolean running() {
        return stage == Stage.RUNNING;
    }

    /** Whether the frame being drawn is the one captured (then the export's own progress must not be drawn on it). */
    boolean capturing() {
        return captureThisFrame;
    }

    /** The video's rectangle on screen, in pixels: x0, y0, width, height (empty before the first pass opens). */
    int[] rect() {
        return new int[]{x0, y0, ow, oh};
    }

    double progress() {
        return totalFrames == 0 ? 0 : framesDone / (double) totalFrames;
    }

    /** "2:10 left", from the pace so far. */
    String left() {
        if (framesDone < 10) return "";
        long ms = (System.currentTimeMillis() - startedAt) * (totalFrames - framesDone) / Math.max(1, framesDone);
        return ReplayEditor.clock(ms) + " left";
    }

    String passName() {
        return pass < passes.size() ? passes.get(pass).name() : "";
    }

    int passIndex() {
        return pass;
    }

    void cancel() {
        if (!running()) return;
        if (worker != null) worker.abort();
        stage = Stage.CANCELLED;
        message = "Export cancelled.";
        restore();
    }

    private void fail(String why) {
        if (worker != null) worker.abort();
        stage = Stage.FAILED;
        message = why;
        BmcAtlasClient.LOG.warn("Video export stopped: {}", why);
        restore();
    }

    private void restore() {
        MapClock.setAnim(Long.MIN_VALUE);
        MapPanels.viewX = savedX;
        MapPanels.viewZ = savedZ;
        MapPanels.scale = MapPanels.zoomTarget = savedScale;
        WarRecorder.exportSeek(savedTime, true);
    }

    /** Start of a map frame (guiW: the map's width in its own units): sets what frame k shows, or keeps waiting. */
    void beforeFrame(int guiW) {
        if (!running()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow().getWidth() != fbW || mc.getWindow().getHeight() != fbH) {
            fail("The window changed size or was minimized: export again with the window as it was.");
            return;
        }
        if (worker == null && !openPass()) return;
        if (worker.error() != null) {
            fail("The encoder stopped: " + worker.error().getMessage());
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (pending) {
            if (nowMs - pendingSince > CAPTURE_MAX_MS) fail("The game did not take the capture.");
            return;
        }
        if (inFlight.get() > 0 && nowMs - Math.max(lastArrival, pendingSince) > CAPTURE_MAX_MS) {
            fail("The game did not hand over the captured frame.");
            return;
        }
        ExportPlan.Pass p = passes.get(pass);
        if (k >= p.frames()) {   // this camera is done: close its file, then the next camera
            if (inFlight.get() > 0) return;   // its last frames are still on their way
            if (!worker.done()) {
                worker.end();
                return;
            }
            if (worker.error() != null) {
                fail("The encoder stopped: " + worker.error().getMessage());
                return;
            }
            outputs.add(worker.output());
            worker = null;
            pass++;
            if (pass >= passes.size()) {
                stage = Stage.DONE;
                message = "Done: " + outputs.size() + (outputs.size() == 1 ? " video" : " videos") + " in " + outDir;
                restore();
            }
            return;
        }
        if (!worker.room(inFlight.get())) return;

        long t = ExportPlan.timeAt(p, k, fps);
        boolean start = ExportPlan.clipStart(p, k, fps);
        WarRecorder.exportSeek(t, start);
        MapClock.setAnim(animBase + framesDone * 1000 / fps);
        double pxPerGui = fbW / (double) Math.max(1, guiW);
        if (p.camera() != null) {
            double[] f = ed.frame(p.camera(), t);
            MapPanels.viewX = f[0];
            MapPanels.viewZ = f[1];
            MapPanels.scale = MapPanels.zoomTarget = Math.max(1e-4, ow / pxPerGui / f[2]);
        }

        // before a clip: let the terrain and images of its area load; while the camera moves: whenever regions are still read
        long now = System.currentTimeMillis();
        if (start && !settling && settleFrames == 0) {
            settling = true;
            settleSince = now;
        }
        if (settling) {
            boolean busy = MapData.loading() || RemoteImages.pending() > 0;
            settleFrames = busy ? 0 : settleFrames + 1;
            if (settleFrames < SETTLE_MIN_FRAMES && now - settleSince < SETTLE_MAX_MS) return;
            settling = false;
        } else if (MapData.loading()) {
            if (loadingSince == 0) loadingSince = now;
            if (now - loadingSince < LOADING_MAX_MS) return;
        }
        loadingSince = 0;
        captureThisFrame = true;
    }

    private boolean openPass() {
        ExportPlan.Pass p = passes.get(pass);
        int[] size = p.camera() != null ? p.camera().outputSize(fbW, fbH) : new int[]{fbW & ~1, fbH & ~1};
        ow = size[0];
        oh = size[1];
        x0 = (fbW - ow) / 2;
        y0 = (fbH - oh) / 2;
        boolean mp4 = ffmpeg != null;
        Path out = outDir.resolve(p.name() + "_" + stamp + (mp4 ? ".mp4" : "_frames"));
        worker = new EncodeWorker(mp4 ? new FfmpegEncoder(ffmpeg) : new PngEncoder());
        worker.start(ow, oh, fps, out);
        k = 0;
        settleFrames = 0;
        settling = false;
        return true;
    }

    /** End of the map frame: when it showed frame k, queue its capture for the start of the next frame. */
    void afterFrame() {
        if (!captureThisFrame) return;
        captureThisFrame = false;
        if (!running()) return;
        pending = true;
        pendingSince = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        EncodeWorker w = worker;
        int rx = x0, ry = y0, rw = ow, rh = oh;
        // At the start of the next frame (before the screen is cleared) the copy of this one is asked of the GPU. Asking is enough:
        // the copy is ordered before what is drawn next, so frame k+1 can be drawn at once while k's pixels are on their way, and
        // they arrive in order.
        Compat.afterFrame(mc, () -> {
            inFlight.incrementAndGet();
            try {
                Compat.grabFrame(mc, (NativeImage img) -> {
                    try {
                        if (!running() || w != worker) return;
                        int iw = img.getWidth(), ih = img.getHeight();
                        if (iw != fbW || ih != fbH) {
                            fail("The captured frame is " + iw + "x" + ih + ", not " + fbW + "x" + fbH + ".");
                            return;
                        }
                        w.add(new EncodeWorker.Frame(Compat.pixels(img), iw, ih, rx, ry, rw, rh));
                        framesDone++;
                    } catch (RuntimeException e) {
                        fail("Capture failed: " + e);
                    } finally {
                        img.close();
                        lastArrival = System.currentTimeMillis();
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RuntimeException e) {
                inFlight.decrementAndGet();
                fail("Capture failed: " + e);
            }
            k++;
            pending = false;
        });
    }
}
