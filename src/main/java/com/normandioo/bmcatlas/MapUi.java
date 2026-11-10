package com.normandioo.bmcatlas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * The drawing pieces the map screen's menus are built from: frames, buttons, headers, table rows, scrolling and tabs,
 * with the layout state they share (clickable areas, the current box and its cursor). MapScreen extends this class.
 */
abstract class MapUi extends Screen {
    MapUi(Component title) {
        super(title);
    }

    // paleta aço e grafite; o dourado fica só no que está ativo
    static final int COL_GOLD = 0xFFCFD3D1;

    static final int COL_GOLD_DIM = 0xFF4A4F52;

    static final int COL_PANEL = 0xF226292B;

    static final int COL_PANEL_2 = 0xFF1A1C1B;

    static final int COL_TEXT = 0xFFE8E2D0;

    static final int COL_DIM = 0xFFA3A7A8;

    static final int COL_LINK = 0xFFB4C3CE;

    /** Cabeçalho sem país (sem caixa de bandeira). */
    static final int NEUTRAL = -1;

    // UI em modo imediato: cada quadro registra as áreas clicáveis
    record Hit(int x, int y, int w, int h, Runnable action, String tip) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    List<Hit> hits = new ArrayList<>();

    List<Hit> prevHits = new ArrayList<>();

    // caixa atual dos helpers (painel ou cartão)
    int bx, by, bw, bh;

    int cy;          // cursor vertical

    int rowIdx;

    int scrollTop, scrollBottom;

    boolean headRed;              // próximo cabeçalho em vermelho (guerra)

    void segment(GuiGraphics g, double x0, double y0, double x1, double y1, int color, float thick) {
        if ((x0 < 0 && x1 < 0) || (x0 > width && x1 > width) || (y0 < 0 && y1 < 0) || (y0 > height && y1 > height)) return;
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 0.3) return;
        g.pose().pushMatrix();
        g.pose().translate((float) x0, (float) y0);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.pose().translate(0f, -thick / 2f);
        g.pose().scale(1f, thick);
        g.fill(0, 0, (int) Math.ceil(len) + 1, 1, color);
        g.pose().popMatrix();
    }

    /** Moldura de bronze chanfrada, com rebites nos cantos (molduras grandes). */
    void box(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, 0xFF0E0C08);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF6A6F72);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF7E8387);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFF7E8387);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF3E4245);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF3E4245);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF0E0C08);
        g.fill(x + 4, y + 4, x + w - 4, y + h - 4, fill);
        if (w >= 60 && h >= 40) {
            int[][] corner = {{x + 1, y + 1}, {x + w - 4, y + 1}, {x + 1, y + h - 4}, {x + w - 4, y + h - 4}};
            for (int[] c : corner) {
                g.fill(c[0], c[1], c[0] + 3, c[1] + 3, 0xFFCFD3D1);
                g.fill(c[0] + 2, c[1] + 2, c[0] + 3, c[1] + 3, 0xFF6A6F72);
            }
        }
    }

    /** Botão chanfrado de bronze (ativo = aceso em dourado). */
    void bevel(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean active) {
        int base = active ? 0xFF4A3F22 : hover ? 0xFF3A3E42 : 0xFF2A2D30;
        int light = active ? 0xFFE0BE62 : hover ? 0xFF9AA0A4 : 0xFF6A6F72;
        g.fill(x, y, x + w, y + h, 0xFF0E0F0D);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, base);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, light);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF14110A);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF14110A);
    }

    void iconButton(GuiGraphics g, int x, int y, int size, String glyph, boolean active, boolean enabled,
                            int mouseX, int mouseY, String tip, Runnable action) {
        boolean hover = enabled && mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        bevel(g, x, y, size, size, hover, active);
        int main = !enabled ? 0xFF6F6D63 : active ? 0xFFF6E7AE : hover ? 0xFFF2ECD8 : 0xFFD6D6D0;
        int accent = !enabled ? 0xFF5A584E : active ? 0xFFE0B840 : 0xFF8F959A;
        int shade = !enabled ? 0xFF3A3932 : 0xFF4A4F52;
        Glyphs.draw(g, glyph, x + (size - 14) / 2, y + (size - 14) / 2, main, accent, shade);
        hits.add(new Hit(x, y, size, size, enabled ? action : null, tip));
    }

    boolean visible(int y, int h) {
        return y >= scrollTop && y + h <= scrollBottom;
    }

    /** Botão de texto dentro da área rolável (só clicável quando inteiro à vista). */
    void textButton(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY, boolean enabled, Runnable action) {
        boolean vis = visible(y, h);
        boolean hover = vis && enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        bevel(g, x, y, w, h, hover, false);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, enabled ? (hover ? 0xFFFFFFFF : COL_TEXT) : COL_DIM, false);
        if (vis && enabled) hits.add(new Hit(x, y, w, h, action, null));
    }

    /** Texto com um pixel a mais entre as letras (títulos). */
    void spaced(GuiGraphics g, String text, int x, int y, int color, boolean shadow) {
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            g.drawString(font, c, x, y, color, shadow);
            x += font.width(c) + 1;
        }
    }

    int spacedWidth(String text) {
        return font.width(text) + Math.max(0, text.length() - 1);
    }

    String fitSpaced(String text, int maxW) {
        if (spacedWidth(text) <= maxW) return text;
        String t = text;
        while (t.length() > 1 && spacedWidth(t + "...") > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    /** Imagem (bandeira) dentro de uma caixa, mantendo a proporção. */
    void drawImage(GuiGraphics g, RemoteImages.Img img, int x, int y, int maxW, int maxH) {
        double sc = Math.min((double) maxW / img.w(), (double) maxH / img.h());
        int dw = Math.max(1, (int) Math.round(img.w() * sc));
        int dh = Math.max(1, (int) Math.round(img.h() * sc));
        int dx = x + (maxW - dw) / 2, dy = y + (maxH - dh) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, img.id(), dx, dy, 0f, 0f, dw, dh, img.w(), img.h(), img.w(), img.h());
    }

    String flagOf(SiteTerritories.Data d, int country) {
        if (country < 0 || d.info[country] == null) return null;
        return SiteTerritories.assetUrl(d.info[country].get("flag"), false);
    }

    /**
     * Cabeçalho fixo. Com país (rgb diferente de NEUTRAL) mostra a caixa da bandeira e o subtítulo;
     * sem país é só o título (menus como Diplomacia, Guerra e a lista não têm bandeira).
     */
    int header(GuiGraphics g, int rgb, String title, String subtitle, String flagUrl, int mouseX, int mouseY, Runnable close) {
        boolean country = rgb != NEUTRAL;
        int x = bx, y = by, w = bw;
        int hh = country ? 46 : subtitle != null ? 36 : 28;
        g.fill(x + 4, y + 4, x + w - 4, y + hh, 0xFF33373A);
        g.fill(x + 4, y + 4, x + w - 4, y + 5, 0xFF050605);
        g.fill(x + 4, y + hh, x + w - 4, y + hh + 1, COL_GOLD_DIM);
        if (headRed) g.fill(x + 4, y + 4, x + w - 4, y + hh, 0xFF4A1E19);
        if (!country) g.fill(x + 4, y + hh - 3, x + w - 4, y + hh, headRed ? 0xFFB0382C : 0xFF7E8387);
        int tx = x + 12;
        if (country) {
            g.fill(x + 4, y + hh - 3, x + w - 4, y + hh, 0xFF000000 | rgb);
            g.fill(x + 9, y + 9, x + 51, y + 37, 0xFF0E0C08);
            g.fill(x + 10, y + 10, x + 50, y + 36, 0xFF6A6F72);
            g.fill(x + 11, y + 11, x + 49, y + 35, 0xFF05080C);
            RemoteImages.Img flag = RemoteImages.flag(flagUrl);
            if (flag != null) {
                drawImage(g, flag, x + 11, y + 11, 38, 24);
            } else {
                g.fill(x + 11, y + 11, x + 49, y + 35, rgb | 0xFF000000);
                g.fill(x + 11, y + 11, x + 49, y + 17, lighten(rgb | 0xFF000000, 1.25f));
            }
            tx = x + 60;
        }
        int maxW = x + w - 30 - tx;
        int ty = country ? y + 12 : subtitle != null ? y + 9 : y + 3 + (hh - 3 - 8) / 2;
        spaced(g, fitSpaced(title.toUpperCase(Locale.ROOT), maxW), tx, ty, 0xFFECEBE4, true);
        if (subtitle != null) g.drawString(font, fit(subtitle, maxW), tx, ty + 13, COL_DIM, false);
        int cx = x + w - 22, cyy = y + 7;
        boolean hover = mouseX >= cx && mouseX < cx + 14 && mouseY >= cyy && mouseY < cyy + 14;
        g.fill(cx, cyy, cx + 14, cyy + 14, 0xFF0A0B08);
        g.fill(cx + 1, cyy + 1, cx + 13, cyy + 13, hover ? 0xFF7A2A22 : 0xFF5A1F1A);
        g.fill(cx + 1, cyy + 1, cx + 13, cyy + 2, 0xFFA24A3F);
        g.fill(cx + 1, cyy + 1, cx + 2, cyy + 13, 0xFFA24A3F);
        g.drawString(font, "X", cx + 5, cyy + 3, 0xFFF4C4B4, false);
        hits.add(new Hit(cx, cyy, 14, 14, close, "Close"));
        headRed = false;
        return y + hh + 5;
    }

    static int darken(int c, float k) {
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * k) << 16) | ((int) (((c >> 8) & 0xFF) * k) << 8) | (int) ((c & 0xFF) * k);
    }

    static int lighten(int c, float k) {
        return 0xFF000000 | (Math.min(255, (int) (((c >> 16) & 0xFF) * k)) << 16)
                | (Math.min(255, (int) (((c >> 8) & 0xFF) * k)) << 8) | Math.min(255, (int) ((c & 0xFF) * k));
    }

    String fit(String s, int maxWidth) {
        if (s == null) return "";
        return font.width(s) <= maxWidth ? s : font.plainSubstrByWidth(s, maxWidth - font.width("...")) + "...";
    }

    List<String> wrap(String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String test = line.length() == 0 ? word : line + " " + word;
            if (line.length() == 0 || font.width(test) <= maxW) {
                line = new StringBuilder(test);
            } else {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        for (int i = 0; i < out.size(); i++) out.set(i, fit(out.get(i), maxW));
        return out;
    }

    /** Começa a área rolável da caixa atual, abaixo do cabeçalho; reserve = altura fixa no fim. */
    void beginScroll(GuiGraphics g, int top, double scroll, int reserve) {
        scrollTop = top;
        scrollBottom = by + bh - 4 - reserve;
        g.enableScissor(bx + 4, top, bx + bw - 4, scrollBottom);
        cy = top + 4 - (int) scroll;
        rowIdx = 0;
    }

    /** Fecha a área rolável; devolve a rolagem corrigida. */
    double endScroll(GuiGraphics g, double scroll) {
        g.disableScissor();
        int contentH = cy - (scrollTop + 4 - (int) scroll) + 8;
        int viewH = scrollBottom - scrollTop;
        double max = Math.max(0, contentH - viewH);
        double fixed = Math.max(0, Math.min(scroll, max));
        if (max > 0) {
            int trackX = bx + bw - 4;
            g.fill(trackX, scrollTop + 2, trackX + 2, scrollBottom - 2, 0x30FFFFFF);
            int thumb = Math.max(14, (int) ((viewH - 4) * (viewH / (double) contentH)));
            int ty = scrollTop + 2 + (int) ((viewH - 4 - thumb) * (fixed / max));
            g.fill(trackX, ty, trackX + 2, ty + thumb, COL_GOLD_DIM);
        }
        return fixed;
    }

    void section(GuiGraphics g, String title) {
        cy += 4;
        g.fill(bx + 6, cy, bx + bw - 6, cy + 13, 0xFF18191B);
        g.fill(bx + 6, cy, bx + bw - 6, cy + 1, 0xFF050605);
        g.fill(bx + 6, cy + 12, bx + bw - 6, cy + 13, 0xFF3E4245);
        int dx = bx + 13, dy = cy + 6;
        g.fill(dx, dy - 3, dx + 1, dy + 4, COL_GOLD);
        g.fill(dx - 3, dy, dx + 4, dy + 1, COL_GOLD);
        g.fill(dx - 1, dy - 1, dx + 2, dy + 2, COL_GOLD);
        String shown = fitSpaced(title.toUpperCase(Locale.ROOT), bw - 40);
        spaced(g, shown, bx + 22, cy + 3, COL_GOLD, false);
        int ruleX = bx + 22 + spacedWidth(shown) + 6;
        if (ruleX < bx + bw - 12) g.fill(ruleX, cy + 6, bx + bw - 12, cy + 7, 0xFF3E4245);
        cy += 17;
        rowIdx = 0;
    }

    void kv(GuiGraphics g, String label, String value) {
        kvImpl(g, label, value, null, 0, 0);
    }

    void kvLink(GuiGraphics g, String label, String value, int mouseX, int mouseY, Runnable action) {
        kvImpl(g, label, value, action, mouseX, mouseY);
    }

    /** Linha da tabela: rótulo à esquerda, valor (com quebra de linha) à direita; com link, o valor é clicável. */
    void kvImpl(GuiGraphics g, String label, String value, Runnable link, int mouseX, int mouseY) {
        if (value == null || value.isBlank()) return;
        int labelCol = 84;
        int valueX = bx + 9 + labelCol;
        List<String> lines = wrap(value, bw - 16 - labelCol - 10);
        int h = Math.max(1, lines.size()) * 10 + 3;
        rowIdx++;
        g.fill(valueX - 4, cy - 1, bx + bw - 7, cy + h - 1, 0xFF161816);
        g.fill(valueX - 4, cy - 1, bx + bw - 7, cy, 0xFF0A0B0A);
        g.fill(valueX - 4, cy + h - 2, bx + bw - 7, cy + h - 1, 0xFF2E3235);
        g.drawString(font, label, bx + 9, cy + 1, COL_DIM, false);
        boolean vis = visible(cy, h);
        boolean hover = link != null && vis && mouseX >= valueX && mouseX < bx + bw - 8 && mouseY >= cy && mouseY < cy + h;
        int color = link == null ? COL_TEXT : hover ? 0xFFDDE7EE : COL_LINK;
        int ly = cy + 1;
        for (String line : lines) {
            g.drawString(font, line, valueX, ly, color, false);
            if (hover) g.fill(valueX, ly + 9, valueX + font.width(line), ly + 10, color);
            ly += 10;
        }
        if (link != null && vis) hits.add(new Hit(valueX, cy, bx + bw - 8 - valueX, h, link, null));
        cy += h;
    }

    void paragraph(GuiGraphics g, String text) {
        paragraph(g, text, COL_TEXT);
    }

    void paragraph(GuiGraphics g, String text, int color) {
        for (String line : wrap(text, bw - 20)) {
            g.drawString(font, line, bx + 10, cy, color, false);
            cy += 10;
        }
        cy += 2;
    }

    void squareRing(GuiGraphics g, int cx, int cyy, int r, int argb) {
        g.fill(cx - r, cyy - r, cx + r + 1, cyy - r + 1, argb);
        g.fill(cx - r, cyy + r, cx + r + 1, cyy + r + 1, argb);
        g.fill(cx - r, cyy - r + 1, cx - r + 1, cyy + r, argb);
        g.fill(cx + r, cyy - r + 1, cx + r + 1, cyy + r, argb);
    }

    void dottedLine(GuiGraphics g, double x0, double y0, double x1, double y1, int color) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 4) return;
        // só a parte da linha que cabe na tela (Liang-Barsky); o resto não gera nenhum desenho
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {x0, width - x0, y0, height - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, r);
                else t1 = Math.min(t1, r);
            }
        }
        if (t0 >= t1) return;
        int steps = (int) Math.min(len / 8, 4000);
        if (steps == 0) return;
        int from = (int) Math.ceil(t0 * steps), to = (int) Math.floor(t1 * steps);
        for (int i = from; i <= to; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(x0 + dx * t), y = (int) Math.round(y0 + dy * t);
            g.fill(x, y, x + 2, y + 2, color);
        }
    }

    /** Botão de texto fora da área rolável. */
    void textButtonFixed(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        bevel(g, x, y, w, h, hover, false);
        g.drawString(font, label, x + 6, y + (h - 8) / 2, hover ? 0xFFFFFFFF : COL_TEXT, false);
        hits.add(new Hit(x, y, w, h, action, null));
    }

    /** Linha com um botão de ligar/desligar. */
    void toggleRow(GuiGraphics g, String label, boolean value, int mouseX, int mouseY, Runnable toggle) {
        int x = bx + 9, w = bw - 18, y = cy;
        boolean vis = visible(y, 16);
        boolean hover = vis && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 16;
        bevel(g, x, y, w, 16, hover, false);
        g.drawString(font, fit(label, w - 60), x + 6, y + 4, COL_TEXT, false);
        String state = value ? "On" : "Off";
        g.fill(x + w - 12, y + 5, x + w - 5, y + 12, 0xFF0E0C08);
        g.fill(x + w - 11, y + 6, x + w - 6, y + 11, value ? 0xFF4CD964 : 0xFF5A584E);
        g.drawString(font, state, x + w - 16 - font.width(state), y + 4, value ? 0xFF9BE59B : COL_DIM, false);
        if (vis) hits.add(new Hit(x, y, w, 16, toggle, null));
        cy += 19;
    }

    /** Linha com uma faixa de passos: clique num trecho para escolher o valor. */
    void stepsRow(GuiGraphics g, String label, String shown, int count, int current, int mouseX, int mouseY, Consumer<Integer> pick) {
        g.drawString(font, fit(label, bw - 24 - font.width(shown)), bx + 9, cy, COL_TEXT, false);
        g.drawString(font, shown, bx + bw - 10 - font.width(shown), cy, COL_LINK, false);
        cy += 11;
        int x = bx + 9, w = bw - 18, y = cy;
        int seg = Math.max(2, w / count);
        int total = seg * count;
        x += (w - total) / 2;
        g.fill(x - 1, y - 1, x + total + 1, y + 13, 0xFF0E0C08);
        boolean vis = visible(y, 12);
        for (int i = 0; i < count; i++) {
            int sx = x + i * seg;
            boolean hover = vis && mouseX >= sx && mouseX < sx + seg && mouseY >= y && mouseY < y + 12;
            int fill = i <= current ? (hover ? 0xFF9AA0A4 : 0xFF7E8387) : (hover ? 0xFF3E4245 : 0xFF2A2D30);
            g.fill(sx, y, sx + seg - 1, y + 12, fill);
            g.fill(sx, y, sx + seg - 1, y + 1, i <= current ? 0xFF9AA0A4 : 0xFF3A3E42);
            final int idx = i;
            if (vis) hits.add(new Hit(sx, y, seg, 12, () -> pick.accept(idx), null));
        }
        cy += 17;
    }

    /** Linha com botões lado a lado (uma escolha). */
    void choiceRow(GuiGraphics g, String label, String[] options, int sel, int mouseX, int mouseY, Consumer<Integer> pick) {
        g.drawString(font, fit(label, bw - 20), bx + 9, cy, COL_TEXT, false);
        cy += 11;
        int n = options.length, gap = 3;
        int w = (bw - 18 - gap * (n - 1)) / n;
        int x = bx + 9;
        boolean vis = visible(cy, 15);
        for (int i = 0; i < n; i++) {
            boolean hover = vis && mouseX >= x && mouseX < x + w && mouseY >= cy && mouseY < cy + 15;
            bevel(g, x, cy, w, 15, hover, i == sel);
            String t = fit(options[i], w - 6);
            g.drawString(font, t, x + (w - font.width(t)) / 2, cy + 4, i == sel ? 0xFFFFF2B0 : COL_TEXT, false);
            final int idx = i;
            if (vis) hits.add(new Hit(x, cy, w, 15, () -> pick.accept(idx), null));
            x += w + gap;
        }
        cy += 19;
    }

    int tabStrip(GuiGraphics g, int top, String[] labels, int sel, int mouseX, int mouseY, Consumer<Integer> pick) {
        int n = labels.length, gap = 2, avail = bw - 16 - gap * (n - 1), sum = 0;
        for (String l : labels) sum += font.width(l);
        int extra = Math.max(2, (avail - sum) / n);
        g.fill(bx + 4, top, bx + bw - 4, top + 18, 0xFF18191B);
        g.fill(bx + 4, top + 17, bx + bw - 4, top + 18, 0xFF7E8387);
        int x = bx + 8, y = top + 2;
        for (int i = 0; i < n; i++) {
            int w = i == n - 1 ? bx + bw - 8 - x : font.width(labels[i]) + extra;
            boolean on = i == sel;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 15;
            int line = on ? 0xFFE0BE62 : hover ? 0xFF7E8387 : 0xFF3E4245;
            g.fill(x, y, x + w, y + (on ? 16 : 15), line);
            g.fill(x + 1, y + 1, x + w - 1, y + (on ? 16 : 15), on ? 0xFF4A3F22 : hover ? 0xFF2A2D30 : 0xFF1E2123);
            String t = fit(labels[i], w - 2);
            g.drawString(font, t, x + (w - font.width(t)) / 2, y + 4, on ? 0xFFF6E7AE : hover ? COL_TEXT : COL_DIM, false);
            final int idx = i;
            hits.add(new Hit(x, y, w, 15, () -> pick.accept(idx), null));
            x += w + gap;
        }
        return top + 21;
    }

    /** Texto clicável (sublinha ao passar o mouse); devolve a largura. */
    int link(GuiGraphics g, String text, int x, int y, boolean clickable, int mouseX, int mouseY, Runnable action) {
        int w = font.width(text);
        boolean vis = visible(y - 1, 11);
        boolean hover = clickable && vis && mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 10;
        g.drawString(font, text, x, y, !clickable ? COL_TEXT : hover ? 0xFFDDE7EE : COL_LINK, false);
        if (hover) g.fill(x, y + 9, x + w, y + 10, 0xFFDDE7EE);
        if (clickable && vis) hits.add(new Hit(x, y - 1, w, 11, action, null));
        return w;
    }

    void miniFlag(GuiGraphics g, SiteTerritories.Data d, int country, int x, int y) {
        g.fill(x, y, x + 22, y + 12, 0xFF05080C);
        if (country < 0) return;
        RemoteImages.Img flag = RemoteImages.flag(flagOf(d, country));
        if (flag != null) drawImage(g, flag, x + 1, y + 1, 20, 10);
        else g.fill(x + 1, y + 1, x + 21, y + 11, d.rgb[country] | 0xFF000000);
    }

    void zebra(GuiGraphics g, int rowTop, int h, boolean hover, boolean selected) {
        if (selected) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + h, 0x508F959A);
        else if (hover) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + h, 0x308F959A);
        else if ((rowIdx & 1) == 0) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + h, 0x14FFFFFF);
    }
}
