package com.normandioo.hoi4map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tela cheia do mapa, estilo Hearts of Iron IV. */
public class MapScreen extends Screen {
    private static final int TOP = 26;
    private static final int PANEL_W = 176;

    private static final int MODE_PHYSICAL = 0;
    private static final int MODE_POLITICAL = 1;
    private static final int MODE_PROVINCES = 2;

    private static final int COL_UNKNOWN = 0xFF141C26;
    private static final int COL_GOLD = 0xFFC8A64A;
    private static final int COL_PANEL = 0xFF101820;
    private static final int COL_TEXT = 0xFFE6E0CC;
    private static final int COL_DIM = 0xFF8C94A0;

    // O estado da vista persiste entre aberturas da tela.
    private static double viewX, viewZ;
    private static double scale = 1.0;
    private static boolean viewInit;
    private static int mode = MODE_POLITICAL;
    private static boolean hasSel;
    private static int selPx, selPz;

    private boolean dragging;
    private boolean moved;
    private double pressX, pressY;

    private Button prevBtn, nextBtn, clearBtn;

    // cache de contagem/centroide por país
    private int cacheVersion = -1;
    private final Map<String, double[]> stats = new HashMap<>(); // id -> {sumPx, sumPz, n}

    public MapScreen() {
        super(Component.literal("Mapa"));
    }

    // ------------------------------------------------------------------ init

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        MapData.scan(mc);
        if (!viewInit && mc.player != null) {
            centerOnPlayer();
            viewInit = true;
        }

        String[] names = {"Físico", "Político", "Províncias"};
        for (int i = 0; i < names.length; i++) {
            final int m = i;
            addRenderableWidget(Button.builder(Component.literal(names[i]), b -> mode = m)
                    .bounds(6 + i * 74, 4, 70, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Centralizar"), b -> centerOnPlayer())
                .bounds(6 + names.length * 74 + 6, 4, 80, 18).build());

        int x0 = width - PANEL_W;
        int by = TOP + 104;
        prevBtn = addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleOwner(-1))
                .bounds(x0 + 8, by, 22, 18).build());
        nextBtn = addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleOwner(1))
                .bounds(x0 + PANEL_W - 30, by, 22, 18).build());
        clearBtn = addRenderableWidget(Button.builder(Component.literal("Sem dono"), b -> {
                    if (hasSel) MapData.setOwner(selPx, selPz, null);
                }).bounds(x0 + 8, by + 22, PANEL_W - 16, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Recarregar países"), b -> Countries.load())
                .bounds(x0 + 8, height - 26, PANEL_W - 16, 18).build());
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        viewX = mc.player.getX();
        viewZ = mc.player.getZ();
    }

    private void cycleOwner(int dir) {
        if (!hasSel || Countries.LIST.isEmpty()) return;
        String cur = MapData.ownerOf(selPx, selPz);
        int idx = -1; // -1 = sem dono
        for (int i = 0; i < Countries.LIST.size(); i++) {
            if (Countries.LIST.get(i).id.equals(cur)) {
                idx = i;
                break;
            }
        }
        int total = Countries.LIST.size() + 1;
        int next = (idx + 1 + dir + total) % total - 1;
        MapData.setOwner(selPx, selPz, next < 0 ? null : Countries.LIST.get(next).id);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        MapData.save();
        super.onClose();
    }

    // ----------------------------------------------------------------- render

    private int areaCx() {
        return (width - PANEL_W) / 2;
    }

    private int areaCy() {
        return TOP + (height - TOP) / 2;
    }

    private double toScreenX(double blockX) {
        return areaCx() + (blockX - viewX) * scale;
    }

    private double toScreenY(double blockZ) {
        return areaCy() + (blockZ - viewZ) * scale;
    }

    private double toBlockX(double sx) {
        return viewX + (sx - areaCx()) / scale;
    }

    private double toBlockZ(double sy) {
        return viewZ + (sy - areaCy()) / scale;
    }

    private boolean inMap(double mx, double my) {
        return mx >= 0 && mx < width - PANEL_W && my >= TOP && my < height;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF0A0F16);

        g.enableScissor(0, TOP, width - PANEL_W, height);
        drawMap(g);
        g.disableScissor();

        drawTopBar(g, mouseX, mouseY);
        drawPanel(g);

        prevBtn.active = hasSel && !Countries.LIST.isEmpty();
        nextBtn.active = prevBtn.active;
        clearBtn.active = hasSel;

        super.render(g, mouseX, mouseY, delta);
    }

    private void drawMap(GuiGraphics g) {
        int step = 1;
        while (step * 16 * scale < 5 && step < 128) step <<= 1;

        double leftB = toBlockX(0);
        double rightB = toBlockX(width - PANEL_W);
        double topB = toBlockZ(TOP);
        double botB = toBlockZ(height);

        int minC = Math.floorDiv((int) Math.floor(leftB / 16), step) * step;
        int maxC = (int) Math.floor(rightB / 16);
        int minR = Math.floorDiv((int) Math.floor(topB / 16), step) * step;
        int maxR = (int) Math.floor(botB / 16);

        for (int cxu = minC; cxu <= maxC; cxu += step) {
            int sx0 = (int) Math.floor(toScreenX(cxu * 16.0));
            int sx1 = (int) Math.floor(toScreenX((cxu + step) * 16.0));
            for (int czu = minR; czu <= maxR; czu += step) {
                int sy0 = (int) Math.floor(toScreenY(czu * 16.0));
                int sy1 = (int) Math.floor(toScreenY((czu + step) * 16.0));
                g.fill(sx0, sy0, sx1, sy1, colorFor(cxu, czu, step));

                if (mode != MODE_PHYSICAL) {
                    int border = mode == MODE_POLITICAL ? 0xCC05080C : 0x66000000;
                    int t = scale >= 1.5 ? 2 : 1;
                    if (differs(cxu, czu, cxu + step, czu)) g.fill(sx1 - t, sy0, sx1, sy1, border);
                    if (differs(cxu, czu, cxu, czu + step)) g.fill(sx0, sy1 - t, sx1, sy1, border);
                }
            }
        }

        if (mode == MODE_POLITICAL) drawCountryNames(g);
        drawSelection(g);
        drawPlayer(g);
    }

    private int provOf(int chunk) {
        return Math.floorDiv(chunk, MapData.PROV);
    }

    /** Há fronteira entre as duas unidades? */
    private boolean differs(int ax, int az, int bx, int bz) {
        int apx = provOf(ax), apz = provOf(az), bpx = provOf(bx), bpz = provOf(bz);
        if (apx == bpx && apz == bpz) return false;
        if (mode == MODE_PROVINCES) return true;
        String a = MapData.ownerOf(apx, apz);
        String b = MapData.ownerOf(bpx, bpz);
        if (a == null) return b != null;
        return !a.equals(b);
    }

    private int terrainAt(int cxu, int czu, int step) {
        if (step == 1) return MapData.CHUNK_COLORS.get(MapData.key(cxu, czu));
        int last = step - 1;
        int[] xs = {cxu, cxu + last};
        int[] zs = {czu, czu + last};
        int r = 0, gg = 0, b = 0, n = 0;
        for (int x : xs) {
            for (int z : zs) {
                int c = MapData.CHUNK_COLORS.get(MapData.key(x, z));
                if (c == 0) continue;
                r += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((r / n) << 16) | ((gg / n) << 8) | (b / n);
    }

    private int colorFor(int cxu, int czu, int step) {
        int terrain = terrainAt(cxu, czu, step);
        switch (mode) {
            case MODE_PHYSICAL:
                return terrain != 0 ? terrain : COL_UNKNOWN;
            case MODE_POLITICAL: {
                String owner = MapData.ownerOf(provOf(cxu), provOf(czu));
                Countries.Country c = owner == null ? null : Countries.BY_ID.get(owner);
                if (c != null) {
                    return terrain != 0 ? lerp(terrain, c.rgb, 0.78f) : lerp(COL_UNKNOWN, c.rgb, 0.65f);
                }
                if (terrain == 0) return COL_UNKNOWN;
                return lerp(terrain, 0xFF6A7078, 0.55f);
            }
            default: {
                int h = hash(provOf(cxu), provOf(czu));
                int base = 0xFF000000 | (70 + ((h >> 16) & 0x7F)) << 16 | (70 + ((h >> 8) & 0x7F)) << 8 | (70 + (h & 0x7F));
                return terrain != 0 ? lerp(terrain, base, 0.7f) : lerp(COL_UNKNOWN, base, 0.5f);
            }
        }
    }

    private static int hash(int a, int b) {
        int h = a * 73856093 ^ b * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h;
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void refreshStats() {
        if (cacheVersion == MapData.ownersVersion()) return;
        cacheVersion = MapData.ownersVersion();
        stats.clear();
        for (Map.Entry<Long, String> e : MapData.OWNERS.entrySet()) {
            double[] s = stats.computeIfAbsent(e.getValue(), k -> new double[3]);
            s[0] += MapData.keyA(e.getKey());
            s[1] += MapData.keyB(e.getKey());
            s[2] += 1;
        }
    }

    private void drawCountryNames(GuiGraphics g) {
        refreshStats();
        for (Map.Entry<String, double[]> e : stats.entrySet()) {
            Countries.Country c = Countries.BY_ID.get(e.getKey());
            if (c == null) continue;
            double[] s = e.getValue();
            double blockX = (s[0] / s[2] + 0.5) * MapData.PROV * 16;
            double blockZ = (s[1] / s[2] + 0.5) * MapData.PROV * 16;
            double sx = toScreenX(blockX);
            double sy = toScreenY(blockZ);
            String text = c.name.toUpperCase();
            int tw = font.width(text);
            double terrW = Math.sqrt(s[2]) * MapData.PROV * 16 * scale;
            float sc = (float) Math.min(4.0, terrW * 0.9 / Math.max(1, tw));
            if (sc < 0.6f) continue;
            if (sx < -200 || sx > width || sy < 0 || sy > height) continue;
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            g.pose().scale(sc, sc);
            g.drawString(font, text, -tw / 2, -4, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
    }

    private void drawSelection(GuiGraphics g) {
        if (!hasSel) return;
        int span = MapData.PROV * 16;
        int x0 = (int) Math.floor(toScreenX(selPx * (double) span));
        int x1 = (int) Math.floor(toScreenX((selPx + 1) * (double) span));
        int y0 = (int) Math.floor(toScreenY(selPz * (double) span));
        int y1 = (int) Math.floor(toScreenY((selPz + 1) * (double) span));
        int c = 0xFFFFE27A;
        g.fill(x0, y0, x1, y0 + 2, c);
        g.fill(x0, y1 - 2, x1, y1, c);
        g.fill(x0, y0, x0 + 2, y1, c);
        g.fill(x1 - 2, y0, x1, y1, c);
    }

    private void drawPlayer(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int px = (int) toScreenX(mc.player.getX());
        int pz = (int) toScreenY(mc.player.getZ());
        g.fill(px - 4, pz - 4, px + 4, pz + 4, 0xFF000000);
        g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFFE03C3C);
    }

    private void drawTopBar(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, width, TOP, 0xFF0D141C);
        g.fill(0, TOP - 2, width, TOP, COL_GOLD);

        String hint = "Arraste: mover   Roda: zoom   Clique: selecionar   1/2/3: modos";
        g.drawString(font, hint, width - PANEL_W - 6 - font.width(hint), 9, COL_DIM, false);
    }

    private void drawPanel(GuiGraphics g) {
        int x0 = width - PANEL_W;
        g.fill(x0, TOP, width, height, COL_PANEL);
        g.fill(x0, TOP, x0 + 2, height, COL_GOLD);

        int x = x0 + 8;
        int y = TOP + 8;
        g.drawString(font, "PROVÍNCIA", x, y, COL_GOLD, false);
        y += 14;

        if (!hasSel) {
            g.drawString(font, "Clique no mapa para", x, y, COL_DIM, false);
            g.drawString(font, "selecionar uma província.", x, y + 11, COL_DIM, false);
        } else {
            int span = MapData.PROV * 16;
            g.drawString(font, "ID: " + selPx + ", " + selPz, x, y, COL_TEXT, false);
            g.drawString(font, "X: " + (selPx * span) + " a " + ((selPx + 1) * span - 1), x, y + 11, COL_TEXT, false);
            g.drawString(font, "Z: " + (selPz * span) + " a " + ((selPz + 1) * span - 1), x, y + 22, COL_TEXT, false);

            int known = 0;
            for (int dx = 0; dx < MapData.PROV; dx++) {
                for (int dz = 0; dz < MapData.PROV; dz++) {
                    if (MapData.CHUNK_COLORS.get(MapData.key(selPx * MapData.PROV + dx, selPz * MapData.PROV + dz)) != 0) known++;
                }
            }
            int total = MapData.PROV * MapData.PROV;
            g.drawString(font, "Explorado: " + known + "/" + total + " chunks", x, y + 33, COL_TEXT, false);

            String ownerId = MapData.ownerOf(selPx, selPz);
            Countries.Country c = ownerId == null ? null : Countries.BY_ID.get(ownerId);
            g.drawString(font, "Dono:", x, y + 46, COL_DIM, false);
            String name = c == null ? "Ninguém" : c.name;
            int nameW = font.width(name);
            int midX = x0 + PANEL_W / 2;
            g.drawString(font, name, midX - nameW / 2, TOP + 109, c == null ? COL_DIM : COL_TEXT, false);
            if (c != null) {
                g.fill(x, y + 58, x + PANEL_W - 16, y + 62, c.rgb);
            }
        }

        // legenda de países
        int ly = TOP + 154;
        g.drawString(font, "PAÍSES", x, ly, COL_GOLD, false);
        ly += 14;
        refreshStats();
        for (Countries.Country c : Countries.LIST) {
            if (ly > height - 40) break;
            g.fill(x, ly, x + 10, ly + 8, c.rgb);
            double[] s = stats.get(c.id);
            int n = s == null ? 0 : (int) s[2];
            g.drawString(font, c.name + " (" + n + ")", x + 16, ly, COL_TEXT, false);
            ly += 12;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (inMap(event.x(), event.y())) {
            dragging = true;
            moved = false;
            pressX = event.x();
            pressY = event.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            if (Math.abs(event.x() - pressX) > 3 || Math.abs(event.y() - pressY) > 3) moved = true;
            if (moved) {
                viewX -= dx / scale;
                viewZ -= dy / scale;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            if (!moved && inMap(event.x(), event.y())) {
                int chunkX = (int) Math.floor(toBlockX(event.x()) / 16);
                int chunkZ = (int) Math.floor(toBlockZ(event.y()) / 16);
                selPx = provOf(chunkX);
                selPz = provOf(chunkZ);
                hasSel = true;
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!inMap(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double bx = toBlockX(mouseX);
        double bz = toBlockZ(mouseY);
        double ns = Math.max(0.03, Math.min(6.0, scale * Math.pow(1.2, scrollY)));
        scale = ns;
        viewX = bx - (mouseX - areaCx()) / scale;
        viewZ = bz - (mouseY - areaCy()) / scale;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (Hoi4MapClient.OPEN_KEY.matches(event)) {
            onClose();
            return true;
        }
        int k = event.key();
        if (k == GLFW.GLFW_KEY_1) {
            mode = MODE_PHYSICAL;
            return true;
        }
        if (k == GLFW.GLFW_KEY_2) {
            mode = MODE_POLITICAL;
            return true;
        }
        if (k == GLFW.GLFW_KEY_3) {
            mode = MODE_PROVINCES;
            return true;
        }
        return super.keyPressed(event);
    }
}
