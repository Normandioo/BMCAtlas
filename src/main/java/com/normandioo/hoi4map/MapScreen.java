package com.normandioo.hoi4map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
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

    private static final int MODE_PHYSICAL = MapStyle.MODE_PHYSICAL;
    private static final int MODE_POLITICAL = MapStyle.MODE_POLITICAL;
    private static final int MODE_PROVINCES = MapStyle.MODE_PROVINCES;

    private static final int COL_GOLD = 0xFFC8A64A;
    private static final int COL_PANEL = 0xFF101820;
    private static final int COL_TEXT = 0xFFE6E0CC;
    private static final int COL_DIM = 0xFF8C94A0;

    // O estado da vista persiste entre aberturas da tela.
    private static double viewX, viewZ;
    private static double scale = 1.0;
    private static boolean viewInit;
    private static int mode = MODE_POLITICAL;

    // seleção no modo manual (províncias de 32x32 blocos)
    private static boolean hasSel;
    private static int selPx, selPz;
    // seleção no modo site (índice da província no arquivo do site)
    private static int selSite = -1;

    private boolean dragging;
    private boolean moved;
    private double pressX, pressY;

    private Button prevBtn, nextBtn, clearBtn, sourceBtn;

    // cache do modo manual: país -> {somaX, somaZ, n, somaXX, somaZZ, somaXZ} (em províncias)
    private int cacheVersion = -1;
    private final Map<String, double[]> stats = new HashMap<>();

    // cache da legenda do site
    private int legendVersion = -1;
    private List<Integer> legendOrder = new ArrayList<>();

    public MapScreen() {
        super(Component.literal("Mapa"));
    }

    private static boolean siteMode() {
        return MapData.siteApplies() && SiteTerritories.active();
    }

    // ------------------------------------------------------------------ init

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        MapData.ensureWorld(mc);
        if (!viewInit && mc.player != null) {
            centerOnPlayer();
            viewInit = true;
        }
        MapStyle.selectedProvince = selSite;

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

        sourceBtn = addRenderableWidget(Button.builder(Component.literal("Fonte: Site"), b -> {
                    SiteTerritories.useSite = !SiteTerritories.useSite;
                    MapData.ownersChanged();
                }).bounds(x0 + 8, height - 48, PANEL_W - 16, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Recarregar países"), b -> {
                    Countries.load();
                    SiteTerritories.reload();
                }).bounds(x0 + 8, height - 26, PANEL_W - 16, 18).build());
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
        MapData.nextFrame();
        g.fill(0, 0, width, height, 0xFF0A0F16);

        g.enableScissor(0, TOP, width - PANEL_W, height);
        drawMap(g);
        g.disableScissor();

        drawTopBar(g);
        drawHover(g, mouseX, mouseY);
        drawPanel(g);

        boolean site = siteMode();
        prevBtn.visible = !site;
        nextBtn.visible = !site;
        clearBtn.visible = !site;
        prevBtn.active = hasSel && !Countries.LIST.isEmpty();
        nextBtn.active = prevBtn.active;
        clearBtn.active = hasSel;
        if (SiteTerritories.data() == null) {
            sourceBtn.setMessage(Component.literal("Fonte: Manual (sem site)"));
            sourceBtn.active = false;
        } else {
            sourceBtn.setMessage(Component.literal(SiteTerritories.useSite ? "Fonte: Site" : "Fonte: Manual"));
            sourceBtn.active = true;
        }

        super.render(g, mouseX, mouseY, delta);
    }

    private boolean regionTouchesSite(SiteTerritories.Data d, int rx, int rz) {
        int x0 = rx * MapData.REGION_PX, z0 = rz * MapData.REGION_PX;
        return x0 + MapData.REGION_PX > d.minX && x0 <= d.maxX && z0 + MapData.REGION_PX > d.minZ && z0 <= d.maxZ;
    }

    private void drawMap(GuiGraphics g) {
        double leftB = toBlockX(0);
        double rightB = toBlockX(width - PANEL_W);
        double topB = toBlockZ(TOP);
        double botB = toBlockZ(height);

        // mar profundo: o que ainda não foi explorado aparece como oceano escuro
        g.fill(0, TOP, width - PANEL_W, height, 0xFF0B1628);

        boolean site = siteMode();
        SiteTerritories.Data sd = site && mode != MODE_PHYSICAL ? SiteTerritories.data() : null;

        // 1) terreno: um blit por região (texturas de 1 pixel por bloco)
        int rx0 = (int) Math.floor(leftB / MapData.REGION_PX);
        int rx1 = (int) Math.floor(rightB / MapData.REGION_PX);
        int rz0 = (int) Math.floor(topB / MapData.REGION_PX);
        int rz1 = (int) Math.floor(botB / MapData.REGION_PX);
        int uploads = 0;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                MapData.Region r = MapData.region(rx, rz, false);
                if (r == null && sd != null && regionTouchesSite(sd, rx, rz)) r = MapData.viewRegion(rx, rz);
                if (r == null) continue;
                MapData.markUsed(r);
                if ((r.id == null || r.dirty || r.styledMode != mode) && uploads < 3) {
                    r.ensureUploaded(mode);
                    uploads++;
                }
                if (r.id == null) continue; // ainda sem textura: espera o próximo quadro
                int sx0 = (int) Math.floor(toScreenX(rx * (double) MapData.REGION_PX));
                int sx1 = (int) Math.floor(toScreenX((rx + 1) * (double) MapData.REGION_PX));
                int sy0 = (int) Math.floor(toScreenY(rz * (double) MapData.REGION_PX));
                int sy1 = (int) Math.floor(toScreenY((rz + 1) * (double) MapData.REGION_PX));
                int size = MapData.REGION_PX;
                g.blit(RenderPipelines.GUI_TEXTURED, r.id, sx0, sy0, 0f, 0f, sx1 - sx0, sy1 - sy0, size, size, size, size);
            }
        }

        if (!site) {
            if (mode == MODE_PROVINCES) drawGrid(g, leftB, rightB, topB, botB);
            if (mode == MODE_POLITICAL) drawManualNames(g);
            drawManualSelection(g);
        } else if (mode == MODE_POLITICAL) {
            drawSiteNames(g);
        }
        drawPlayer(g);
    }

    private void drawGrid(GuiGraphics g, double leftB, double rightB, double topB, double botB) {
        int span = MapData.PROV * 16;
        if (span * scale < 6) return; // linhas muito juntas: não desenha
        int gx0 = (int) Math.floor(leftB / span);
        int gx1 = (int) Math.floor(rightB / span) + 1;
        int gz0 = (int) Math.floor(topB / span);
        int gz1 = (int) Math.floor(botB / span) + 1;
        int line = 0x55FFFFFF;
        for (int gx = gx0; gx <= gx1; gx++) {
            int sx = (int) Math.floor(toScreenX(gx * (double) span));
            g.fill(sx, TOP, sx + 1, height, line);
        }
        for (int gz = gz0; gz <= gz1; gz++) {
            int sy = (int) Math.floor(toScreenY(gz * (double) span));
            g.fill(0, sy, width - PANEL_W, sy + 1, line);
        }
    }

    private int provOf(int chunk) {
        return Math.floorDiv(chunk, MapData.PROV);
    }

    // ------------------------------------------------------------------ nomes

    private void refreshStats() {
        if (cacheVersion == MapData.ownersVersion()) return;
        cacheVersion = MapData.ownersVersion();
        stats.clear();
        for (Map.Entry<Long, String> e : MapData.OWNERS.entrySet()) {
            double x = MapData.keyA(e.getKey());
            double z = MapData.keyB(e.getKey());
            double[] s = stats.computeIfAbsent(e.getValue(), k -> new double[6]);
            s[0] += x;
            s[1] += z;
            s[2] += 1;
            s[3] += x * x;
            s[4] += z * z;
            s[5] += x * z;
        }
    }

    private void drawManualNames(GuiGraphics g) {
        refreshStats();
        int span = MapData.PROV * 16;
        for (Map.Entry<String, double[]> e : stats.entrySet()) {
            Countries.Country c = Countries.BY_ID.get(e.getKey());
            if (c == null) continue;
            double[] s = e.getValue();
            double n = s[2];
            double mx = s[0] / n;
            double mz = s[1] / n;
            double vxx = s[3] / n - mx * mx;
            double vzz = s[4] / n - mz * mz;
            double vxz = s[5] / n - mx * mz;
            double k = (double) span * span;
            drawName(g, c.name, (mx + 0.5) * span, (mz + 0.5) * span, vxx * k, vzz * k, vxz * k, span, 0.08 * k);
        }
    }

    private void drawSiteNames(GuiGraphics g) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) return;
        for (int c = 0; c < d.ids.length; c++) {
            double[] s = d.shape[c];
            if (s == null) continue;
            drawName(g, d.names[c], s[0], s[1], s[2], s[3], s[4], 0, 400);
        }
    }

    /** Nome grande, em maiúsculas, espaçado, inclinado ao longo do território, com contorno. */
    private void drawName(GuiGraphics g, String name, double mxB, double mzB,
                          double vxx, double vzz, double vxz, double pad, double minVar) {
        double common = Math.sqrt(((vxx - vzz) / 2) * ((vxx - vzz) / 2) + vxz * vxz);
        double l1 = (vxx + vzz) / 2 + common;
        double l2 = (vxx + vzz) / 2 - common;
        double angle = 0;
        if (l1 > 1.8 * Math.max(l2, minVar / 2)) {
            angle = 0.5 * Math.atan2(2 * vxz, vxx - vzz);
            if (angle > 0.6 || angle < -0.6) angle = 0;
        }
        double extentBlocks = Math.sqrt(12 * Math.max(l1, minVar)) + pad;

        double sx = toScreenX(mxB);
        double sy = toScreenY(mzB);
        if (sx < -300 || sx > width || sy < -100 || sy > height + 100) return;

        String text = name.toUpperCase();
        int spacing = 2;
        int tw = 0;
        int[] widths = new int[text.length()];
        Component[] comps = new Component[text.length()];
        for (int i = 0; i < text.length(); i++) {
            comps[i] = Component.literal(String.valueOf(text.charAt(i))).withStyle(ChatFormatting.BOLD);
            widths[i] = font.width(comps[i]);
            tw += widths[i] + (i > 0 ? spacing : 0);
        }
        float sc = (float) Math.min(8.0, extentBlocks * scale * 0.85 / Math.max(1, tw));
        if (sc < 0.55f) return;

        g.pose().pushMatrix();
        g.pose().translate((float) sx, (float) sy);
        g.pose().rotate((float) angle);
        g.pose().scale(sc, sc);
        int x = -tw / 2;
        int[][] outline = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < comps.length; i++) {
            for (int[] o : outline) {
                g.drawString(font, comps[i], x + o[0], -4 + o[1], 0xB00A0E14, false);
            }
            g.drawString(font, comps[i], x, -4, 0xE6EDE8D8, false);
            x += widths[i] + spacing;
        }
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------ seleção/jogador

    private void drawManualSelection(GuiGraphics g) {
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

    private void drawTopBar(GuiGraphics g) {
        g.fill(0, 0, width, TOP, 0xFF0D141C);
        g.fill(0, TOP - 2, width, TOP, COL_GOLD);

        String hint = "Arraste: mover | Roda: zoom | 1/2/3: modos";
        g.drawString(font, hint, width - PANEL_W - 6 - font.width(hint), 9, COL_DIM, false);
    }

    /** Coordenadas (e país/província, no modo site) sob o cursor, no canto do mapa. */
    private void drawHover(GuiGraphics g, int mouseX, int mouseY) {
        if (!inMap(mouseX, mouseY)) return;
        int bx = (int) Math.floor(toBlockX(mouseX));
        int bz = (int) Math.floor(toBlockZ(mouseY));
        String text = "X " + bx + "  Z " + bz;
        SiteTerritories.Data d = SiteTerritories.data();
        if (siteMode() && d != null) {
            int p = SiteTerritories.provinceAt(d, bx, bz);
            if (p >= 0) text += "  |  " + d.names[d.provCountry[p]] + " - " + d.provLabel[p];
        }
        int w = font.width(text) + 8;
        int y = height - 16;
        g.fill(4, y - 2, 4 + w, y + 11, 0xB00D141C);
        g.drawString(font, text, 8, y, COL_TEXT, false);
    }

    // ------------------------------------------------------------------ painel

    private String fit(String s, int maxWidth) {
        if (s == null) return "";
        return font.width(s) <= maxWidth ? s : font.plainSubstrByWidth(s, maxWidth - font.width("...")) + "...";
    }

    private void row(GuiGraphics g, int x, int y, String label, String value) {
        if (value == null || value.isBlank()) return;
        g.drawString(font, label, x, y, COL_DIM, false);
        int lw = font.width(label) + 4;
        g.drawString(font, fit(value, PANEL_W - 16 - lw), x + lw, y, COL_TEXT, false);
    }

    private void drawPanel(GuiGraphics g) {
        int x0 = width - PANEL_W;
        g.fill(x0, TOP, width, height, COL_PANEL);
        g.fill(x0, TOP, x0 + 2, height, COL_GOLD);
        if (siteMode()) drawSitePanel(g, x0);
        else drawManualPanel(g, x0);
    }

    private void drawSitePanel(GuiGraphics g, int x0) {
        SiteTerritories.Data d = SiteTerritories.data();
        int x = x0 + 8;
        int y = TOP + 8;
        g.drawString(font, "PROVÍNCIA", x, y, COL_GOLD, false);
        y += 14;

        if (selSite < 0 || selSite >= d.n) {
            g.drawString(font, "Clique num território", x, y, COL_DIM, false);
            g.drawString(font, "para ver os dados.", x, y + 11, COL_DIM, false);
        } else {
            int c = d.provCountry[selSite];
            g.drawString(font, fit(d.provLabel[selSite], PANEL_W - 16), x, y, COL_TEXT, false);
            y += 14;
            g.fill(x, y, x + 10, y + 9, d.rgb[c] | 0xFF000000);
            g.drawString(font, fit(d.names[c], PANEL_W - 34), x + 15, y + 1, COL_TEXT, false);
            y += 15;
            Map<String, String> info = d.info[c];
            row(g, x, y, "Tipo:", info.get("type"));
            row(g, x, y + 11, "Oficial:", info.get("officialName"));
            row(g, x, y + 22, "Formação:", info.get("formed"));
            row(g, x, y + 33, "Governo:", info.get("government"));
            row(g, x, y + 44, "Líder:", info.get("leader"));
            row(g, x, y + 55, "Capital:", info.get("capital"));
            row(g, x, y + 66, "Etnia:", info.get("ethnicity"));
            row(g, x, y + 77, "População:", String.valueOf(d.population[c]));
            row(g, x, y + 88, "Províncias:", String.valueOf(d.provinceCount[c]));
        }

        // legenda: países com mais províncias primeiro
        if (legendVersion != SiteTerritories.version()) {
            legendVersion = SiteTerritories.version();
            legendOrder = new ArrayList<>();
            for (int i = 0; i < d.ids.length; i++) legendOrder.add(i);
            legendOrder.sort((a, b) -> Integer.compare(d.provinceCount[b], d.provinceCount[a]));
        }
        int ly = TOP + 146;
        g.drawString(font, "PAÍSES", x, ly, COL_GOLD, false);
        ly += 13;
        for (int idx : legendOrder) {
            if (ly > height - 66) break;
            if (d.provinceCount[idx] == 0) continue;
            g.fill(x, ly, x + 10, ly + 8, d.rgb[idx] | 0xFF000000);
            g.drawString(font, fit(d.names[idx], PANEL_W - 56) + " (" + d.provinceCount[idx] + ")", x + 16, ly, COL_TEXT, false);
            ly += 11;
        }
        g.drawString(font, fit(SiteTerritories.status(), PANEL_W - 16), x, height - 62, COL_DIM, false);
    }

    private void drawManualPanel(GuiGraphics g, int x0) {
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
                    if (MapData.chunkKnown(selPx * MapData.PROV + dx, selPz * MapData.PROV + dz)) known++;
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
            if (ly > height - 66) break;
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
            if (!moved && inMap(event.x(), event.y())) select(toBlockX(event.x()), toBlockZ(event.y()));
            return true;
        }
        return super.mouseReleased(event);
    }

    private void select(double bx, double bz) {
        if (siteMode()) {
            SiteTerritories.Data d = SiteTerritories.data();
            int old = selSite;
            selSite = SiteTerritories.provinceAt(d, (int) Math.floor(bx), (int) Math.floor(bz));
            MapStyle.selectedProvince = selSite;
            if (old >= 0 && old < d.n) MapData.invalidateRect(d.bounds[old][0], d.bounds[old][1], d.bounds[old][2], d.bounds[old][3]);
            if (selSite >= 0) {
                int[] b = d.bounds[selSite];
                MapData.invalidateRect(b[0], b[1], b[2], b[3]);
            }
            return;
        }
        selPx = provOf((int) Math.floor(bx / 16));
        selPz = provOf((int) Math.floor(bz / 16));
        hasSel = true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!inMap(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double bx = toBlockX(mouseX);
        double bz = toBlockZ(mouseY);
        double ns = Math.max(0.05, Math.min(8.0, scale * Math.pow(1.2, scrollY)));
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
