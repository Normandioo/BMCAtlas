package com.normandioo.hoi4map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Tela cheia do mapa, no estilo do menu de mapa do Hearts of Iron IV. */
public class MapScreen extends Screen {
    private static final int BTN = 26;       // lado dos botões de ícone
    private static final int PW = 272;       // largura do painel
    private static final int CARD_W = 244;   // largura do cartão do jogador
    private static final int MARGIN = 8;

    private static final int MODE_PHYSICAL = MapStyle.MODE_PHYSICAL;
    private static final int MODE_POLITICAL = MapStyle.MODE_POLITICAL;
    private static final int MODE_PROVINCES = MapStyle.MODE_PROVINCES;
    private static final int MODE_DIPLOMACY = MapStyle.MODE_DIPLOMACY;
    private static final int MODE_WAR = MapStyle.MODE_WAR;

    // paleta bronze e ardósia, como os menus do Hearts of Iron IV
    private static final int COL_GOLD = 0xFFD9B45A;
    private static final int COL_GOLD_DIM = 0xFF6B5A2A;
    private static final int COL_PANEL = 0xF2252826;
    private static final int COL_PANEL_2 = 0xFF1A1C1B;
    private static final int COL_TEXT = 0xFFE8E2D0;
    private static final int COL_DIM = 0xFF9A978A;
    private static final int COL_LINK = 0xFFE0C068;
    /** Cabeçalho sem país (sem caixa de bandeira). */
    private static final int NEUTRAL = -1;

    private static final int TAB_NONE = 0, TAB_INFO = 1, TAB_LIST = 2, TAB_DIPLO = 3, TAB_WAR = 4, TAB_CITY = 5, TAB_GEN = 6, TAB_DIPLOMENU = 7;

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
    private static boolean showPlayers = true;
    private static int dmTab;               // aba do menu diplomático: 0 alianças, 1 NAP, 2 purge, 3 diplolock
    private static boolean dmAllPurge;
    private static int warTab;              // aba do painel da guerra: 0 lados, 1 cidades, 2 Lei 7
    private static int warCityFilter;       // 0 todas, 1 tomadas, 2 em disputa
    private static final Set<String> WAR_CLOSED = new HashSet<>();   // países recolhidos na lista de cidades
    private static int pulseCity = -1;      // cidade com o destaque pulsante
    private static long pulseStart;
    private static final long PULSE_MS = 6000;

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

    // altura e posição dos botões da barra (calculadas a cada quadro)
    private int barH = 34;

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
        OnlinePlayers.reset();
        DynmapMarkers.reset();
        WarMarkers.clear();
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
        if (SiteTerritories.showPlayers()) OnlinePlayers.tick();
        tickWar();
        layoutBar();
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

    private static int[] lastTint;

    private void refreshOverrides() {
        refreshRelations();
        refreshZoneTint();
    }

    /** Cor de dentro das zonas de cidade (limites ligados, cidade escolhida, guerra); repinta as regiões se mudou. */
    private void refreshZoneTint() {
        SiteTerritories.Data d = SiteTerritories.data();
        int[] t = null;
        if (d != null && siteMode() && !d.zones.isEmpty()) {
            boolean war = mode == MODE_WAR && !zoneColors.isEmpty();
            SiteTerritories.Zone sel = selZone(d);
            if (showLimits || war || sel != null) {
                t = new int[d.zones.size()];
                for (int i = 0; i < t.length; i++) {
                    SiteTerritories.Zone z = d.zones.get(i);
                    int v = showLimits ? 0x1AFFFFFF : 0;
                    if (war) {
                        Integer c = zoneColors.get(SiteTerritories.zoneKey(z.name, z.country));
                        if (c != null) v = 0x40000000 | (c & 0xFFFFFF);
                    }
                    if (z == sel) v = 0x38FFE27A;
                    t[i] = v;
                }
            }
        }
        if (!Arrays.equals(t, lastTint)) {
            lastTint = t;
            MapStyle.zoneTint = t;
            MapStyle.overrideVersion++;
        }
    }

    /** Recalcula as cores dos modos diplomacia/guerra quando algo muda. */
    private void refreshRelations() {
        SiteTerritories.Data d = SiteTerritories.data();
        boolean rel = relationalMode() && siteMode() && d != null;
        if (d != null) {
            if (dipFocus >= d.ids.length) dipFocus = -1;
            if (selCity >= d.localities.size()) selCity = -1;
            if (dipAlliance >= (d.war == null || d.war.alliances == null ? 0 : d.war.alliances.size())) dipAlliance = -1;
            if (warSel >= Relations.wars(d).size()) warSel = -1;
        }
        int sig = Objects.hash(mode, dipFocus, dipAlliance, warSel, SiteTerritories.version(), rel, mode == MODE_WAR ? DynmapMarkers.markersAt() : 0L);
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
                boolean stale = r.id == null || r.dirty || r.styledMode != mode || r.styledVer != MapStyle.overrideVersion;
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
            drawWarFront(g, d);
            drawCities(g, d, mouseX, mouseY);
            drawWarMarkers(g, d);
        }
        drawOnlinePlayers(g, site ? d : null, mouseX, mouseY);
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

    /**
     * Contornos da zona simplificados para o zoom atual: pontos a pelo menos ~5 pixels um do outro
     * (nível = potência de 2 em blocos) e, de perto, suavizados. Guardados por nível: o desenho de
     * todas as cidades de uma vez fica com poucos segmentos.
     */
    @SuppressWarnings("unchecked")
    private List<double[]> ringsFor(SiteTerritories.Zone z) {
        int level = (int) Math.max(0, Math.min(7, Math.ceil(Math.log(Math.max(1.0, 5.0 / scale)) / Math.log(2))));
        if (z.lods == null) z.lods = new Object[8];
        Object cached = z.lods[level];
        if (cached != null) return (List<double[]>) cached;
        double minDist = 1 << level;
        List<double[]> out = new ArrayList<>();
        for (double[] ring : z.rings) {
            double[] r = level == 0 ? ring : decimate(ring, minDist);
            int passes = level == 0 ? 2 : level <= 2 ? 1 : 0;
            out.add(passes == 0 ? r : smoothRing(r, passes));
        }
        z.lods[level] = out;
        return out;
    }

    private static double[] decimate(double[] ring, double minDist) {
        int n = ring.length / 2;
        if (n <= 4) return ring;
        double[] out = new double[ring.length];
        int k = 0;
        double lx = ring[0], lz = ring[1];
        out[k++] = lx;
        out[k++] = lz;
        double min2 = minDist * minDist;
        for (int i = 1; i < n; i++) {
            double x = ring[2 * i], zz = ring[2 * i + 1];
            if ((x - lx) * (x - lx) + (zz - lz) * (zz - lz) >= min2) {
                out[k++] = x;
                out[k++] = zz;
                lx = x;
                lz = zz;
            }
        }
        if (k < 6) return ring;
        return Arrays.copyOf(out, k);
    }

    /** Suaviza o contorno em degraus de blocos (duas passadas de corte de cantos). */
    private static double[] smoothRing(double[] ring, int passes) {
        if (ring.length < 6) return ring;
        double[] cur = ring;
        for (int pass = 0; pass < passes; pass++) {
            int m = cur.length / 2;
            double[] nx = new double[m * 4];
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                nx[4 * i] = 0.75 * cur[2 * i] + 0.25 * cur[2 * j];
                nx[4 * i + 1] = 0.75 * cur[2 * i + 1] + 0.25 * cur[2 * j + 1];
                nx[4 * i + 2] = 0.25 * cur[2 * i] + 0.75 * cur[2 * j];
                nx[4 * i + 3] = 0.25 * cur[2 * i + 1] + 0.75 * cur[2 * j + 1];
            }
            cur = nx;
        }
        return cur;
    }

    /** Linhas dos limites: fina, branca e suave; a cidade escolhida em dourado e as de guerra na cor de quem segura. */
    private void drawZones(GuiGraphics g, SiteTerritories.Data d) {
        if (scale < 0.08 || d.zones.isEmpty()) return;
        double left = toBlockX(0), right = toBlockX(width), top = toBlockZ(0), bottom = toBlockZ(height);
        SiteTerritories.Zone sel = selZone(d);
        boolean war = mode == MODE_WAR && !zoneColors.isEmpty();
        for (SiteTerritories.Zone z : d.zones) {
            boolean isSel = z == sel;
            Integer warColor = war ? zoneColors.get(SiteTerritories.zoneKey(z.name, z.country)) : null;
            if (!showLimits && !isSel && warColor == null) continue;
            double[] box = z.box;
            if (box == null || box[2] < left || box[0] > right || box[3] < top || box[1] > bottom) continue;
            int color;
            float thick = 1.25f;
            if (isSel) {
                color = 0xFFFFE27A;
                thick = 2f;
            } else if (warColor != null) {
                color = 0xFF000000 | warColor;
                thick = 2f;
            } else {
                color = 0xE6F4F1E8;
            }
            if ((box[2] - box[0]) * scale < 4 && (box[3] - box[1]) * scale < 4) continue; // pequena demais para uma linha
            if (warColor != null && !isSel) {
                // em guerra: tracejado (8 ligado, 5 desligado) sobre uma sombra escura
                for (double[] ring : ringsFor(z)) {
                    drawRing(g, ring, 0x90000000, 3.5f);
                    drawRingDashed(g, ring, color, 2f, 8, 5);
                }
            } else {
                for (double[] ring : ringsFor(z)) drawRing(g, ring, color, thick);
            }
        }
    }

    private SiteTerritories.Zone selZone(SiteTerritories.Data d) {
        return selCity >= 0 && selCity < d.localities.size() ? zoneOf(d, d.localities.get(selCity)) : null;
    }

    private void drawRing(GuiGraphics g, double[] ring, int color, float thick) {
        int n = ring.length / 2;
        if (n < 2) return;
        double fx = toScreenX(ring[0]), fy = toScreenY(ring[1]);
        double px = fx, py = fy;
        for (int i = 1; i < n; i++) {
            double x = toScreenX(ring[2 * i]), y = toScreenY(ring[2 * i + 1]);
            if (Math.abs(x - px) + Math.abs(y - py) < 3 && i < n - 1) continue; // pontos muito juntos
            segment(g, px, py, x, y, color, thick);
            px = x;
            py = y;
        }
        segment(g, px, py, fx, fy, color, thick);
    }

    /** Contorno tracejado: on pixels ligados, off desligados, contados ao longo de todo o anel. */
    private void drawRingDashed(GuiGraphics g, double[] ring, int color, float thick, double on, double off) {
        int n = ring.length / 2;
        if (n < 2) return;
        double period = on + off, pos = 0;
        double px = toScreenX(ring[0]), py = toScreenY(ring[1]);
        double fx = px, fy = py;
        for (int i = 1; i <= n; i++) {
            double x = i == n ? fx : toScreenX(ring[2 * i]), y = i == n ? fy : toScreenY(ring[2 * i + 1]);
            double dx = x - px, dy = y - py, len = Math.hypot(dx, dy);
            if (len < 0.5 && i < n) continue;
            double t = 0;
            while (t < len) {
                double phase = (pos + t) % period;
                double run = phase < on ? on - phase : period - phase;
                double step = Math.min(run, len - t);
                if (phase < on) {
                    double a = t / len, b = (t + step) / len;
                    segment(g, px + dx * a, py + dy * a, px + dx * b, py + dy * b, color, thick);
                }
                t += step;
            }
            pos += len;
            px = x;
            py = y;
        }
    }

    private void segment(GuiGraphics g, double x0, double y0, double x1, double y1, int color, float thick) {
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

    private static final double[] ICON_MIN = {0.35, 0.16, 0.08, 0.04, 0.025};
    private static final double[] NAME_MIN = {0.9, 0.45, 0.22, 0.12, 0.07};
    private static final int[] ICON_PX = {10, 13, 15, 18, 22};

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
            double r = iconRadius(l) + 3;
            if (Math.abs(mx - sx) <= r && Math.abs(my - sy) <= r) return l;
        }
        return null;
    }

    private static int iconSize(SiteTerritories.Locality l) {
        return "Fort".equals(l.kind) ? 14 : ICON_PX[l.tier];
    }

    private int iconRadius(SiteTerritories.Locality l) {
        return iconSize(l) / 2;
    }

    /** O ícone que o próprio site usa para essa localidade (icons/<tipo>.png), ou null enquanto baixa/ausente. */
    private RemoteImages.Img cityImage(SiteTerritories.Locality l, int size) {
        String root = SiteTerritories.rootUrl();
        if (root.isEmpty()) return null;
        String kind = l.iconKind != null ? l.iconKind : l.kind;
        if (kind == null || kind.isBlank()) return null;
        String u1 = root + "icons/" + kind + ".png";
        if (!RemoteImages.failed(u1, size)) return RemoteImages.icon(u1, size);
        if (l.kind != null && !l.kind.equals(kind)) {
            String u2 = root + "icons/" + l.kind + ".png";
            if (!RemoteImages.failed(u2, size)) return RemoteImages.icon(u2, size);
        }
        return null;
    }

    private void drawCities(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        if (!showCities || d.cityOrder.length == 0) return;
        SiteTerritories.Locality hover = blockedAt(prevHits, mouseX, mouseY) ? null : cityAt(mouseX, mouseY);
        List<int[]> taken = new ArrayList<>();
        long nowMs = System.currentTimeMillis();
        for (SiteTerritories.Locality l : d.cityOrder) {
            if (!cityVisible(l)) continue;
            int sx = (int) Math.round(toScreenX(l.x + 0.5));
            int sy = (int) Math.round(toScreenY(l.z + 0.5));
            if (sx < -30 || sx > width + 30 || sy < -30 || sy > height + 30) continue;
            int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] : 0xFF909090;
            boolean pulsing = l.idx == pulseCity && nowMs - pulseStart < PULSE_MS;
            boolean hot = l == hover || l.idx == selCity || pulsing;
            if (pulsing) drawPulse(g, sx, sy, iconRadius(l), nowMs - pulseStart);
            RemoteImages.Img img = cityImage(l, iconSize(l));
            if (img != null) {
                if (hot) {
                    int r = iconRadius(l) + 3;
                    g.fill(sx - r, sy - r, sx + r + 1, sy + r + 1, 0x70FFE27A);
                }
                g.blit(RenderPipelines.GUI_TEXTURED, img.id(), sx - img.w() / 2, sy - img.h() / 2, 0f, 0f, img.w(), img.h(), img.w(), img.h(), img.w(), img.h());
            } else {
                drawIcon(g, sx, sy, l, rgb, hot);
            }
            if (scale >= NAME_MIN[l.tier] || hot) {
                String name = SiteTerritories.splitCamel(l.name);
                int w = font.width(name) + 2;
                int rx = sx + iconRadius(l) + 3, ry = sy - 5;
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

    /** Destaque pulsante de 6 s: moldura dourada e anéis que se expandem e somem. */
    private void drawPulse(GuiGraphics g, int sx, int sy, int radius, long age) {
        double fade = age > PULSE_MS - 1000 ? Math.max(0, (PULSE_MS - age) / 1000.0) : 1.0;
        int base = radius + 4;
        squareRing(g, sx, sy, base, ((int) Math.round(255 * fade) << 24) | 0xFFE27A);
        squareRing(g, sx, sy, base + 1, ((int) Math.round(160 * fade) << 24) | 0x05080C);
        for (int k = 0; k < 3; k++) {
            double ph = ((age % 1500) / 1500.0 + k / 3.0) % 1.0;
            int a = (int) Math.round(220 * (1 - ph) * fade);
            if (a < 8) continue;
            squareRing(g, sx, sy, base + 2 + (int) Math.round(ph * 22), (a << 24) | 0xFFE27A);
        }
    }

    /** Reserva: quadradinho colorido enquanto o ícone do site não chega. */
    private void drawIcon(GuiGraphics g, int x, int y, SiteTerritories.Locality l, int rgb, boolean hot) {
        int r = Math.max(1, iconRadius(l) / 2);
        int fill = lighten(rgb, 1.3f);
        int dark = 0xFF05080C;
        if (hot) g.fill(x - r - 2, y - r - 2, x + r + 3, y + r + 3, 0xFFFFF2B0);
        g.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2, l.tier == 4 ? COL_GOLD : dark);
        g.fill(x - r, y - r, x + r + 1, y + r + 1, fill);
    }

    // ------------------------------------------------------ jogadores online

    private record Spot(OnlinePlayers.Entry e, int sx, int sy, int country, String site) {}

    private List<Spot> spots = new ArrayList<>();

    private boolean playersOn() {
        return showPlayers && SiteTerritories.showPlayers();
    }

    private Spot playerAt(double mx, double my) {
        for (int i = spots.size() - 1; i >= 0; i--) {
            Spot sp = spots.get(i);
            if (mx >= sp.sx() - 10 && mx < sp.sx() + 30 && my >= sp.sy() - 10 && my < sp.sy() + 10) return sp;
        }
        return null;
    }

    /** Cabeça (dynmap, ou skin do site), com borda na cor do país, bandeira ao lado e o nome ao aproximar. */
    private void drawOnlinePlayers(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        spots = new ArrayList<>();
        if (!playersOn()) return;
        Minecraft mc = Minecraft.getInstance();
        String self = mc.player == null ? "" : mc.player.getName().getString();
        String dyn = SiteTerritories.dynmapUrl();
        long now = System.currentTimeMillis();
        Spot hover = blockedAt(prevHits, mouseX, mouseY) ? null : playerAt(mouseX, mouseY);
        for (OnlinePlayers.Entry e : OnlinePlayers.list()) {
            if (e.account.equalsIgnoreCase(self)) continue;
            int sx = (int) Math.round(toScreenX(e.x(now)));
            int sy = (int) Math.round(toScreenY(e.z(now)));
            if (sx < -40 || sx > width + 40 || sy < -40 || sy > height + 40) continue;
            String low = e.account.toLowerCase(Locale.ROOT);
            String site = d == null ? null : d.ignToSite.get(low);
            Integer ci = d == null ? null : d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : low);
            int country = ci == null ? -1 : ci;
            Spot sp = new Spot(e, sx, sy, country, site);
            spots.add(sp);
            boolean hot = hover != null && hover.e() == e;
            int rgb = country >= 0 ? 0xFF000000 | d.rgb[country] : 0xFF909090;

            // cabeça 16x16 com moldura na cor do país
            g.fill(sx - 10, sy - 10, sx + 10, sy + 10, 0xFF05080C);
            g.fill(sx - 9, sy - 9, sx + 9, sy + 9, hot ? 0xFFFFE27A : rgb);
            RemoteImages.Img face = dyn == null ? null : RemoteImages.icon(dyn + "tiles/faces/32x32/" + e.account + ".png", 16);
            boolean drawn = false;
            if (face != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, face.id(), sx - 8, sy - 8, 0f, 0f, 16, 16, face.w(), face.h(), face.w(), face.h());
                drawn = true;
            } else if (d != null && site != null) {
                SiteTerritories.Player p = d.players.get(site);
                RemoteImages.Img skin = p == null ? null : RemoteImages.skin(SiteTerritories.assetUrl(p.skin, false));
                if (skin != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, skin.id(), sx - 8, sy - 8, 4f, 0f, 16, 16, 8, 8, skin.w(), skin.h());
                    drawn = true;
                }
            }
            if (!drawn) {
                g.fill(sx - 8, sy - 8, sx + 8, sy + 8, 0xFF3A3F45);
                g.drawString(font, e.account.substring(0, 1).toUpperCase(Locale.ROOT), sx - 2, sy - 4, COL_TEXT, false);
            }
            // bandeira do país ao lado
            if (country >= 0) {
                g.fill(sx + 10, sy - 7, sx + 30, sy + 7, 0xFF05080C);
                RemoteImages.Img flag = RemoteImages.flag(flagOf(d, country));
                if (flag != null) drawImage(g, flag, sx + 11, sy - 6, 18, 12);
                else g.fill(sx + 11, sy - 6, sx + 29, sy + 6, rgb);
            }
            if (scale >= 0.5 || hot) {
                String name = e.account;
                int w = font.width(name);
                g.drawString(font, name, sx - w / 2 + (country >= 0 ? 10 : 0), sy + 12, hot ? 0xFFFFF2B0 : 0xFFF0EAD6, true);
            }
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
        g.fill(MARGIN - 2, y - 3, MARGIN - 2 + w, y + 12, 0xFF0E0C08);
        g.fill(MARGIN - 1, y - 2, MARGIN - 3 + w, y + 11, 0xF2252826);
        g.fill(MARGIN - 1, y - 2, MARGIN - 3 + w, y - 1, 0xFF7A6538);
        g.drawString(font, text, MARGIN + 3, y, COL_TEXT, false);
    }

    /** Legenda dos modos diplomacia e guerra, no canto inferior esquerdo. */
    private void drawLegend(GuiGraphics g) {
        if (!relationalMode() || legend.isEmpty()) return;
        int rows = Math.min(legend.size(), 9);
        int w = 150;
        for (int i = 0; i < rows; i++) w = Math.max(w, font.width(legend.get(i).label()) + 46);
        int h = rows * 13 + 14;
        int x = MARGIN - 2;
        int y = height - 34 - 8 - h;
        box(g, x, y, w, h, COL_PANEL);
        for (int i = 0; i < rows; i++) {
            Relations.Rel rel = legend.get(i);
            int yy = y + 7 + i * 13;
            g.fill(x + 9, yy, x + 23, yy + 9, 0xFF05080C);
            g.fill(x + 10, yy + 1, x + 22, yy + 8, 0xFF000000 | rel.rgb());
            String label = rel.label();
            if (!rel.countries().isEmpty() && rel.countries().size() > 1) label += " (" + rel.countries().size() + ")";
            g.drawString(font, label, x + 28, yy + 1, COL_TEXT, false);
        }
    }

    // ------------------------------------------------------------ peças de UI

    /** Moldura de bronze chanfrada, com rebites nos cantos (molduras grandes). */
    private void box(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, 0xFF0E0C08);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF7A6538);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFB89C5C);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFFA3874D);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF4B3C21);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF4B3C21);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF0E0C08);
        g.fill(x + 4, y + 4, x + w - 4, y + h - 4, fill);
        if (w >= 60 && h >= 40) {
            int[][] corner = {{x + 1, y + 1}, {x + w - 4, y + 1}, {x + 1, y + h - 4}, {x + w - 4, y + h - 4}};
            for (int[] c : corner) {
                g.fill(c[0], c[1], c[0] + 3, c[1] + 3, 0xFFD9B45A);
                g.fill(c[0] + 2, c[1] + 2, c[0] + 3, c[1] + 3, 0xFF8A6F32);
            }
        }
    }

    /** Botão chanfrado de bronze (ativo = aceso em dourado). */
    private void bevel(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean active) {
        int base = active ? 0xFF4A3F22 : hover ? 0xFF3C3727 : 0xFF2A2B25;
        int light = active ? 0xFFE0BE62 : hover ? 0xFFB59A5E : 0xFF7A6538;
        g.fill(x, y, x + w, y + h, 0xFF0E0F0D);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, base);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, light);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF14110A);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF14110A);
    }

    private void iconButton(GuiGraphics g, int x, int y, String glyph, boolean active, boolean enabled,
                            int mouseX, int mouseY, String tip, Runnable action) {
        boolean hover = enabled && mouseX >= x && mouseX < x + BTN && mouseY >= y && mouseY < y + BTN;
        bevel(g, x, y, BTN, BTN, hover, active);
        int main = !enabled ? 0xFF6F6D63 : active ? 0xFFF6E7AE : hover ? 0xFFF2ECD8 : 0xFFD8D2BE;
        int accent = !enabled ? 0xFF5A584E : active ? 0xFFE0B840 : 0xFFB9A35E;
        int shade = !enabled ? 0xFF3A3932 : 0xFF6B5A2A;
        Glyphs.draw(g, glyph, x + (BTN - 14) / 2, y + (BTN - 14) / 2, main, accent, shade);
        hits.add(new Hit(x, y, BTN, BTN, enabled ? action : null, tip));
    }

    private boolean visible(int y, int h) {
        return y >= scrollTop && y + h <= scrollBottom;
    }

    /** Botão de texto dentro da área rolável (só clicável quando inteiro à vista). */
    private void textButton(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY, boolean enabled, Runnable action) {
        boolean vis = visible(y, h);
        boolean hover = vis && enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        bevel(g, x, y, w, h, hover, false);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, enabled ? (hover ? 0xFFFFF2B0 : COL_TEXT) : COL_DIM, false);
        if (vis && enabled) hits.add(new Hit(x, y, w, h, action, null));
    }

    /** Texto com um pixel a mais entre as letras (títulos). */
    private void spaced(GuiGraphics g, String text, int x, int y, int color, boolean shadow) {
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            g.drawString(font, c, x, y, color, shadow);
            x += font.width(c) + 1;
        }
    }

    private int spacedWidth(String text) {
        return font.width(text) + Math.max(0, text.length() - 1);
    }

    private String fitSpaced(String text, int maxW) {
        if (spacedWidth(text) <= maxW) return text;
        String t = text;
        while (t.length() > 1 && spacedWidth(t + "...") > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    // --------------------------------------------------------------- barra de ícones

    private record Btn(String glyph, boolean active, boolean enabled, String tip, Runnable action, boolean gap) {}

    private List<Btn> barButtons() {
        boolean site = siteMode();
        boolean haveSite = SiteTerritories.data() != null;
        String needSite = "\nRequer dados do site.";
        List<Btn> b = new ArrayList<>();
        b.add(new Btn("physical", mode == MODE_PHYSICAL, true, "Mapa físico\nRelevo e terreno reais. (1)", () -> mode = MODE_PHYSICAL, false));
        b.add(new Btn("political", mode == MODE_POLITICAL, true, "Mapa político\nPaíses tingidos e nomes. (2)", () -> mode = MODE_POLITICAL, false));
        b.add(new Btn("provinces", mode == MODE_PROVINCES, true, "Províncias\nDivisões de cada país. (3)", () -> mode = MODE_PROVINCES, false));
        b.add(new Btn("diplomacy", mode == MODE_DIPLOMACY, site, "Diplomacia\nRelações de um país ou de uma aliança. (4)" + (site ? "" : needSite), this::enterDiplomacy, false));
        b.add(new Btn("war", mode == MODE_WAR, site, "Guerra\nLados, cidades tomadas e disputadas. (5)" + (site ? "" : needSite), this::enterWar, false));

        b.add(new Btn("list", tab == TAB_LIST, true, "Lista de países", () -> tab = tab == TAB_LIST ? TAB_NONE : TAB_LIST, true));
        b.add(new Btn("treaty", tab == TAB_DIPLOMENU, site, "Panorama diplomático\nAlianças, NAP ativos, purge e diplolock." + (site ? "" : needSite),
                () -> {
                    tab = tab == TAB_DIPLOMENU ? TAB_NONE : TAB_DIPLOMENU;
                    SCROLL[TAB_DIPLOMENU] = 0;
                }, false));
        b.add(new Btn("cities", showCities, site, "Cidades\n" + (showCities ? "Ligado" : "Desligado") + (site ? "" : needSite), () -> showCities = !showCities, false));
        b.add(new Btn("limits", showLimits, site, "Limites das cidades\n" + (showLimits ? "Ligado" : "Desligado") + (site ? "" : needSite), () -> showLimits = !showLimits, false));
        boolean dyn = SiteTerritories.showPlayers();
        String playersTip = "Jogadores online\n" + (!dyn ? "Desativado em site.json" : !showPlayers ? "Desligado"
                : OnlinePlayers.live() ? "Ligado (" + OnlinePlayers.list().size() + " no mapa, via dynmap)"
                : OnlinePlayers.error() != null ? "Ligado (dynmap sem resposta: só quem está por perto)" : "Ligado (carregando...)");
        b.add(new Btn("players", showPlayers && dyn, dyn, playersTip, () -> showPlayers = !showPlayers, false));

        b.add(new Btn("center", false, true, "Centralizar em você", this::centerOnPlayer, true));
        String sourceTip = !haveSite ? "Fonte: manual\nSem dados do site."
                : SiteTerritories.useSite ? "Fonte: site\nClique para usar o modo manual." : "Fonte: manual\nClique para usar o site.";
        b.add(new Btn("source", haveSite && SiteTerritories.useSite, haveSite, sourceTip, () -> {
            SiteTerritories.useSite = !SiteTerritories.useSite;
            tab = TAB_NONE;
            selCity = -1;
            MapData.ownersChanged();
        }, false));
        b.add(new Btn("reload", false, true, "Recarregar países e site", () -> {
            Countries.load();
            SiteTerritories.reload();
        }, false));

        boolean genRunning = ChunkGen.state != ChunkGen.State.IDLE;
        b.add(new Btn("gen", tab == TAB_GEN || genRunning, true,
                genRunning ? "Gerador de chunks\nEm andamento." : "Gerar todos os chunks do mapa", () -> {
                    genConfirm = false;
                    tab = tab == TAB_GEN ? TAB_NONE : TAB_GEN;
                }, true));
        return b;
    }

    private List<Btn> bar = List.of();
    private int[] barX = new int[0], barRow = new int[0];
    private int barW;

    /** Distribui os botões em linhas conforme a largura da janela e calcula a altura da barra. */
    private void layoutBar() {
        bar = barButtons();
        int n = bar.size();
        barX = new int[n];
        barRow = new int[n];
        int maxW = Math.max(BTN, width - 2 * MARGIN - 16);
        int x = 0, row = 0, wMax = 0;
        for (int i = 0; i < n; i++) {
            int gap = bar.get(i).gap() && x > 0 ? 8 : 0;
            if (x + gap + BTN > maxW) {
                row++;
                x = 0;
                gap = 0;
            }
            x += gap;
            barX[i] = x;
            barRow[i] = row;
            x += BTN + 4;
            wMax = Math.max(wMax, x - 4);
        }
        barW = wMax + 16;
        barH = (row + 1) * (BTN + 4) + 12;
    }

    private void drawBar(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = width - barW - MARGIN;
        int y0 = height - barH - MARGIN;
        box(g, x0, y0, barW, barH, COL_PANEL);
        hits.add(new Hit(x0, y0, barW, barH, null, null));
        for (int i = 0; i < bar.size(); i++) {
            Btn b = bar.get(i);
            int bx0 = x0 + 8 + barX[i], by0 = y0 + 8 + barRow[i] * (BTN + 4);
            iconButton(g, bx0, by0, b.glyph(), b.active(), b.enabled(), mouseX, mouseY, b.tip(), b.action());
            if (b.glyph().equals("war") && b.enabled()) {
                int n = activeWarCount();
                if (n > 0) {
                    g.fill(bx0 + 2, by0 + 2, bx0 + BTN - 2, by0 + BTN - 2, 0x40D83A3A);
                    String txt = String.valueOf(n);
                    int tw = font.width(txt) + 5;
                    g.fill(bx0 + BTN - tw - 1, by0 - 3, bx0 + BTN + 2, by0 + 9, 0xFF0E0C08);
                    g.fill(bx0 + BTN - tw, by0 - 2, bx0 + BTN + 1, by0 + 8, 0xFFC02B2B);
                    g.drawString(font, txt, bx0 + BTN - tw + 3, by0, 0xFFFFFFFF, false);
                }
            }
        }
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
        if (tip == null && !blockedAt(hits, mouseX, mouseY)) {
            Spot sp = playerAt(mouseX, mouseY);
            if (sp != null) {
                SiteTerritories.Data d = SiteTerritories.data();
                tip = sp.e().account;
                if (sp.country() >= 0 && d != null) tip += "\n" + d.labels[sp.country()];
                tip += "\nX " + (int) Math.floor(sp.e().x(System.currentTimeMillis())) + "  Z " + (int) Math.floor(sp.e().z(System.currentTimeMillis()));
            } else {
                SiteTerritories.Locality city = cityAt(mouseX, mouseY);
                if (city != null) {
                    SiteTerritories.Data d = SiteTerritories.data();
                    tip = SiteTerritories.splitCamel(city.name) + "\n" + SiteTerritories.splitCamel(city.kind == null ? "" : city.kind);
                    if (d != null && city.countryIdx >= 0) tip += "\n" + d.labels[city.countryIdx];
                }
            }
        }
        if (tip == null) return;
        String[] lines = tip.split("\n");
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 18;
        int h = lines.length * 11 + 11;
        int x = Math.min(mouseX + 10, width - w - 4);
        int y = Math.max(4, Math.min(mouseY + 12, height - h - 4));
        box(g, x, y, w, h, 0xFF161815);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 && lines.length > 1 ? COL_GOLD : i == 0 ? COL_TEXT : COL_DIM;
            g.drawString(font, lines[i], x + 9, y + 7 + i * 11, color, false);
        }
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
            case TAB_DIPLO, TAB_WAR, TAB_DIPLOMENU -> true;
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
        panelH = height - MARGIN * 2 - barH - 8;
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
            case TAB_DIPLOMENU -> drawDiploMenu(g, mouseX, mouseY);
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

    /**
     * Cabeçalho fixo. Com país (rgb diferente de NEUTRAL) mostra a caixa da bandeira e o subtítulo;
     * sem país é só o título (menus como Diplomacia, Guerra e a lista não têm bandeira).
     */
    private int header(GuiGraphics g, int rgb, String title, String subtitle, String flagUrl, int mouseX, int mouseY, Runnable close) {
        boolean country = rgb != NEUTRAL;
        int x = bx, y = by, w = bw;
        int hh = country ? 46 : subtitle != null ? 36 : 28;
        g.fill(x + 4, y + 4, x + w - 4, y + hh, 0xFF121312);
        g.fill(x + 4, y + 4, x + w - 4, y + 5, 0xFF050605);
        g.fill(x + 4, y + hh, x + w - 4, y + hh + 1, COL_GOLD_DIM);
        int tx = x + 12;
        if (country) {
            g.fill(x + 4, y + hh - 3, x + w - 4, y + hh, 0xFF000000 | rgb);
            g.fill(x + 9, y + 9, x + 51, y + 37, 0xFF0E0C08);
            g.fill(x + 10, y + 10, x + 50, y + 36, 0xFF7A6538);
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
        spaced(g, fitSpaced(title.toUpperCase(Locale.ROOT), maxW), tx, ty, 0xFFF2E3B0, true);
        if (subtitle != null) g.drawString(font, fit(subtitle, maxW), tx, ty + 13, COL_DIM, false);
        int cx = x + w - 22, cyy = y + 7;
        boolean hover = mouseX >= cx && mouseX < cx + 14 && mouseY >= cyy && mouseY < cyy + 14;
        bevel(g, cx, cyy, 14, 14, hover, false);
        g.drawString(font, "x", cx + 5, cyy + 3, hover ? 0xFFFFF2B0 : COL_TEXT, false);
        hits.add(new Hit(cx, cyy, 14, 14, close, "Fechar"));
        return y + hh + 5;
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
        scrollBottom = by + bh - 4 - reserve;
        g.enableScissor(bx + 4, top, bx + bw - 4, scrollBottom);
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
        g.fill(bx + 6, cy, bx + bw - 6, cy + 13, 0xFF141513);
        g.fill(bx + 6, cy, bx + bw - 6, cy + 1, 0xFF050605);
        g.fill(bx + 6, cy + 12, bx + bw - 6, cy + 13, 0xFF4B3C21);
        int dx = bx + 13, dy = cy + 6;
        g.fill(dx, dy - 3, dx + 1, dy + 4, COL_GOLD);
        g.fill(dx - 3, dy, dx + 4, dy + 1, COL_GOLD);
        g.fill(dx - 1, dy - 1, dx + 2, dy + 2, COL_GOLD);
        spaced(g, fitSpaced(title.toUpperCase(Locale.ROOT), bw - 40), bx + 22, cy + 3, COL_GOLD, false);
        cy += 17;
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
        List<String> lines = wrap(value, bw - 16 - labelCol - 10);
        int h = Math.max(1, lines.size()) * 10 + 3;
        rowIdx++;
        g.fill(valueX - 4, cy - 1, bx + bw - 7, cy + h - 1, 0xFF161816);
        g.fill(valueX - 4, cy - 1, bx + bw - 7, cy, 0xFF0A0B0A);
        g.fill(valueX - 4, cy + h - 2, bx + bw - 7, cy + h - 1, 0xFF2E2A1E);
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
            g.fill(x, lineY, x + w, lineY + rowH, hover ? 0xFF40392A : 0xFF222421);
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

        List<SiteTerritories.Locality> places = placesOf(d, c);
        if (!places.isEmpty()) {
            section(g, "LOCALIDADES (" + places.size() + ")  -  clique para ir");
            for (SiteTerritories.Locality l : places) {
                int rowTop = cy;
                if (visible(rowTop, 16)) {
                    boolean isSel = l.idx == selCity;
                    boolean hover = mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 16;
                    if (isSel) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + 16, 0x50D9B45A);
                    else if (hover) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + 16, 0x40D9B45A);
                    else if ((rowIdx & 1) == 0) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + 16, 0x16FFFFFF);
                    RemoteImages.Img ic = cityImage(l, 14);
                    if (ic != null) {
                        g.blit(RenderPipelines.GUI_TEXTURED, ic.id(), bx + 10 + (14 - ic.w()) / 2, rowTop + 1 + (14 - ic.h()) / 2, 0f, 0f, ic.w(), ic.h(), ic.w(), ic.h(), ic.w(), ic.h());
                    } else {
                        g.fill(bx + 14, rowTop + 5, bx + 20, rowTop + 11, lighten(d.rgb[c], 1.3f));
                    }
                    String kind = pretty(l.kind);
                    int kw = kind == null ? 0 : Math.min(font.width(kind), 90);
                    g.drawString(font, fit(pretty(l.name), bw - 66 - kw), bx + 30, rowTop + 4, isSel ? 0xFFFFF2B0 : COL_TEXT, false);
                    if (kind != null) g.drawString(font, fit(kind, 90), bx + bw - 12 - kw, rowTop + 4, COL_DIM, false);
                    hits.add(new Hit(bx + 6, rowTop, bw - 12, 16, () -> focusCity(l), "Ir para " + pretty(l.name)));
                }
                rowIdx++;
                cy += 16;
            }
        }

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

    private int placesCountry = -2, placesVersion = -1;
    private List<SiteTerritories.Locality> places = List.of();

    /** Localidades do país, das maiores para as menores (guardado até mudar o país ou os dados). */
    private List<SiteTerritories.Locality> placesOf(SiteTerritories.Data d, int country) {
        if (placesCountry != country || placesVersion != SiteTerritories.version()) {
            placesCountry = country;
            placesVersion = SiteTerritories.version();
            List<SiteTerritories.Locality> list = new ArrayList<>();
            for (SiteTerritories.Locality l : d.localities) if (l.countryIdx == country) list.add(l);
            list.sort((a, b) -> a.tier != b.tier ? Integer.compare(b.tier, a.tier) : pretty(a.name).compareToIgnoreCase(pretty(b.name)));
            places = list;
        }
        return places;
    }

    private void openPlayer(String name) {
        selPlayer = name;
        cardScroll = 0;
    }

    private void selectCountry(int country) {
        centerCountry(country);
        tab = TAB_INFO;
        SCROLL[TAB_INFO] = 0;
    }

    /** Seleciona o país (sua maior província) e leva a câmera até ele, sem trocar de painel. */
    private void centerCountry(int country) {
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
    }

    /** Leva a câmera até a cidade e a marca (ícone e limite em destaque); o painel atual continua aberto. */
    private void focusCity(SiteTerritories.Locality l) {
        selCity = l.idx;
        pulseCity = l.idx;
        pulseStart = System.currentTimeMillis();
        viewX = l.x + 0.5;
        viewZ = l.z + 0.5;
        if (scale < 0.7) scale = 0.7;
    }

    // ----------------------------------------------------------- painel da cidade

    private void drawCityPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (selCity < 0 || selCity >= d.localities.size()) return;
        SiteTerritories.Locality l = d.localities.get(selCity);
        int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] : NEUTRAL;
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
        int top = header(g, NEUTRAL, "Países", d.ids.length + " países  -  " + d.n + " províncias", null, mouseX, mouseY, this::closePanel);
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
        int rgb = dipFocus >= 0 ? d.rgb[dipFocus] : NEUTRAL;
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

    private static final int COL_WAR = 0xFFE06A6A;

    private WarFront frontCache;
    private SiteTerritories.WarInfo frontCacheWar;

    private static boolean warRunning(SiteTerritories.WarInfo w) {
        return w != null && WarMarkers.running(w);
    }

    private int activeWarCount() {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) return 0;
        int n = 0;
        for (SiteTerritories.WarInfo w : Relations.wars(d)) if (warRunning(w)) n++;
        return n;
    }

    private SiteTerritories.WarInfo viewedWar(SiteTerritories.Data d) {
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        return warSel >= 0 && warSel < wars.size() ? wars.get(warSel) : null;
    }

    /** A frente da guerra em exibição (null se nenhuma ou se o site não manda as conexões). */
    private WarFront viewedFront(SiteTerritories.Data d) {
        SiteTerritories.WarInfo w = viewedWar(d);
        if (w == null || w.sides == null || w.sides.size() < 2) return null;
        WarFront f = WarFront.of(d, w);
        return f.hasNetwork() ? f : null;
    }

    /** Uma vez por quadro: marcadores do dynmap (só com guerra ao vivo à vista) e efeitos de guerra. */
    private void tickWar() {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null || !siteMode()) return;
        SiteTerritories.WarInfo viewed = viewedWar(d);
        if (viewed != null && viewed.live && viewed.ended == null && SiteTerritories.liveWar()) DynmapMarkers.want();
        DynmapMarkers.tick();
        if (SiteTerritories.warMarkers()) {
            SiteTerritories.WarInfo f = WarMarkers.followed(d, viewed == null ? null : viewed.id);
            if (f != null) WarMarkers.update(d, f, OnlinePlayers.list(), System.currentTimeMillis());
            else WarMarkers.clear();
        }
    }

    private static int ctlColor(SiteTerritories.WarInfo war, int ctl) {
        if (ctl >= 0 && war.sides != null && ctl < war.sides.size()) return 0xFF000000 | Relations.sideColor(war.sides.get(ctl), ctl);
        if (ctl == WarFront.CONTESTED) return 0xFF000000 | Relations.CONTESTED;
        if (ctl == WarFront.UNCONTROLLED) return 0xFF000000 | Relations.UNCONTROLLED;
        return 0xFF9A9A9A;
    }

    /** Linhas de frente (grossas, cada metade na cor de quem segura), ligações internas (pontilhadas) e anéis nas cidades In Play. */
    private void drawWarFront(GuiGraphics g, SiteTerritories.Data d) {
        if (mode != MODE_WAR || scale < 0.04) return;
        SiteTerritories.WarInfo war = viewedWar(d);
        WarFront f = viewedFront(d);
        if (war == null || f == null) return;
        for (WarFront.Hot h : f.innerLines()) {
            double x0 = toScreenX(h.a().x + 0.5), y0 = toScreenY(h.a().z + 0.5), x1 = toScreenX(h.b().x + 0.5), y1 = toScreenY(h.b().z + 0.5);
            dottedLine(g, x0, y0, x1, y1, 0xC8C8C8C8);
        }
        float thick = (float) Math.max(2, Math.min(9, 3 * scale));
        for (WarFront.Hot h : f.hotLines()) {
            double x0 = toScreenX(h.a().x + 0.5), y0 = toScreenY(h.a().z + 0.5), x1 = toScreenX(h.b().x + 0.5), y1 = toScreenY(h.b().z + 0.5);
            double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            segment(g, x0, y0, x1, y1, 0xB0000000, thick + 2);
            segment(g, x0, y0, mx, my, ctlColor(war, h.controlA()), thick);
            segment(g, mx, my, x1, y1, ctlColor(war, h.controlB()), thick);
        }
        long now = System.currentTimeMillis();
        double phase = (now % 1400) / 1400.0;
        for (WarFront.Unit u : f.units()) {
            if (!u.inPlay || u.neutral()) continue;
            int color = ctlColor(war, u.control) & 0xFFFFFF;
            for (SiteTerritories.Locality l : u.members) {
                int sx = (int) Math.round(toScreenX(l.x + 0.5)), sy = (int) Math.round(toScreenY(l.z + 0.5));
                if (sx < -30 || sx > width + 30 || sy < -30 || sy > height + 30 || !cityVisible(l)) continue;
                int r = iconRadius(l) + 2 + (int) Math.round(phase * 6);
                int a = (int) Math.round(200 * (1 - phase));
                squareRing(g, sx, sy, r, (a << 24) | color);
                squareRing(g, sx, sy, iconRadius(l) + 2, 0xC0000000 | color);
            }
        }
    }

    private void squareRing(GuiGraphics g, int cx, int cyy, int r, int argb) {
        g.fill(cx - r, cyy - r, cx + r + 1, cyy - r + 1, argb);
        g.fill(cx - r, cyy + r, cx + r + 1, cyy + r + 1, argb);
        g.fill(cx - r, cyy - r + 1, cx - r + 1, cyy + r, argb);
        g.fill(cx + r, cyy - r + 1, cx + r + 1, cyy + r, argb);
    }

    private void dottedLine(GuiGraphics g, double x0, double y0, double x1, double y1, int color) {
        if ((x0 < 0 && x1 < 0) || (x0 > width && x1 > width) || (y0 < 0 && y1 < 0) || (y0 > height && y1 > height)) return;
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 4) return;
        int steps = (int) Math.min(len / 6, 400);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(x0 + dx * t), y = (int) Math.round(y0 + dy * t);
            if (x < 0 || x >= width || y < 0 || y >= height) continue;
            g.fill(x, y, x + 2, y + 2, color);
        }
    }

    private void drawArt(GuiGraphics g, WarArt.Art art, int x, int y, int cell) {
        WarArt.rects(art, x, y, cell, (x0, y0, x1, y1, argb) -> g.fill(x0, y0, x1, y1, argb));
    }

    /** Espadas (jogadores de dois lados na cidade) e bandeiras (cidade recém-tomada) por cima dos ícones. */
    private void drawWarMarkers(GuiGraphics g, SiteTerritories.Data d) {
        if (!SiteTerritories.warMarkers() || scale < 0.12) return;
        List<WarMarkers.Marker> list = WarMarkers.current();
        if (list.isEmpty()) return;
        int phase = (int) ((System.currentTimeMillis() / 350) % 2);
        for (WarMarkers.Marker m : list) {
            int sx = (int) Math.round(toScreenX(m.x() + 0.5)), sy = (int) Math.round(toScreenY(m.z() + 0.5));
            if (sx < -30 || sx > width + 30 || sy < -30 || sy > height + 30) continue;
            if (m.kind() == WarMarkers.Kind.SWORD) {
                WarArt.Art a = WarArt.SWORD;
                drawArt(g, a, sx - a.w() + 2, sy - 8 - a.h() * 2, 2);
            } else {
                WarArt.Art a = WarArt.flag(m.rgb(), phase);
                drawArt(g, a, sx + 3, sy - 4 - a.h() * 2, 2);
            }
        }
    }

    // -- painel

    private static String dateText(long ms) {
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()));
    }

    private static String pts(double v) {
        return Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String warName(SiteTerritories.WarInfo w) {
        return w.name == null || w.name.isBlank() ? String.valueOf(w.id) : w.name;
    }

    private static String sideName(SiteTerritories.WarInfo w, int i) {
        String n = i >= 0 && w.sides != null && i < w.sides.size() ? w.sides.get(i).name : null;
        return n == null || n.isBlank() ? "Lado " + (i + 1) : n;
    }

    /** Barra de pesos por lado, com a porcentagem dentro de cada trecho largo o bastante. */
    private void sideBar(GuiGraphics g, SiteTerritories.WarInfo war, double[] weights, int x, int y, int w) {
        double total = 0;
        for (double v : weights) total += v;
        g.fill(x - 1, y - 1, x + w + 1, y + 13, 0xFF0E0C08);
        if (total <= 0) {
            g.fill(x, y, x + w, y + 12, 0xFF2A2B25);
            return;
        }
        int used = 0;
        for (int i = 0; i < weights.length; i++) {
            int end = i == weights.length - 1 ? w : (int) Math.round(w * sumTo(weights, i) / total);
            int segW = end - used;
            if (segW > 0) {
                int color = ctlColor(war, i);
                g.fill(x + used, y, x + end, y + 12, color);
                g.fill(x + used, y, x + end, y + 2, lighten(color, 1.25f));
                g.fill(x + used, y + 10, x + end, y + 12, darken(color, 0.7f));
                String pct = Math.round(weights[i] * 100 / total) + "%";
                if (segW >= font.width(pct) + 6) g.drawString(font, pct, x + used + (segW - font.width(pct)) / 2, y + 2, 0xFFFFFFFF, true);
            }
            if (i > 0 && segW > 0) g.fill(x + used, y, x + used + 1, y + 12, 0xFF0E0C08);
            used = end;
        }
    }

    private static double sumTo(double[] v, int i) {
        double t = 0;
        for (int k = 0; k <= i; k++) t += v[k];
        return t;
    }

    private void drawWarPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        SiteTerritories.WarInfo sel = viewedWar(d);
        int running = activeWarCount();
        String subtitle = sel == null ? running + " em andamento, " + (wars.size() - running) + " encerrada(s)" : warName(sel);
        int top = header(g, NEUTRAL, "Guerra", subtitle, null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            warSel = -1;
        });
        if (sel == null) {
            beginScroll(g, top, SCROLL[TAB_WAR], 0);
            warList(g, d, wars, mouseX, mouseY);
        } else {
            textButtonFixed(g, bx + 8, top, bw - 16, 15, "< Todas as guerras", mouseX, mouseY, () -> {
                warSel = -1;
                SCROLL[TAB_WAR] = 0;
            });
            top += 19;
            top = tabStrip(g, top, new String[]{"Lados", "Cidades", "Lei 7"}, warTab, mouseX, mouseY, i -> {
                warTab = i;
                SCROLL[TAB_WAR] = 0;
            });
            beginScroll(g, top, SCROLL[TAB_WAR], 0);
            warSummary(g, d, sel, mouseX, mouseY);
            switch (warTab) {
                case 0 -> warSides(g, d, sel, mouseX, mouseY);
                case 1 -> warCities(g, d, sel, mouseX, mouseY);
                default -> warLaw7(g, d, sel, mouseX, mouseY);
            }
        }
        SCROLL[TAB_WAR] = endScroll(g, SCROLL[TAB_WAR]);
    }

    /** Botão de texto fora da área rolável. */
    private void textButtonFixed(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        bevel(g, x, y, w, h, hover, false);
        g.drawString(font, label, x + 6, y + (h - 8) / 2, hover ? 0xFFFFF2B0 : COL_TEXT, false);
        hits.add(new Hit(x, y, w, h, action, null));
    }

    private void warList(GuiGraphics g, SiteTerritories.Data d, List<SiteTerritories.WarInfo> wars, int mouseX, int mouseY) {
        if (wars.isEmpty()) {
            paragraph(g, "O site não registra nenhuma guerra neste momento. Quando houver uma, ela aparece aqui: placar, lados, cidades tomadas, frentes e capitulação.", COL_DIM);
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < wars.size(); i++) if (warRunning(wars.get(i)) == (pass == 0)) idxs.add(i);
            if (idxs.isEmpty()) continue;
            section(g, (pass == 0 ? "Em andamento" : "Encerradas") + " (" + idxs.size() + ")");
            for (int i : idxs) warCard(g, d, wars.get(i), i, mouseX, mouseY);
        }
    }

    /** Uma guerra na lista: nome, estado, barra de pontos e datas. */
    private void warCard(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int idx, int mouseX, int mouseY) {
        int h = 42, x = bx + 8, wd = bw - 16;
        int y = cy;
        boolean vis = visible(y, h);
        boolean hover = vis && mouseX >= x && mouseX < x + wd && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + wd, y + h, hover ? 0xFF2A2820 : 0xFF181A18);
        g.fill(x, y, x + wd, y + 1, 0xFF050605);
        g.fill(x, y + h - 1, x + wd, y + h, 0xFF4B3C21);
        boolean run = warRunning(w);
        String tag = !run ? "ENCERRADA" : w.live ? "AO VIVO" : "ATIVA";
        int tagCol = !run ? COL_DIM : COL_WAR;
        g.drawString(font, fit(warName(w), wd - 14 - font.width(tag) - 6), x + 6, y + 4, hover ? 0xFFFFF2B0 : COL_TEXT, false);
        g.drawString(font, tag, x + wd - 6 - font.width(tag), y + 4, tagCol, false);
        if (w.sides != null && w.sides.size() >= 2) {
            WarScore.Result r = WarScore.of(d, w);
            sideBar(g, w, r.points().clone(), x + 6, y + 16, wd - 12);
        }
        long st = parseMs(w.started), en = parseMs(w.ended);
        long now = System.currentTimeMillis();
        String line = st != NONE ? "iniciada " + countdown(st - now) : "";
        if (en != NONE) line += (line.isEmpty() ? "" : "   ") + (en <= now ? "terminou " : "termina ") + countdown(en - now);
        if (!line.isEmpty()) g.drawString(font, fit(line, wd - 12), x + 6, y + 31, COL_DIM, false);
        if (vis) hits.add(new Hit(x, y, wd, h, () -> {
            warSel = idx;
            warTab = 0;
            SCROLL[TAB_WAR] = 0;
        }, null));
        cy += h + 3;
    }

    /** Placar, estado e datas, no topo de qualquer aba. */
    private void warSummary(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        boolean run = warRunning(w);
        long now = System.currentTimeMillis();
        long st = parseMs(w.started), en = parseMs(w.ended);
        int n = w.sides == null ? 0 : w.sides.size();
        section(g, "Placar");
        if (n >= 2) {
            WarScore.Result r = WarScore.of(d, w);
            boolean any = r.total() > 0;
            g.drawString(font, "Pontos mantidos (Lei 7.9)", bx + 9, cy, COL_DIM, false);
            cy += 11;
            sideBar(g, w, r.points().clone(), bx + 9, cy, bw - 18);
            cy += 17;
            String verdict = !any ? "Sem valores de cidade registrados" : r.even() ? "Equilíbrio: nenhum lado à frente" : sideName(w, r.leader()) + " na frente";
            g.drawString(font, fit(verdict, bw - 18), bx + 9, cy, !any || r.even() ? COL_DIM : ctlColor(w, r.leader()), false);
            cy += 12;
            double[] caps = new double[n];
            int taken = 0;
            for (WarScore.Holding h : r.holdings()) {
                if (h.taken()) {
                    caps[h.holder()]++;
                    taken++;
                }
            }
            g.drawString(font, "Cidades tomadas do inimigo", bx + 9, cy, COL_DIM, false);
            cy += 11;
            sideBar(g, w, caps, bx + 9, cy, bw - 18);
            cy += 17;
            if (taken == 0) {
                g.drawString(font, "Nenhuma cidade tomada ainda", bx + 9, cy, COL_DIM, false);
                cy += 12;
            }
            for (int i = 0; i < n; i++) {
                int color = ctlColor(w, i);
                g.fill(bx + 9, cy + 1, bx + 17, cy + 9, 0xFF0E0C08);
                g.fill(bx + 10, cy + 2, bx + 16, cy + 8, color);
                String tail = (any ? Math.round(r.share(i) * 100) + "%  " + pts(r.points()[i]) + " pts" : "") + "   " + (int) caps[i] + " tomada(s)";
                int tw = font.width(tail);
                g.drawString(font, fit(sideName(w, i), bw - 40 - tw), bx + 21, cy, COL_TEXT, false);
                g.drawString(font, tail, bx + bw - 10 - tw, cy, COL_DIM, false);
                cy += 11;
            }
            cy += 3;
        }
        section(g, "Estado");
        kv(g, "Situação", !run ? "Encerrada" : w.live ? "Em andamento (ao vivo)" : "Em andamento");
        kv(g, "Tipo", w.type);
        kv(g, "Início", st == NONE ? null : dateText(st) + "  (" + countdown(st - now) + ")");
        if (en != NONE) {
            kv(g, run ? "Termina" : "Fim", dateText(en) + "  (" + countdown(en - now) + ")");
            long truce = en + 3 * DAY;
            if (!run && truce > now) kv(g, "Trégua até", dateText(truce) + "  (" + countdown(truce - now) + ")");
        }
        kv(g, "Teatro", w.theater == null || w.theater.isBlank() ? "Irrestrita (todas as cidades dos beligerantes)" : "Restrita: " + w.theater);
        kv(g, "Fonte", LiveControl.isLive(w) ? "Dynmap ao vivo (marcadores das cidades)" : w.live ? "Guerra ao vivo; aguardando o dynmap" : "Lista de cidades do site");
        kv(g, "Notas", w.notes);
    }

    private void warSides(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        if (w.sides == null) return;
        WarScore.Result r = WarScore.of(d, w);
        WarFront f = viewedFront(d);
        Map<String, WarFront.Score> scoreBy = new HashMap<>();
        if (f != null) for (WarFront.Score s : f.scores()) scoreBy.put(s.nation().toLowerCase(Locale.ROOT), s);
        for (int i = 0; i < w.sides.size(); i++) {
            SiteTerritories.Side side = w.sides.get(i);
            section(g, sideName(w, i));
            int color = ctlColor(w, i);
            g.fill(bx + bw - 20, cy - 14, bx + bw - 10, cy - 5, 0xFF0E0C08);
            g.fill(bx + bw - 19, cy - 13, bx + bw - 11, cy - 6, color);
            g.drawString(font, pts(r.points()[i]) + " pts  (" + Math.round(r.share(i) * 100) + "%)", bx + 9, cy, COL_TEXT, false);
            cy += 13;
            if (side.members == null) continue;
            for (SiteTerritories.Member m : side.members) {
                if (m.name != null) {
                    g.drawString(font, fit(stripAt(m.name) + (m.note != null && !m.note.isBlank() ? "  (" + m.note + ")" : ""), bw - 20), bx + 9, cy, COL_LINK, false);
                    cy += 11;
                }
                List<String> countries = m.countries == null || m.countries.isEmpty() ? (m.name == null ? List.<String>of() : List.of(stripAt(m.name))) : m.countries;
                for (String c : countries) {
                    int ci = countryIndex(d, c);
                    int held = 0, own = 0;
                    for (WarScore.Holding h : r.holdings()) {
                        if (h.locality().country == null || !h.locality().country.equalsIgnoreCase(c)) continue;
                        own++;
                        if (h.holder() == h.owner()) held++;
                    }
                    int rowTop = cy;
                    boolean vis = visible(rowTop, 14);
                    boolean hover = vis && ci >= 0 && mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 14;
                    zebra(g, rowTop, 14, hover, false);
                    rowIdx++;
                    if (ci >= 0) miniFlag(g, d, ci, bx + 10, rowTop + 1);
                    String tail = own == 0 ? "" : held + "/" + own + " cidades";
                    WarFront.Score sc = scoreBy.get(c.toLowerCase(Locale.ROOT));
                    if (sc != null && sc.capitulated()) tail = "CAPITULOU";
                    int tw = font.width(tail);
                    g.drawString(font, fit(ci >= 0 ? d.labels[ci] : c, bw - 60 - tw), bx + 36, rowTop + 3, hover ? 0xFFFFE27A : COL_TEXT, false);
                    g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 3, sc != null && sc.capitulated() ? COL_WAR : COL_DIM, false);
                    final int cc = ci;
                    if (vis && ci >= 0) hits.add(new Hit(bx + 6, rowTop, bw - 12, 14, () -> selectCountry(cc), null));
                    cy += 14;
                }
            }
            cy += 2;
        }
    }

    private static int countryIndex(SiteTerritories.Data d, String name) {
        Integer i = Relations.nameIndex(d).get(Relations.norm(name));
        return i == null ? -1 : i;
    }

    private void warCities(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        if (w.sides == null) return;
        WarScore.Result r = WarScore.of(d, w);
        WarFront f = viewedFront(d);
        int boxW = (bw - 16 - 6) / 3;
        String[] labels = {"Todas", "Tomadas", "Em disputa"};
        for (int i = 0; i < 3; i++) {
            final int fi = i;
            int x = bx + 8 + i * (boxW + 3);
            boolean vis = visible(cy, 15);
            boolean hover = vis && mouseX >= x && mouseX < x + boxW && mouseY >= cy && mouseY < cy + 15;
            bevel(g, x, cy, boxW, 15, hover, warCityFilter == i);
            g.drawString(font, labels[i], x + (boxW - font.width(labels[i])) / 2, cy + 4, warCityFilter == i ? 0xFFFFF2B0 : COL_TEXT, false);
            if (vis) hits.add(new Hit(x, cy, boxW, 15, () -> warCityFilter = fi, null));
        }
        cy += 19;

        if (warCityFilter != 0 || true) {
            // cidades tomadas, por lado que as segura
            List<WarScore.Holding> taken = new ArrayList<>();
            for (WarScore.Holding h : r.holdings()) if (h.taken()) taken.add(h);
            if (warCityFilter != 2) {
                section(g, "Tomadas do inimigo (" + taken.size() + ")");
                if (taken.isEmpty()) paragraph(g, "Nenhuma cidade mudou de lado.", COL_DIM);
                for (int s = 0; s < w.sides.size(); s++) {
                    List<WarScore.Holding> mine = new ArrayList<>();
                    for (WarScore.Holding h : taken) if (h.holder() == s) mine.add(h);
                    if (mine.isEmpty()) continue;
                    mine.sort(BY_VALUE);
                    g.fill(bx + 9, cy + 1, bx + 17, cy + 9, ctlColor(w, s));
                    g.drawString(font, fit(sideName(w, s) + " tomou " + mine.size(), bw - 34), bx + 21, cy, COL_TEXT, false);
                    cy += 12;
                    for (WarScore.Holding h : mine) warCityRow(g, d, f, h, true, mouseX, mouseY);
                }
            }
            // em disputa / sem controle
            List<WarScore.Holding> disputed = new ArrayList<>();
            for (WarScore.Holding h : r.holdings()) if (h.disputed()) disputed.add(h);
            if (warCityFilter != 1) {
                section(g, "Em disputa ou sem controle (" + disputed.size() + ")");
                if (disputed.isEmpty()) paragraph(g, "Nenhuma no momento.", COL_DIM);
                disputed.sort(BY_VALUE);
                for (WarScore.Holding h : disputed) warCityRow(g, d, f, h, true, mouseX, mouseY);
            }
        }
        if (warCityFilter == 0) {
            // por país, recolhíveis
            Map<String, List<WarScore.Holding>> byCountry = new java.util.LinkedHashMap<>();
            for (int s = 0; s < w.sides.size(); s++) {
                for (WarScore.Holding h : r.holdings()) {
                    if (h.owner() != s || h.locality().country == null) continue;
                    byCountry.computeIfAbsent(h.locality().country, k -> new ArrayList<>()).add(h);
                }
            }
            section(g, "Por país");
            for (Map.Entry<String, List<WarScore.Holding>> e : byCountry.entrySet()) {
                String key = w.id + "/" + e.getKey();
                boolean closed = WAR_CLOSED.contains(key);
                List<WarScore.Holding> list = e.getValue();
                list.sort(BY_VALUE);
                int lost = 0;
                for (WarScore.Holding h : list) if (h.holder() != h.owner()) lost++;
                int ci = countryIndex(d, e.getKey());
                int rowTop = cy;
                boolean vis = visible(rowTop, 15);
                boolean hover = vis && mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 15;
                zebra(g, rowTop, 15, hover, false);
                rowIdx++;
                g.drawString(font, closed ? "+" : "-", bx + 10, rowTop + 4, COL_GOLD, false);
                if (ci >= 0) miniFlag(g, d, ci, bx + 20, rowTop + 1);
                String tail = list.size() + (list.size() == 1 ? " cidade" : " cidades") + (lost > 0 ? ", " + lost + " perdida(s)" : "");
                int tw = font.width(tail);
                g.drawString(font, fit(ci >= 0 ? d.labels[ci] : e.getKey(), bw - 64 - tw), bx + 46, rowTop + 4, hover ? 0xFFFFE27A : COL_TEXT, false);
                g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 4, lost > 0 ? COL_WAR : COL_DIM, false);
                if (vis) hits.add(new Hit(bx + 6, rowTop, bw - 12, 15, () -> {
                    if (!WAR_CLOSED.remove(key)) WAR_CLOSED.add(key);
                }, null));
                cy += 15;
                if (!closed) for (WarScore.Holding h : list) warCityRow(g, d, f, h, false, mouseX, mouseY);
            }
        }
    }

    private static final java.util.Comparator<WarScore.Holding> BY_VALUE = java.util.Comparator
            .comparingDouble((WarScore.Holding h) -> -h.value()).thenComparing(h -> String.valueOf(h.locality().name), String.CASE_INSENSITIVE_ORDER);

    /** Uma cidade numa lista: bolinha da cor de quem segura, nome (clique vai até ela), país opcional, valor e estado. */
    private void warCityRow(GuiGraphics g, SiteTerritories.Data d, WarFront f, WarScore.Holding h, boolean withCountry, int mouseX, int mouseY) {
        SiteTerritories.Locality l = h.locality();
        SiteTerritories.WarInfo w = viewedWar(d);
        int rowTop = cy;
        boolean vis = visible(rowTop, 12);
        boolean hover = vis && mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 12;
        zebra(g, rowTop, 12, hover, false);
        rowIdx++;
        int dot = "contested".equals(h.state()) ? 0xFF000000 | Relations.CONTESTED : "uncontrolled".equals(h.state()) ? 0xFF000000 | Relations.UNCONTROLLED
                : h.holder() >= 0 ? ctlColor(w, h.holder()) : 0xFF9A9A9A;
        g.fill(bx + 16, rowTop + 3, bx + 24, rowTop + 11, 0xFF0E0C08);
        g.fill(bx + 17, rowTop + 4, bx + 23, rowTop + 10, dot);
        String status = f == null ? null : f.statusOf(l);
        String tail = h.value() > 0 ? pts(h.value()) + " pts" : "";
        if (status != null) tail = (tail.isEmpty() ? "" : tail + "  ") + status;
        int tw = font.width(tail);
        String name = pretty(l.name) + (withCountry && l.country != null && !l.country.isBlank() ? " (" + l.country + ")" : "");
        g.drawString(font, fit(name, bw - 56 - tw), bx + 29, rowTop + 2, hover ? 0xFFFFE27A : COL_LINK, false);
        g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 2, status != null ? COL_WAR : COL_DIM, false);
        if (vis) {
            final SiteTerritories.Locality ll = l;
            hits.add(new Hit(bx + 6, rowTop, bw - 12, 12, () -> focusPoint(ll), null));
        }
        cy += 12;
    }

    /** Leva a câmera a uma cidade (mesmo as que o site tem e o mapa não lista) e a faz pulsar. */
    private void focusPoint(SiteTerritories.Locality l) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d != null && l.idx >= 0 && l.idx < d.localities.size() && d.localities.get(l.idx) == l) {
            focusCity(l);
            return;
        }
        viewX = l.x + 0.5;
        viewZ = l.z + 0.5;
        if (scale < 0.7) scale = 0.7;
    }

    private void warLaw7(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        WarFront f = viewedFront(d);
        if (f == null) {
            section(g, "Lei 7");
            paragraph(g, "O site não enviou as conexões entre cidades; sem elas não dá para calcular frentes, In Play, cerco ou capitulação.", COL_DIM);
            return;
        }
        section(g, "Frente por lado");
        for (int i = 0; i < w.sides.size(); i++) {
            int play = 0, enc = 0;
            for (WarFront.Unit u : f.units()) {
                if (u.control != i) continue;
                if (u.inPlay) play += u.members.size();
                if (u.encircled) enc += u.members.size();
            }
            g.fill(bx + 9, cy + 1, bx + 17, cy + 9, ctlColor(w, i));
            String tail = play + " In Play  /  " + enc + " cercadas";
            int tw = font.width(tail);
            g.drawString(font, fit(sideName(w, i), bw - 40 - tw), bx + 21, cy, COL_TEXT, false);
            g.drawString(font, tail, bx + bw - 10 - tw, cy, COL_DIM, false);
            cy += 12;
        }
        for (int side : f.noCapital()) {
            if (side >= 0 && side < w.sides.size()) paragraph(g, sideName(w, side) + " não segura nenhuma capital: nada deles conta como cercado.", COL_DIM);
        }
        if (!f.ties().isEmpty()) {
            paragraph(g, f.ties().size() + (f.ties().size() == 1 ? " empate" : " empates") + " sobre terra neutra esperam a escolha da staff (Short Circuit):", COL_WAR);
            for (WarFront.Tie t : f.ties()) {
                for (String[] pair : t.pairs()) paragraph(g, "  " + pair[0] + " - " + pair[1] + " (" + t.distance() + ")", COL_DIM);
            }
        }

        // cidades In Play
        List<SiteTerritories.Locality> play = new ArrayList<>();
        for (WarFront.Unit u : f.units()) if (u.inPlay && !u.neutral()) play.addAll(u.members);
        section(g, "Cidades In Play (" + play.size() + ")");
        if (play.isEmpty()) paragraph(g, "Nenhuma agora.", COL_DIM);
        play.sort(java.util.Comparator.comparing((SiteTerritories.Locality l) -> String.valueOf(l.country), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(l -> String.valueOf(l.name), String.CASE_INSENSITIVE_ORDER));
        for (SiteTerritories.Locality l : play) {
            WarFront.Unit u = f.unitOf(l);
            int rowTop = cy;
            boolean vis = visible(rowTop, 12);
            boolean hover = vis && mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 12;
            zebra(g, rowTop, 12, hover, false);
            rowIdx++;
            int color = ctlColor(w, u == null ? -4 : u.control);
            g.fill(bx + 16, rowTop + 3, bx + 24, rowTop + 11, 0xFF0E0C08);
            g.fill(bx + 17, rowTop + 4, bx + 23, rowTop + 10, color);
            String st = f.statusOf(l);
            String tail = st == null ? "In Play" : st;
            int tw = font.width(tail);
            String name = pretty(l.name) + (l.country == null || l.country.isBlank() ? "" : " (" + l.country + ")");
            g.drawString(font, fit(name, bw - 56 - tw), bx + 29, rowTop + 2, hover ? 0xFFFFE27A : COL_LINK, false);
            g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 2, COL_WAR, false);
            if (vis) hits.add(new Hit(bx + 6, rowTop, bw - 12, 12, () -> focusPoint(l), null));
            cy += 12;
        }

        // capitulação
        List<WarFront.Score> risk = new ArrayList<>();
        for (WarFront.Score s : f.scores()) if (s.score() > 0 || s.capitulated()) risk.add(s);
        section(g, "Capitulação (Lei 7.9)");
        paragraph(g, "Uma nação capitula com mais de 100 pontos perdidos e a capital tomada.", COL_DIM);
        if (risk.isEmpty()) paragraph(g, "Nenhuma nação em risco.", COL_DIM);
        for (WarFront.Score s : risk) {
            int rowTop = cy;
            g.fill(bx + 9, rowTop + 1, bx + 17, rowTop + 9, ctlColor(w, s.side()));
            String tail = s.capitulated() ? "CAPITULOU" : pts(s.score()) + " / 100" + (s.capitalTaken() ? "  capital tomada" : "");
            int tw = font.width(tail);
            g.drawString(font, fit(s.nation() + (s.hasCapital() ? "" : " (sem capital)"), bw - 40 - tw), bx + 21, rowTop, s.capitulated() ? COL_WAR : COL_TEXT, false);
            g.drawString(font, tail, bx + bw - 10 - tw, rowTop, s.capitulated() ? COL_WAR : COL_DIM, false);
            cy += 11;
            int barW = bw - 18;
            g.fill(bx + 9, cy, bx + 9 + barW, cy + 4, 0xFF0E0C08);
            int fill = (int) Math.min(barW - 2, Math.round((barW - 2) * Math.min(1.0, s.score() / 100.0)));
            g.fill(bx + 10, cy + 1, bx + 10 + fill, cy + 3, s.capitulated() ? 0xFFC02B2B : 0xFFD9B45A);
            cy += 8;
        }

        section(g, "Legenda do mapa");
        paragraph(g, "Linha grossa: frente, cada metade na cor de quem segura aquela ponta. Linha pontilhada cinza: ligação interna de um lado. Anel pulsante: cidade In Play. Espada: jogadores dos dois lados no mesmo território. Bandeira: cidade tomada há menos de 10 minutos.", COL_DIM);
    }

    // ------------------------------------------------- panorama diplomático

    private static final long NONE = Long.MIN_VALUE;
    private static final long DAY = 86_400_000L;

    private static long parseMs(String iso) {
        if (iso == null || iso.isBlank()) return NONE;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /** "em 2d 5h" / "há 3h 10m". */
    private static String countdown(long ms) {
        long a = Math.abs(ms) / 60000;
        long days = a / 1440, hours = (a % 1440) / 60, mins = a % 60;
        String t = days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + mins + "m" : mins + "m";
        return ms < 0 ? "há " + t : "em " + t;
    }

    private static String stripAt(String n) {
        return n != null && n.startsWith("@") ? n.substring(1) : n;
    }

    private int tabStrip(GuiGraphics g, int top, String[] labels, int sel, int mouseX, int mouseY, Consumer<Integer> pick) {
        int n = labels.length, gap = 3;
        int w = (bw - 16 - gap * (n - 1)) / n;
        int x = bx + 8;
        for (int i = 0; i < n; i++) {
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + 16;
            bevel(g, x, top, w, 16, hover, i == sel);
            g.drawString(font, labels[i], x + (w - font.width(labels[i])) / 2, top + 4, i == sel ? 0xFFFFF2B0 : COL_TEXT, false);
            final int idx = i;
            hits.add(new Hit(x, top, w, 16, () -> pick.accept(idx), null));
            x += w + gap;
        }
        return top + 21;
    }

    /** Texto clicável (sublinha ao passar o mouse); devolve a largura. */
    private int link(GuiGraphics g, String text, int x, int y, boolean clickable, int mouseX, int mouseY, Runnable action) {
        int w = font.width(text);
        boolean vis = visible(y - 1, 11);
        boolean hover = clickable && vis && mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 10;
        g.drawString(font, text, x, y, !clickable ? COL_TEXT : hover ? 0xFFFFE27A : COL_LINK, false);
        if (hover) g.fill(x, y + 9, x + w, y + 10, 0xFFFFE27A);
        if (clickable && vis) hits.add(new Hit(x, y - 1, w, 11, action, null));
        return w;
    }

    private void miniFlag(GuiGraphics g, SiteTerritories.Data d, int country, int x, int y) {
        g.fill(x, y, x + 22, y + 12, 0xFF05080C);
        if (country < 0) return;
        RemoteImages.Img flag = RemoteImages.flag(flagOf(d, country));
        if (flag != null) drawImage(g, flag, x + 1, y + 1, 20, 10);
        else g.fill(x + 1, y + 1, x + 21, y + 11, d.rgb[country] | 0xFF000000);
    }

    private void zebra(GuiGraphics g, int rowTop, int h, boolean hover, boolean selected) {
        if (selected) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + h, 0x50D9B45A);
        else if (hover) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + h, 0x30D9B45A);
        else if ((rowIdx & 1) == 0) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + h, 0x14FFFFFF);
    }

    private void drawDiploMenu(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        int top = header(g, NEUTRAL, "Panorama diplomático", "Alianças, NAP, purge e diplolock", null, mouseX, mouseY, () -> tab = TAB_NONE);
        top = tabStrip(g, top, new String[]{"Alianças", "NAP", "Purge", "Diplolock"}, dmTab, mouseX, mouseY, i -> {
            dmTab = i;
            SCROLL[TAB_DIPLOMENU] = 0;
        });
        beginScroll(g, top, SCROLL[TAB_DIPLOMENU], 0);
        switch (dmTab) {
            case 0 -> dmAlliances(g, d, mouseX, mouseY);
            case 1 -> dmNaps(g, d, mouseX, mouseY);
            case 2 -> dmPurge(g, d, mouseX, mouseY);
            default -> dmLocks(g, d, mouseX, mouseY);
        }
        SCROLL[TAB_DIPLOMENU] = endScroll(g, SCROLL[TAB_DIPLOMENU]);
    }

    private void dmAlliances(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        List<SiteTerritories.AllianceInfo> all = d.war == null || d.war.alliances == null ? List.of() : d.war.alliances;
        if (all.isEmpty()) {
            paragraph(g, "O site não lista alianças.", COL_DIM);
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            int count = 0;
            for (SiteTerritories.AllianceInfo a : all) if (Relations.isBilateral(a) == (pass == 1)) count++;
            if (count == 0) continue;
            section(g, (pass == 0 ? "Alianças" : "Alianças bilaterais") + " (" + count + ")");
            for (int i = 0; i < all.size(); i++) {
                SiteTerritories.AllianceInfo a = all.get(i);
                if (Relations.isBilateral(a) != (pass == 1)) continue;
                int rowTop = cy;
                if (visible(rowTop, 18)) {
                    boolean hover = mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 18;
                    zebra(g, rowTop, 18, hover, i == dipAlliance);
                    g.fill(bx + 10, rowTop + 2, bx + 32, rowTop + 16, 0xFF05080C);
                    RemoteImages.Img flag = a.flag == null ? null : RemoteImages.flag(SiteTerritories.assetUrl(a.flag, true));
                    if (flag != null) drawImage(g, flag, bx + 11, rowTop + 3, 20, 12);
                    else g.fill(bx + 11, rowTop + 3, bx + 31, rowTop + 15, 0xFF000000 | Relations.blocColor(a, i));
                    String count2 = String.valueOf(Relations.countriesOf(d, a).size());
                    g.drawString(font, fit(a.name == null ? a.id : a.name, bw - 90), bx + 38, rowTop + 5, COL_TEXT, false);
                    g.drawString(font, count2, bx + bw - 12 - font.width(count2), rowTop + 5, COL_DIM, false);
                    final int idx = i;
                    hits.add(new Hit(bx + 6, rowTop, bw - 12, 18, () -> dipAlliance = dipAlliance == idx ? -1 : idx, null));
                }
                rowIdx++;
                cy += 18;
            }
        }
        if (dipAlliance >= 0 && dipAlliance < all.size()) {
            SiteTerritories.AllianceInfo a = all.get(dipAlliance);
            section(g, a.name == null ? "Aliança" : a.name);
            textButton(g, bx + 9, cy, bw - 18, 16, "Ver no mapa (modo Diplomacia)", mouseX, mouseY, true, () -> {
                mode = MODE_DIPLOMACY;
                dipFocus = -1;
            });
            cy += 21;
            kv(g, "Nome oficial", a.officialName);
            kv(g, "Formada", a.formed);
            if (a.members != null) {
                for (SiteTerritories.Member m : a.members) kv(g, m.name == null ? "Membro" : m.name, m.note == null ? "membro" : m.note);
            }
            kv(g, "Notas", a.notes);
            List<String> names = new ArrayList<>();
            for (int c : Relations.countriesOf(d, a)) names.add(d.labels[c]);
            if (!names.isEmpty()) {
                section(g, "Países (" + names.size() + ")  -  clique para ir");
                chips(g, names, mouseX, mouseY, n -> {
                    Integer ci = Relations.nameIndex(d).get(Relations.norm(n));
                    if (ci != null) centerCountry(ci);
                });
            }
        }
    }

    private record NapPair(String a, String b, long until) {}

    private void dmNaps(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        Map<String, Integer> idx = Relations.nameIndex(d);
        Map<String, NapPair> pairs = new java.util.LinkedHashMap<>();
        if (d.war != null && d.war.naps != null) {
            for (SiteTerritories.Nap n : d.war.naps) {
                if (n.a == null || n.b == null) continue;
                long u = parseMs(n.until);
                if (u != NONE && u < now) continue;     // já acabou
                String[] k = {Relations.norm(n.a), Relations.norm(n.b)};
                Arrays.sort(k);
                pairs.putIfAbsent(k[0] + "|" + k[1], new NapPair(n.a, n.b, u));
            }
        }
        for (int c = 0; c < d.ids.length; c++) {
            SiteTerritories.Diplomacy dip = d.diplomacy[c];
            if (dip == null || dip.nap == null) continue;
            for (String other : dip.nap) {
                String o = stripAt(other);
                if (o == null || o.isBlank()) continue;
                String[] k = {Relations.norm(d.labels[c]), Relations.norm(o)};
                Arrays.sort(k);
                pairs.putIfAbsent(k[0] + "|" + k[1], new NapPair(d.labels[c], o, NONE));
            }
        }
        List<NapPair> list = new ArrayList<>(pairs.values());
        list.sort((x, y) -> {
            if (x.until() == NONE && y.until() == NONE) return x.a().compareToIgnoreCase(y.a());
            if (x.until() == NONE) return 1;
            if (y.until() == NONE) return -1;
            return Long.compare(x.until(), y.until());
        });
        section(g, "NAP ativos (" + list.size() + ")");
        if (list.isEmpty()) {
            paragraph(g, "Nenhum pacto de não agressão ativo no site.", COL_DIM);
            return;
        }
        paragraph(g, "Clique num nome para ir até o país. Os com prazo vêm primeiro, do que acaba antes para o que acaba depois.", COL_DIM);
        int half = (bw - 24 - 40) / 2;
        for (NapPair n : list) {
            int rowTop = cy;
            if (visible(rowTop, 24)) {
                zebra(g, rowTop, 24, false, false);
                Integer ia = idx.get(Relations.norm(n.a())), ib = idx.get(Relations.norm(n.b()));
                int x = bx + 11;
                String ta = fit(ia != null ? d.labels[ia] : n.a(), half), tb = fit(ib != null ? d.labels[ib] : n.b(), half);
                x += link(g, ta, x, rowTop + 3, ia != null, mouseX, mouseY, () -> centerCountry(ia));
                g.drawString(font, " - ", x, rowTop + 3, COL_DIM, false);
                x += font.width(" - ");
                link(g, tb, x, rowTop + 3, ib != null, mouseX, mouseY, () -> centerCountry(ib));
                String when = n.until() == NONE ? "sem prazo no site" : countdown(n.until() - now);
                g.drawString(font, when, bx + 11, rowTop + 13, n.until() == NONE ? COL_DIM : COL_TEXT, false);
            }
            rowIdx++;
            cy += 24;
        }
    }

    private record Purgeable(String site, SiteTerritories.Player p, long end) {}

    private void dmPurge(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        List<Purgeable> list = new ArrayList<>();
        int overdue = 0, soon = 0;
        for (Map.Entry<String, SiteTerritories.Player> e : d.players.entrySet()) {
            SiteTerritories.Player p = e.getValue();
            if (p == null || p.purge == null || p.purge.days <= 0) continue;
            if (p.immune != null && !p.immune.isBlank()) continue;   // imune não sofre purge
            long last = parseMs(p.lastJoined);
            if (last == NONE) continue;
            long end = last + p.purge.days * DAY;
            long left = end - now;
            if (left < 0) overdue++;
            if (left <= 7 * DAY) soon++;
            if (!dmAllPurge && left > 7 * DAY) continue;
            list.add(new Purgeable(e.getKey(), p, end));
        }
        list.sort((x, y) -> Long.compare(x.end(), y.end()));
        section(g, "Prestes a tomar purge");
        paragraph(g, soon + " jogador(es) a 7 dias ou menos do purge; " + overdue + " já passaram do prazo. Jogadores imunes ficam de fora.", COL_TEXT);
        textButton(g, bx + 9, cy, bw - 18, 16, dmAllPurge ? "Mostrando todos (clique: só os próximos)" : "Mostrando os próximos 7 dias (clique: todos)",
                mouseX, mouseY, true, () -> dmAllPurge = !dmAllPurge);
        cy += 21;
        if (list.isEmpty()) {
            paragraph(g, "Ninguém nessa situação agora.", COL_DIM);
            return;
        }
        int shown = 0;
        for (Purgeable pu : list) {
            if (shown++ >= 150) break;
            int rowTop = cy;
            long left = pu.end() - now;
            if (visible(rowTop, 24)) {
                boolean hover = mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 24;
                zebra(g, rowTop, 24, hover, false);
                String when = left < 0 ? "vencido " + countdown(left) : countdown(left);
                int color = left < 0 ? 0xFFE06A6A : left < 2 * DAY ? 0xFFE8B04A : COL_TEXT;
                g.drawString(font, fit(pu.site(), bw - 36 - font.width(when)), bx + 11, rowTop + 3, hover ? 0xFFFFE27A : COL_LINK, false);
                g.drawString(font, when, bx + bw - 11 - font.width(when), rowTop + 3, color, false);
                String sub = (pu.p().country == null ? "" : pu.p().country)
                        + (pu.p().purge.realm == null ? "" : "  -  reino " + pu.p().purge.realm)
                        + "  -  " + pu.p().purge.days + " dias";
                g.drawString(font, fit(sub, bw - 24), bx + 11, rowTop + 13, COL_DIM, false);
                final String site = pu.site();
                hits.add(new Hit(bx + 6, rowTop, bw - 12, 24, () -> openPlayer(site), null));
            }
            rowIdx++;
            cy += 24;
        }
        if (list.size() > 150) paragraph(g, "... e mais " + (list.size() - 150) + " (use o filtro dos próximos 7 dias).", COL_DIM);
    }

    private static final class Lk {
        String name;
        int country = -1;
        final List<String> reasons = new ArrayList<>();
        long end = NONE;
    }

    private void dmLocks(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        Map<String, Integer> idx = Relations.nameIndex(d);
        Map<String, Lk> map = new java.util.TreeMap<>();
        java.util.function.BiFunction<String, Integer, Lk> get = (name, country) -> {
            Lk l = map.computeIfAbsent(Relations.norm(name), k -> new Lk());
            if (l.name == null) {
                l.name = country != null && country >= 0 ? d.labels[country] : name;
                l.country = country == null ? -1 : country;
            }
            return l;
        };
        SiteTerritories.WarData w = d.war;
        if (w != null && w.locks != null) {
            for (SiteTerritories.Lock lk : w.locks) {
                if (lk.nation == null) continue;
                long u = parseMs(lk.until);
                if (u != NONE && u < now) continue;
                Lk l = get.apply(lk.nation, idx.get(Relations.norm(lk.nation)));
                l.reasons.add("Foundation".equals(lk.kind) ? "fundação recente (sem ultimatos, alianças ou garantias)" : "definido pela staff");
                if (u != NONE && (l.end == NONE || u > l.end)) l.end = u;
            }
        }
        // beligerantes: países em guerra ainda aberta
        for (SiteTerritories.WarInfo war : Relations.wars(d)) {
            long ended = parseMs(war.ended);
            if (war.ended != null && !war.ended.isBlank() && (ended == NONE || ended <= now)) continue;
            if (war.sides == null) continue;
            for (SiteTerritories.Side side : war.sides) {
                for (int c : Relations.countriesOf(d, side)) get.apply(d.labels[c], c).reasons.add("beligerante: " + (war.name == null ? war.id : war.name));
            }
        }
        // ultimatos pendentes (72 h)
        if (w != null && w.ultimatums != null) {
            for (SiteTerritories.Ultimatum u : w.ultimatums) {
                long sent = parseMs(u.sent);
                if (sent == NONE || u.from == null || u.to == null || sent + 3 * DAY <= now) continue;
                long end = sent + 3 * DAY;
                Lk a = get.apply(u.from, idx.get(Relations.norm(u.from)));
                Lk b = get.apply(u.to, idx.get(Relations.norm(u.to)));
                a.reasons.add("ultimato com " + u.to);
                b.reasons.add("ultimato de " + u.from);
                if (a.end == NONE || end > a.end) a.end = end;
                if (b.end == NONE || end > b.end) b.end = end;
            }
        }
        List<Lk> list = new ArrayList<>(map.values());
        list.removeIf(l -> l.reasons.isEmpty());
        section(g, "Países diplolocked (" + list.size() + ")");
        if (list.isEmpty()) {
            paragraph(g, "Nenhum país diplolocked agora: sem fundação recente, sem guerra aberta, sem ultimato pendente e sem bloqueio da staff.", COL_DIM);
            return;
        }
        for (Lk l : list) {
            List<String> lines = new ArrayList<>();
            for (String r : new java.util.LinkedHashSet<>(l.reasons)) lines.addAll(wrap(r, bw - 40));
            int h = 18 + lines.size() * 10;
            int rowTop = cy;
            if (visible(rowTop, h)) {
                zebra(g, rowTop, h, false, false);
                miniFlag(g, d, l.country, bx + 10, rowTop + 3);
                int c = l.country;
                link(g, fit(l.name, bw - 120), bx + 38, rowTop + 5, c >= 0, mouseX, mouseY, () -> centerCountry(c));
                String when = l.end == NONE ? "" : countdown(l.end - now);
                g.drawString(font, when, bx + bw - 11 - font.width(when), rowTop + 5, COL_TEXT, false);
                int ly = rowTop + 17;
                for (String line : lines) {
                    g.drawString(font, line, bx + 38, ly, COL_DIM, false);
                    ly += 10;
                }
            }
            rowIdx++;
            cy += h;
        }
    }

    // ------------------------------------------------------------ gerar chunks

    private void drawGenPanel(GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        boolean remote = ChunkGen.isRemote(mc);
        boolean running = ChunkGen.state != ChunkGen.State.IDLE;
        int top = header(g, NEUTRAL, "Gerar chunks", remote ? "servidor (teleporte)" : "mundo local (servidor interno)", null, mouseX, mouseY, () -> {
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
        cardH = height - MARGIN * 2 - barH - 8;
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
        Integer pc = d.countryOfPlayer.get(selPlayer.toLowerCase(Locale.ROOT));
        int top = header(g, pc == null ? NEUTRAL : d.rgb[pc], selPlayer, sub, pc == null ? null : flagOf(d, pc), mouseX, mouseY, () -> selPlayer = null);

        // skin
        int figX = bx + 14, figY = top + 6;
        g.fill(figX - 3, figY - 3, figX + 51, figY + 99, 0xFF0F100F);
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
            g.fill(bx + 9, by0, bx + bw - 9, by0 + 18, hover ? 0xFF3C3727 : 0xFF1D1F1E);
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
        int top = header(g, c == null ? NEUTRAL : c.rgb, c == null ? "Sem dono" : c.name,
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
        int top = header(g, NEUTRAL, "Países", "modo manual", null, mouseX, mouseY, this::closePanel);
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
            Spot sp = playerAt(sx, sy);
            if (sp != null) {
                openPlayer(sp.site() != null ? sp.site() : sp.e().account);
                return;
            }
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
        Spot sp = playerAt(sx, sy);
        if (sp != null) {
            openPlayer(sp.e().account);
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
