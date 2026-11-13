package com.normandioo.bmcatlas;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The server's news channel: diplomatic news and Staff posts (with their picture) that the BmcWar plugin sends on "bmcatlas:sync"
 * ({"t":"news","fresh":..,"news":{id, map, type, title, date, time, details[], notes?, image?}}). The latest ones come when the player
 * joins (fresh false: only listed); one that comes out while the player is on (fresh true) also slides down from the top corner of
 * the screen. Read in the map's News panel. Forgotten when leaving the server.
 */
final class ServerNews {
    private ServerNews() {}

    record Item(long id, String map, String type, String title, String date, List<String> details, String notes, String image) {}

    private static final int KEEP = 50, MAX_QUEUE = 4;
    private static final long IN = 350, HOLD = 7000, OUT = 320;
    private static final List<Item> ITEMS = new ArrayList<>();     // newest first
    private static final ArrayDeque<Item> QUEUE = new ArrayDeque<>();
    private static Item current;
    private static long startedAt;
    private static int unread;

    /** A "news" message (on the game thread). */
    static void receive(JsonObject o) {
        JsonElement n = o.get("news");
        if (n == null || !n.isJsonObject()) return;
        JsonObject j = n.getAsJsonObject();
        String title = str(j, "title");
        if (title == null || title.isBlank()) return;
        List<String> details = new ArrayList<>();
        if (j.has("details") && j.get("details").isJsonArray()) for (JsonElement e : j.getAsJsonArray("details")) if (e.isJsonPrimitive()) details.add(e.getAsString());
        long id = j.has("id") ? j.get("id").getAsLong() : 0;
        Item it = new Item(id, str(j, "map"), str(j, "type"), title, str(j, "date"), List.copyOf(details), str(j, "notes"), str(j, "image"));
        if (ITEMS.stream().anyMatch(x -> x.id() == id)) return;   // the same news again (a second join message)
        ITEMS.add(0, it);
        ITEMS.sort((a, b) -> Long.compare(b.id(), a.id()));
        while (ITEMS.size() > KEEP) ITEMS.remove(ITEMS.size() - 1);
        if (it.image() != null) RemoteImages.picture(it.image());   // starts the download, so it is ready when shown
        if (o.has("fresh") && o.get("fresh").getAsBoolean()) {
            unread++;
            if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
            QUEUE.addLast(it);
        }
    }

    static List<Item> items() { return ITEMS; }

    static int unread() { return unread; }

    /** The News panel is open: everything is read. */
    static void read() { unread = 0; }

    static void reset() {
        ITEMS.clear();
        QUEUE.clear();
        current = null;
        unread = 0;
    }

    /** What the type is called on screen, and its colour. */
    static String label(String type) {
        return switch (type == null ? "" : type) {
            case "war" -> "WAR!"; case "peace" -> "Peace"; case "post" -> "Staff notice"; case "breaking" -> "Breaking News"; case "alliance" -> "New alliance";
            case "alliance-join" -> "An alliance grows"; case "alliance-leave" -> "An alliance breaks"; case "alliance-end" -> "Alliance dissolved";
            case "truce" -> "Truce"; case "nap" -> "Pact signed"; case "ultimatum" -> "Ultimatum"; case "vassal" -> "New subject";
            case "independence" -> "Independence"; default -> "News";
        };
    }

    static int color(String type) {
        return switch (type == null ? "" : type) {
            case "war" -> 0xFFE06A6A; case "peace", "truce", "nap" -> 0xFF7CD67C; case "post" -> 0xFFF0B429; case "breaking" -> 0xFFF0643C; case "ultimatum" -> 0xFFF2E05A;
            default -> 0xFF8FC8F0;
        };
    }

    /** The notice in the top corner away from the minimap: slides down, stays a few seconds, goes back up. */
    static void render(GuiGraphics g) {
        long now = System.currentTimeMillis();
        if (current != null && now - startedAt >= IN + HOLD + OUT) current = null;
        if (current == null && !QUEUE.isEmpty()) {
            current = QUEUE.pollFirst();
            startedAt = now;
        }
        if (current == null) return;
        Item it = current;
        long t = now - startedAt;
        double shown = t < IN ? 1 - Math.pow(1 - t / (double) IN, 2) : t < IN + HOLD ? 1 : 1 - Math.pow((t - IN - HOLD) / (double) OUT, 2);

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        RemoteImages.Img pic = it.image() == null ? null : RemoteImages.picture(it.image());
        int picW = pic == null ? 0 : 48, picH = 27;
        String kind = label(it.type()).toUpperCase(Locale.ROOT);
        String key = BmcAtlasClient.OPEN_KEY.getTranslatedKeyMessage().getString();
        String hint = "Press " + key + " and open News to read it";
        int textMax = Math.min(220, screenW - picW - 40);
        String title = font.width(it.title()) <= textMax ? it.title() : font.plainSubstrByWidth(it.title(), textMax - font.width("...")) + "...";
        int w = Math.max(Math.max(font.width(kind), font.width(title)), font.width(hint)) + 16 + (pic == null ? 0 : picW + 6);
        int h = Math.max(36, picH + 10);
        boolean right = Prefs.get().minimapCorner != 1;   // the minimap is top left by default: the notice goes top right
        int x = right ? screenW - w - 4 : 4;
        int y = 4 - (int) Math.round((1 - shown) * (h + 6));

        g.fill(x, y, x + w, y + h, 0xF01E2123);
        g.fill(x, y, x + 2, y + h, color(it.type()));
        g.fill(x, y + h - 1, x + w, y + h, 0xFF3E4245);
        int tx = x + 8;
        if (pic != null) {
            double sc = Math.min((double) picW / pic.w(), (double) picH / pic.h());
            int dw = Math.max(1, (int) Math.round(pic.w() * sc)), dh = Math.max(1, (int) Math.round(pic.h() * sc));
            g.blit(RenderPipelines.GUI_TEXTURED, pic.id(), tx + (picW - dw) / 2, y + (h - dh) / 2, 0f, 0f, dw, dh, pic.w(), pic.h(), pic.w(), pic.h());
            tx += picW + 6;
        }
        g.drawString(font, kind, tx, y + 5, color(it.type()), false);
        g.drawString(font, title, tx, y + 15, 0xFFECEBE4, false);
        g.drawString(font, hint, tx, y + 25, 0xFF8A8F92, false);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
