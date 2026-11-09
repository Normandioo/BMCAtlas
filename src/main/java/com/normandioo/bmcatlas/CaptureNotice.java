package com.normandioo.bmcatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aviso de cidade tomada: uma caixa que sai de trás do minimapa, com a cidade e quem tomou de quem.
 * Com o chat aberto (cursor visível), clicar nela abre o mapa na cidade.
 */
final class CaptureNotice {
    private CaptureNotice() {}

    private static final long IN = 350, HOLD = 4400, OUT = 320;
    private static final long QUIET = 10_000;      // a mesma cidade de novo neste intervalo: sem aviso
    private static final int MAX_QUEUE = 5, GROUP_FROM = 4, BOX_H = 26;

    record Item(SiteTerritories.Locality city, String key, String title, String by, int byRgb, String from, int fromRgb, int country) {}

    private static final ArrayDeque<Item> QUEUE = new ArrayDeque<>();
    private static final Map<String, Long> LAST = new HashMap<>();
    private static Item current;
    private static long startedAt;
    private static boolean wasDown;

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
        List<Item> changes = new java.util.ArrayList<>();
        for (WarScore.Holding h : score.holdings()) {
            String key = WarScore.key(h.locality().name, h.locality().country);
            int now = h.holder();
            Integer before = HOLDER.put(key, now);
            if (first || before == null || before < 0 || now < 0 || before == now) continue;
            changes.add(new Item(h.locality(), key, titleOf(h.locality()), sideLabel(d, w, now), MapScreen.minimapCtlColor(d, now) & 0xFFFFFF,
                    sideLabel(d, w, before), MapScreen.minimapCtlColor(d, before) & 0xFFFFFF, firstCountry(d, w, now)));
        }
        pushAll(changes, System.currentTimeMillis());
    }

    /** Nome da cidade com o tamanho dela (Small, Medium, Large, capital...). */
    private static String titleOf(SiteTerritories.Locality l) {
        String name = SiteTerritories.splitCamel(l.name);
        String kind = l.kind == null || l.kind.isBlank() ? "" : SiteTerritories.splitCamel(l.kind);
        return kind.isEmpty() ? name : name + "  -  " + kind;
    }

    private static int firstCountry(SiteTerritories.Data d, SiteTerritories.WarInfo w, int side) {
        java.util.Iterator<Integer> it = Relations.countriesOf(d, w.sides.get(side)).iterator();
        return it.hasNext() ? it.next() : -1;
    }

    private static String sideLabel(SiteTerritories.Data d, SiteTerritories.WarInfo w, int side) {
        java.util.Set<Integer> countries = Relations.countriesOf(d, w.sides.get(side));
        if (countries.size() == 1) return d.labels[countries.iterator().next()];
        String n = w.sides.get(side).name;
        if ("Attackers".equalsIgnoreCase(n)) return "Attackers";
        if ("Defenders".equalsIgnoreCase(n)) return "Defenders";
        return n == null || n.isBlank() ? "Side " + (side + 1) : n;
    }

    static void clear() {
        warId = null;
        lastScore = null;
        HOLDER.clear();
        QUEUE.clear();
        LAST.clear();
        current = null;
    }

    /** Recebe as cidades que mudaram de lado numa mesma leitura. */
    static void pushAll(List<Item> changes, long now) {
        if (changes.isEmpty()) return;
        List<Item> fresh = new java.util.ArrayList<>();
        for (Item it : changes) {
            Long last = LAST.put(it.key(), now);
            if (last != null && now - last < QUIET) {       // vai e vem: tira o aviso anterior e não repete
                QUEUE.removeIf(q -> q.key().equals(it.key()));
                if (current != null && current.key().equals(it.key())) current = null;
                continue;
            }
            fresh.add(it);
        }
        if (fresh.size() >= GROUP_FROM) {       // muitas de uma vez (capitulação): um aviso só
            Map<String, Integer> count = new HashMap<>();
            Item top = fresh.get(0);
            for (Item it : fresh) {
                int n = count.merge(it.by(), 1, Integer::sum);
                if (n > count.getOrDefault(top.by(), 0)) top = it;
            }
            offer(new Item(top.city(), top.key(), fresh.size() + " cities taken", top.by(), top.byRgb(), "", 0, top.country()));
            return;
        }
        for (Item it : fresh) offer(it);
    }

    private static void offer(Item it) {
        if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
        QUEUE.addLast(it);
    }

    private static int light(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return 0xFF000000 | (r + (255 - r) * 2 / 5) << 16 | (g + (255 - g) * 2 / 5) << 8 | (b + (255 - b) * 2 / 5);
    }

    /**
     * Desenha o aviso colado na borda do minimapa. edgeY é a borda de onde a caixa sai: a de baixo da faixa de
     * coordenadas, ou a de cima do minimapa quando ele está num canto inferior (upward).
     */
    static void render(GuiGraphicsExtractor g, Font font, Minecraft mc, int mapX, int mapW, int edgeY, boolean upward, int screenW) {
        long now = System.currentTimeMillis();
        if (current != null && now - startedAt >= IN + HOLD + OUT) current = null;
        if (current == null && !QUEUE.isEmpty()) {
            current = QUEUE.pollFirst();
            startedAt = now;
        }
        boolean down = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (current == null) {
            wasDown = down;
            return;
        }
        Item it = current;
        long t = now - startedAt;
        double shown;
        if (t < IN) {
            double u = t / (double) IN;
            shown = 1 - (1 - u) * (1 - u);
        } else if (t < IN + HOLD) {
            shown = 1;
        } else {
            double u = (t - IN - HOLD) / (double) OUT;
            shown = 1 - u * u;
        }

        String middle = it.from().isEmpty() ? " advanced" : " took it from ";
        int textW = Math.max(font.width(it.title()), font.width(it.by()) + font.width(middle) + font.width(it.from()));
        int w = Math.max(mapW + 6, textW + 34);
        int left = Math.max(2, Math.min(screenW - w - 2, mapX - 3 - (w - mapW - 6) / 2));
        int clipTop = upward ? edgeY - BOX_H : edgeY;
        int top = upward ? edgeY - BOX_H + (int) Math.round((1 - shown) * BOX_H) : edgeY - (int) Math.round((1 - shown) * BOX_H);

        boolean chat = mc.screen instanceof ChatScreen;
        double gs = Math.max(1.0, mc.getWindow().getGuiScale());
        double mx = mc.mouseHandler.xpos() / gs, my = mc.mouseHandler.ypos() / gs;
        boolean hover = chat && mx >= left && mx < left + w && my >= clipTop && my < clipTop + BOX_H;

        g.enableScissor(left, clipTop, left + w, clipTop + BOX_H);
        g.fill(left, top, left + w, top + BOX_H, hover ? 0xF22C3236 : 0xF21E2123);
        g.fill(left, top + BOX_H - 1, left + w, top + BOX_H, hover ? 0xFF7E8387 : 0xFF3E4245);
        g.fill(left + 5, top + 7, left + 23, top + 19, 0xFF0A0B08);
        SiteTerritories.Data sd = SiteTerritories.data();
        RemoteImages.Img flag = sd == null ? null : MapScreen.noticeFlag(sd, it.country());
        if (flag != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, flag.id(), left + 6, top + 8, 0f, 0f, 16, 10, flag.w(), flag.h(), flag.w(), flag.h());
        } else {
            g.fill(left + 6, top + 8, left + 22, top + 18, 0xFF000000 | it.byRgb());
        }
        g.text(font, it.title(), left + 28, top + 4, 0xFFECEBE4, false);
        int tx = left + 28, ty = top + 15;
        g.text(font, it.by(), tx, ty, light(it.byRgb()), false);
        tx += font.width(it.by());
        g.text(font, middle, tx, ty, 0xFFBFC2C2, false);
        if (!it.from().isEmpty()) {
            tx += font.width(middle);
            g.text(font, it.from(), tx, ty, light(it.fromRgb()), false);
        }
        g.disableScissor();

        if (hover && down && !wasDown) {
            current = null;
            MapScreen.openAt(it.city());
        }
        wasDown = down;
    }
}
