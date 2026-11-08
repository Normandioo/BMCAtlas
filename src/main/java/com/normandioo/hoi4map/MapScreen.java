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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Tela cheia do mapa, no estilo do menu de mapa do Hearts of Iron IV. */
public class MapScreen extends Screen {
    private static final int BTN = 26;       // lado dos botões de ícone
    private static final int BAR_H = 34;     // altura da barra de ícones
    private static final int PW = 272;       // largura do painel
    private static final int CARD_W = 244;   // largura do cartão do jogador
    private static final int MARGIN = 8;

    private static final int MODE_PHYSICAL = MapStyle.MODE_PHYSICAL;
    private static final int MODE_POLITICAL = MapStyle.MODE_POLITICAL;
    private static final int MODE_PROVINCES = MapStyle.MODE_PROVINCES;
    private static final int MODE_DIPLOMACY = MapStyle.MODE_DIPLOMACY;
    private static final int MODE_WAR = MapStyle.MODE_WAR;

    private static final int COL_GOLD = 0xFFC8A64A;
    private static final int COL_GOLD_DIM = 0xFF6B5A2A;
    private static final int COL_PANEL = 0xF2101820;
    private static final int COL_PANEL_2 = 0xFF16212C;
    private static final int COL_TEXT = 0xFFE6E0CC;
    private static final int COL_DIM = 0xFF8C94A0;
    private static final int COL_LINK = 0xFFE0C068;

    private static final int TAB_NONE = 0, TAB_INFO = 1, TAB_LIST = 2, TAB_DIPLO = 3, TAB_WAR = 4, TAB_CITY = 5, TAB_GEN = 6;

    // O estado da vista persiste entre aberturas da tela.
    private static double viewX, viewZ;
    private static double scale = 1.0;
    private static boolean viewInit;
    private static int mode = MODE_POLITICAL;
    private static int tab = TAB_NONE;
    private static final double[] SCROLL = new double[8];
    private static double cardScroll;
    private static boolean showCities = true;
    private static boolean showLimits = false;

    // seleção no modo manual (províncias de 32x32 blocos)
    private static boolean hasSel;
    private static int selPx, selPz;
    // seleção no modo site
    private static int selSite = -1;       // província
    private static int selCity = -1;       // índice em Data.localities
    private static String selPlayer;       // nome no site
    private static int dipFocus = -1;      // país em foco no modo diplomacia
    private static int dipAlliance = -1;   // aliança escolhida (índice em war.alliances)
    private static int warSel = -1;        // guerra escolhida (índice em war.wars)
    private static boolean genConfirm;
    private static int copiedFrames;

    // cores dos modos diplomacia/guerra
    private static int ovSig = Integer.MIN_VALUE;
    private static List<Relations.Rel> legend = List.of();
    private static Map<String, Integer> zoneColors = Map.of();

    private boolean dragging;
    private boolean moved;
    private double pressX, pressY;

    // UI em modo imediato: cada quadro registra as áreas clicáveis
    private record Hit(int x, int y, int w, int h, Runnable action, String tip) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private List<Hit> hits = new ArrayList<>();
    private List<Hit> prevHits = new ArrayList<>();

    // painel principal
    private int panelX, panelY, panelW, panelH;
    private boolean panelVisible;
    // caixa atual dos helpers (painel ou cartão)
    private int bx, by, bw, bh;
    private int cy;          // cursor vertical
    private int rowIdx;
    private int scrollTop, scrollBottom;
    private int cardX, cardY, cardH;
    private boolean cardVisible;

    private final ItemStack iconPhysical = new ItemStack(Items.GRASS_BLOCK);
    private final ItemStack iconPolitical = new ItemStack(Items.WHITE_BANNER);
    private final ItemStack iconProvinces = new ItemStack(Items.IRON_BARS);
    private final ItemStack iconDiplomacy = new ItemStack(Items.EMERALD);
    private final ItemStack iconWar = new ItemStack(Items.IRON_SWORD);
    private final ItemStack iconCountries = new ItemStack(Items.BOOK);
    private final ItemStack iconCities = new ItemStack(Items.BELL);
    private final ItemStack iconLimits = new ItemStack(Items.OAK_FENCE);
    private final ItemStack iconCenter = new ItemStack(Items.COMPASS);
    private final ItemStack iconSource = new ItemStack(Items.LEVER);
    private final ItemStack iconReload = new ItemStack(Items.CLOCK);
    private final ItemStack iconGen = new ItemStack(Items.MAP);

    // cache do modo manual: país -> {somaX, somaZ, n, somaXX, somaZZ, somaXZ} (em províncias)
    private int cacheVersion = -1;
    private final Map<String, double[]> stats = new HashMap<>();

    // cache da lista do site
    private int legendVersion = -1;
    private List<Integer> legendOrder = new ArrayList<>();
    private static final Map<String, Integer> TEXT_WIDTH = new HashMap<>();

    public MapScreen() {
        super(Component.literal("Mapa"));
    }

    private static boolean siteMode() {
        return MapData.siteApplies() && SiteTerritories.active();
    }

    private static boolean relationalMode() {
        return MapStyle.relational(mode);
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

    private boolean blockedAt(List<Hit> list, double mx, double my) {
        for (Hit h : list) if (h.contains(mx, my)) return true;
        return false;
    }

    // ----------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        MapData.nextFrame();
        RemoteImages.pump();
        List<Hit> t = prevHits;
        prevHits = hits;
        hits = t;
        hits.clear();
        panelVisible = false;
        cardVisible = false;
        if (relationalMode() && !siteMode()) mode = MODE_POLITICAL;
        refreshOverrides();

        g.fill(0, 0, width, height, 0xFF0B1628);
        drawMap(g, mouseX, mouseY);

        drawPanel(g, mouseX, mouseY);
        drawCard(g, mouseX, mouseY);
        drawLegend(g);
        drawBar(g, mouseX, mouseY);
        drawHover(g, mouseX, mouseY);
        drawTooltip(g, mouseX, mouseY);
        if (copiedFrames > 0) copiedFrames--;
    }

    /** Recalcula as cores dos modos diplomacia/guerra quando algo muda. */
    private void refreshOverrides() {
        SiteTerritories.Data d = SiteTerritories.data();
        boolean rel = relationalMode() && siteMode() && d != null;
        if (d != null) {
            if (dipFocus >= d.ids.length) dipFocus = -1;
            if (selCity >= d.localities.size()) selCity = -1;
            if (dipAlliance >= (d.war == null || d.war.alliances == null ? 0 : d.war.alliances.size())) dipAlliance = -1;
            if (warSel >= Relations.wars(d).size()) warSel = -1;
        }
        int sig = Objects.hash(mode, dipFocus, dipAlliance, warSel, SiteTerritories.version(), rel);
        if (sig == ovSig) return;
        ovSig = sig;
        if (!rel) {
            MapStyle.countryOverride = null;
            MapStyle.provinceOverride = null;
            legend = List.of();
            zoneColors = Map.of();
            return;
        }
        Relations.Result r;
        if (mode == MODE_DIPLOMACY) {
            if (dipFocus >= 0) r = Relations.focus(d, dipFocus);
            else if (dipAlliance >= 0) r = Relations.alliance(d, d.war.alliances.get(dipAlliance));
            else r = Relations.blocs(d);
        } else {
            List<SiteTerritories.WarInfo> wars = Relations.wars(d);
            r = Relations.war(d, warSel >= 0 ? wars.get(warSel) : null);
        }
        MapStyle.countryOverride = r.country();
        MapStyle.provinceOverride = r.provinces();
        MapStyle.overrideVersion++;
        legend = r.legend();
        zoneColors = r.zones();
    }

    private boolean regionTouchesSite(SiteTerritories.Data d, int rx, int rz) {
        int x0 = rx * MapData.REGION_PX, z0 = rz * MapData.REGION_PX;
        return x0 + MapData.REGION_PX > d.minX && x0 <= d.maxX && z0 + MapData.REGION_PX > d.minZ && z0 <= d.maxZ;
    }

    private void drawMap(GuiGraphics g, int mouseX, int mouseY) {
        double leftB = toBlockX(0);
        double rightB = toBlockX(width);
        double topB = toBlockZ(0);
        double botB = toBlockZ(height);

        boolean site = siteMode();
        SiteTerritories.Data sd = site && mode != MODE_PHYSICAL ? SiteTerritories.data() : null;
        boolean rel = relationalMode();

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
                boolean stale = r.id == null || r.dirty || r.styledMode != mode || (rel && r.styledVer != MapStyle.overrideVersion);
                if (stale && uploads < 3) {
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

        SiteTerritories.Data d = SiteTerritories.data();
        if (!site) {
            if (mode == MODE_PROVINCES) drawGrid(g, leftB, rightB, topB, botB);
            if (mode == MODE_POLITICAL) drawManualNames(g);
            drawManualSelection(g);
        } else if (d != null) {
            if (mode == MODE_POLITICAL || rel) drawSiteNames(g, d);
            drawZones(g, d);
            drawCities(g, d, mouseX, mouseY);
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

    private int textWidth(String text) {
        Integer c = TEXT_WIDTH.get(text);
        if (c != null) return c;
        int tw = 0;
        for (int i = 0; i < text.length(); i++) {
            tw += font.width(Component.literal(String.valueOf(text.charAt(i))).withStyle(ChatFormatting.BOLD)) + (i > 0 ? 2 : 0);
        }
        TEXT_WIDTH.put(text, tw);
        return tw;
    }

    /** Nomes dos países: cada um cabe inteiro dentro do território (posição calculada por LabelFit). */
    private void drawSiteNames(GuiGraphics g, SiteTerritories.Data d) {
        if (scale > 1.2) return; // muito perto: os nomes só atrapalham
        for (int c = 0; c < d.ids.length; c++) {
            if (d.shape[c] == null) continue;
            String text = d.labels[c].toUpperCase();
            double[] f = d.fit[c];
            if (f == null) {
                if (!d.fitAsked[c]) {
                    d.fitAsked[c] = true;
                    LabelFit.request(d, c, textWidth(text));
                }
                continue;
            }
            if (f.length < 4) continue;
            float sc = (float) (f[3] * scale);
            if (sc < 0.55f) continue;           // pequeno demais para ler
            sc = Math.min(sc, 6.0f);
            double sx = toScreenX(f[0]);
            double sy = toScreenY(f[1]);
            if (sx < -400 || sx > width + 400 || sy < -200 || sy > height + 200) continue;
            drawLabel(g, text, (float) sx, (float) sy, (float) f[2], sc);
        }
    }

    private void drawLabel(GuiGraphics g, String text, float sx, float sy, float angle, float sc) {
        int spacing = 2;
        int tw = 0;
        int[] widths = new int[text.length()];
        Component[] comps = new Component[text.length()];
        for (int i = 0; i < text.length(); i++) {
            comps[i] = Component.literal(String.valueOf(text.charAt(i))).withStyle(ChatFormatting.BOLD);
            widths[i] = font.width(comps[i]);
            tw += widths[i] + (i > 0 ? spacing : 0);
        }
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().rotate(angle);
        g.pose().scale(sc, sc);
        int x = -tw / 2;
        int[][] outline = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < comps.length; i++) {
            for (int[] o : outline) g.drawString(font, comps[i], x + o[0], -4 + o[1], 0xB00A0E14, false);
            g.drawString(font, comps[i], x, -4, 0xE6EDE8D8, false);
            x += widths[i] + spacing;
        }
        g.pose().popMatrix();
    }

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

    /** Modo manual: nome no centro do país, com o tamanho limitado pela área real. */
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
            double common = Math.sqrt(((vxx - vzz) / 2) * ((vxx - vzz) / 2) + vxz * vxz);
            double l1 = ((vxx + vzz) / 2 + common) * k;
            double area = n * k;
            double extent = Math.min(Math.sqrt(12 * Math.max(l1, 0.08 * k)), 1.6 * Math.sqrt(area));
            double sx = toScreenX((mx + 0.5) * span);
            double sy = toScreenY((mz + 0.5) * span);
            if (sx < -300 || sx > width + 300 || sy < -100 || sy > height + 100) continue;
            String text = c.name.toUpperCase();
            float sc = (float) (extent * scale * 0.8 / Math.max(1, textWidth(text)));
            sc = Math.min(Math.min(sc, 4.0f), width * 0.3f / Math.max(1, textWidth(text)));
            if (sc < 0.55f) continue;
            drawLabel(g, text, (float) sx, (float) sy, 0f, sc);
        }
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

    // ----------------------------------------------------------- limites e cidades

    private SiteTerritories.Zone zoneOf(SiteTerritories.Data d, SiteTerritories.Locality l) {
        return d.zoneByKey.get(SiteTerritories.zoneKey(l.name, l.country));
    }

    private void drawZones(GuiGraphics g, SiteTerritories.Data d) {
        if (scale < 0.1 || d.zones.isEmpty()) return;
        double left = toBlockX(0), right = toBlockX(width), top = toBlockZ(0), bottom = toBlockZ(height);
        SiteTerritories.Zone sel = selCity >= 0 && selCity < d.localities.size() ? zoneOf(d, d.localities.get(selCity)) : null;
        boolean war = mode == MODE_WAR && !zoneColors.isEmpty();
        for (SiteTerritories.Zone z : d.zones) {
            boolean isSel = z == sel;
            Integer warColor = war ? zoneColors.get(SiteTerritories.zoneKey(z.name, z.country)) : null;
            if (!showLimits && !isSel && warColor == null) continue;
            double[] box = z.box;
            if (box == null || box[2] < left || box[0] > right || box[3] < top || box[1] > bottom) continue;
            int color;
            int thick = 1;
            if (isSel) {
                color = 0xFFFFF2B0;
                thick = 2;
            } else if (warColor != null) {
                color = 0xFF000000 | warColor;
                thick = 2;
            } else {
                int base = z.countryIdx >= 0 ? d.rgb[z.countryIdx] : 0xFF909090;
                color = lighten(base, 1.6f);
            }
            for (double[] ring : z.rings) drawRing(g, ring, color, thick);
        }
    }

    private void drawRing(GuiGraphics g, double[] ring, int color, int thick) {
        int n = ring.length / 2;
        if (n < 2) return;
        double fx = toScreenX(ring[0]), fy = toScreenY(ring[1]);
        double px = fx, py = fy;
        for (int i = 1; i < n; i++) {
            double x = toScreenX(ring[2 * i]), y = toScreenY(ring[2 * i + 1]);
            if (Math.abs(x - px) + Math.abs(y - py) < 4 && i < n - 1) continue; // pontos muito juntos
            segment(g, px, py, x, y, color, thick);
            px = x;
            py = y;
        }
        segment(g, px, py, fx, fy, color, thick);
    }

    private void segment(GuiGraphics g, double x0, double y0, double x1, double y1, int color, int thick) {
        if ((x0 < 0 && x1 < 0) || (x0 > width && x1 > width) || (y0 < 0 && y1 < 0) || (y0 > height && y1 > height)) return;
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 0.5) return;
        if (Math.abs(dy) < 0.5) {
            int xa = (int) Math.floor(Math.min(x0, x1)), xb = (int) Math.ceil(Math.max(x0, x1));
            g.fill(xa, (int) Math.floor(y0), xb, (int) Math.floor(y0) + thick, color);
        } else if (Math.abs(dx) < 0.5) {
            int ya = (int) Math.floor(Math.min(y0, y1)), yb = (int) Math.ceil(Math.max(y0, y1));
            g.fill((int) Math.floor(x0), ya, (int) Math.floor(x0) + thick, yb, color);
        } else {
            g.pose().pushMatrix();
            g.pose().translate((float) x0, (float) y0);
            g.pose().rotate((float) Math.atan2(dy, dx));
            g.fill(0, 0, (int) Math.ceil(len), thick, color);
            g.pose().popMatrix();
        }
    }

    private static final double[] ICON_MIN = {0.35, 0.16, 0.08, 0.04, 0.025};
    private static final double[] NAME_MIN = {0.9, 0.45, 0.22, 0.12, 0.07};

    private boolean cityVisible(SiteTerritories.Locality l) {
        return showCities && scale >= ICON_MIN[l.tier];
    }

    /** A cidade mais importante sob o ponto da tela, ou null. */
    private SiteTerritories.Locality cityAt(double mx, double my) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null || !siteMode() || !showCities) return null;
        for (SiteTerritories.Locality l : d.cityOrder) {
            if (!cityVisible(l)) continue;
            double sx = toScreenX(l.x + 0.5), sy = toScreenY(l.z + 0.5);
            double r = iconRadius(l) + 4;
            if (Math.abs(mx - sx) <= r && Math.abs(my - sy) <= r) return l;
        }
        return null;
    }

    private int iconRadius(SiteTerritories.Locality l) {
        if ("Fort".equals(l.kind)) return 3;
        return l.tier == 4 ? 5 : l.tier == 3 ? 4 : l.tier == 2 ? 3 : l.tier == 1 ? 2 : 1;
    }

    private void drawCities(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        if (!showCities || d.cityOrder.length == 0) return;
        SiteTerritories.Locality hover = blockedAt(prevHits, mouseX, mouseY) ? null : cityAt(mouseX, mouseY);
        List<int[]> taken = new ArrayList<>();
        for (SiteTerritories.Locality l : d.cityOrder) {
            if (!cityVisible(l)) continue;
            int sx = (int) Math.round(toScreenX(l.x + 0.5));
            int sy = (int) Math.round(toScreenY(l.z + 0.5));
            if (sx < -30 || sx > width + 30 || sy < -30 || sy > height + 30) continue;
            int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] : 0xFF909090;
            boolean hot = l == hover || l.idx == selCity;
            drawIcon(g, sx, sy, l, rgb, hot);
            if (scale >= NAME_MIN[l.tier] || hot) {
                String name = SiteTerritories.splitCamel(l.name);
                int w = font.width(name) + 2;
                int rx = sx + iconRadius(l) + 4, ry = sy - 5;
                boolean clash = false;
                for (int[] r : taken) {
                    if (rx < r[0] + r[2] && rx + w > r[0] && ry < r[1] + r[3] && ry + 10 > r[1]) {
                        clash = true;
                        break;
                    }
                }
                if (!clash || hot) {
                    g.drawString(font, name, rx, ry + 1, hot ? 0xFFFFF2B0 : 0xFFF0EAD6, true);
                    taken.add(new int[]{rx, ry, w, 10});
                }
            }
        }
    }

    private void drawIcon(GuiGraphics g, int x, int y, SiteTerritories.Locality l, int rgb, boolean hot) {
        int r = iconRadius(l);
        int fill = lighten(rgb, 1.3f);
        int dark = 0xFF05080C;
        if ("Fort".equals(l.kind)) {
            for (int i = -r; i <= r; i++) {
                int w = r - Math.abs(i);
                g.fill(x - w, y + i, x + w + 1, y + i + 1, hot ? 0xFFFFF2B0 : dark);
            }
            for (int i = -(r - 1); i <= r - 1; i++) {
                int w = r - 1 - Math.abs(i);
                if (w >= 0) g.fill(x - w, y + i, x + w + 1, y + i + 1, fill);
            }
            return;
        }
        if (hot) g.fill(x - r - 2, y - r - 2, x + r + 3, y + r + 3, 0xFFFFF2B0);
        if (l.tier == 4) {
            g.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2, COL_GOLD);
            g.fill(x - r, y - r, x + r + 1, y + r + 1, dark);
            g.fill(x - r + 1, y - r + 1, x + r, y + r, fill);
        } else {
            g.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2, dark);
            g.fill(x - r, y - r, x + r + 1, y + r + 1, fill);
        }
    }

    // ------------------------------------------------------------- coordenadas

    private void drawHover(GuiGraphics g, int mouseX, int mouseY) {
        String hint = "Arraste: mover  |  Roda: zoom  |  Clique: selecionar  |  1-5: modos";
        g.drawString(font, hint, MARGIN, height - 12, COL_DIM, true);
        if (blockedAt(hits, mouseX, mouseY)) return;
        int bxx = (int) Math.floor(toBlockX(mouseX));
        int bzz = (int) Math.floor(toBlockZ(mouseY));
        String text = "X " + bxx + "  Z " + bzz;
        SiteTerritories.Data d = SiteTerritories.data();
        if (siteMode() && d != null) {
            SiteTerritories.Locality city = cityAt(mouseX, mouseY);
            if (city != null) {
                text += "  |  " + SiteTerritories.splitCamel(city.name);
            } else {
                int p = SiteTerritories.provinceAt(d, bxx, bzz);
                if (p >= 0) text += "  |  " + d.labels[d.provCountry[p]] + " - " + d.provLabel[p];
            }
        }
        int w = font.width(text) + 10;
        int y = height - 34;
        box(g, MARGIN - 2, y - 3, w, 15, COL_PANEL_2);
        g.drawString(font, text, MARGIN + 3, y, COL_TEXT, false);
    }

    /** Legenda dos modos diplomacia e guerra, no canto inferior esquerdo. */
    private void drawLegend(GuiGraphics g) {
        if (!relationalMode() || legend.isEmpty()) return;
        int rows = Math.min(legend.size(), 9);
        int w = 150;
        for (int i = 0; i < rows; i++) w = Math.max(w, font.width(legend.get(i).label()) + 34);
        int h = rows * 13 + 8;
        int x = MARGIN - 2;
        int y = height - 34 - 8 - h;
        box(g, x, y, w, h, COL_PANEL);
        for (int i = 0; i < rows; i++) {
            Relations.Rel rel = legend.get(i);
            int yy = y + 5 + i * 13;
            g.fill(x + 6, yy, x + 20, yy + 9, 0xFF05080C);
            g.fill(x + 7, yy + 1, x + 19, yy + 8, 0xFF000000 | rel.rgb());
            String label = rel.label();
            if (!rel.countries().isEmpty() && rel.countries().size() > 1) label += " (" + rel.countries().size() + ")";
            g.drawString(font, label, x + 25, yy + 1, COL_TEXT, false);
        }
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

    private boolean visible(int y, int h) {
        return y >= scrollTop && y + h <= scrollBottom;
    }

    /** Botão de texto dentro da área rolável (só clicável quando inteiro à vista). */
    private void textButton(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY, boolean enabled, Runnable action) {
        boolean vis = visible(y, h);
        boolean hover = vis && enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF26364A : 0xFF1A2430);
        g.fill(x, y, x + w, y + 1, COL_GOLD_DIM);
        g.fill(x, y + h - 1, x + w, y + h, COL_GOLD_DIM);
        g.fill(x, y, x + 1, y + h, COL_GOLD_DIM);
        g.fill(x + w - 1, y, x + w, y + h, COL_GOLD_DIM);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, enabled ? COL_TEXT : COL_DIM, false);
        if (vis && enabled) hits.add(new Hit(x, y, w, h, action, null));
    }

    // --------------------------------------------------------------- barra de ícones

    private void drawBar(GuiGraphics g, int mouseX, int mouseY) {
        boolean site = siteMode();
        int n = 12;
        int barW = n * (BTN + 4) + 4 + 3 * 6;
        int barX = width - barW - MARGIN;
        int by0 = height - BAR_H - MARGIN;
        box(g, barX, by0, barW, BAR_H, COL_PANEL);
        g.fill(barX + 1, by0 + 1, barX + barW - 1, by0 + 2, COL_GOLD);
        hits.add(new Hit(barX, by0, barW, BAR_H, null, null));

        int x = barX + 4;
        int y = by0 + 4;
        String needSite = " (requer dados do site)";
        iconButton(g, x, y, iconPhysical, mode == MODE_PHYSICAL, true, mouseX, mouseY, "Mapa físico (1)", () -> mode = MODE_PHYSICAL);
        x += BTN + 4;
        iconButton(g, x, y, iconPolitical, mode == MODE_POLITICAL, true, mouseX, mouseY, "Mapa político (2)", () -> mode = MODE_POLITICAL);
        x += BTN + 4;
        iconButton(g, x, y, iconProvinces, mode == MODE_PROVINCES, true, mouseX, mouseY, "Províncias (3)", () -> mode = MODE_PROVINCES);
        x += BTN + 4;
        iconButton(g, x, y, iconDiplomacy, mode == MODE_DIPLOMACY, site, mouseX, mouseY,
                site ? "Diplomacia (4)" : "Diplomacia" + needSite, this::enterDiplomacy);
        x += BTN + 4;
        iconButton(g, x, y, iconWar, mode == MODE_WAR, site, mouseX, mouseY,
                site ? "Guerra (5)" : "Guerra" + needSite, this::enterWar);
        x += BTN + 4 + 6;

        iconButton(g, x, y, iconCountries, tab == TAB_LIST, true, mouseX, mouseY, "Lista de países",
                () -> tab = tab == TAB_LIST ? TAB_NONE : TAB_LIST);
        x += BTN + 4;
        iconButton(g, x, y, iconCities, showCities, site, mouseX, mouseY,
                site ? (showCities ? "Cidades: ligado" : "Cidades: desligado") : "Cidades" + needSite, () -> showCities = !showCities);
        x += BTN + 4;
        iconButton(g, x, y, iconLimits, showLimits, site, mouseX, mouseY,
                site ? (showLimits ? "Limites das cidades: ligado" : "Limites das cidades: desligado") : "Limites das cidades" + needSite,
                () -> showLimits = !showLimits);
        x += BTN + 4 + 6;

        iconButton(g, x, y, iconCenter, false, true, mouseX, mouseY, "Centralizar em você", this::centerOnPlayer);
        x += BTN + 4;
        boolean haveSite = SiteTerritories.data() != null;
        String sourceTip = !haveSite ? "Fonte: manual (sem dados do site)"
                : SiteTerritories.useSite ? "Fonte: site (clique para modo manual)" : "Fonte: manual (clique para usar o site)";
        iconButton(g, x, y, iconSource, haveSite && SiteTerritories.useSite, haveSite, mouseX, mouseY, sourceTip, () -> {
            SiteTerritories.useSite = !SiteTerritories.useSite;
            tab = TAB_NONE;
            selCity = -1;
            MapData.ownersChanged();
        });
        x += BTN + 4;
        iconButton(g, x, y, iconReload, false, true, mouseX, mouseY, "Recarregar países e site", () -> {
            Countries.load();
            SiteTerritories.reload();
        });
        x += BTN + 4 + 6;
        iconButton(g, x, y, iconGen, tab == TAB_GEN || ChunkGen.state != ChunkGen.State.IDLE, true, mouseX, mouseY,
                ChunkGen.state == ChunkGen.State.IDLE ? "Gerar todos os chunks do mapa" : "Gerador de chunks (em andamento)",
                () -> {
                    genConfirm = false;
                    tab = tab == TAB_GEN ? TAB_NONE : TAB_GEN;
                });
    }

    private void enterDiplomacy() {
        mode = MODE_DIPLOMACY;
        tab = TAB_DIPLO;
        SCROLL[TAB_DIPLO] = 0;
    }

    private void enterWar() {
        mode = MODE_WAR;
        tab = TAB_WAR;
        SCROLL[TAB_WAR] = 0;
    }

    private void drawTooltip(GuiGraphics g, int mouseX, int mouseY) {
        String tip = null;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mouseX, mouseY)) {
                tip = h.tip();
                break;
            }
        }
        if (tip == null) {
            SiteTerritories.Locality city = blockedAt(hits, mouseX, mouseY) ? null : cityAt(mouseX, mouseY);
            if (city != null) tip = SiteTerritories.splitCamel(city.name) + "  (" + SiteTerritories.splitCamel(city.kind == null ? "" : city.kind) + ")";
        }
        if (tip == null) return;
        int w = font.width(tip) + 10;
        int x = Math.min(mouseX + 8, width - w - 4);
        int y = Math.max(4, mouseY - 22);
        box(g, x, y, w, 16, 0xFF0D141C);
        g.drawString(font, tip, x + 5, y + 4, COL_TEXT, false);
    }

    // ------------------------------------------------------------------ painel

    private boolean panelOpen() {
        if (tab == TAB_NONE) return false;
        if (tab == TAB_LIST || tab == TAB_GEN) return true;
        if (!siteMode()) {
            return tab == TAB_INFO && hasSel;
        }
        return switch (tab) {
            case TAB_INFO -> selSite >= 0;
            case TAB_CITY -> selCity >= 0;
            case TAB_DIPLO, TAB_WAR -> true;
            default -> false;
        };
    }

    private void closePanel() {
        tab = TAB_NONE;
        hasSel = false;
        selCity = -1;
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
        bx = panelX;
        by = panelY;
        bw = panelW;
        bh = panelH;
        box(g, panelX, panelY, panelW, panelH, COL_PANEL);
        hits.add(new Hit(panelX, panelY, panelW, panelH, null, null));

        switch (tab) {
            case TAB_LIST -> {
                if (siteMode()) drawSiteList(g, mouseX, mouseY);
                else drawManualList(g, mouseX, mouseY);
            }
            case TAB_GEN -> drawGenPanel(g, mouseX, mouseY);
            case TAB_DIPLO -> drawDiploPanel(g, mouseX, mouseY);
            case TAB_WAR -> drawWarPanel(g, mouseX, mouseY);
            case TAB_CITY -> drawCityPanel(g, mouseX, mouseY);
            default -> {
                if (siteMode()) drawCountryPanel(g, mouseX, mouseY);
                else drawManualPanel(g, mouseX, mouseY);
            }
        }
    }

    /** Imagem (bandeira) dentro de uma caixa, mantendo a proporção. */
    private void drawImage(GuiGraphics g, RemoteImages.Img img, int x, int y, int maxW, int maxH) {
        double sc = Math.min((double) maxW / img.w(), (double) maxH / img.h());
        int dw = Math.max(1, (int) Math.round(img.w() * sc));
        int dh = Math.max(1, (int) Math.round(img.h() * sc));
        int dx = x + (maxW - dw) / 2, dy = y + (maxH - dh) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, img.id(), dx, dy, 0f, 0f, dw, dh, img.w(), img.h(), img.w(), img.h());
    }

    private String flagOf(SiteTerritories.Data d, int country) {
        if (country < 0 || d.info[country] == null) return null;
        return SiteTerritories.assetUrl(d.info[country].get("flag"), false);
    }

    /** Cabeçalho fixo: faixa na cor, bandeira, título, subtítulo e botão de fechar. */
    private int header(GuiGraphics g, int rgb, String title, String subtitle, String flagUrl, int mouseX, int mouseY, Runnable close) {
        int x = bx, y = by, w = bw;
        int band = darken(rgb | 0xFF000000, 0.55f);
        g.fill(x + 1, y + 1, x + w - 1, y + 44, band);
        g.fill(x + 1, y + 44, x + w - 1, y + 45, COL_GOLD);
        g.fill(x + 8, y + 8, x + 50, y + 36, 0xFF05080C);
        RemoteImages.Img flag = RemoteImages.flag(flagUrl);
        if (flag != null) {
            drawImage(g, flag, x + 9, y + 9, 40, 26);
        } else {
            g.fill(x + 9, y + 9, x + 49, y + 35, rgb | 0xFF000000);
            g.fill(x + 9, y + 9, x + 49, y + 16, lighten(rgb | 0xFF000000, 1.25f));
        }
        g.drawString(font, fit(title.toUpperCase(), w - 92), x + 58, y + 11, 0xFFFFFFFF, true);
        if (subtitle != null) g.drawString(font, fit(subtitle, w - 92), x + 58, y + 25, COL_TEXT, false);
        int cx = x + w - 20, cyy = y + 6;
        boolean hover = mouseX >= cx && mouseX < cx + 14 && mouseY >= cyy && mouseY < cyy + 14;
        g.fill(cx, cyy, cx + 14, cyy + 14, hover ? 0xFF5A2A2A : 0xFF2A1A1A);
        g.drawString(font, "x", cx + 4, cyy + 3, COL_TEXT, false);
        hits.add(new Hit(cx, cyy, 14, 14, close, "Fechar"));
        return y + 50;
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

    /** Começa a área rolável da caixa atual, abaixo do cabeçalho; reserve = altura fixa no fim. */
    private void beginScroll(GuiGraphics g, int top, double scroll, int reserve) {
        scrollTop = top;
        scrollBottom = by + bh - 1 - reserve;
        g.enableScissor(bx + 1, top, bx + bw - 1, scrollBottom);
        cy = top + 4 - (int) scroll;
        rowIdx = 0;
    }

    /** Fecha a área rolável; devolve a rolagem corrigida. */
    private double endScroll(GuiGraphics g, double scroll) {
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

    private void section(GuiGraphics g, String title) {
        cy += 4;
        g.drawString(font, title, bx + 9, cy, COL_GOLD, false);
        g.fill(bx + 8, cy + 10, bx + bw - 8, cy + 11, COL_GOLD_DIM);
        cy += 15;
        rowIdx = 0;
    }

    private void kv(GuiGraphics g, String label, String value) {
        kvImpl(g, label, value, null, 0, 0);
    }

    private void kvLink(GuiGraphics g, String label, String value, int mouseX, int mouseY, Runnable action) {
        kvImpl(g, label, value, action, mouseX, mouseY);
    }

    /** Linha da tabela: rótulo à esquerda, valor (com quebra de linha) à direita; com link, o valor é clicável. */
    private void kvImpl(GuiGraphics g, String label, String value, Runnable link, int mouseX, int mouseY) {
        if (value == null || value.isBlank()) return;
        int labelCol = 84;
        int valueX = bx + 9 + labelCol;
        List<String> lines = wrap(value, bw - 16 - labelCol - 6);
        int h = Math.max(1, lines.size()) * 10 + 3;
        if ((rowIdx++ & 1) == 0) g.fill(bx + 5, cy - 1, bx + bw - 5, cy + h - 1, 0x26FFFFFF);
        g.drawString(font, label, bx + 9, cy + 1, COL_DIM, false);
        boolean vis = visible(cy, h);
        boolean hover = link != null && vis && mouseX >= valueX && mouseX < bx + bw - 8 && mouseY >= cy && mouseY < cy + h;
        int color = link == null ? COL_TEXT : hover ? 0xFFFFE27A : COL_LINK;
        int ly = cy + 1;
        for (String line : lines) {
            g.drawString(font, line, valueX, ly, color, false);
            if (hover) g.fill(valueX, ly + 9, valueX + font.width(line), ly + 10, color);
            ly += 10;
        }
        if (link != null && vis) hits.add(new Hit(valueX, cy, bx + bw - 8 - valueX, h, link, null));
        cy += h;
    }

    private void paragraph(GuiGraphics g, String text) {
        paragraph(g, text, COL_TEXT);
    }

    private void paragraph(GuiGraphics g, String text, int color) {
        for (String line : wrap(text, bw - 20)) {
            g.drawString(font, line, bx + 10, cy, color, false);
            cy += 10;
        }
        cy += 2;
    }

    /** Nomes clicáveis em fluxo, como "fichas" (usado para a população e as relações). */
    private void chips(GuiGraphics g, List<String> names, int mouseX, int mouseY, Consumer<String> click) {
        int x = bx + 9, right = bx + bw - 9, rowH = 14;
        int lineY = cy;
        for (String n : names) {
            int w = font.width(n) + 8;
            if (x + w > right) {
                x = bx + 9;
                lineY += rowH + 2;
            }
            boolean vis = visible(lineY, rowH);
            boolean hover = vis && mouseX >= x && mouseX < x + w && mouseY >= lineY && mouseY < lineY + rowH;
            g.fill(x, lineY, x + w, lineY + rowH, hover ? 0xFF2E4258 : 0xFF1E2B3A);
            g.fill(x, lineY, x + w, lineY + 1, COL_GOLD_DIM);
            g.fill(x, lineY + rowH - 1, x + w, lineY + rowH, COL_GOLD_DIM);
            g.drawString(font, n, x + 4, lineY + 3, hover ? 0xFFFFE27A : COL_TEXT, false);
            if (vis) hits.add(new Hit(x, lineY, w, rowH, () -> click.accept(n), null));
            x += w + 4;
        }
        cy = lineY + rowH + 4;
    }

    private static String join(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(", ", list);
    }

    private static String pretty(String s) {
        return s == null ? null : SiteTerritories.splitCamel(s);
    }

    // ----------------------------------------------------------- painel do país

    private void drawCountryPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (selSite < 0 || selSite >= d.n) return;
        int c = d.provCountry[selSite];
        Map<String, String> info = d.info[c];
        int top = header(g, d.rgb[c], d.names[c], info.get("type"), flagOf(d, c), mouseX, mouseY, this::closePanel);

        beginScroll(g, top, SCROLL[TAB_INFO], 0);
        int bxx = bx + 9;
        textButton(g, bxx, cy, 124, 16, "Ver relações", mouseX, mouseY, true, () -> {
            dipFocus = c;
            dipAlliance = -1;
            mode = MODE_DIPLOMACY;
        });
        textButton(g, bxx + 130, cy, bw - 18 - 130, 16, "Centralizar", mouseX, mouseY, true, () -> selectCountry(c));
        cy += 20;

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
        int cities = 0;
        for (SiteTerritories.Locality l : d.localities) if (l.countryIdx == c) cities++;
        kv(g, "Localidades", String.valueOf(cities));
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
            section(g, "POPULAÇÃO (" + people.size() + ")  -  clique num nome");
            chips(g, people.size() > 80 ? people.subList(0, 80) : people, mouseX, mouseY, this::openPlayer);
        }
        SCROLL[TAB_INFO] = endScroll(g, SCROLL[TAB_INFO]);
    }

    private void openPlayer(String name) {
        selPlayer = name;
        cardScroll = 0;
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
        selCity = -1;
        if (mode == MODE_DIPLOMACY) {
            dipFocus = country;
            dipAlliance = -1;
        }
        int[] b = d.bounds[bestIdx];
        viewX = (b[0] + b[2]) / 2.0;
        viewZ = (b[1] + b[3]) / 2.0;
        if (scale < 0.25) scale = 0.25;
        tab = TAB_INFO;
        SCROLL[TAB_INFO] = 0;
    }

    // ----------------------------------------------------------- painel da cidade

    private void drawCityPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (selCity < 0 || selCity >= d.localities.size()) return;
        SiteTerritories.Locality l = d.localities.get(selCity);
        int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] : 0xFF909090;
        String subtitle = pretty(l.kind) + (l.development == null ? "" : "  -  " + l.development);
        int top = header(g, rgb, pretty(l.name), subtitle, flagOf(d, l.countryIdx), mouseX, mouseY, this::closePanel);

        beginScroll(g, top, SCROLL[TAB_CITY], 0);
        textButton(g, bx + 9, cy, bw - 18, 16, "Centralizar no mapa", mouseX, mouseY, true, () -> {
            viewX = l.x;
            viewZ = l.z;
            if (scale < 0.6) scale = 0.6;
        });
        cy += 20;
        section(g, "INFORMAÇÕES");
        kv(g, "Nome", pretty(l.name));
        kv(g, "Tipo", pretty(l.kind));
        kv(g, "Desenvolvimento", l.development);
        if (l.countryIdx >= 0) {
            kvLink(g, "País", d.labels[l.countryIdx], mouseX, mouseY, () -> selectCountry(l.countryIdx));
        } else {
            kv(g, "País", l.country);
        }
        if (l.lord != null && !l.lord.isBlank()) kvLink(g, "Senhor", l.lord, mouseX, mouseY, () -> openPlayer(l.lord));
        kv(g, "Etnia", l.ethnicity);
        kv(g, "Designações", join(l.designations));
        kv(g, "Coordenadas", l.x + ", " + l.z);
        kv(g, "Limite de captura", zoneOf(d, l) != null ? "sim (contorno dourado no mapa)" : "não definido");
        SCROLL[TAB_CITY] = endScroll(g, SCROLL[TAB_CITY]);
    }

    // ------------------------------------------------------------ lista de países

    private void drawSiteList(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        int top = header(g, 0xFFC8A64A, "Países", d.ids.length + " países  -  " + d.n + " províncias", null, mouseX, mouseY, this::closePanel);
        if (legendVersion != SiteTerritories.version()) {
            legendVersion = SiteTerritories.version();
            legendOrder = new ArrayList<>();
            for (int i = 0; i < d.ids.length; i++) if (d.provinceCount[i] > 0) legendOrder.add(i);
            legendOrder.sort((a, b) -> Integer.compare(d.provinceCount[b], d.provinceCount[a]));
        }
        beginScroll(g, top, SCROLL[TAB_LIST], 0);
        for (int idx : legendOrder) {
            int rowTop = cy;
            boolean vis = visible(rowTop, 16);
            if (vis) {
                boolean hover = mouseX >= bx + 4 && mouseX < bx + bw - 4 && mouseY >= rowTop && mouseY < rowTop + 16;
                if (hover) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x40C8A64A);
                else if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x16FFFFFF);
                g.fill(bx + 8, rowTop + 2, bx + 30, rowTop + 14, 0xFF05080C);
                RemoteImages.Img flag = RemoteImages.flag(flagOf(d, idx));
                if (flag != null) drawImage(g, flag, bx + 9, rowTop + 3, 20, 10);
                else g.fill(bx + 9, rowTop + 3, bx + 29, rowTop + 13, d.rgb[idx] | 0xFF000000);
                String count = String.valueOf(d.provinceCount[idx]);
                g.drawString(font, fit(d.labels[idx], bw - 84), bx + 36, rowTop + 4, COL_TEXT, false);
                g.drawString(font, count, bx + bw - 10 - font.width(count), rowTop + 4, COL_DIM, false);
                final int country = idx;
                hits.add(new Hit(bx + 4, rowTop, bw - 8, 16, () -> selectCountry(country), null));
            }
            rowIdx++;
            cy += 16;
        }
        SCROLL[TAB_LIST] = endScroll(g, SCROLL[TAB_LIST]);
    }

    // ------------------------------------------------------------------ diplomacia

    private void drawDiploPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        String subtitle = dipFocus >= 0 ? "Relações de " + d.labels[dipFocus]
                : dipAlliance >= 0 ? "Aliança escolhida" : "Blocos e alianças";
        int rgb = dipFocus >= 0 ? d.rgb[dipFocus] : 0xFF3A6FD8;
        int top = header(g, rgb, "Diplomacia", subtitle, dipFocus >= 0 ? flagOf(d, dipFocus) : null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            dipFocus = -1;
            dipAlliance = -1;
        });
        beginScroll(g, top, SCROLL[TAB_DIPLO], 0);

        if (dipFocus < 0) paragraph(g, "Clique num país do mapa para ver as relações dele, ou escolha uma aliança abaixo.", COL_DIM);

        if (dipFocus >= 0) {
            section(g, "RELAÇÕES");
            for (Relations.Rel rel : legend) {
                int yy = cy;
                g.fill(bx + 9, yy + 1, bx + 23, yy + 10, 0xFF05080C);
                g.fill(bx + 10, yy + 2, bx + 22, yy + 9, 0xFF000000 | rel.rgb());
                g.drawString(font, rel.label(), bx + 28, yy + 1, COL_TEXT, false);
                cy += 13;
                if (rel.countries().size() > 1 || !"Selecionado".equals(rel.label())) {
                    List<String> names = new ArrayList<>();
                    for (int c : rel.countries()) names.add(d.labels[c]);
                    if (!names.isEmpty()) chips(g, names, mouseX, mouseY, n -> {
                        Integer ci = Relations.nameIndex(d).get(Relations.norm(n));
                        if (ci != null) {
                            dipFocus = ci;
                            dipAlliance = -1;
                        }
                    });
                }
            }
            if (legend.size() <= 1) paragraph(g, "Nenhuma relação registrada no site para este país.", COL_DIM);
        }

        List<SiteTerritories.AllianceInfo> alliances = d.war == null || d.war.alliances == null ? List.of() : d.war.alliances;
        section(g, "ALIANÇAS (" + alliances.size() + ")");
        if (alliances.isEmpty()) paragraph(g, "O site não lista alianças.", COL_DIM);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < alliances.size(); i++) {
                SiteTerritories.AllianceInfo a = alliances.get(i);
                if (Relations.isBilateral(a) != (pass == 1)) continue;
                int rowTop = cy;
                boolean vis = visible(rowTop, 16);
                if (vis) {
                    boolean sel = i == dipAlliance;
                    boolean hover = mouseX >= bx + 4 && mouseX < bx + bw - 4 && mouseY >= rowTop && mouseY < rowTop + 16;
                    if (sel) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x50C8A64A);
                    else if (hover) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x40C8A64A);
                    else if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x16FFFFFF);
                    g.fill(bx + 8, rowTop + 2, bx + 30, rowTop + 14, 0xFF05080C);
                    RemoteImages.Img flag = a.flag == null ? null : RemoteImages.flag(SiteTerritories.assetUrl(a.flag, true));
                    if (flag != null) drawImage(g, flag, bx + 9, rowTop + 3, 20, 10);
                    else g.fill(bx + 9, rowTop + 3, bx + 29, rowTop + 13, 0xFF000000 | Relations.blocColor(a, i));
                    String count = String.valueOf(Relations.countriesOf(d, a).size());
                    g.drawString(font, fit(a.name == null ? a.id : a.name, bw - 84), bx + 36, rowTop + 4, COL_TEXT, false);
                    g.drawString(font, count, bx + bw - 10 - font.width(count), rowTop + 4, COL_DIM, false);
                    final int idx = i;
                    hits.add(new Hit(bx + 4, rowTop, bw - 8, 16, () -> {
                        dipAlliance = dipAlliance == idx ? -1 : idx;
                        dipFocus = -1;
                    }, null));
                }
                rowIdx++;
                cy += 16;
            }
        }

        if (dipAlliance >= 0 && dipAlliance < alliances.size()) {
            SiteTerritories.AllianceInfo a = alliances.get(dipAlliance);
            section(g, "ALIANÇA");
            kv(g, "Nome", a.name);
            kv(g, "Nome oficial", a.officialName);
            kv(g, "Formada", a.formed);
            if (a.members != null) {
                for (SiteTerritories.Member m : a.members) {
                    kv(g, m.name == null ? "Membro" : m.name, m.note == null ? "membro" : m.note);
                }
            }
            kv(g, "Notas", a.notes);
        }

        SiteTerritories.WarData w = d.war;
        if (w != null) {
            if (w.truces != null && !w.truces.isEmpty()) {
                section(g, "TRÉGUAS");
                for (SiteTerritories.Truce t : w.truces) kv(g, t.a + " / " + t.b, t.until == null ? "" : "até " + t.until);
            }
            if (w.naps != null && !w.naps.isEmpty()) {
                section(g, "PACTOS DE NÃO AGRESSÃO");
                for (SiteTerritories.Nap n : w.naps) kv(g, n.a + " / " + n.b, n.until == null ? "" : "até " + n.until);
            }
            if (w.ultimatums != null && !w.ultimatums.isEmpty()) {
                section(g, "ULTIMATOS");
                for (SiteTerritories.Ultimatum u : w.ultimatums) kv(g, u.from + " > " + u.to, u.sent);
            }
        }
        SCROLL[TAB_DIPLO] = endScroll(g, SCROLL[TAB_DIPLO]);
    }

    // ---------------------------------------------------------------------- guerra

    private void drawWarPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        SiteTerritories.WarInfo sel = warSel >= 0 && warSel < wars.size() ? wars.get(warSel) : null;
        int top = header(g, 0xFFD83A3A, "Guerra", sel == null ? wars.size() + " guerra(s) registrada(s)" : sel.name, null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            warSel = -1;
        });
        beginScroll(g, top, SCROLL[TAB_WAR], 0);
        if (wars.isEmpty()) {
            paragraph(g, "O site não registra nenhuma guerra neste momento. Quando houver uma, ela aparece aqui e o mapa mostra os lados, as cidades tomadas e as disputadas.", COL_DIM);
        } else {
            section(g, "GUERRAS");
            for (int i = 0; i < wars.size(); i++) {
                SiteTerritories.WarInfo w = wars.get(i);
                int rowTop = cy;
                if (visible(rowTop, 16)) {
                    boolean isSel = i == warSel;
                    boolean hover = mouseX >= bx + 4 && mouseX < bx + bw - 4 && mouseY >= rowTop && mouseY < rowTop + 16;
                    if (isSel) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x50D83A3A);
                    else if (hover) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x40C8A64A);
                    else if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x16FFFFFF);
                    g.drawString(font, fit(w.name == null ? w.id : w.name, bw - 70), bx + 10, rowTop + 4, COL_TEXT, false);
                    String tag = w.ended != null ? "fim" : w.live ? "ao vivo" : "ativa";
                    g.drawString(font, tag, bx + bw - 10 - font.width(tag), rowTop + 4, w.ended != null ? COL_DIM : 0xFFE06A6A, false);
                    final int idx = i;
                    hits.add(new Hit(bx + 4, rowTop, bw - 8, 16, () -> warSel = warSel == idx ? -1 : idx, null));
                }
                rowIdx++;
                cy += 16;
            }
        }
        if (sel != null) {
            section(g, "DETALHES");
            kv(g, "Tipo", sel.type);
            kv(g, "Início", sel.started);
            kv(g, "Fim", sel.ended);
            kv(g, "Notas", sel.notes);
            if (sel.sides != null) {
                for (int s = 0; s < sel.sides.size(); s++) {
                    SiteTerritories.Side side = sel.sides.get(s);
                    int color = Relations.sideColor(side, s);
                    section(g, (side.name == null ? "LADO " + (s + 1) : side.name.toUpperCase()));
                    g.fill(bx + bw - 24, cy - 14, bx + bw - 10, cy - 6, 0xFF000000 | color);
                    if (side.members != null) {
                        for (SiteTerritories.Member m : side.members) kv(g, m.name == null ? "Membro" : m.name, m.note);
                    }
                }
            }
            if (sel.captured != null && !sel.captured.isEmpty()) {
                section(g, "CIDADES TOMADAS / EM DISPUTA (" + sel.captured.size() + ")");
                for (SiteTerritories.Captured c : sel.captured) {
                    String state = "contested".equals(c.state) ? "em disputa" : "uncontrolled".equals(c.state) ? "sem controle"
                            : c.holder == null ? "mantida" : "de " + c.holder;
                    final SiteTerritories.Captured cc = c;
                    kvLink(g, pretty(c.name), state, mouseX, mouseY, () -> {
                        viewX = cc.x;
                        viewZ = cc.z;
                        if (scale < 0.4) scale = 0.4;
                    });
                }
            }
        }
        SCROLL[TAB_WAR] = endScroll(g, SCROLL[TAB_WAR]);
    }

    // ------------------------------------------------------------ gerar chunks

    private void drawGenPanel(GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        boolean remote = ChunkGen.isRemote(mc);
        boolean running = ChunkGen.state != ChunkGen.State.IDLE;
        int top = header(g, 0xFFC8A64A, "Gerar chunks", remote ? "servidor (teleporte)" : "mundo local (servidor interno)", null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            genConfirm = false;
        });
        beginScroll(g, top, SCROLL[TAB_GEN], 0);

        int[] lim = running ? ChunkGen.limit : ChunkGen.computeLimit(mc);
        section(g, "LIMITE DO MAPA");
        if (lim == null) {
            paragraph(g, "Não foi possível saber o tamanho do mapa (sem borda do mundo e sem dados do site). Defina worldHalfSize em config/hoi4map/site.json.", 0xFFE06A6A);
        } else {
            kv(g, "Fonte", ChunkGen.limitSource);
            kv(g, "X", lim[0] + " a " + lim[2]);
            kv(g, "Z", lim[1] + " a " + lim[3]);
            long total = (long) (((lim[2] >> 4) - (lim[0] >> 4)) + 1) * (((lim[3] >> 4) - (lim[1] >> 4)) + 1);
            kv(g, "Chunks", String.valueOf(total));
        }

        section(g, "PROGRESSO");
        if (running) {
            double p = ChunkGen.progress();
            int barX = bx + 10, barW = bw - 20;
            g.fill(barX, cy, barX + barW, cy + 10, 0xFF05080C);
            g.fill(barX + 1, cy + 1, barX + 1 + (int) ((barW - 2) * p), cy + 9, 0xFF3FA34D);
            String pct = (int) (p * 100) + "%";
            g.drawString(font, pct, barX + barW / 2 - font.width(pct) / 2, cy + 1, 0xFFFFFFFF, true);
            cy += 16;
            kv(g, "No mapa", ChunkGen.known + " / " + ChunkGen.total);
            kv(g, "Tempo restante", ChunkGen.eta());
            kv(g, "Estado", ChunkGen.state == ChunkGen.State.PAUSED ? "pausado" : "rodando");
        }
        if (ChunkGen.message != null && !ChunkGen.message.isBlank()) paragraph(g, ChunkGen.message, 0xFFFFE27A);

        cy += 4;
        int bxx = bx + 9;
        int full = bw - 18;
        if (running) {
            textButton(g, bxx, cy, full / 2 - 2, 18, ChunkGen.state == ChunkGen.State.PAUSED ? "Continuar" : "Pausar", mouseX, mouseY, true, ChunkGen::togglePause);
            textButton(g, bxx + full / 2 + 2, cy, full / 2 - 2, 18, "Parar", mouseX, mouseY, true, () -> ChunkGen.stop(mc, "Parado por você."));
            cy += 24;
        } else if (genConfirm && remote) {
            paragraph(g, "ATENÇÃO: isto usa /tp para levar você por todo o mapa, em modo espectador, e volta no fim. Exige operador e pesa no servidor: faça só com permissão da administração.", 0xFFE06A6A);
            textButton(g, bxx, cy, full / 2 - 2, 18, "Confirmar", mouseX, mouseY, lim != null, () -> {
                genConfirm = false;
                ChunkGen.start(mc);
            });
            textButton(g, bxx + full / 2 + 2, cy, full / 2 - 2, 18, "Cancelar", mouseX, mouseY, true, () -> genConfirm = false);
            cy += 24;
        } else {
            textButton(g, bxx, cy, full, 18, "Gerar todos os chunks", mouseX, mouseY, lim != null, () -> {
                if (remote) genConfirm = true;
                else ChunkGen.start(mc);
            });
            cy += 24;
        }

        section(g, "COMO FUNCIONA");
        if (remote) {
            paragraph(g, "Em servidor, o mod teleporta você (modo espectador) por uma grade de pontos dentro do limite, espera os chunks chegarem e os desenha no mapa. No fim, volta ao ponto de partida e ao seu modo de jogo. Pode demorar bastante; dá para pausar ou parar. Cada teleporte aparece no chat.", COL_DIM);
        } else {
            paragraph(g, "No mundo local, o mod pede os chunks ao servidor interno, do mais perto para o mais longe, sem mover você, e desenha o mapa conforme chegam. O jogo pode ficar mais pesado enquanto roda. Dá para pausar, parar e fechar o mapa: continua em segundo plano.", COL_DIM);
        }
        SCROLL[TAB_GEN] = endScroll(g, SCROLL[TAB_GEN]);
    }

    // ---------------------------------------------------------- cartão do jogador

    private SiteTerritories.Player findPlayer(SiteTerritories.Data d, String name) {
        if (name == null) return null;
        SiteTerritories.Player p = d.players.get(name);
        if (p != null) return p;
        for (Map.Entry<String, SiteTerritories.Player> e : d.players.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String ago(String iso) {
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso);
            long days = Duration.between(t.toInstant(), Instant.now()).toDays();
            return (days <= 0 ? "hoje" : days == 1 ? "ontem" : "há " + days + " dias") + "  (" + iso.replace('T', ' ') + ")";
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String purgeText(SiteTerritories.Player p) {
        if (p.lastJoined == null || p.purge == null || p.purge.days <= 0) return null;
        try {
            OffsetDateTime t = OffsetDateTime.parse(p.lastJoined).plusDays(p.purge.days);
            long left = Duration.between(Instant.now(), t.toInstant()).toDays();
            String date = t.toLocalDate().toString();
            return left < 0 ? "desde " + date : "em " + (left + 1) + " dia(s), " + date;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void drawCard(GuiGraphics g, int mouseX, int mouseY) {
        if (selPlayer == null) return;
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) {
            selPlayer = null;
            return;
        }
        SiteTerritories.Player p = findPlayer(d, selPlayer);
        cardH = height - MARGIN * 2 - BAR_H - 8;
        cardX = panelVisible ? panelX - 6 - CARD_W : width - CARD_W - MARGIN;
        if (cardX < MARGIN) cardX = MARGIN;
        cardY = MARGIN;
        cardVisible = true;
        bx = cardX;
        by = cardY;
        bw = CARD_W;
        bh = cardH;
        box(g, bx, by, bw, bh, COL_PANEL);
        hits.add(new Hit(bx, by, bw, bh, null, null));

        String sub = p == null ? "sem registro no site" : p.ign == null ? null : "no jogo: " + p.ign;
        int top = header(g, 0xFF4A5560, selPlayer, sub, null, mouseX, mouseY, () -> selPlayer = null);

        // skin
        int figX = bx + 14, figY = top + 6;
        g.fill(figX - 3, figY - 3, figX + 51, figY + 99, 0xFF0A1018);
        String skinUrl = p == null ? null : SiteTerritories.assetUrl(p.skin, false);
        RemoteImages.Img skin = RemoteImages.skin(skinUrl);
        if (skin != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, skin.id(), figX, figY, 0f, 0f, 48, 96, skin.w(), skin.h(), skin.w(), skin.h());
        } else {
            g.drawString(font, skinUrl == null ? "sem skin" : "...", figX + 4, figY + 44, COL_DIM, false);
        }
        int tx = figX + 60, ty = top + 8;
        int tw = bx + bw - 10 - tx;
        if (p != null) {
            if (p.ign != null) {
                g.drawString(font, fit(p.ign, tw), tx, ty, 0xFFFFFFFF, false);
                ty += 12;
            }
            if (p.country != null) {
                g.drawString(font, fit(p.country, tw), tx, ty, COL_TEXT, false);
                ty += 12;
            }
            if (p.immune != null) {
                for (String line : wrap("Imune: " + p.immune, tw)) {
                    g.drawString(font, line, tx, ty, 0xFF7BC96F, false);
                    ty += 10;
                }
            }
        }

        int bottomReserve = 26;
        int bodyTop = figY + 102;
        beginScroll(g, bodyTop, cardScroll, bottomReserve);
        if (p != null) {
            section(g, "ATIVIDADE");
            kv(g, "Última entrada", p.lastJoined == null ? null : ago(p.lastJoined));
            if (p.purge != null) {
                kv(g, "Reino", p.purge.realm);
                kv(g, "População", p.purge.population > 0 ? String.valueOf(p.purge.population) : null);
                kv(g, "Prazo (purge)", p.purge.days > 0 ? p.purge.days + " dias" : null);
            }
            kv(g, "Purgeable", purgeText(p));
            if (p.current != null && !p.current.isEmpty()) {
                section(g, "AFILIAÇÕES ATUAIS");
                for (String s : p.current) paragraph(g, s);
            }
            if (p.affiliations != null && !p.affiliations.isEmpty()) {
                section(g, "HISTÓRICO");
                for (String s : p.affiliations) paragraph(g, s, COL_DIM);
            }
            List<String> lordships = new ArrayList<>();
            for (SiteTerritories.Locality l : d.localities) {
                if (l.lord != null && l.lord.equalsIgnoreCase(selPlayer)) {
                    String kind = pretty(l.kind);
                    lordships.add(pretty(l.name) + (kind == null || kind.isBlank() ? "" : " (" + kind + ")"));
                }
            }
            if (!lordships.isEmpty()) {
                section(g, "SENHORIOS (" + lordships.size() + ")");
                for (String s : lordships) paragraph(g, s);
            }
            if (p.notes != null && !p.notes.isBlank()) {
                section(g, "NOTAS");
                paragraph(g, p.notes);
            }
        } else {
            paragraph(g, "Este nome não tem registro de jogador no site.", COL_DIM);
        }
        cardScroll = endScroll(g, cardScroll);

        if (p != null && p.ign != null) {
            int by0 = by + bh - 22;
            boolean copied = copiedFrames > 0;
            boolean hover = mouseX >= bx + 9 && mouseX < bx + bw - 9 && mouseY >= by0 && mouseY < by0 + 18;
            g.fill(bx + 9, by0, bx + bw - 9, by0 + 18, hover ? 0xFF26364A : 0xFF1A2430);
            g.fill(bx + 9, by0, bx + bw - 9, by0 + 1, COL_GOLD_DIM);
            g.fill(bx + 9, by0 + 17, bx + bw - 9, by0 + 18, COL_GOLD_DIM);
            String label = copied ? "Link copiado!" : "Copiar link do NameMC";
            g.drawString(font, label, bx + (bw - font.width(label)) / 2, by0 + 5, COL_TEXT, false);
            final String ign = p.ign;
            hits.add(new Hit(bx + 9, by0, bw - 18, 18, () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard("https://namemc.com/profile/" + ign);
                copiedFrames = 90;
            }, null));
        }
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
                "Província " + selPx + ", " + selPz + "  (modo manual)", null, mouseX, mouseY, this::closePanel);

        beginScroll(g, top, 0, 0);
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
        int bxx = bx + 9;
        boolean enabled = !Countries.LIST.isEmpty();
        textButton(g, bxx, cy, 28, 18, "<", mouseX, mouseY, enabled, () -> cycleOwner(-1));
        textButton(g, bxx + 32, cy, 28, 18, ">", mouseX, mouseY, enabled, () -> cycleOwner(1));
        textButton(g, bxx + 64, cy, bw - 18 - 64, 18, "Sem dono", mouseX, mouseY, true,
                () -> MapData.setOwner(selPx, selPz, null));
        cy += 24;
        paragraph(g, "Sem dados do site: as províncias (32x32 blocos) são atribuídas na mão. Edite config/hoi4map/countries.json para mudar os países.");
        endScroll(g, 0);
    }

    private void drawManualList(GuiGraphics g, int mouseX, int mouseY) {
        int top = header(g, 0xFFC8A64A, "Países", "modo manual", null, mouseX, mouseY, this::closePanel);
        refreshStats();
        beginScroll(g, top, SCROLL[TAB_LIST], 0);
        for (Countries.Country c : Countries.LIST) {
            int rowTop = cy;
            if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 14, 0x16FFFFFF);
            g.fill(bx + 9, rowTop + 3, bx + 21, rowTop + 11, 0xFF05080C);
            g.fill(bx + 10, rowTop + 4, bx + 20, rowTop + 10, c.rgb);
            double[] s = stats.get(c.id);
            String count = String.valueOf(s == null ? 0 : (int) s[2]);
            g.drawString(font, fit(c.name, bw - 70), bx + 27, rowTop + 3, COL_TEXT, false);
            g.drawString(font, count, bx + bw - 10 - font.width(count), rowTop + 3, COL_DIM, false);
            rowIdx++;
            cy += 14;
        }
        SCROLL[TAB_LIST] = endScroll(g, SCROLL[TAB_LIST]);
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
        if (event.button() == 1) {
            closePanel();
            selPlayer = null;
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
            if (!moved) select(toBlockX(event.x()), toBlockZ(event.y()), event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }

    private void select(double bxx, double bzz, double sx, double sy) {
        if (siteMode()) {
            SiteTerritories.Data d = SiteTerritories.data();
            SiteTerritories.Locality city = cityAt(sx, sy);
            if (city != null) {
                selCity = city.idx;
                tab = TAB_CITY;
                SCROLL[TAB_CITY] = 0;
                return;
            }
            int p = SiteTerritories.provinceAt(d, (int) Math.floor(bxx), (int) Math.floor(bzz));
            setSite(p);
            selCity = -1;
            if (p >= 0) {
                if (mode == MODE_DIPLOMACY) {
                    dipFocus = d.provCountry[p];
                    dipAlliance = -1;
                }
                tab = TAB_INFO;
                SCROLL[TAB_INFO] = 0;
            } else {
                if (tab == TAB_INFO || tab == TAB_CITY) tab = TAB_NONE;
                if (mode == MODE_DIPLOMACY) dipFocus = -1;
            }
            return;
        }
        selPx = provOf((int) Math.floor(bxx / 16));
        selPz = provOf((int) Math.floor(bzz / 16));
        hasSel = true;
        tab = TAB_INFO;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (cardVisible && mouseX >= cardX && mouseX < cardX + CARD_W && mouseY >= cardY && mouseY < cardY + cardH) {
            cardScroll = Math.max(0, cardScroll - scrollY * 20);
            return true;
        }
        if (panelVisible && mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH) {
            SCROLL[tab] = Math.max(0, SCROLL[tab] - scrollY * 20);
            return true;
        }
        if (blockedAt(hits, mouseX, mouseY)) return true;
        double bxx = toBlockX(mouseX);
        double bzz = toBlockZ(mouseY);
        scale = Math.max(0.03, Math.min(8.0, scale * Math.pow(1.2, scrollY)));
        viewX = bxx - (mouseX - areaCx()) / scale;
        viewZ = bzz - (mouseY - areaCy()) / scale;
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
        if (k == GLFW.GLFW_KEY_4 && siteMode()) {
            enterDiplomacy();
            return true;
        }
        if (k == GLFW.GLFW_KEY_5 && siteMode()) {
            enterWar();
            return true;
        }
        return super.keyPressed(event);
    }
}
