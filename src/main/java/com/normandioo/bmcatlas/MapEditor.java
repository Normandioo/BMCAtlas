package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The replay editor, a mode of the map screen (video plan, phase 3a): the list of recordings, and for the one opened the map
 * with a timeline under it: the recording with its sessions, time away and highlights; the clips of the video; transport and
 * clip buttons. The map above stays free to move and zoom. MapScreen draws the map, then calls drawEditor instead of its menus.
 */
abstract class MapEditor extends MapPanels {
    MapEditor(Component title) {
        super(title);
    }

    /** The recording open in the editor, or null. */
    ReplayEditor ed;
    /** The list of recordings is open. */
    boolean listOpen;
    private List<Path> listDirs = List.of();
    private List<RecFormat.Meta> listMeta = List.of();
    private int listFirst;

    // timeline geometry (set while drawing) and what is being dragged
    private int tlX, tlW;
    private static final int DRAG_NONE = 0, DRAG_SEEK = 1, DRAG_MOVE = 2, DRAG_FROM = 3, DRAG_TO = 4;
    private int dragKind;
    private long dragGrab;
    private RecProject.Clip dragClip;

    boolean editing() {
        return ed != null;
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
        g.drawString(font, fit("Open one to watch it, pick highlights and make clips", w - 90), x + 8, y + 20, COL_DIM, false);
        textButtonFixed(g, x + w - 52, y + 5, 46, 16, "Close", mouseX, mouseY, () -> listOpen = false);
        int ry = y + 36;
        if (listDirs.isEmpty()) {
            g.drawString(font, "No recordings yet: turn on Recording on the war map.", x + 8, ry + 6, COL_DIM, false);
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
            g.drawString(font, fit(name == null ? "?" : name, w - 20), x + 10, ry + 2, COL_TEXT, false);
            g.drawString(font, fit(sub, w - 20), x + 10, ry + 12, COL_DIM, false);
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

    void drawEditor(GuiGraphics g, int mouseX, int mouseY) {
        ReplayEditor e = ed;
        Replay r = e.replay;
        WarRecorder.advance();
        SiteTerritories.Data d = SiteTerritories.data();

        // top left: the war, the moment on screen, the score
        int ix = 8, iy = 8, iw = 240;
        boolean score = e.war != null && e.war.sides != null && e.war.sides.size() >= 2 && d != null;
        box(g, ix, iy, iw, score ? 46 : 32, COL_PANEL);
        hits.add(new Hit(ix, iy, iw, score ? 46 : 32, null, null));
        g.drawString(font, fit(e.title, iw - 12), ix + 6, iy + 5, COL_TEXT, false);
        String when = MapFormat.dateText(r.absTime()) + "  " + ReplayEditor.clock(r.time()) + " / " + ReplayEditor.clock(r.length());
        g.drawString(font, fit(when, iw - 12), ix + 6, iy + 17, r.recorded(r.time()) ? 0xFFF0B429 : COL_DIM, false);
        if (score) sideBar(g, e.war, WarScore.of(d, e.war).points().clone(), ix + 6, iy + 31, iw - 12);

        // bottom: transport, the two tracks, the clip line
        int px = 8, pw = width - 16, ph = 92, py = height - ph - 8;
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
        int closeW = font.width("Close") + 12, expW = font.width("Export") + 12;
        textButton(g, px + pw - 4 - closeW - 2 - expW, by, expW, 16, "Export", mouseX, mouseY, false, () -> {});
        textButtonFixed(g, px + pw - 4 - closeW, by, closeW, 16, "Close", mouseX, mouseY, this::closeEditor);

        // track 1: the recording (sessions light, time away dark), hour marks, highlights, In/Out marks
        tlX = px + 8;
        tlW = pw - 16;
        int t1 = py + 26, t2 = py + 50;
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
            int a = xAt(hc.from), b = xAt(hc.to);
            dragClip = hc;
            dragKind = Math.abs(mouseX - a) <= 3 ? DRAG_FROM : Math.abs(mouseX - b) <= 3 ? DRAG_TO : DRAG_MOVE;
            dragGrab = timeAtX(mouseX) - hc.from;
        }, null));

        // the cursor
        int cxr = xAt(r.time());
        g.fill(cxr - 1, t1 - 3, cxr + 1, t2 + 19, 0xFFFFF2B0);

        // bottom line: the selected clip, or what is under the mouse, or the keys
        int ly = py + ph - 18;
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
        } else {
            String help = hot != null ? hot.text() + "  (" + ReplayEditor.clock(hot.t()) + ")"
                    : "Space play   Left/Right 1 s   Shift+Left/Right highlights   I/O marks   Enter clip   Del delete   Esc close";
            g.drawString(font, fit(help, pw - 16), px + 8, ly + 4, hot != null ? COL_TEXT : COL_DIM, false);
        }
    }

    /** Centres the map on a highlight that has a place. */
    private void jumpTo(RecHighlights.Highlight h) {
        if (h == null || h.type() == RecHighlights.Type.START || h.type() == RecHighlights.Type.END || h.type() == RecHighlights.Type.CAPITULATION) return;
        viewX = h.x();
        viewZ = h.z();
    }

    /** A drag on the timeline; true when the editor took it. */
    boolean editorDrag(double mx) {
        if (ed == null || dragKind == DRAG_NONE) return false;
        long t = timeAtX(mx), len = ed.replay.length();
        if (dragKind == DRAG_SEEK) ed.seek(t);
        else if (dragClip != null) {
            if (dragKind == DRAG_FROM) dragClip.from = Math.max(0, Math.min(dragClip.to - 1000, t));
            else if (dragKind == DRAG_TO) dragClip.to = Math.min(len, Math.max(dragClip.from + 1000, t));
            else {
                long l = dragClip.length(), a = Math.max(0, Math.min(len - l, t - dragGrab));
                dragClip.from = a;
                dragClip.to = a + l;
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
            ed.save();
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
            case InputConstants.KEY_ESCAPE -> closeEditor();
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
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> e.addClip();
            case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> e.deleteClip();
            case InputConstants.KEY_HOME -> e.seek(0);
            case InputConstants.KEY_END -> e.seek(r.length());
            default -> {
                return false;
            }
        }
        return true;
    }
}
