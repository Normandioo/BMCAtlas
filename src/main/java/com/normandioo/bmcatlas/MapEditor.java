package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The replay editor, a mode of the map screen (video plan, phases 3a and 3b): the list of recordings, and for the one opened
 * the map with a timeline under it: the recording with its sessions, time away and highlights; the clips of the video; the
 * keyframes of the camera selected. Cameras show on the map as coloured rectangles: drag the name tab to move one, a corner
 * to resize it (keeping its aspect); either makes a keyframe at the moment on screen. The map stays free to move and zoom.
 * MapScreen draws the map, then calls drawEditor instead of its menus.
 */
abstract class MapEditor extends MapPanels {
    MapEditor(Component title) {
        super(title);
    }

    /** Asks for a name in the map's text box (MapScreen); done gets it on Enter. */
    abstract void askName(String current, Consumer<String> done);

    /** The recording open in the editor, or null. */
    ReplayEditor ed;
    /** The list of recordings is open. */
    boolean listOpen;
    /** The account of the player last clicked on the map (for a camera's "Follow"). */
    String pickedAccount;
    /** The editor's view follows the selected camera. */
    boolean viewCam;
    private List<Path> listDirs = List.of();
    private List<RecFormat.Meta> listMeta = List.of();
    private int listFirst;

    // timeline geometry (set while drawing) and what is being dragged
    private int tlX, tlW;
    private static final int DRAG_NONE = 0, DRAG_SEEK = 1, DRAG_MOVE = 2, DRAG_FROM = 3, DRAG_TO = 4, DRAG_CAM_MOVE = 5, DRAG_CAM_SIZE = 6, DRAG_KEY = 7;
    private int dragKind;
    private long dragGrab;
    private RecProject.Clip dragClip;
    private double grabX, grabZ, fixedX, fixedZ;
    /** Each camera's share of terrain, worked out every 2 s: {share, when}. */
    private final Map<RecCamera, double[]> terrain = new IdentityHashMap<>();

    boolean editing() {
        return ed != null;
    }

    // map coordinates (the same as MapScreen's: the view centred on the screen)
    private double scrX(double bx) {
        return width / 2 + (bx - viewX) * scale;
    }

    private double scrY(double bz) {
        return height / 2 + (bz - viewZ) * scale;
    }

    private double blkX(double mx) {
        return viewX + (mx - width / 2) / scale;
    }

    private double blkZ(double my) {
        return viewZ + (my - height / 2) / scale;
    }

    private double windowRatio() {
        return width / (double) Math.max(1, height);
    }

    // ------------------------------------------------------------------ the list of recordings

    void openList() {
        listDirs = RecFormat.list(WarRecorder.recordingsDir());
        List<RecFormat.Meta> metas = new ArrayList<>();
        for (Path p : listDirs) metas.add(RecFormat.readMeta(p));
        listMeta = metas;
        listFirst = 0;
        listOpen = true;
    }

    void openEditor(Path dir) {
        ReplayEditor e = ReplayEditor.open(dir);
        if (e == null) return;
        ed = e;
        listOpen = false;
        viewCam = false;
        terrain.clear();
        RecHighlights.Highlight first = null;
        for (RecHighlights.Highlight h : e.highlights) if (h.type() != RecHighlights.Type.START && h.type() != RecHighlights.Type.END) { first = h; break; }
        if (first != null && first.city() != null) {   // start looking at the first place something happened
            viewX = first.x();
            viewZ = first.z();
        }
    }

    void closeEditor() {
        if (ed == null) return;
        ed.save();
        ed = null;
        viewCam = false;
        dragKind = DRAG_NONE;
        WarRecorder.stop();
    }

    void drawRecordingsList(GuiGraphics g, int mouseX, int mouseY) {
        if (!listOpen) return;
        int w = Math.min(width - 20, 420), rowH = 24, rows = Math.max(1, Math.min(listDirs.size(), (height - 120) / rowH));
        int h = 36 + Math.max(1, rows) * rowH + 24, x = (width - w) / 2, y = Math.max(10, (height - h) / 2);
        box(g, x, y, w, h, COL_PANEL);
        hits.add(new Hit(x, y, w, h, null, null));
        g.drawString(font, "Recordings", x + 8, y + 8, COL_TEXT, false);
        drawFit(g, "Open one to watch it, pick highlights and make clips", w - 90, x + 8, y + 20, COL_DIM, false);
        textButtonFixed(g, x + w - 52, y + 5, 46, 16, "Close", mouseX, mouseY, () -> listOpen = false);
        int ry = y + 36;
        if (listDirs.isEmpty()) {
            g.drawString(font, "No recordings yet: start recording on the war map.", x + 8, ry + 6, COL_DIM, false);
        }
        listFirst = Math.max(0, Math.min(listFirst, Math.max(0, listDirs.size() - rows)));
        for (int i = listFirst; i < Math.min(listDirs.size(), listFirst + rows); i++) {
            Path dir = listDirs.get(i);
            RecFormat.Meta m = listMeta.get(i);
            boolean hover = mouseX >= x + 4 && mouseX < x + w - 4 && mouseY >= ry && mouseY < ry + rowH - 2;
            g.fill(x + 4, ry, x + w - 4, ry + rowH - 2, hover ? 0x408F959A : 0x20FFFFFF);
            String name = m == null ? dir.getFileName().toString() : m.warName != null ? m.warName : m.warId;
            String sub = m == null ? "unreadable" : MapFormat.dateText(m.start) + "   " + ReplayEditor.clock(m.end - m.start)
                    + "   " + m.intervals.size() + (m.intervals.size() == 1 ? " session" : " sessions")
                    + (ReplayEditor.hasPlayers(dir) ? "   players" : "") + (m.importedFrom != null ? "   (old format)" : "");
            drawFit(g, name == null ? "?" : name, w - 20, x + 10, ry + 2, COL_TEXT, false);
            drawFit(g, sub, w - 20, x + 10, ry + 12, COL_DIM, false);
            if (m != null) hits.add(new Hit(x + 4, ry, w - 8, rowH - 2, () -> openEditor(dir), null));
            ry += rowH;
        }
        if (listDirs.size() > rows) {
            int by = y + h - 20;
            textButton(g, x + 8, by, 40, 16, "Up", mouseX, mouseY, listFirst > 0, () -> listFirst = Math.max(0, listFirst - rows));
            textButton(g, x + 52, by, 50, 16, "Down", mouseX, mouseY, listFirst + rows < listDirs.size(), () -> listFirst += rows);
            g.drawString(font, (listFirst + 1) + "-" + Math.min(listDirs.size(), listFirst + rows) + " of " + listDirs.size(), x + 110, by + 4, COL_DIM, false);
        }
    }

    // ------------------------------------------------------------------ the editor

    private long timeAtX(double mx) {
        long len = ed.replay.length();
        return Math.max(0, Math.min(len, Math.round((mx - tlX) / Math.max(1, tlW) * len)));
    }

    private int xAt(long t) {
        long len = Math.max(1, ed.replay.length());
        return tlX + (int) Math.round(tlW * (double) t / len);
    }

    private static int colorOf(RecHighlights.Type t) {
        return switch (t) {
            case CAPTURE -> 0xFFE8403A;
            case RETAKEN -> 0xFF4CAF50;
            case CONTESTED -> 0xFFE0C040;
            case CAPITAL -> 0xFFFF7A00;
            case CAPITULATION -> 0xFFFFFFFF;
            case BATTLE -> 0xFFB06CFF;
            default -> 0xFF9AA0A6;
        };
    }

    private void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int thick) {
        g.fill(x0, y0, x1, y0 + thick, color);
        g.fill(x0, y1 - thick, x1, y1, color);
        g.fill(x0, y0, x0 + thick, y1, color);
        g.fill(x1 - thick, y0, x1, y1, color);
    }

    /** The share of terrain a camera covers, worked out again every 2 s. */
    private double terrainOf(RecCamera c) {
        long now = System.currentTimeMillis();
        double[] t = terrain.get(c);
        if (t == null || now - t[1] > 2000) {
            t = new double[]{c.terrain(windowRatio(), MapData::chunkKnown), now};
            terrain.put(c, t);
        }
        return t[0];
    }

    void drawEditor(GuiGraphics g, int mouseX, int mouseY) {
        ReplayEditor e = ed;
        Replay r = e.replay;
        WarRecorder.advance();
        SiteTerritories.Data d = SiteTerritories.data();
        long now = r.time();
        double ratio = windowRatio();
        RecCamera selCam = e.camera();
        if (viewCam && selCam == null) viewCam = false;
        if (viewCam) {   // the view follows the camera: exactly what its video will show
            double[] f = e.frame(selCam, now);
            double cr = selCam.ratio(ratio), fw = f[2], fh = fw / cr;
            viewX = f[0];
            viewZ = f[1];
            scale = zoomTarget = Math.max(0.03, Math.min(8, Math.min(width / fw, height / fh)));
        }

        drawCameras(g, e, now, ratio, mouseX, mouseY);

        // top left: the war, the moment on screen, the score
        int ix = 8, iy = 8, iw = 240;
        boolean score = e.war != null && e.war.sides != null && e.war.sides.size() >= 2 && d != null;
        box(g, ix, iy, iw, score ? 46 : 32, COL_PANEL);
        hits.add(new Hit(ix, iy, iw, score ? 46 : 32, null, null));
        drawFit(g, e.title, iw - 12, ix + 6, iy + 5, COL_TEXT, false);
        String when = MapFormat.dateText(r.absTime()) + "  " + ReplayEditor.clock(now) + " / " + ReplayEditor.clock(r.length());
        drawFit(g, when + (viewCam ? "   (camera view)" : ""), iw - 12, ix + 6, iy + 17, r.recorded(now) ? 0xFFF0B429 : COL_DIM, false);
        if (score) sideBar(g, e.war, WarScore.of(d, e.war).points().clone(), ix + 6, iy + 31, iw - 12);

        int ph = 112, py = height - ph - 8;
        drawCameraPanel(g, e, ratio, mouseX, mouseY, py);

        // bottom: transport, the three tracks, the clip line
        int px = 8, pw = width - 16;
        box(g, px, py, pw, ph, COL_PANEL);
        hits.add(new Hit(px, py, pw, ph, null, null));
        int cx = px + 4, by = py + 4;
        textButtonFixed(g, cx, by, 24, 16, "|<", mouseX, mouseY, () -> e.seek(0));
        cx += 26;
        textButtonFixed(g, cx, by, 24, 16, "<*", mouseX, mouseY, () -> jumpTo(e.jumpHighlight(-1)));
        cx += 26;
        textButtonFixed(g, cx, by, 42, 16, r.playing() ? "Pause" : "Play", mouseX, mouseY, e::togglePlay);
        cx += 44;
        textButtonFixed(g, cx, by, 24, 16, "*>", mouseX, mouseY, () -> jumpTo(e.jumpHighlight(1)));
        cx += 26;
        textButtonFixed(g, cx, by, 24, 16, ">|", mouseX, mouseY, () -> e.seek(r.length()));
        cx += 30;
        textButtonFixed(g, cx, by, 16, 16, "-", mouseX, mouseY, () -> r.changeSpeed(-1));
        cx += 17;
        int sw = font.width("0.25x") + 10;
        box(g, cx, by, sw, 16, COL_PANEL);
        g.drawString(font, r.speedText(), cx + (sw - font.width(r.speedText())) / 2, by + 4, COL_TEXT, false);
        cx += sw + 1;
        textButtonFixed(g, cx, by, 16, 16, "+", mouseX, mouseY, () -> r.changeSpeed(1));
        cx += 24;
        textButtonFixed(g, cx, by, 26, 16, "In", mouseX, mouseY, e::markIn);
        cx += 28;
        textButtonFixed(g, cx, by, 30, 16, "Out", mouseX, mouseY, e::markOut);
        cx += 32;
        int addW = font.width("Add clip") + 12;
        textButtonFixed(g, cx, by, addW, 16, "Add clip", mouseX, mouseY, e::addClip);
        cx += addW + 2;
        int autoW = font.width("Auto highlights") + 12;
        textButtonFixed(g, cx, by, autoW, 16, "Auto highlights", mouseX, mouseY, e::autoHighlights);
        cx += autoW + 2;
        int camW = font.width("New camera") + 12;
        textButtonFixed(g, cx, by, camW, 16, "New camera", mouseX, mouseY, () -> {
            viewCam = false;
            e.newCamera(viewX, viewZ, Math.max(16, width * 0.6 / scale));
        });
        int closeW = font.width("Close") + 12, expW = font.width("Export") + 12;
        textButton(g, px + pw - 4 - closeW - 2 - expW, by, expW, 16, "Export", mouseX, mouseY, false, () -> {});
        textButtonFixed(g, px + pw - 4 - closeW, by, closeW, 16, "Close", mouseX, mouseY, this::closeEditor);

        // track 1: the recording (sessions light, time away dark), hour marks, highlights, In/Out marks
        tlX = px + 8;
        tlW = pw - 16;
        int t1 = py + 26, t2 = py + 48, t3 = py + 70;
        long len = Math.max(1, r.length());
        g.fill(tlX, t1, tlX + tlW, t1 + 16, 0xFF1E2123);
        for (long[] s : r.sessions()) g.fill(xAt(s[0]), t1, Math.max(xAt(s[0]) + 1, xAt(s[1])), t1 + 16, 0xFF3A4046);
        long hour = 3_600_000, step = len > 6 * hour ? hour : len > hour ? 15 * 60_000 : len > 600_000 ? 5 * 60_000 : 60_000;
        for (long t = step; t < len; t += step) g.fill(xAt(t), t1 + 12, xAt(t) + 1, t1 + 16, 0xFF6A6F72);
        RecHighlights.Highlight hot = null;
        for (RecHighlights.Highlight h : e.highlights) {
            if (h.type() == RecHighlights.Type.START || h.type() == RecHighlights.Type.END) continue;
            int hx = xAt(h.t()), hh = (int) Math.max(4, Math.min(12, 3 + h.weight()));
            g.fill(hx, t1 + 12 - hh, hx + 2, t1 + 12, colorOf(h.type()));
            if (Math.abs(mouseX - hx) <= 2 && mouseY >= t1 && mouseY < t1 + 16) hot = h;
        }
        if (e.markIn >= 0) g.fill(xAt(e.markIn), t1 - 2, xAt(e.markIn) + 1, t2 + 18, 0xFF4CAF50);
        if (e.markOut >= 0) g.fill(xAt(e.markOut), t1 - 2, xAt(e.markOut) + 1, t2 + 18, 0xFFE8403A);
        final RecHighlights.Highlight hotF = hot;
        hits.add(new Hit(tlX, t1, tlW, 16, () -> {
            if (hotF != null) {
                jumpTo(hotF);
                e.seek(hotF.t());
            } else {
                dragKind = DRAG_SEEK;
                e.seek(timeAtX(mouseX));
            }
        }, null));

        // track 2: the clips (orange at normal speed, blue when fast), with handles at the ends
        g.fill(tlX, t2, tlX + tlW, t2 + 16, 0xFF1E2123);
        RecProject.Clip hoverClip = null;
        for (RecProject.Clip c : e.project.clips) {
            int a = xAt(c.from), b = Math.max(a + 2, xAt(c.to));
            boolean sel = c == e.selected();
            int col = c.speed > 2 ? 0xFF3A7BD5 : c.speed < 1 ? 0xFF6CC3A0 : 0xFFF0B429;
            g.fill(a, t2 + 2, b, t2 + 14, sel ? 0xFFFFF2B0 : col);
            g.fill(a, t2 + 2, a + 1, t2 + 14, 0xFF0A0B08);
            g.fill(b - 1, t2 + 2, b, t2 + 14, 0xFF0A0B08);
            if (b - a > font.width(ReplayEditor.speedText(c.speed)) + 4) g.drawString(font, ReplayEditor.speedText(c.speed), a + 3, t2 + 4, 0xFF0A0B08, false);
            if (mouseX >= a - 3 && mouseX < b + 3 && mouseY >= t2 && mouseY < t2 + 16) hoverClip = c;
        }
        final RecProject.Clip hc = hoverClip;
        hits.add(new Hit(tlX, t2, tlW, 16, () -> {
            if (hc == null) {
                e.selClip = -1;
                return;
            }
            e.select(hc);
            e.selKey = null;
            int a = xAt(hc.from), b = xAt(hc.to);
            dragClip = hc;
            dragKind = Math.abs(mouseX - a) <= 3 ? DRAG_FROM : Math.abs(mouseX - b) <= 3 ? DRAG_TO : DRAG_MOVE;
            dragGrab = timeAtX(mouseX) - hc.from;
        }, null));

        // track 3: the keyframes of the camera selected (diamonds)
        g.fill(tlX, t3, tlX + tlW, t3 + 16, 0xFF1E2123);
        RecCamera.Key hoverKey = null;
        if (selCam != null) {
            for (RecCamera.Key k : selCam.keys) {
                int kx = xAt(k.t);
                boolean sel = k == e.selKey;
                int col = sel ? 0xFFFFF2B0 : selCam.color;
                for (int i = 0; i <= 4; i++) g.fill(kx - i, t3 + 4 + i, kx + i + 1, t3 + 5 + i, col);
                for (int i = 0; i < 4; i++) g.fill(kx - 3 + i, t3 + 9 + i, kx + 4 - i, t3 + 10 + i, col);
                if (Math.abs(mouseX - kx) <= 4 && mouseY >= t3 && mouseY < t3 + 16) hoverKey = k;
            }
        } else {
            g.drawString(font, "Select a camera to see its keyframes", tlX + 4, t3 + 4, 0xFF6A6F72, false);
        }
        final RecCamera.Key hk = hoverKey;
        hits.add(new Hit(tlX, t3, tlW, 16, () -> {
            if (hk == null) return;
            e.selKey = hk;
            e.selClip = -1;
            e.seek(hk.t);
            dragKind = DRAG_KEY;
        }, null));

        // the cursor
        int cxr = xAt(now);
        g.fill(cxr - 1, t1 - 3, cxr + 1, t3 + 19, 0xFFFFF2B0);

        // bottom line: the selected clip, or what is under the mouse, or the keys
        int ly = py + ph - 20;
        RecProject.Clip sel = e.selected();
        if (sel != null) {
            int lx = px + 8;
            String txt = "Clip " + (e.selClip + 1) + "/" + e.project.clips.size() + "   " + ReplayEditor.clock(sel.from) + " - " + ReplayEditor.clock(sel.to);
            g.drawString(font, txt, lx, ly + 4, COL_TEXT, false);
            lx += font.width(txt) + 10;
            textButtonFixed(g, lx, ly, 16, 16, "-", mouseX, mouseY, () -> e.clipSpeed(-1));
            lx += 17;
            String sp = ReplayEditor.speedText(sel.speed);
            box(g, lx, ly, sw, 16, COL_PANEL);
            g.drawString(font, sp, lx + (sw - font.width(sp)) / 2, ly + 4, COL_TEXT, false);
            lx += sw + 1;
            textButtonFixed(g, lx, ly, 16, 16, "+", mouseX, mouseY, () -> e.clipSpeed(1));
            lx += 24;
            int dw = font.width("Delete clip") + 12;
            textButtonFixed(g, lx, ly, dw, 16, "Delete clip", mouseX, mouseY, e::deleteClip);
            lx += dw + 10;
            long frames = 0;
            for (RecProject.Clip c : e.project.clips) frames += RecProject.frames(c, e.project.fps);
            g.drawString(font, "Video: " + ReplayEditor.clock(frames * 1000 / e.project.fps), lx, ly + 4, COL_DIM, false);
        } else if (e.selKey != null && selCam != null) {
            String txt = selCam.name + ": keyframe at " + ReplayEditor.clock(e.selKey.t) + ", " + Math.round(e.selKey.width) + " blocks wide";
            g.drawString(font, txt, px + 8, ly + 4, COL_TEXT, false);
            int dw = font.width("Delete keyframe") + 12;
            textButton(g, px + 16 + font.width(txt), ly, dw, 16, "Delete keyframe", mouseX, mouseY, selCam.keys.size() > 1, e::deleteKey);
        } else {
            String help = hot != null ? hot.text() + "  (" + ReplayEditor.clock(hot.t()) + ")"
                    : "Space play   Left/Right 1 s   Shift+Left/Right highlights   I/O marks   Enter clip   K keyframe   Del delete   Esc back";
            drawFit(g, help, pw - 16, px + 8, ly + 4, hot != null ? COL_TEXT : COL_DIM, false);
        }
    }

    /** The cameras on the map: a rectangle each, with its name on a tab (drag it to move) and, when selected, corner handles. */
    private void drawCameras(GuiGraphics g, ReplayEditor e, long now, double ratio, int mouseX, int mouseY) {
        RecCamera selCam = e.camera();
        for (RecCamera c : e.project.cameras) {
            if (!c.active(now)) continue;
            double[] f = e.frame(c, now);
            double w = f[2], h = w / c.ratio(ratio);
            int x0 = (int) Math.round(scrX(f[0] - w / 2)), x1 = (int) Math.round(scrX(f[0] + w / 2));
            int y0 = (int) Math.round(scrY(f[1] - h / 2)), y1 = (int) Math.round(scrY(f[1] + h / 2));
            boolean sel = c == selCam;
            if (viewCam && sel) {   // darken what the video will not show
                g.fill(0, 0, width, Math.max(0, y0), 0xA0000000);
                g.fill(0, Math.min(height, y1), width, height, 0xA0000000);
                g.fill(0, Math.max(0, y0), Math.max(0, x0), Math.min(height, y1), 0xA0000000);
                g.fill(Math.min(width, x1), Math.max(0, y0), width, Math.min(height, y1), 0xA0000000);
            }
            outline(g, x0, y0, x1, y1, c.color, sel ? 2 : 1);
            String follow = c.followedAccount() != null ? "  > " + c.followedAccount() : "highlight".equals(c.follow) ? "  > highlights" : "";
            String label = c.name + follow;
            int lw = font.width(label) + 8, ly = y0 - 12;
            g.fill(x0, ly, x0 + lw, y0, c.color);
            g.drawString(font, label, x0 + 4, ly + 2, 0xFF0A0B08, false);
            final double fcx = f[0], fcz = f[1];
            hits.add(new Hit(x0, ly, lw, 12, () -> {   // select, and drag to move
                e.selectCamera(c);
                if (viewCam) return;
                dragKind = DRAG_CAM_MOVE;
                grabX = blkX(mouseX) - fcx;
                grabZ = blkZ(mouseY) - fcz;
            }, null));
            if (!sel || viewCam) continue;
            int[][] corners = {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}};
            for (int i = 0; i < 4; i++) {
                int hx = corners[i][0], hy = corners[i][1];
                boolean hover = Math.abs(mouseX - hx) <= 5 && Math.abs(mouseY - hy) <= 5;
                g.fill(hx - 4, hy - 4, hx + 4, hy + 4, 0xFF0A0B08);
                g.fill(hx - 3, hy - 3, hx + 3, hy + 3, hover ? 0xFFFFF2B0 : c.color);
                final int[] opp = corners[3 - i];
                hits.add(new Hit(hx - 5, hy - 5, 10, 10, () -> {   // resize from the opposite corner, keeping the aspect
                    dragKind = DRAG_CAM_SIZE;
                    fixedX = blkX(opp[0]);
                    fixedZ = blkZ(opp[1]);
                }, null));
            }
        }
    }

    /** The cameras panel, on the right above the timeline: the list, and the selected camera's options. */
    private void drawCameraPanel(GuiGraphics g, ReplayEditor e, double ratio, int mouseX, int mouseY, int bottom) {
        List<RecCamera> cams = e.project.cameras;
        RecCamera sel = e.camera();
        int w = 200, x = width - 8 - w, rowH = 14;
        int h = 18 + Math.max(1, cams.size()) * rowH + (sel != null ? 4 + 4 * 18 : 0) + 4;
        int y = Math.max(8, bottom - 6 - h);
        box(g, x, y, w, h, COL_PANEL);
        hits.add(new Hit(x, y, w, h, null, null));
        g.drawString(font, "Cameras", x + 6, y + 5, COL_TEXT, false);
        if (cams.isEmpty()) {
            g.drawString(font, "None: the export uses the view", x + 6, y + 18, COL_DIM, false);
        }
        int ry = y + 17;
        for (RecCamera c : cams) {
            boolean s = c == sel;
            if (s) g.fill(x + 3, ry - 1, x + w - 3, ry + rowH - 1, 0x40FFF2B0);
            g.fill(x + 6, ry + 2, x + 14, ry + 10, c.color);
            double share = terrainOf(c);
            String right = (c.export ? "" : "off  ") + c.aspect + (share < 0.999 ? "  " + Math.round(share * 100) + "%" : "");
            int rw = font.width(right);
            drawFit(g, c.name, w - 30 - rw, x + 18, ry + 2, COL_TEXT, false);
            g.drawString(font, right, x + w - 6 - rw, ry + 2, share < 0.999 ? 0xFFE0BE62 : COL_DIM, false);
            String tip = share < 0.999 ? "Only " + Math.round(share * 100) + "% of what this camera covers has terrain read: the video would have empty areas" : null;
            hits.add(new Hit(x + 3, ry - 1, w - 6, rowH, () -> e.selectCamera(c), tip));
            ry += rowH;
        }
        if (sel == null) return;
        int bw = (w - 16) / 2, bx1 = x + 6, bx2 = x + 10 + bw;
        ry = Math.max(ry, y + 17 + rowH) + 4;
        textButtonFixed(g, bx1, ry, bw, 16, "Rename", mouseX, mouseY, () -> askName(sel.name, n -> {
            sel.name = n;
            e.save();
        }));
        textButtonFixed(g, bx2, ry, bw, 16, "Aspect " + sel.aspect, mouseX, mouseY, () -> {
            sel.nextAspect();
            terrain.remove(sel);
            e.save();
        });
        ry += 18;
        textButtonFixed(g, bx1, ry, bw, 16, viewCam ? "Free view" : "View", mouseX, mouseY, () -> viewCam = !viewCam);
        textButtonFixed(g, bx2, ry, bw, 16, sel.export ? "Export: on" : "Export: off", mouseX, mouseY, () -> {
            sel.export = !sel.export;
            e.save();
        });
        ry += 18;
        String fl = sel.followedAccount() != null ? "Follow " + sel.followedAccount() : "highlight".equals(sel.follow) ? "Follow highlights" : "Follow: none";
        String flTip = "Click: none, then the highlights, then the player last clicked on the map"
                + (pickedAccount != null ? " (" + pickedAccount + ")" : ": click a player first");
        textButtonFixed(g, bx1, ry, w - 12, 16, fit(fl, w - 24), mouseX, mouseY, () -> e.nextFollow(pickedAccount));
        hits.set(hits.size() - 1, new Hit(bx1, ry, w - 12, 16, () -> e.nextFollow(pickedAccount), flTip));
        ry += 18;
        textButtonFixed(g, bx1, ry, bw, 16, "Duplicate", mouseX, mouseY, e::duplicateCamera);
        textButtonFixed(g, bx2, ry, bw, 16, "Delete", mouseX, mouseY, () -> {
            viewCam = false;
            e.deleteCamera();
        });
    }

    /** Centres the map on a highlight that has a place. */
    private void jumpTo(RecHighlights.Highlight h) {
        if (h == null || h.type() == RecHighlights.Type.START || h.type() == RecHighlights.Type.END || h.type() == RecHighlights.Type.CAPITULATION) return;
        if (viewCam) return;
        viewX = h.x();
        viewZ = h.z();
    }

    /** A drag on the timeline or a camera; true when the editor took it. */
    boolean editorDrag(double mx, double my) {
        if (ed == null || dragKind == DRAG_NONE) return false;
        long t = timeAtX(mx), len = ed.replay.length();
        RecCamera cam = ed.camera();
        switch (dragKind) {
            case DRAG_SEEK -> ed.seek(t);
            case DRAG_KEY -> {
                if (cam != null && ed.selKey != null) {
                    cam.moveKey(ed.selKey, t);
                    ed.seek(ed.selKey.t);
                }
            }
            case DRAG_CAM_MOVE -> {
                if (cam != null) {
                    double[] f = ed.frame(cam, ed.replay.time());
                    ed.selKey = cam.setKey(ed.replay.time(), blkX(mx) - grabX, blkZ(my) - grabZ, f[2]);
                }
            }
            case DRAG_CAM_SIZE -> {
                if (cam != null) {
                    double r = cam.ratio(windowRatio()), bx = blkX(mx), bz = blkZ(my);
                    double w = Math.max(16, Math.max(Math.abs(bx - fixedX), Math.abs(bz - fixedZ) * r)), h = w / r;
                    double cx = fixedX + Math.signum(bx - fixedX == 0 ? 1 : bx - fixedX) * w / 2;
                    double cz = fixedZ + Math.signum(bz - fixedZ == 0 ? 1 : bz - fixedZ) * h / 2;
                    ed.selKey = cam.setKey(ed.replay.time(), cx, cz, w);
                }
            }
            default -> {
                if (dragClip != null) {
                    if (dragKind == DRAG_FROM) dragClip.from = Math.max(0, Math.min(dragClip.to - 1000, t));
                    else if (dragKind == DRAG_TO) dragClip.to = Math.min(len, Math.max(dragClip.from + 1000, t));
                    else {
                        long l = dragClip.length(), a = Math.max(0, Math.min(len - l, t - dragGrab));
                        dragClip.from = a;
                        dragClip.to = a + l;
                    }
                }
            }
        }
        return true;
    }

    boolean editorRelease() {
        if (ed == null || dragKind == DRAG_NONE) return false;
        if (dragClip != null) {
            RecProject.Clip c = dragClip;
            ed.project.sort();
            ed.select(c);
        }
        if (dragKind != DRAG_SEEK) {
            ed.save();
            RecCamera cam = ed.camera();
            if (cam != null) terrain.remove(cam);
        }
        dragKind = DRAG_NONE;
        dragClip = null;
        return true;
    }

    /** The editor's keys; true when one was used. */
    boolean editorKey(int k, boolean shift) {
        if (listOpen && k == InputConstants.KEY_ESCAPE) {
            listOpen = false;
            return true;
        }
        ReplayEditor e = ed;
        if (e == null) return false;
        Replay r = e.replay;
        switch (k) {
            case InputConstants.KEY_ESCAPE -> {   // out of the camera view, then the camera, then the editor
                if (viewCam) viewCam = false;
                else if (e.camera() != null) e.selectCamera(null);
                else closeEditor();
            }
            case InputConstants.KEY_SPACE -> e.togglePlay();
            case InputConstants.KEY_LEFT -> {
                if (shift) jumpTo(e.jumpHighlight(-1));
                else e.seek(r.time() - 1000);
            }
            case InputConstants.KEY_RIGHT -> {
                if (shift) jumpTo(e.jumpHighlight(1));
                else e.seek(r.time() + 1000);
            }
            case InputConstants.KEY_I -> e.markIn();
            case InputConstants.KEY_O -> e.markOut();
            case InputConstants.KEY_K -> e.keyHere();
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> e.addClip();
            case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> {
                if (e.selKey != null) e.deleteKey();
                else e.deleteClip();
            }
            case InputConstants.KEY_HOME -> e.seek(0);
            case InputConstants.KEY_END -> e.seek(r.length());
            default -> {
                return false;
            }
        }
        return true;
    }
}
