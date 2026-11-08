package com.normandioo.hoi4map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tela cheia do mapa, no estilo do menu de mapa do Hearts of Iron IV. */
public class MapScreen extends Screen {
    private static final int BTN = 26;       // lado dos botões de ícone
    private static final int BAR_H = 34;     // altura da barra de ícones
    private static final int PW = 262;       // largura do painel de informações
    private static final int MARGIN = 8;

    private static final int MODE_PHYSICAL = MapStyle.MODE_PHYSICAL;
    private static final int MODE_POLITICAL = MapStyle.MODE_POLITICAL;
    private static final int MODE_PROVINCES = MapStyle.MODE_PROVINCES;

    private static final int COL_GOLD = 0xFFC8A64A;
    private static final int COL_GOLD_DIM = 0xFF6B5A2A;
    private static final int COL_PANEL = 0xF2101820;
    private static final int COL_PANEL_2 = 0xFF16212C;
    private static final int COL_TEXT = 0xFFE6E0CC;
    private static final int COL_DIM = 0xFF8C94A0;

    private static final int TAB_NONE = 0, TAB_INFO = 1, TAB_LIST = 2;

    // O estado da vista persiste entre aberturas da tela.
    private static double viewX, viewZ;
    private static double scale = 1.0;
    private static boolean viewInit;
    private static int mode = MODE_POLITICAL;
    private static int tab = TAB_NONE;
    private static double scrollInfo, scrollList;

    // seleção no modo manual (províncias de 32x32 blocos)
    private static boolean hasSel;
    private static int selPx, selPz;
    // seleção no modo site (índice da província no arquivo do site)
    private static int selSite = -1;

    private boolean dragging;
    private boolean moved;
    private double pressX, pressY;

    // UI em modo imediato: cada quadro registra as áreas clicáveis
    private record Hit(int x, int y, int w, int h, Runnable action, String tip) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private int panelX, panelY, panelW, panelH;
    private boolean panelVisible;
    private int cy;          // cursor vertical dentro do painel
    private int rowIdx;      // para o zebrado das linhas
    private int contentH;    // altura total do conteúdo desenhado (para a rolagem)

    private final ItemStack iconPhysical = new ItemStack(Items.GRASS_BLOCK);
    private final ItemStack iconPolitical = new ItemStack(Items.WHITE_BANNER);
    private final ItemStack iconProvinces = new ItemStack(Items.IRON_BARS);
    private final ItemStack iconCountries = new ItemStack(Items.BOOK);
    private final ItemStack iconCenter = new ItemStack(Items.COMPASS);
    private final ItemStack iconSource = new ItemStack(Items.LEVER);
    private final ItemStack iconReload = new ItemStack(Items.CLOCK);

    // cache do modo manual: país -> {somaX, somaZ, n, somaXX, somaZZ, somaXZ} (em províncias)
    private int cacheVersion = -1;
    private final Map<String, double[]> stats = new HashMap<>();

    // cache da lista do site
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
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        viewX = mc.player.getX();
        viewZ = mc.player.getZ();
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

    // ----------------------------------------------------------- coordenadas

    private int areaCx() {
        return width / 2;
    }

    private int areaCy() {
        return height / 2;
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

    private boolean blockedAt(double mx, double my) {
        for (Hit h : hits) if (h.contains(mx, my)) return true;
        return false;
    }

    // ----------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        MapData.nextFrame();
        hits.clear();
        panelVisible = false;

        g.fill(0, 0, width, height, 0xFF0B1628);
        drawMap(g);

        drawPanel(g, mouseX, mouseY);
        drawBar(g, mouseX, mouseY);
        drawHover(g, mouseX, mouseY);
        drawTooltip(g, mouseX, mouseY);
    }

    private boolean regionTouchesSite(SiteTerritories.Data d, int rx, int rz) {
        int x0 = rx * MapData.REGION_PX, z0 = rz * MapData.REGION_PX;
        return x0 + MapData.REGION_PX > d.minX && x0 <= d.maxX && z0 + MapData.REGION_PX > d.minZ && z0 <= d.maxZ;
    }

    private void drawMap(GuiGraphics g) {
        double leftB = toBlockX(0);
        double rightB = toBlockX(width);
        double topB = toBlockZ(0);
        double botB = toBlockZ(height);

        boolean site = siteMode();
        SiteTerritories.Data sd = site && mode != MODE_PHYSICAL ? SiteTerritories.data() : null;

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
            g.fill(sx, 0, sx + 1, height, line);
        }
        for (int gz = gz0; gz <= gz1; gz++) {
            int sy = (int) Math.floor(toScreenY(gz * (double) span));
            g.fill(0, sy, width, sy + 1, line);
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
            drawName(g, c.name, (mx + 0.5) * span, (mz + 0.5) * span, vxx * k, vzz * k, vxz * k, n * k, span, 0.08 * k);
        }
    }

    private void drawSiteNames(GuiGraphics g) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) return;
        for (int c = 0; c < d.ids.length; c++) {
            double[] s = d.shape[c];
            if (s == null) continue;
            drawName(g, d.labels[c], s[0], s[1], s[2], s[3], s[4], s[5], 0, 400);
        }
    }

    /**
     * Nome em maiúsculas, espaçado, inclinado ao longo do território, com contorno.
     * O tamanho vem do território, mas limitado pela área real do país (países feitos de ilhas
     * espalhadas têm variância enorme) e por uma fração da largura da tela.
     */
    private void drawName(GuiGraphics g, String name, double mxB, double mzB,
                          double vxx, double vzz, double vxz, double area, double pad, double minVar) {
        double sx = toScreenX(mxB);
        double sy = toScreenY(mzB);
        if (sx < -300 || sx > width + 300 || sy < -100 || sy > height + 100) return;

        double common = Math.sqrt(((vxx - vzz) / 2) * ((vxx - vzz) / 2) + vxz * vxz);
        double l1 = (vxx + vzz) / 2 + common;
        double l2 = (vxx + vzz) / 2 - common;
        double angle = 0;
        if (l1 > 1.8 * Math.max(l2, minVar / 2)) {
            angle = 0.5 * Math.atan2(2 * vxz, vxx - vzz);
            if (angle > 0.6 || angle < -0.6) angle = 0;
        }
        double extentBlocks = Math.min(Math.sqrt(12 * Math.max(l1, minVar)), 2.0 * Math.sqrt(Math.max(area, 1))) + pad;

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
        float sc = (float) (extentBlocks * scale * 0.75 / Math.max(1, tw));
        sc = Math.min(sc, 4.0f);                      // nunca maior que 4x
        sc = Math.min(sc, width * 0.30f / tw);        // nem mais que 30% da largura da tela
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

    /** Coordenadas (e país/província, no modo site) sob o cursor, e a dica de controles. */
    private void drawHover(GuiGraphics g, int mouseX, int mouseY) {
        String hint = "Arraste: mover  |  Roda: zoom  |  Clique: selecionar  |  1/2/3: modos";
        g.drawString(font, hint, MARGIN, height - 12, COL_DIM, true);
        if (blockedAt(mouseX, mouseY)) return;
        int bx = (int) Math.floor(toBlockX(mouseX));
        int bz = (int) Math.floor(toBlockZ(mouseY));
        String text = "X " + bx + "  Z " + bz;
        SiteTerritories.Data d = SiteTerritories.data();
        if (siteMode() && d != null) {
            int p = SiteTerritories.provinceAt(d, bx, bz);
            if (p >= 0) text += "  |  " + d.labels[d.provCountry[p]] + " - " + d.provLabel[p];
        }
        int w = font.width(text) + 10;
        int y = height - 34;
        box(g, MARGIN - 2, y - 3, w, 15, COL_PANEL_2);
        g.drawString(font, text, MARGIN + 3, y, COL_TEXT, false);
    }

    // ------------------------------------------------------------ peças de UI

    private void box(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, COL_GOLD_DIM);
        g.fill(x, y + h - 1, x + w, y + h, COL_GOLD_DIM);
        g.fill(x, y, x + 1, y + h, COL_GOLD_DIM);
        g.fill(x + w - 1, y, x + w, y + h, COL_GOLD_DIM);
    }

    private void iconButton(GuiGraphics g, int x, int y, ItemStack icon, boolean active, boolean enabled,
                            int mouseX, int mouseY, String tip, Runnable action) {
        boolean hover = enabled && mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN;
        int bg = active ? 0xFF3A3220 : hover ? 0xFF26364A : 0xFF1A2430;
        g.fill(x, y, x + BTN, y + BTN, bg);
        int border = active ? COL_GOLD : hover ? 0xFF9C8A4A : COL_GOLD_DIM;
        g.fill(x, y, x + BTN, y + 1, border);
        g.fill(x, y + BTN - 1, x + BTN, y + BTN, border);
        g.fill(x, y, x + 1, y + BTN, border);
        g.fill(x + BTN - 1, y, x + BTN, y + BTN, border);
        g.renderItem(icon, x + (BTN - 16) / 2, y + (BTN - 16) / 2);
        if (!enabled) g.fill(x + 1, y + 1, x + BTN - 1, y + BTN - 1, 0x99101820);
        hits.add(new Hit(x, y, BTN, BTN, enabled ? action : null, tip));
    }

    private void textButton(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY, boolean enabled, Runnable action) {
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF26364A : 0xFF1A2430);
        g.fill(x, y, x + w, y + 1, COL_GOLD_DIM);
        g.fill(x, y + h - 1, x + w, y + h, COL_GOLD_DIM);
        g.fill(x, y, x + 1, y + h, COL_GOLD_DIM);
        g.fill(x + w - 1, y, x + w, y + h, COL_GOLD_DIM);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, enabled ? COL_TEXT : COL_DIM, false);
        hits.add(new Hit(x, y, w, h, enabled ? action : null, null));
    }

    // --------------------------------------------------------------- barra de ícones

    private void drawBar(GuiGraphics g, int mouseX, int mouseY) {
        int n = 7;
        int barW = n * (BTN + 4) + 4;
        int bx = width - barW - MARGIN;
        int by = height - BAR_H - MARGIN;
        box(g, bx, by, barW, BAR_H, COL_PANEL);
        g.fill(bx + 1, by + 1, bx + barW - 1, by + 2, COL_GOLD);
        hits.add(new Hit(bx, by, barW, BAR_H, null, null));

        int x = bx + 4;
        int y = by + 4;
        iconButton(g, x, y, iconPhysical, mode == MODE_PHYSICAL, true, mouseX, mouseY, "Mapa físico (1)", () -> mode = MODE_PHYSICAL);
        x += BTN + 4;
        iconButton(g, x, y, iconPolitical, mode == MODE_POLITICAL, true, mouseX, mouseY, "Mapa político (2)", () -> mode = MODE_POLITICAL);
        x += BTN + 4;
        iconButton(g, x, y, iconProvinces, mode == MODE_PROVINCES, true, mouseX, mouseY, "Províncias (3)", () -> mode = MODE_PROVINCES);
        x += BTN + 4;
        iconButton(g, x, y, iconCountries, tab == TAB_LIST, true, mouseX, mouseY, "Lista de países",
                () -> tab = tab == TAB_LIST ? TAB_NONE : TAB_LIST);
        x += BTN + 4;
        iconButton(g, x, y, iconCenter, false, true, mouseX, mouseY, "Centralizar em você", this::centerOnPlayer);
        x += BTN + 4;
        boolean haveSite = SiteTerritories.data() != null;
        String sourceTip = !haveSite ? "Fonte: manual (sem dados do site)"
                : SiteTerritories.useSite ? "Fonte: site (clique para modo manual)" : "Fonte: manual (clique para usar o site)";
        iconButton(g, x, y, iconSource, haveSite && SiteTerritories.useSite, haveSite, mouseX, mouseY, sourceTip, () -> {
            SiteTerritories.useSite = !SiteTerritories.useSite;
            tab = TAB_NONE;
            MapData.ownersChanged();
        });
        x += BTN + 4;
        iconButton(g, x, y, iconReload, false, true, mouseX, mouseY, "Recarregar países e site", () -> {
            Countries.load();
            SiteTerritories.reload();
        });
    }

    private void drawTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip() == null || !h.contains(mouseX, mouseY)) continue;
            int w = font.width(h.tip()) + 10;
            int x = Math.min(mouseX + 8, width - w - 4);
            int y = Math.max(4, mouseY - 22);
            box(g, x, y, w, 16, 0xFF0D141C);
            g.drawString(font, h.tip(), x + 5, y + 4, COL_TEXT, false);
            return;
        }
    }

    // ------------------------------------------------------------------ painel

    private boolean panelOpen() {
        if (tab == TAB_LIST) return true;
        if (tab != TAB_INFO) return false;
        return siteMode() ? selSite >= 0 : hasSel;
    }

    private void closePanel() {
        tab = TAB_NONE;
        hasSel = false;
        setSite(-1);
    }

    private void setSite(int idx) {
        SiteTerritories.Data d = SiteTerritories.data();
        int old = selSite;
        selSite = idx;
        MapStyle.selectedProvince = idx;
        if (d == null) return;
        if (old >= 0 && old < d.n) MapData.invalidateRect(d.bounds[old][0], d.bounds[old][1], d.bounds[old][2], d.bounds[old][3]);
        if (idx >= 0 && idx < d.n) MapData.invalidateRect(d.bounds[idx][0], d.bounds[idx][1], d.bounds[idx][2], d.bounds[idx][3]);
    }

    private void drawPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (!panelOpen()) return;
        panelX = width - PW - MARGIN;
        panelY = MARGIN;
        panelW = PW;
        panelH = height - MARGIN * 2 - BAR_H - 8;
        panelVisible = true;
        box(g, panelX, panelY, panelW, panelH, COL_PANEL);
        hits.add(new Hit(panelX, panelY, panelW, panelH, null, null));

        if (tab == TAB_LIST) {
            if (siteMode()) drawSiteList(g, mouseX, mouseY);
            else drawManualList(g, mouseX, mouseY);
        } else if (siteMode()) {
            drawCountryPanel(g, mouseX, mouseY);
        } else {
            drawManualPanel(g, mouseX, mouseY);
        }
    }

    /** Cabeçalho fixo: faixa na cor do país, "bandeira", título, subtítulo e botão de fechar. */
    private int header(GuiGraphics g, int rgb, String title, String subtitle, int mouseX, int mouseY) {
        int x = panelX, y = panelY, w = panelW;
        int band = darken(rgb | 0xFF000000, 0.55f);
        g.fill(x + 1, y + 1, x + w - 1, y + 40, band);
        g.fill(x + 1, y + 40, x + w - 1, y + 41, COL_GOLD);
        // "bandeira": a cor do país num quadro com moldura
        g.fill(x + 9, y + 8, x + 45, y + 32, 0xFF05080C);
        g.fill(x + 10, y + 9, x + 44, y + 31, rgb | 0xFF000000);
        g.fill(x + 10, y + 9, x + 44, y + 15, lighten(rgb | 0xFF000000, 1.25f));
        g.drawString(font, fit(title.toUpperCase(), w - 86), x + 52, y + 9, 0xFFFFFFFF, true);
        if (subtitle != null) g.drawString(font, fit(subtitle, w - 86), x + 52, y + 22, COL_TEXT, false);
        // fechar
        int cx = x + w - 20, cyy = y + 6;
        boolean hover = mouseX >= cx && mouseX < cx + 14 && mouseY >= cyy && mouseY < cyy + 14;
        g.fill(cx, cyy, cx + 14, cyy + 14, hover ? 0xFF5A2A2A : 0xFF2A1A1A);
        g.drawString(font, "x", cx + 4, cyy + 3, COL_TEXT, false);
        hits.add(new Hit(cx, cyy, 14, 14, this::closePanel, "Fechar"));
        return y + 46;
    }

    private static int darken(int c, float k) {
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * k) << 16) | ((int) (((c >> 8) & 0xFF) * k) << 8) | (int) ((c & 0xFF) * k);
    }

    private static int lighten(int c, float k) {
        return 0xFF000000 | (Math.min(255, (int) (((c >> 16) & 0xFF) * k)) << 16)
                | (Math.min(255, (int) (((c >> 8) & 0xFF) * k)) << 8) | Math.min(255, (int) ((c & 0xFF) * k));
    }

    private String fit(String s, int maxWidth) {
        if (s == null) return "";
        return font.width(s) <= maxWidth ? s : font.plainSubstrByWidth(s, maxWidth - font.width("...")) + "...";
    }

    private List<String> wrap(String text, int maxW) {
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

    /** Começa a área rolável do painel, abaixo do cabeçalho. */
    private void beginScroll(GuiGraphics g, int top, double scroll) {
        g.enableScissor(panelX + 1, top, panelX + panelW - 1, panelY + panelH - 1);
        cy = top + 4 - (int) scroll;
        rowIdx = 0;
    }

    private void endScroll(GuiGraphics g, int top, boolean info) {
        g.disableScissor();
        contentH = cy - (top + 4) + 8;
        int viewH = panelY + panelH - top;
        double max = Math.max(0, contentH - viewH);
        if (info) scrollInfo = Math.max(0, Math.min(scrollInfo, max));
        else scrollList = Math.max(0, Math.min(scrollList, max));
    }

    private void section(GuiGraphics g, String title) {
        cy += 4;
        g.drawString(font, title, panelX + 9, cy, COL_GOLD, false);
        g.fill(panelX + 8, cy + 10, panelX + panelW - 8, cy + 11, COL_GOLD_DIM);
        cy += 15;
        rowIdx = 0;
    }

    /** Linha da tabela: rótulo à esquerda, valor (com quebra de linha) à direita. */
    private void kv(GuiGraphics g, String label, String value) {
        if (value == null || value.isBlank()) return;
        int labelCol = 84;
        List<String> lines = wrap(value, panelW - 16 - labelCol - 6);
        int h = Math.max(1, lines.size()) * 10 + 3;
        if ((rowIdx++ & 1) == 0) g.fill(panelX + 5, cy - 1, panelX + panelW - 5, cy + h - 1, 0x26FFFFFF);
        g.drawString(font, label, panelX + 9, cy + 1, COL_DIM, false);
        int ly = cy + 1;
        for (String line : lines) {
            g.drawString(font, line, panelX + 9 + labelCol, ly, COL_TEXT, false);
            ly += 10;
        }
        cy += h;
    }

    private void paragraph(GuiGraphics g, String text) {
        for (String line : wrap(text, panelW - 20)) {
            g.drawString(font, line, panelX + 10, cy, COL_TEXT, false);
            cy += 10;
        }
        cy += 2;
    }

    private static String join(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(", ", list);
    }

    private void drawCountryPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (selSite < 0 || selSite >= d.n) return;
        int c = d.provCountry[selSite];
        Map<String, String> info = d.info[c];
        int top = header(g, d.rgb[c], d.names[c], info.get("type"), mouseX, mouseY);

        beginScroll(g, top, scrollInfo);
        section(g, "INFORMAÇÕES");
        String official = info.get("officialName");
        kv(g, "Nome oficial", official == null ? d.names[c] : official);
        kv(g, "Tipo", info.get("type"));
        kv(g, "Formação", info.get("formed"));
        kv(g, "Governo", info.get("government"));
        kv(g, "Líder", info.get("leader"));
        kv(g, "Capital", info.get("capital"));
        kv(g, "Etnia", info.get("ethnicity"));
        kv(g, "Gentílico", info.get("demonym"));

        section(g, "TERRITÓRIO");
        kv(g, "Províncias", String.valueOf(d.provinceCount[c]));
        kv(g, "Selecionada", d.provLabel[selSite]);
        kv(g, "População", String.valueOf(d.population[c]));

        SiteTerritories.Diplomacy dip = d.diplomacy[c];
        if (dip != null) {
            section(g, "DIPLOMACIA");
            kv(g, "Suserano", join(dip.overlord));
            kv(g, "Súditos", join(dip.subject));
            kv(g, "Aliados", join(dip.allies));
            kv(g, "Pacto (NAP)", join(dip.nap));
            kv(g, "Alianças", join(dip.alliances));
            if (dip.wars != null && !dip.wars.isEmpty()) {
                List<String> wars = new ArrayList<>();
                for (SiteTerritories.WarRef w : dip.wars) {
                    String side = "attacker".equals(w.side) ? "atacante" : "defender".equals(w.side) ? "defensor" : w.side;
                    wars.add(w.name + (side == null ? "" : " (" + side + ")"));
                }
                kv(g, "Guerras", join(wars));
            }
        }

        List<String> people = d.people[c];
        if (people != null && !people.isEmpty()) {
            section(g, "POPULAÇÃO (" + people.size() + ")");
            paragraph(g, String.join(", ", people.size() > 60 ? people.subList(0, 60) : people)
                    + (people.size() > 60 ? " ..." : ""));
        }
        endScroll(g, top, true);
    }

    private void selectCountry(int country) {
        SiteTerritories.Data d = SiteTerritories.data();
        long best = -1;
        int bestIdx = -1;
        for (int i = 0; i < d.n; i++) {
            if (d.provCountry[i] != country) continue;
            int[] b = d.bounds[i];
            long area = (long) (b[2] - b[0] + 1) * (b[3] - b[1] + 1);
            if (area > best) {
                best = area;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) return;
        setSite(bestIdx);
        int[] b = d.bounds[bestIdx];
        viewX = (b[0] + b[2]) / 2.0;
        viewZ = (b[1] + b[3]) / 2.0;
        if (scale < 0.25) scale = 0.25;
        tab = TAB_INFO;
        scrollInfo = 0;
    }

    private void drawSiteList(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        int top = header(g, 0xFFC8A64A, "Países", d.ids.length + " países  -  " + d.n + " províncias", mouseX, mouseY);
        if (legendVersion != SiteTerritories.version()) {
            legendVersion = SiteTerritories.version();
            legendOrder = new ArrayList<>();
            for (int i = 0; i < d.ids.length; i++) if (d.provinceCount[i] > 0) legendOrder.add(i);
            legendOrder.sort((a, b) -> Integer.compare(d.provinceCount[b], d.provinceCount[a]));
        }
        beginScroll(g, top, scrollList);
        int bottom = panelY + panelH - 2;
        for (int idx : legendOrder) {
            int rowTop = cy;
            boolean visible = rowTop >= top - 12 && rowTop + 14 <= bottom + 12;
            if (visible) {
                boolean hover = mouseX >= panelX + 4 && mouseX < panelX + panelW - 4 && mouseY >= rowTop && mouseY < rowTop + 14
                        && mouseY >= top && mouseY < bottom;
                if (hover) g.fill(panelX + 4, rowTop, panelX + panelW - 4, rowTop + 14, 0x40C8A64A);
                else if ((rowIdx & 1) == 0) g.fill(panelX + 4, rowTop, panelX + panelW - 4, rowTop + 14, 0x16FFFFFF);
                g.fill(panelX + 9, rowTop + 3, panelX + 21, rowTop + 11, 0xFF05080C);
                g.fill(panelX + 10, rowTop + 4, panelX + 20, rowTop + 10, d.rgb[idx] | 0xFF000000);
                String count = String.valueOf(d.provinceCount[idx]);
                g.drawString(font, fit(d.labels[idx], panelW - 70), panelX + 27, rowTop + 3, COL_TEXT, false);
                g.drawString(font, count, panelX + panelW - 10 - font.width(count), rowTop + 3, COL_DIM, false);
                if (rowTop >= top && rowTop + 14 <= bottom) {
                    final int country = idx;
                    hits.add(new Hit(panelX + 4, rowTop, panelW - 8, 14, () -> selectCountry(country), null));
                }
            }
            rowIdx++;
            cy += 14;
        }
        endScroll(g, top, false);
    }

    // ---------------------------------------------------------- painel (modo manual)

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

    private void drawManualPanel(GuiGraphics g, int mouseX, int mouseY) {
        String ownerId = MapData.ownerOf(selPx, selPz);
        Countries.Country c = ownerId == null ? null : Countries.BY_ID.get(ownerId);
        int span = MapData.PROV * 16;
        int top = header(g, c == null ? 0xFF4A5560 : c.rgb, c == null ? "Sem dono" : c.name,
                "Província " + selPx + ", " + selPz + "  (modo manual)", mouseX, mouseY);

        beginScroll(g, top, 0);
        section(g, "PROVÍNCIA");
        kv(g, "ID", selPx + ", " + selPz);
        kv(g, "X", (selPx * span) + " a " + ((selPx + 1) * span - 1));
        kv(g, "Z", (selPz * span) + " a " + ((selPz + 1) * span - 1));
        int known = 0;
        for (int dx = 0; dx < MapData.PROV; dx++) {
            for (int dz = 0; dz < MapData.PROV; dz++) {
                if (MapData.chunkKnown(selPx * MapData.PROV + dx, selPz * MapData.PROV + dz)) known++;
            }
        }
        kv(g, "Explorado", known + "/" + (MapData.PROV * MapData.PROV) + " chunks");
        kv(g, "Dono", c == null ? "Ninguém" : c.name);

        cy += 6;
        int bx = panelX + 9;
        boolean enabled = !Countries.LIST.isEmpty();
        textButton(g, bx, cy, 28, 18, "<", mouseX, mouseY, enabled, () -> cycleOwner(-1));
        textButton(g, bx + 32, cy, 28, 18, ">", mouseX, mouseY, enabled, () -> cycleOwner(1));
        textButton(g, bx + 64, cy, panelW - 18 - 64, 18, "Sem dono", mouseX, mouseY, true,
                () -> MapData.setOwner(selPx, selPz, null));
        cy += 24;
        paragraph(g, "Sem dados do site: as províncias (32x32 blocos) são atribuídas na mão. Edite config/hoi4map/countries.json para mudar os países.");
        endScroll(g, top, true);
    }

    private void drawManualList(GuiGraphics g, int mouseX, int mouseY) {
        int top = header(g, 0xFFC8A64A, "Países", "modo manual", mouseX, mouseY);
        refreshStats();
        beginScroll(g, top, scrollList);
        for (Countries.Country c : Countries.LIST) {
            int rowTop = cy;
            if ((rowIdx & 1) == 0) g.fill(panelX + 4, rowTop, panelX + panelW - 4, rowTop + 14, 0x16FFFFFF);
            g.fill(panelX + 9, rowTop + 3, panelX + 21, rowTop + 11, 0xFF05080C);
            g.fill(panelX + 10, rowTop + 4, panelX + 20, rowTop + 10, c.rgb);
            double[] s = stats.get(c.id);
            String count = String.valueOf(s == null ? 0 : (int) s[2]);
            g.drawString(font, fit(c.name, panelW - 70), panelX + 27, rowTop + 3, COL_TEXT, false);
            g.drawString(font, count, panelX + panelW - 10 - font.width(count), rowTop + 3, COL_DIM, false);
            rowIdx++;
            cy += 14;
        }
        endScroll(g, top, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(mx, my)) continue;
            if (h.action() != null && event.button() == 0) h.action().run();
            return true;
        }
        dragging = true;
        moved = false;
        pressX = mx;
        pressY = my;
        return true;
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
            if (!moved) select(toBlockX(event.x()), toBlockZ(event.y()));
            return true;
        }
        return super.mouseReleased(event);
    }

    private void select(double bx, double bz) {
        if (siteMode()) {
            SiteTerritories.Data d = SiteTerritories.data();
            int p = SiteTerritories.provinceAt(d, (int) Math.floor(bx), (int) Math.floor(bz));
            setSite(p);
            if (p >= 0) {
                tab = TAB_INFO;
                scrollInfo = 0;
            } else if (tab == TAB_INFO) {
                tab = TAB_NONE; // clicar no mar fecha o painel
            }
            return;
        }
        selPx = provOf((int) Math.floor(bx / 16));
        selPz = provOf((int) Math.floor(bz / 16));
        hasSel = true;
        tab = TAB_INFO;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (panelVisible && mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH) {
            if (tab == TAB_LIST) scrollList = Math.max(0, scrollList - scrollY * 20);
            else scrollInfo = Math.max(0, scrollInfo - scrollY * 20);
            return true;
        }
        if (blockedAt(mouseX, mouseY)) return true;
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
