package com.normandioo.hoi4map;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Aviso de captura no minimapa: quando uma cidade da guerra acompanhada muda de lado, uma faixa desce do minimapa
 * dizendo quem tomou de quem. Só olha quem segura cada cidade (o mesmo cálculo do placar); nada vai ao site.
 */
final class CaptureNotice {
    private CaptureNotice() {}

    private record Event(String city, String by, int byRgb, String from, int fromRgb, int byCountry) {}

    private static final long IN = 360, HOLD = 4400, OUT = 320;
    private static final int HEIGHT = 34;

    private static final Deque<Event> QUEUE = new ArrayDeque<>();
    private static Event current;
    private static long startedAt;

    private static String warId;
    private static WarScore.Result lastScore;
    private static final Map<String, Integer> HOLDER = new HashMap<>();

    /** Compara quem segura cada cidade com a leitura anterior; a primeira leitura de uma guerra só memoriza. */
    static void observe(SiteTerritories.Data d, SiteTerritories.WarInfo w) {
        if (w == null || w.sides == null || w.sides.size() < 2) return;
        String id = w.id == null ? w.name : w.id;
        WarScore.Result score = WarScore.of(d, w);
        if (id != null && id.equals(warId) && score == lastScore) return;
        boolean first = !(id != null && id.equals(warId));
        if (first) HOLDER.clear();
        warId = id;
        lastScore = score;
        for (WarScore.Holding h : score.holdings()) {
            String key = WarScore.key(h.locality().name, h.locality().country);
            int now = h.holder();
            Integer before = HOLDER.put(key, now);
            if (first || before == null || before < 0 || now < 0 || before == now) continue;
            QUEUE.add(new Event(SiteTerritories.splitCamel(h.locality().name), sideLabel(d, w, now), MapScreen.minimapCtlColor(d, now) & 0xFFFFFF,
                    sideLabel(d, w, before), MapScreen.minimapCtlColor(d, before) & 0xFFFFFF, firstCountry(d, w, now)));
        }
        while (QUEUE.size() > 4) QUEUE.poll();
    }

    private static int firstCountry(SiteTerritories.Data d, SiteTerritories.WarInfo w, int side) {
        java.util.Iterator<Integer> it = Relations.countriesOf(d, w.sides.get(side)).iterator();
        return it.hasNext() ? it.next() : -1;
    }

    private static String sideLabel(SiteTerritories.Data d, SiteTerritories.WarInfo w, int side) {
        java.util.Set<Integer> countries = Relations.countriesOf(d, w.sides.get(side));
        if (countries.size() == 1) return d.labels[countries.iterator().next()];
        String n = w.sides.get(side).name;
        if ("Attackers".equalsIgnoreCase(n)) return "Atacantes";
        if ("Defenders".equalsIgnoreCase(n)) return "Defensores";
        return n == null || n.isBlank() ? "Lado " + (side + 1) : n;
    }

    static boolean active() {
        return current != null || !QUEUE.isEmpty();
    }

    /** Desenha a faixa na área x..x+w; above = a faixa fica acima do ponto y (minimapa no canto de baixo). */
    static void draw(GuiGraphics g, Font font, SiteTerritories.Data d, int x, int y, int w, boolean above) {
        long now = System.currentTimeMillis();
        if (current == null) {
            current = QUEUE.poll();
            startedAt = now;
            if (current == null) return;
        }
        long t = now - startedAt;
        double shown;
        if (t < IN) {
            double u = t / (double) IN;
            shown = 1 - (1 - u) * (1 - u);
        } else if (t < IN + HOLD) {
            shown = 1;
        } else if (t < IN + HOLD + OUT) {
            double u = (t - IN - HOLD) / (double) OUT;
            shown = 1 - u * u;
        } else {
            current = null;
            return;
        }
        int top = above ? y - HEIGHT : y;
        g.enableScissor(x, top, x + w, top + HEIGHT);
        int off = (int) Math.round((1 - shown) * HEIGHT * (above ? 1 : -1));
        int by0 = top + off;
        g.fill(x, by0, x + w, by0 + HEIGHT, 0xF21E2123);
        g.fill(x, by0 + HEIGHT - 2, x + w, by0 + HEIGHT, 0xFF3E4245);
        int fx = x + 6, fy = by0 + 7;
        g.fill(fx, fy, fx + 24, fy + 18, 0xFF0A0B08);
        RemoteImages.Img flag = current.byCountry() >= 0 ? MapScreen.noticeFlag(d, current.byCountry()) : null;
        if (flag != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, flag.id(), fx + 1, fy + 1, 0f, 0f, 22, 16, flag.w(), flag.h(), flag.w(), flag.h());
        } else {
            g.fill(fx + 1, fy + 1, fx + 23, fy + 17, 0xFF000000 | current.byRgb());
        }
        int tx = x + 36, room = w - 42;
        String city = fit(font, current.city(), room);
        g.drawString(font, city, tx, by0 + 6, 0xFFECEBE4, false);
        String by = current.by(), mid = " tomou de ", from = current.from();
        int avail = room - font.width(mid);
        int half = Math.max(8, avail / 2);
        by = fit(font, by, Math.max(half, avail - font.width(from)));
        from = fit(font, from, Math.max(8, avail - font.width(by)));
        int cx = tx;
        g.drawString(font, by, cx, by0 + 18, 0xFF000000 | current.byRgb(), false);
        cx += font.width(by);
        g.drawString(font, mid, cx, by0 + 18, 0xFFBFC2C2, false);
        cx += font.width(mid);
        g.drawString(font, from, cx, by0 + 18, 0xFF000000 | current.fromRgb(), false);
        g.disableScissor();
    }

    private static String fit(Font font, String s, int max) {
        if (font.width(s) <= max) return s;
        while (s.length() > 1 && font.width(s + "..") > max) s = s.substring(0, s.length() - 1);
        return s + "..";
    }
}
