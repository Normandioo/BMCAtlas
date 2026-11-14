package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
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
import static com.normandioo.bmcatlas.MapFormat.*;
import static com.normandioo.bmcatlas.MapGeometry.*;
import static com.normandioo.bmcatlas.MapWarInfo.*;

/**
 * The side panels of the map screen (country, city, country list, diplomacy, diplomatic overview, war, settings,
 * chunk generator, player card, manual mode) and the state the screen keeps between frames. MapScreen extends this
 * class and supplies the map-side methods the panels need (declared abstract below).
 */
abstract class MapPanels extends MapUi {
    MapPanels(Component title) {
        super(title);
    }

    static final int BTN = 26;       // lado dos botões de ícone
    static final int PW = 272;       // largura do painel
    static final int CARD_W = 244;   // largura do cartão do jogador
    static final int MARGIN = 8;
    static final int TOP = 32;       // altura da barra superior
    static final int OUTLINE_W = 148; // largura do resumo à direita

    static final int MODE_PHYSICAL = MapStyle.MODE_PHYSICAL;
    static final int MODE_POLITICAL = MapStyle.MODE_POLITICAL;
    static final int MODE_PROVINCES = MapStyle.MODE_PROVINCES;
    static final int MODE_DIPLOMACY = MapStyle.MODE_DIPLOMACY;
    static final int MODE_WAR = MapStyle.MODE_WAR;

    static final int TAB_NONE = 0, TAB_INFO = 1, TAB_LIST = 2, TAB_DIPLO = 3, TAB_WAR = 4, TAB_CITY = 5, TAB_GEN = 6, TAB_DIPLOMENU = 7, TAB_SET = 8, TAB_NEWS = 9;

    // O estado da vista persiste entre aberturas da tela.
    static double viewX, viewZ;
    static double scale = Prefs.get().mapScale;
    static boolean viewInit;
    static SiteTerritories.Locality pendingFocus;     // cidade a focar ao abrir (aviso de captura)
    static int mode = Prefs.get().mapMode <= MODE_PROVINCES ? Prefs.get().mapMode : MODE_POLITICAL;
    static int tab = TAB_NONE;
    static String lastWaveKey;     // guerra e mapa da última leitura de cores
    static int listTab;            // aba da lista de países (lista / ranking)
    EditBox searchBox;
    boolean searchOpen;
    int searchX, searchY, searchW, searchH, searchIdx;
    record SearchHit(String label, String kind, Runnable action) {}
    List<SearchHit> searchHits = List.of();
    int toolMode;                  // 0 nenhuma, 1 medir, 2 marcar ponto
    final List<int[]> measurePts = new ArrayList<>();     // route being measured (blocks)
    Prefs.Mark markMenu;           // marked point with its little menu open
    Prefs.Mark renameMark;         // marked point being renamed in the search box
    long searchOpenedAt;           // the key that opens the search must not land in the box
    boolean helpOpen;
    int hoverMX, hoverMY;
    String toastText;
    long toastUntil;
    // smooth zoom: scale runs after zoomTarget, keeping the block under the cursor in place
    static double zoomTarget = scale, zoomSeen = scale;
    double zoomBX, zoomBZ, zoomMX, zoomMY;
    long zoomAt;
    boolean photoMode;
    long photoAt;
    final java.util.LinkedHashMap<String, SiteTerritories.Locality> simCities = new java.util.LinkedHashMap<>();
    SiteTerritories.WarInfo simWar;
    int simVersion = -1;
    boolean simDirty;
    WarScore.Result simScore;
    WarFront simFront;
    int areaVersion = -1;
    double[] countryArea = new double[0];
    double totalArea;
    static int infoTab;            // aba da janela do país
    static final double[] SCROLL = new double[10];
    static double cardScroll;
    static boolean showCities = Prefs.get().showCities;
    static boolean showLimits = Prefs.get().limits;
    static boolean showPlayers = Prefs.get().showPlayers;
    static int dmTab;               // aba do menu diplomático: 0 alianças, 1 NAP, 2 purge, 3 diplolock
    static boolean dmAllPurge;
    static int warTab;              // aba do painel da guerra: 0 lados, 1 cidades, 2 Lei 7
    static int warCityFilter;       // 0 todas, 1 tomadas, 2 em disputa
    static final Set<String> WAR_CLOSED = new HashSet<>();   // países recolhidos na lista de cidades
    static int pulseCity = -1;      // cidade com o destaque pulsante
    static long pulseStart;
    static final long PULSE_MS = 6000;

    // seleção no modo manual (províncias de 32x32 blocos)
    static boolean hasSel;
    static int selPx, selPz;
    // seleção no modo site
    static int selSite = -1;       // província
    static int selCity = -1;       // índice em Data.localities
    static String selPlayer;       // nome no site
    static int dipFocus = -1;      // país em foco no modo diplomacia
    static int dipAlliance = -1;   // aliança escolhida (índice em war.alliances)
    static int warSel = -1;        // guerra escolhida (índice em war.wars)
    static boolean genConfirm;
    static int copiedFrames;

    // cores dos modos diplomacia/guerra
    static int ovSig = Integer.MIN_VALUE;
    static List<Relations.Rel> legend = List.of();
    static Map<String, Integer> zoneColors = Map.of();

    boolean dragging;
    boolean moved;
    double pressX, pressY;

    // painel principal
    int panelX, panelY, panelW, panelH;
    boolean panelVisible;
    /** The news article open beside the News list (its own panel and scroll). */
    int articleX, articleY, articleW, articleH;
    boolean articleVisible;
    static double articleScroll;
    /** The news picture shown full screen, at its original size (its address), or null. */
    static String viewImage;
    int cardX, cardY, cardH;
    boolean cardVisible;

    // altura e posição dos botões da barra (calculadas a cada quadro)
    int barH = 34;
    List<Btn> nav = List.of(), tools = List.of();
    // resumo à direita: recalculado uma vez por segundo
    long outlineAt;
    int outlineVer = -1;
    List<SiteTerritories.WarInfo> outWars = List.of();
    List<Timed> outTruces = List.of(), outUlts = List.of();
    int myWarsN, myAlliesN, myTrucesN, myUltsN, myPlayersN;
    static boolean mineOnly;       // listas abertas pela barra de cima: só o país do jogador

    // cache do modo manual: país -> {somaX, somaZ, n, somaXX, somaZZ, somaXZ} (em províncias)
    int cacheVersion = -1;
    final Map<String, double[]> stats = new HashMap<>();

    // cache da lista do site
    int legendVersion = -1;
    List<Integer> legendOrder = new ArrayList<>();
    static final Map<String, Integer> TEXT_WIDTH = new HashMap<>();

    record Btn(String glyph, boolean active, boolean enabled, String tip, Runnable action, boolean gap) {}

    // ------------------------------------------------------------------ painel

    boolean panelOpen() {
        if (tab == TAB_NONE) return false;
        if (tab == TAB_LIST || tab == TAB_GEN || tab == TAB_SET || tab == TAB_NEWS) return true;
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

    void closePanel() {
        tab = TAB_NONE;
        hasSel = false;
        selCity = -1;
        setSite(-1);
    }

    void setSite(int idx) {
        SiteTerritories.Data d = SiteTerritories.data();
        int old = selSite;
        selSite = idx;
        MapStyle.selectedProvince = idx;
        if (d == null) return;
        setHighlight(d, -1);
        if (old >= 0 && old < d.n) MapData.invalidateRect(d.bounds[old][0], d.bounds[old][1], d.bounds[old][2], d.bounds[old][3]);
        if (idx >= 0 && idx < d.n) MapData.invalidateRect(d.bounds[idx][0], d.bounds[idx][1], d.bounds[idx][2], d.bounds[idx][3]);
    }

    void drawPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (!panelOpen()) return;
        boolean wide = tab == TAB_SET && width >= 480;
        panelW = wide ? Math.min(520, width - 2 * MARGIN) : PW;
        panelX = wide ? (width - panelW) / 2 : MARGIN;
        panelY = TOP + 8;
        panelH = height - panelY - MARGIN;
        if (!wide && panelX + panelW > width - barWLocal() - MARGIN) panelH -= barHLocal() + 8;
        panelVisible = true;
        bx = panelX;
        by = panelY;
        bw = panelW;
        bh = panelH;
        if (tab == TAB_NEWS) NewsArt.nine(g, "panel_frame", 16, 4, panelX, panelY, panelW, panelH);
        else box(g, panelX, panelY, panelW, panelH, COL_PANEL);
        hits.add(new Hit(panelX, panelY, panelW, panelH, null, null));

        switch (tab) {
            case TAB_LIST -> {
                if (siteMode()) drawSiteList(g, mouseX, mouseY);
                else drawManualList(g, mouseX, mouseY);
            }
            case TAB_GEN -> drawGenPanel(g, mouseX, mouseY);
            case TAB_SET -> drawSettingsPanel(g, mouseX, mouseY);
            case TAB_DIPLO -> drawDiploPanel(g, mouseX, mouseY);
            case TAB_DIPLOMENU -> drawDiploMenu(g, mouseX, mouseY);
            case TAB_WAR -> drawWarPanel(g, mouseX, mouseY);
            case TAB_CITY -> drawCityPanel(g, mouseX, mouseY);
            case TAB_NEWS -> drawNewsPanel(g, mouseX, mouseY);
            default -> {
                if (siteMode()) drawCountryPanel(g, mouseX, mouseY);
                else drawManualPanel(g, mouseX, mouseY);
            }
        }
    }

    /** O jogador online (dynmap) que corresponde a este nome do site, ou null. Só vale se o dynmap está respondendo. */
    OnlinePlayers.Entry onlineEntry(SiteTerritories.Data d, String siteName) {
        if (siteName == null || !SiteTerritories.showPlayers() || !OnlinePlayers.live()) return null;
        String low = siteName.toLowerCase(Locale.ROOT);
        SiteTerritories.Player p = d == null ? null : findPlayer(d, siteName);
        for (OnlinePlayers.Entry e : OnlinePlayers.list()) {
            String acc = e.account.toLowerCase(Locale.ROOT);
            if (acc.equals(low) || (p != null && p.ign != null && p.ign.equalsIgnoreCase(e.account))) return e;
            String site = d == null ? null : d.ignToSite.get(acc);
            if (site != null && site.equalsIgnoreCase(siteName)) return e;
        }
        return null;
    }

    /** Nomes clicáveis em fluxo, como "fichas" (usado para a população e as relações). */
    void chips(GuiGraphics g, List<String> names, int mouseX, int mouseY, Consumer<String> click) {
        chips(g, names, mouseX, mouseY, click, false);
    }

    /** Idem; com markOnline, uma bolinha verde marca quem está online agora. */
    void chips(GuiGraphics g, List<String> names, int mouseX, int mouseY, Consumer<String> click, boolean markOnline) {
        SiteTerritories.Data dd = SiteTerritories.data();
        int x = bx + 9, right = bx + bw - 9, rowH = 14;
        int lineY = cy;
        for (String n : names) {
            boolean online = markOnline && onlineEntry(dd, n) != null;
            int w = font.width(n) + 8 + (online ? 7 : 0);
            if (x + w > right) {
                x = bx + 9;
                lineY += rowH + 2;
            }
            boolean vis = visible(lineY, rowH);
            boolean hover = vis && mouseX >= x && mouseX < x + w && mouseY >= lineY && mouseY < lineY + rowH;
            g.fill(x, lineY, x + w, lineY + rowH, hover ? 0xFF3A3E42 : 0xFF2A2D30);
            g.fill(x, lineY, x + w, lineY + 1, COL_GOLD_DIM);
            g.fill(x, lineY + rowH - 1, x + w, lineY + rowH, COL_GOLD_DIM);
            if (online) {
                g.fill(x + 3, lineY + 4, x + 8, lineY + 9, 0xFF0E0C08);
                g.fill(x + 4, lineY + 5, x + 7, lineY + 8, 0xFF4CD964);
            }
            g.drawString(font, n, x + 4 + (online ? 7 : 0), lineY + 3, hover ? 0xFFDDE7EE : COL_TEXT, false);
            if (vis) hits.add(new Hit(x, lineY, w, rowH, () -> click.accept(n), null));
            x += w + 4;
        }
        cy = lineY + rowH + 4;
    }

    static String join(List<String> list) {
        return list == null || list.isEmpty() ? null : String.join(", ", list);
    }

    static String pretty(String s) {
        return s == null ? null : SiteTerritories.splitCamel(s);
    }

    // ----------------------------------------------------------- painel do país

    void drawCountryPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (selSite < 0 || selSite >= d.n) return;
        int c = d.provCountry[selSite];
        Map<String, String> info = d.info[c];
        int top = header(g, d.rgb[c], d.names[c], info.get("type"), flagOf(d, c), mouseX, mouseY, this::closePanel);
        top = tabStrip(g, top, new String[]{"General", "Localities", "Diplomacy", "Population"}, infoTab, mouseX, mouseY, i -> {
            infoTab = i;
            SCROLL[TAB_INFO] = 0;
        });

        beginScroll(g, top, SCROLL[TAB_INFO], 0);
        if (infoTab == 0) {
        refreshAreas(d);
        int cityCount = 0;
        for (SiteTerritories.Locality l : d.localities) if (l.countryIdx == c) cityCount++;
        statRow(g, new String[][]{{String.valueOf(d.provinceCount[c]), "Provinces"}, {String.valueOf(cityCount), "Cities"}, {String.valueOf(d.population[c]), "Citizens"}, {areaPct(c), "Of the map"}});
        int bxx = bx + 9;
        textButton(g, bxx, cy, 124, 16, "View relations", mouseX, mouseY, true, () -> {
            dipFocus = c;
            dipAlliance = -1;
            mode = MODE_DIPLOMACY;
        });
        textButton(g, bxx + 130, cy, bw - 18 - 130, 16, "Center", mouseX, mouseY, true, () -> selectCountry(c));
        cy += 20;

        section(g, "INFORMATION");
        String official = info.get("officialName");
        kv(g, "Official name", official == null ? d.names[c] : official);
        kv(g, "Type", info.get("type"));
        kv(g, "Founded", info.get("formed"));
        kv(g, "Government", info.get("government"));
        kv(g, "Leader", info.get("leader"));
        kv(g, "Capital", info.get("capital"));
        kv(g, "Ethnicity", info.get("ethnicity"));
        kv(g, "Demonym", info.get("demonym"));

        section(g, "TERRITORY");
        kv(g, "Provinces", String.valueOf(d.provinceCount[c]));
        kv(g, "Selected", d.provLabel[selSite]);
        int cities = 0;
        for (SiteTerritories.Locality l : d.localities) if (l.countryIdx == c) cities++;
        kv(g, "Localities", String.valueOf(cities));
        kv(g, "Population", String.valueOf(d.population[c]));
        kv(g, "Purge deadline", countryPurge(d, c));
        }

        List<SiteTerritories.Locality> places = placesOf(d, c);
        if (infoTab == 1 && places.isEmpty()) paragraph(g, "No localities recorded.", COL_DIM);
        if (infoTab == 1 && !places.isEmpty()) {
            section(g, "LOCALITIES (" + places.size() + ")  -  click to go");
            for (SiteTerritories.Locality l : places) {
                int rowTop = cy;
                if (visible(rowTop, 16)) {
                    boolean isSel = l.idx == selCity;
                    boolean hover = mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 16;
                    if (isSel) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + 16, 0x508F959A);
                    else if (hover) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + 16, 0x408F959A);
                    else if ((rowIdx & 1) == 0) g.fill(bx + 6, rowTop, bx + bw - 6, rowTop + 16, 0x16FFFFFF);
                    RemoteImages.Img ic = cityImage(l, 14);
                    if (ic != null) {
                        g.blit(RenderPipelines.GUI_TEXTURED, ic.id(), bx + 10 + (14 - ic.w()) / 2, rowTop + 1 + (14 - ic.h()) / 2, 0f, 0f, ic.w(), ic.h(), ic.w(), ic.h(), ic.w(), ic.h());
                    } else {
                        g.fill(bx + 14, rowTop + 5, bx + 20, rowTop + 11, lighten(d.rgb[c], 1.3f));
                    }
                    String kind = pretty(l.kind);
                    int kw = kind == null ? 0 : Math.min(font.width(kind), 90);
                    drawFit(g, pretty(l.name), bw - 66 - kw, bx + 30, rowTop + 4, isSel ? 0xFFFFF2B0 : COL_TEXT, false);
                    if (kind != null) drawFit(g, kind, 90, bx + bw - 12 - kw, rowTop + 4, COL_DIM, false);
                    hits.add(new Hit(bx + 6, rowTop, bw - 12, 16, () -> focusCity(l), "Go to " + pretty(l.name)));
                }
                rowIdx++;
                cy += 16;
            }
        }

        SiteTerritories.Diplomacy dip = d.diplomacy[c];
        if (infoTab == 2 && dip == null) paragraph(g, "No relations recorded.", COL_DIM);
        if (infoTab == 2 && dip != null) {
            section(g, "DIPLOMACY");
            kv(g, "Overlord", join(dip.overlord));
            kv(g, "Subjects", join(dip.subject));
            kv(g, "Allies", join(dip.allies));
            kv(g, "Pact (NAP)", join(dip.nap));
            kv(g, "Alliances", join(dip.alliances));
            if (dip.wars != null && !dip.wars.isEmpty()) {
                List<String> wars = new ArrayList<>();
                for (SiteTerritories.WarRef w : dip.wars) {
                    String side = "attacker".equals(w.side) ? "attacker" : "defender".equals(w.side) ? "defender" : w.side;
                    wars.add(w.name + (side == null ? "" : " (" + side + ")"));
                }
                kv(g, "Wars", join(wars));
            }
        }

        List<String> people = d.people[c];
        if (infoTab == 3 && (people == null || people.isEmpty())) paragraph(g, "No players recorded.", COL_DIM);
        if (infoTab == 3 && people != null && !people.isEmpty()) {
            section(g, "POPULATION (" + people.size() + ")  -  click a name");
            chips(g, people.size() > 80 ? people.subList(0, 80) : people, mouseX, mouseY, this::openPlayer, true);
        }
        SCROLL[TAB_INFO] = endScroll(g, SCROLL[TAB_INFO]);
    }

    int placesCountry = -2, placesVersion = -1;
    List<SiteTerritories.Locality> places = List.of();

    /** Localidades do país, das maiores para as menores (guardado até mudar o país ou os dados). */
    List<SiteTerritories.Locality> placesOf(SiteTerritories.Data d, int country) {
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

    void openPlayer(String name) {
        selPlayer = name;
        cardScroll = 0;
    }

    void selectCountry(int country) {
        tab = TAB_INFO;
        centerCountry(country);
        SCROLL[TAB_INFO] = 0;
    }

    /** Seleciona o país (sua maior província) e leva a câmera até ele, sem trocar de painel. */
    void centerCountry(int country) {
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
        setHighlight(d, country);
        // enquadra o país inteiro, com a câmera na capital
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < d.n; i++) {
            if (d.provCountry[i] != country) continue;
            int[] bb = d.bounds[i];
            minX = Math.min(minX, bb[0]);
            minZ = Math.min(minZ, bb[1]);
            maxX = Math.max(maxX, bb[2]);
            maxZ = Math.max(maxZ, bb[3]);
        }
        double cxm = (minX + maxX) / 2.0, czm = (minZ + maxZ) / 2.0;
        for (SiteTerritories.Locality l : d.localities) {
            if (l.countryIdx == country && WarFront.isCapital(l)) {
                cxm = l.x + 0.5;
                czm = l.z + 0.5;
                break;
            }
        }
        int panelRight = tab != TAB_NONE ? MARGIN + PW + 8 : 0;
        double halfW = Math.max(60, Math.min(width / 2.0 - panelRight, width / 2.0) - 24);
        double halfH = Math.max(60, (height - TOP) / 2.0 - 30);
        double dx = Math.max(8, Math.max(cxm - minX, maxX + 1 - cxm)), dz = Math.max(8, Math.max(czm - minZ, maxZ + 1 - czm));
        double fit = Math.min(halfW / dx, halfH / dz);
        scale = Math.max(0.03, Math.min(8.0, fit));
        viewX = cxm;
        viewZ = czm;
    }

    /** Contorno dourado em volta do país escolhido (-1 apaga). */
    static void setHighlight(SiteTerritories.Data d, int country) {
        if (MapStyle.highlightCountry == country) return;
        int old = MapStyle.highlightCountry;
        MapStyle.highlightCountry = country;
        if (d == null) return;
        for (int c : new int[]{old, country}) {
            if (c < 0) continue;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < d.n; i++) {
                if (d.provCountry[i] != c) continue;
                int[] bb = d.bounds[i];
                minX = Math.min(minX, bb[0]);
                minZ = Math.min(minZ, bb[1]);
                maxX = Math.max(maxX, bb[2]);
                maxZ = Math.max(maxZ, bb[3]);
            }
            if (minX != Integer.MAX_VALUE) MapData.invalidateRect(minX, minZ, maxX, maxZ);
        }
    }

    /** Leva a câmera até a cidade e a marca (ícone e limite em destaque); o painel atual continua aberto. */

    // ------------------------------------------------------------ país e histórico

    void statRow(GuiGraphics g, String[][] cells) {
        int n = cells.length, gap = 2, w = (bw - 18 - gap * (n - 1)) / n, y = cy;
        for (int i = 0; i < n; i++) {
            int x = bx + 9 + i * (w + gap);
            g.fill(x, y, x + w, y + 25, 0xFF0A0B08);
            g.fill(x + 1, y + 1, x + w - 1, y + 24, 0xFF131517);
            String v = fit(cells[i][0], w - 4), l = fit(cells[i][1], w - 4);
            g.drawString(font, v, x + (w - font.width(v)) / 2, y + 4, 0xFFECEBE4, false);
            g.drawString(font, l, x + (w - font.width(l)) / 2, y + 14, COL_DIM, false);
        }
        cy += 29;
    }

    void refreshAreas(SiteTerritories.Data d) {
        if (areaVersion == SiteTerritories.version() && countryArea.length == d.ids.length) return;
        areaVersion = SiteTerritories.version();
        countryArea = new double[d.ids.length];
        totalArea = 0;
        for (int p = 0; p < d.n; p++) {
            double area = 0;
            for (int[] r : d.rings[p]) {
                int len = r.length / 2;
                double sum = 0;
                for (int a = 0, c = len - 1; a < len; c = a++) sum += (double) r[2 * c] * r[2 * a + 1] - (double) r[2 * a] * r[2 * c + 1];
                area += Math.abs(sum) / 2;
            }
            countryArea[d.provCountry[p]] += area;
            totalArea += area;
        }
    }

    String areaPct(int c) {
        if (totalArea <= 0) return "-";
        return String.format(Locale.ROOT, "%.1f%%", 100 * countryArea[c] / totalArea);
    }

    /** O prazo de purge do país, como o site informa nos cidadãos dele. */
    String countryPurge(SiteTerritories.Data d, int c) {
        if (d.people[c] == null) return null;
        for (String name : d.people[c]) {
            SiteTerritories.Player p = findPlayer(d, name);
            if (p != null && p.purge != null && p.purge.days > 0) return p.purge.days + " days";
        }
        return null;
    }


    void eventRow(GuiGraphics g, String text, String right) {
        int rowTop = cy;
        zebra(g, rowTop, 14, false, false);
        int rw = font.width(right);
        drawFit(g, text, bw - 28 - rw, bx + 11, rowTop + 3, COL_TEXT, false);
        g.drawString(font, right, bx + bw - 11 - rw, rowTop + 3, COL_DIM, false);
        rowIdx++;
        cy += 14;
    }

    void drawWorldHistory(GuiGraphics g, SiteTerritories.Data d) {
        section(g, "World history");
        List<String[]> list = new ArrayList<>();
        if (d.war != null && d.war.alliances != null) {
            for (SiteTerritories.AllianceInfo a : d.war.alliances) if (a.formed != null) list.add(new String[]{"Alliance: " + a.name, a.formed});
        }
        for (int c = 0; c < d.ids.length; c++) {
            String formed = d.info[c] == null ? null : d.info[c].get("formed");
            if (formed != null && d.provinceCount[c] > 0) list.add(new String[]{"Founded: " + d.labels[c], formed});
        }
        list.sort((a, b) -> Long.compare(yearOf(b[1]), yearOf(a[1])));
        for (String[] row : list.subList(0, Math.min(40, list.size()))) eventRow(g, row[0], row[1]);
        if (list.isEmpty()) paragraph(g, "The site records no historical dates.", COL_DIM);
    }

    void drawRanking(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        refreshAreas(d);
        section(g, "By map area");
        List<Integer> order = new ArrayList<>();
        for (int c = 0; c < d.ids.length; c++) if (d.provinceCount[c] > 0) order.add(c);
        order.sort((a, b) -> Double.compare(countryArea[b], countryArea[a]));
        int place = 1;
        for (int c : order) {
            final int country = c;
            int rowTop = cy;
            boolean hover = visible(rowTop, 16) && mouseX >= bx + 4 && mouseX < bx + bw - 4 && mouseY >= rowTop && mouseY < rowTop + 16;
            zebra(g, rowTop, 16, hover, false);
            miniFlag(g, d, c, bx + 9, rowTop + 2);
            String right = areaPct(c);
            drawFit(g, place + ". " + d.labels[c], bw - 50 - font.width(right), bx + 36, rowTop + 4, COL_TEXT, false);
            g.drawString(font, right, bx + bw - 11 - font.width(right), rowTop + 4, COL_DIM, false);
            if (visible(rowTop, 16)) hits.add(new Hit(bx + 4, rowTop, bw - 8, 16, () -> selectCountry(country), null));
            rowIdx++;
            cy += 16;
            place++;
        }
    }

    void focusCity(SiteTerritories.Locality l) {
        selCity = l.idx;
        pulseCity = l.idx;
        pulseStart = System.currentTimeMillis();
        viewX = l.x + 0.5;
        viewZ = l.z + 0.5;
        if (scale < 0.7) scale = 0.7;
    }

    // ----------------------------------------------------------- painel da cidade

    void drawCityPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (selCity < 0 || selCity >= d.localities.size()) return;
        SiteTerritories.Locality l = d.localities.get(selCity);
        int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] : NEUTRAL;
        String subtitle = pretty(l.kind) + (l.development == null ? "" : "  -  " + l.development);
        int top = header(g, rgb, pretty(l.name), subtitle, flagOf(d, l.countryIdx), mouseX, mouseY, this::closePanel);

        beginScroll(g, top, SCROLL[TAB_CITY], 0);
        textButton(g, bx + 9, cy, bw - 18, 16, "Center on the map", mouseX, mouseY, true, () -> {
            viewX = l.x;
            viewZ = l.z;
            if (scale < 0.6) scale = 0.6;
        });
        cy += 20;
        section(g, "INFORMATION");
        kv(g, "Name", pretty(l.name));
        kv(g, "Type", pretty(l.kind));
        kv(g, "Development", l.development);
        if (l.countryIdx >= 0) {
            kvLink(g, "Country", d.labels[l.countryIdx], mouseX, mouseY, () -> selectCountry(l.countryIdx));
        } else {
            kv(g, "Country", l.country);
        }
        if (l.lord != null && !l.lord.isBlank()) kvLink(g, "Lord", l.lord, mouseX, mouseY, () -> openPlayer(l.lord));
        kv(g, "Ethnicity", l.ethnicity);
        kv(g, "Designations", join(l.designations));
        kv(g, "Coordinates", l.x + ", " + l.z);
        kv(g, "Capture limit", zoneOf(d, l) != null ? "yes (golden outline on the map)" : "not set");
        SCROLL[TAB_CITY] = endScroll(g, SCROLL[TAB_CITY]);
    }

    // ------------------------------------------------------------ lista de países

    void drawSiteList(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        int top = header(g, NEUTRAL, "Countries", d.ids.length + " countries  -  " + d.n + " provinces", null, mouseX, mouseY, this::closePanel);
        if (legendVersion != SiteTerritories.version()) {
            legendVersion = SiteTerritories.version();
            legendOrder = new ArrayList<>();
            for (int i = 0; i < d.ids.length; i++) if (d.provinceCount[i] > 0) legendOrder.add(i);
            legendOrder.sort((a, b) -> Integer.compare(d.provinceCount[b], d.provinceCount[a]));
        }
        top = tabStrip(g, top, new String[]{"Countries", "Ranking"}, listTab, mouseX, mouseY, i -> {
            listTab = i;
            SCROLL[TAB_LIST] = 0;
        });
        beginScroll(g, top, SCROLL[TAB_LIST], 0);
        if (listTab == 1) {
            drawRanking(g, d, mouseX, mouseY);
            SCROLL[TAB_LIST] = endScroll(g, SCROLL[TAB_LIST]);
            return;
        }
        for (int idx : legendOrder) {
            int rowTop = cy;
            boolean vis = visible(rowTop, 16);
            if (vis) {
                boolean hover = mouseX >= bx + 4 && mouseX < bx + bw - 4 && mouseY >= rowTop && mouseY < rowTop + 16;
                if (hover) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x408F959A);
                else if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x16FFFFFF);
                g.fill(bx + 8, rowTop + 2, bx + 30, rowTop + 14, 0xFF05080C);
                RemoteImages.Img flag = RemoteImages.flag(flagOf(d, idx));
                if (flag != null) drawImage(g, flag, bx + 9, rowTop + 3, 20, 10);
                else g.fill(bx + 9, rowTop + 3, bx + 29, rowTop + 13, d.rgb[idx] | 0xFF000000);
                String count = String.valueOf(d.provinceCount[idx]);
                drawFit(g, d.labels[idx], bw - 84, bx + 36, rowTop + 4, COL_TEXT, false);
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

    void drawDiploPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        String subtitle = dipFocus >= 0 ? "Relations of " + d.labels[dipFocus]
                : dipAlliance >= 0 ? "Chosen alliance" : "Blocs and alliances";
        int rgb = dipFocus >= 0 ? d.rgb[dipFocus] : NEUTRAL;
        int top = header(g, rgb, "Diplomacy", subtitle, dipFocus >= 0 ? flagOf(d, dipFocus) : null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            dipFocus = -1;
            dipAlliance = -1;
        });
        beginScroll(g, top, SCROLL[TAB_DIPLO], 0);

        if (dipFocus < 0) paragraph(g, "Click a country on the map to see its relations, or pick an alliance below.", COL_DIM);

        if (dipFocus >= 0) {
            for (Relations.Rel rel : legend) {
                if ("Selected".equals(rel.label())) continue;
                List<String> names = new ArrayList<>();
                for (int c : rel.countries()) names.add(d.labels[c]);
                if (names.isEmpty()) continue;
                section(g, rel.label() + " (" + names.size() + ")");
                chips(g, names, mouseX, mouseY, n -> {
                    Integer ci = Relations.nameIndex(d).get(Relations.norm(n));
                    if (ci != null) {
                        dipFocus = ci;
                        dipAlliance = -1;
                    }
                });
            }
            if (legend.size() <= 1) paragraph(g, "No relations recorded on the site for this country.", COL_DIM);
        }

        List<SiteTerritories.AllianceInfo> alliances = d.war == null || d.war.alliances == null ? List.of() : d.war.alliances;
        section(g, "ALLIANCES (" + alliances.size() + ")");
        if (alliances.isEmpty()) paragraph(g, "The site lists no alliances.", COL_DIM);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < alliances.size(); i++) {
                SiteTerritories.AllianceInfo a = alliances.get(i);
                if (Relations.isBilateral(a) != (pass == 1)) continue;
                int rowTop = cy;
                boolean vis = visible(rowTop, 16);
                if (vis) {
                    boolean sel = i == dipAlliance;
                    boolean hover = mouseX >= bx + 4 && mouseX < bx + bw - 4 && mouseY >= rowTop && mouseY < rowTop + 16;
                    if (sel) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x508F959A);
                    else if (hover) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x408F959A);
                    else if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 16, 0x16FFFFFF);
                    g.fill(bx + 8, rowTop + 2, bx + 30, rowTop + 14, 0xFF05080C);
                    RemoteImages.Img flag = a.flag == null ? null : RemoteImages.flag(SiteTerritories.assetUrl(a.flag, true));
                    if (flag != null) drawImage(g, flag, bx + 9, rowTop + 3, 20, 10);
                    else g.fill(bx + 9, rowTop + 3, bx + 29, rowTop + 13, 0xFF000000 | Relations.blocColor(a, i));
                    String count = String.valueOf(Relations.countriesOf(d, a).size());
                    drawFit(g, a.name == null ? a.id : a.name, bw - 84, bx + 36, rowTop + 4, COL_TEXT, false);
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
            section(g, "ALLIANCE");
            kv(g, "Name", a.name);
            kv(g, "Official name", a.officialName);
            kv(g, "Formed", a.formed);
            if (a.members != null) {
                for (SiteTerritories.Member m : a.members) {
                    kv(g, m.name == null ? "Member" : m.name, m.note == null ? "member" : m.note);
                }
            }
            kv(g, "Notes", a.notes);
        }

        SiteTerritories.WarData w = d.war;
        if (w != null) {
            if (w.truces != null && !w.truces.isEmpty()) {
                section(g, "TRUCES");
                for (SiteTerritories.Truce t : w.truces) kv(g, t.a + " / " + t.b, t.until == null ? "" : "until " + t.until);
            }
            if (w.naps != null && !w.naps.isEmpty()) {
                section(g, "NON-AGGRESSION PACTS");
                for (SiteTerritories.Nap n : w.naps) kv(g, n.a + " / " + n.b, n.until == null ? "" : "until " + n.until);
            }
            if (w.ultimatums != null && !w.ultimatums.isEmpty()) {
                section(g, "ULTIMATUMS");
                for (SiteTerritories.Ultimatum u : w.ultimatums) kv(g, u.from + " > " + u.to, u.sent);
            }
        }
        drawWorldHistory(g, d);
        SCROLL[TAB_DIPLO] = endScroll(g, SCROLL[TAB_DIPLO]);
    }


    // -- painel

    /** Barra de pesos por lado, com a porcentagem dentro de cada trecho largo o bastante. */
    void sideBar(GuiGraphics g, SiteTerritories.WarInfo war, double[] weights, int x, int y, int w) {
        double total = 0;
        for (double v : weights) total += v;
        g.fill(x - 1, y - 1, x + w + 1, y + 13, 0xFF0E0C08);
        if (total <= 0) {
            g.fill(x, y, x + w, y + 12, 0xFF2A2D30);
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

    static double sumTo(double[] v, int i) {
        double t = 0;
        for (int k = 0; k <= i; k++) t += v[k];
        return t;
    }

    /** What the player opened or folded in the war menu, by section key; a section nobody touched uses its own default. */
    static final Map<String, Boolean> FOLD = new HashMap<>();

    /** A section header that folds: click to hide or show what is under it. Returns true when the content must be drawn. */
    boolean foldable(GuiGraphics g, String key, String title, boolean open0, int mouseX, int mouseY) {
        boolean open = FOLD.getOrDefault(key, open0);
        cy += 4;
        int y = cy;
        boolean vis = visible(y, 13);
        boolean hover = vis && mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= y && mouseY < y + 13;
        g.fill(bx + 6, y, bx + bw - 6, y + 13, hover ? 0xFF22262A : 0xFF18191B);
        g.fill(bx + 6, y, bx + bw - 6, y + 1, 0xFF050605);
        g.fill(bx + 6, y + 12, bx + bw - 6, y + 13, 0xFF3E4245);
        // a small button: gold with a dark "+" when folded, dark with a gold "-" when open
        int bx0 = bx + 9, by0 = y + 2, dx = bx0 + 4, dy = by0 + 4;
        int sign = open ? COL_GOLD : 0xFF14130F;
        g.fill(bx0, by0, bx0 + 9, by0 + 9, COL_GOLD);
        g.fill(bx0 + 1, by0 + 1, bx0 + 8, by0 + 8, open ? 0xFF3A2F10 : 0xFFE0A820);
        g.fill(dx - 2, dy, dx + 3, dy + 1, sign);
        if (!open) g.fill(dx, dy - 2, dx + 1, dy + 3, sign);
        String shown = fitSpaced(title.toUpperCase(Locale.ROOT), bw - 40);
        spaced(g, shown, bx + 24, y + 3, open ? COL_GOLD : 0xFFFFF2B0, false);
        int ruleX = bx + 24 + spacedWidth(shown) + 6;
        if (ruleX < bx + bw - 12) g.fill(ruleX, y + 6, bx + bw - 12, y + 7, 0xFF3E4245);
        if (vis) hits.add(new Hit(bx + 6, y, bw - 12, 13, () -> FOLD.put(key, !FOLD.getOrDefault(key, open0)), open ? "Fold" : "Unfold"));
        cy += 17;
        rowIdx = 0;
        return open;
    }

    void drawWarPanel(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        SiteTerritories.WarInfo sel = viewedWar(d);
        int running = activeWarCount();
        String subtitle = sel == null ? running + " in progress, " + (wars.size() - running) + " ended"
                : sel.sides != null && sel.sides.size() >= 2 ? sideName(sel, 0) + " vs " + sideName(sel, 1) : "";
        headRed = true;
        int top = header(g, NEUTRAL, sel == null ? "Wars" : warName(sel), subtitle.isEmpty() ? null : subtitle, null, mouseX, mouseY, () -> tab = TAB_NONE);   // fechar o menu mantém a guerra escolhida no mapa
        if (sel == null) {
            beginScroll(g, top, SCROLL[TAB_WAR], 0);
            warList(g, d, wars, mouseX, mouseY);
        } else {
            textButtonFixed(g, bx + 8, top, bw - 16, 15, "< All wars", mouseX, mouseY, () -> {
                warSel = -1;
                SCROLL[TAB_WAR] = 0;
            });
            top += 19;
            top = tabStrip(g, top, new String[]{"Sides", "Cities", "Law 7", "What if?"}, warTab, mouseX, mouseY, i -> {
                warTab = i;
                SCROLL[TAB_WAR] = 0;
            });
            beginScroll(g, top, SCROLL[TAB_WAR], 0);
            if (warTab != 3) warSummary(g, d, sel, mouseX, mouseY);
            switch (warTab) {
                case 0 -> warSides(g, d, sel, mouseX, mouseY);
                case 1 -> warCities(g, d, sel, mouseX, mouseY);
                case 3 -> warSim(g, d, sel, mouseX, mouseY);
                default -> warLaw7(g, d, sel, mouseX, mouseY);
            }
        }
        SCROLL[TAB_WAR] = endScroll(g, SCROLL[TAB_WAR]);
    }

    void warList(GuiGraphics g, SiteTerritories.Data d, List<SiteTerritories.WarInfo> wars, int mouseX, int mouseY) {
        mineToggle(g, d, mouseX, mouseY);
        int me = mineFilter(d);
        if (me >= 0) {
            boolean any = false;
            for (SiteTerritories.WarInfo w : wars) if (warInvolves(d, w, me)) any = true;
            if (!any) {
                paragraph(g, "Your country is not at war.", COL_DIM);
                return;
            }
        }
        if (wars.isEmpty()) {
            paragraph(g, "The site records no war right now. When there is one it shows up here: scoreboard, sides, taken cities, fronts and capitulation.", COL_DIM);
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < wars.size(); i++) if (warRunning(wars.get(i)) == (pass == 0) && (me < 0 || warInvolves(d, wars.get(i), me))) idxs.add(i);
            if (idxs.isEmpty()) continue;
            section(g, (pass == 0 ? "In progress" : "Ended") + " (" + idxs.size() + ")");
            for (int i : idxs) warCard(g, d, wars.get(i), i, mouseX, mouseY);
        }
    }

    /** Uma guerra na lista: nome, estado, barra de pontos e datas. */
    void warCard(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int idx, int mouseX, int mouseY) {
        int h = 42, x = bx + 8, wd = bw - 16;
        int y = cy;
        boolean vis = visible(y, h);
        boolean hover = vis && mouseX >= x && mouseX < x + wd && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + wd, y + h, hover ? 0xFF2A2820 : 0xFF181A18);
        g.fill(x, y, x + wd, y + 1, 0xFF050605);
        g.fill(x, y + h - 1, x + wd, y + h, 0xFF3E4245);
        boolean run = warRunning(w);
        String tag = !run ? "ENDED" : w.live ? "LIVE" : "ACTIVE";
        int tagCol = !run ? COL_DIM : COL_WAR;
        drawFit(g, warName(w), wd - 14 - font.width(tag) - 6, x + 6, y + 4, hover ? 0xFFFFFFFF : COL_TEXT, false);
        g.drawString(font, tag, x + wd - 6 - font.width(tag), y + 4, tagCol, false);
        if (w.sides != null && w.sides.size() >= 2) {
            WarScore.Result r = WarScore.of(d, w);
            sideBar(g, w, r.points().clone(), x + 6, y + 16, wd - 12);
        }
        long st = parseMs(w.started), en = parseMs(w.ended);
        long now = System.currentTimeMillis();
        String line = st != NONE ? "started " + countdown(st - now) : "";
        if (en != NONE) line += (line.isEmpty() ? "" : "   ") + (en <= now ? "ended " : "ends ") + countdown(en - now);
        if (!line.isEmpty()) drawFit(g, line, wd - 12, x + 6, y + 31, COL_DIM, false);
        if (vis) hits.add(new Hit(x, y, wd, h, () -> {
            warSel = idx;
            warTab = 0;
            SCROLL[TAB_WAR] = 0;
        }, null));
        cy += h + 3;
    }

    /** Placar, estado e datas, no topo de qualquer aba. */
    void warSummary(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        boolean run = warRunning(w);
        long now = System.currentTimeMillis();
        long st = parseMs(w.started), en = parseMs(w.ended);
        int n = w.sides == null ? 0 : w.sides.size();
        if (foldable(g, "sb", "Scoreboard", true, mouseX, mouseY) && n >= 2) {
            WarScore.Result r = WarScore.of(d, w);
            boolean any = r.total() > 0;
            if (n == 2) warTugHeader(g, d, w, r);
            g.drawString(font, "Points held (Law 7.9)", bx + 9, cy, COL_DIM, false);
            cy += 11;
            sideBar(g, w, r.points().clone(), bx + 9, cy, bw - 18);
            if (n == 2) g.fill(bx + bw / 2, cy - 3, bx + bw / 2 + 1, cy + 15, 0xFFECEBE4);
            cy += 17;
            String verdict = !any ? "No city values recorded" : r.even() ? "Balanced: no side ahead" : sideName(w, r.leader()) + " ahead";
            drawFit(g, verdict, bw - 18, bx + 9, cy, !any || r.even() ? COL_DIM : ctlColor(w, r.leader()), false);
            cy += 12;
            double[] caps = new double[n];
            int taken = 0;
            for (WarScore.Holding h : r.holdings()) {
                if (h.taken()) {
                    caps[h.holder()]++;
                    taken++;
                }
            }
            g.drawString(font, "Cities taken from the enemy", bx + 9, cy, COL_DIM, false);
            cy += 11;
            sideBar(g, w, caps, bx + 9, cy, bw - 18);
            cy += 17;
            if (taken == 0) {
                g.drawString(font, "No city taken yet", bx + 9, cy, COL_DIM, false);
                cy += 12;
            }
            for (int i = 0; i < n; i++) {
                int color = ctlColor(w, i);
                g.fill(bx + 9, cy + 1, bx + 17, cy + 9, 0xFF0E0C08);
                g.fill(bx + 10, cy + 2, bx + 16, cy + 8, color);
                String tail = (any ? Math.round(r.share(i) * 100) + "%  " + pts(r.points()[i]) + " pts" : "") + "   " + (int) caps[i] + " taken";
                int tw = font.width(tail);
                drawFit(g, sideName(w, i), bw - 40 - tw, bx + 21, cy, COL_TEXT, false);
                g.drawString(font, tail, bx + bw - 10 - tw, cy, COL_DIM, false);
                cy += 11;
            }
            cy += 3;
        }
        if (foldable(g, "state", "State", true, mouseX, mouseY)) {
            kv(g, "Status", !run ? "Ended" : w.live ? "In progress (live)" : "In progress");
            kv(g, "Type", "Total War".equalsIgnoreCase(w.type) ? "Total war" : w.type);
            kv(g, "Start", st == NONE ? null : dateText(st) + "  (" + countdown(st - now) + ")");
            if (en != NONE) {
                kv(g, run ? "Ends" : "End", dateText(en) + "  (" + countdown(en - now) + ")");
                long truce = en + 3 * DAY;
                if (!run && truce > now) kv(g, "Truce until", dateText(truce) + "  (" + countdown(truce - now) + ")");
            }
            kv(g, "Theater", w.theater == null || w.theater.isBlank() ? "Unrestricted (all belligerent cities)" : "Restricted: " + w.theater);
            kv(g, "Source", LiveControl.isLive(w) ? "Live dynmap (city markers)" : w.live ? "Live war; waiting for dynmap" : "Site city list");
            kv(g, "Notes", w.notes);
        }
    }

    void warSides(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        textButton(g, bx + 9, cy, bw - 18, 16, "Simulate a city falling", mouseX, mouseY, true, () -> {
            warTab = 3;
            SCROLL[TAB_WAR] = 0;
        });
        cy += 20;
        if (w.sides == null) return;
        WarScore.Result r = WarScore.of(d, w);
        WarFront f = viewedFront(d);
        Map<String, WarFront.Score> scoreBy = new HashMap<>();
        if (f != null) for (WarFront.Score s : f.scores()) scoreBy.put(s.nation().toLowerCase(Locale.ROOT), s);
        for (int i = 0; i < w.sides.size(); i++) {
            SiteTerritories.Side side = w.sides.get(i);
            boolean openSide = foldable(g, "side" + i, sideName(w, i), true, mouseX, mouseY);
            int color = ctlColor(w, i);
            g.fill(bx + bw - 20, cy - 14, bx + bw - 10, cy - 5, 0xFF0E0C08);
            g.fill(bx + bw - 19, cy - 13, bx + bw - 11, cy - 6, color);
            if (!openSide) continue;
            g.drawString(font, pts(r.points()[i]) + " pts  (" + Math.round(r.share(i) * 100) + "%)", bx + 9, cy, COL_TEXT, false);
            cy += 13;
            if (side.members == null) continue;
            for (SiteTerritories.Member m : side.members) {
                if (m.name != null) {
                    drawFit(g, stripAt(m.name) + (m.note != null && !m.note.isBlank() ? "  (" + m.note + ")" : ""), bw - 20, bx + 9, cy, COL_LINK, false);
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
                    String tail = own == 0 ? "" : held + "/" + own + " cities";
                    WarFront.Score sc = scoreBy.get(c.toLowerCase(Locale.ROOT));
                    if (sc != null && sc.capitulated()) tail = "CAPITULOU";
                    int tw = font.width(tail);
                    drawFit(g, ci >= 0 ? d.labels[ci] : c, bw - 60 - tw, bx + 36, rowTop + 3, hover ? 0xFFDDE7EE : COL_TEXT, false);
                    g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 3, sc != null && sc.capitulated() ? COL_WAR : COL_DIM, false);
                    final int cc = ci;
                    if (vis && ci >= 0) hits.add(new Hit(bx + 6, rowTop, bw - 12, 14, () -> selectCountry(cc), null));
                    cy += 14;
                }
            }
            cy += 2;
        }
    }

    void warCities(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        if (w.sides == null) return;
        WarScore.Result r = WarScore.of(d, w);
        WarFront f = viewedFront(d);
        int boxW = (bw - 16 - 6) / 3;
        String[] labels = {"All", "Taken", "Contested"};
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

        {
            // cidades tomadas, por lado que as segura
            List<WarScore.Holding> taken = new ArrayList<>();
            for (WarScore.Holding h : r.holdings()) if (h.taken()) taken.add(h);
            if (warCityFilter != 2 && foldable(g, "taken", "Taken from the enemy (" + taken.size() + ")", true, mouseX, mouseY)) {
                if (taken.isEmpty()) paragraph(g, "No city changed sides.", COL_DIM);
                for (int s = 0; s < w.sides.size(); s++) {
                    List<WarScore.Holding> mine = new ArrayList<>();
                    for (WarScore.Holding h : taken) if (h.holder() == s) mine.add(h);
                    if (mine.isEmpty()) continue;
                    mine.sort(BY_VALUE);
                    g.fill(bx + 9, cy + 1, bx + 17, cy + 9, ctlColor(w, s));
                    drawFit(g, sideName(w, s) + " took " + mine.size(), bw - 34, bx + 21, cy, COL_TEXT, false);
                    cy += 12;
                    for (WarScore.Holding h : mine) warCityRow(g, d, f, h, true, mouseX, mouseY);
                }
            }
            // em disputa / sem controle
            List<WarScore.Holding> disputed = new ArrayList<>();
            for (WarScore.Holding h : r.holdings()) if (h.disputed()) disputed.add(h);
            if (warCityFilter != 1 && foldable(g, "disputed", "Contested or uncontrolled (" + disputed.size() + ")", true, mouseX, mouseY)) {
                if (disputed.isEmpty()) paragraph(g, "None at the moment.", COL_DIM);
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
            if (f != null) {
                List<SiteTerritories.Locality> inPlay = new ArrayList<>();
                for (WarFront.Unit u : f.units()) if (u.inPlay && !u.neutral()) inPlay.addAll(u.members);
                if (foldable(g, "inplay", "In Play (" + inPlay.size() + ")", true, mouseX, mouseY)) for (SiteTerritories.Locality l : inPlay) {
                    link(g, fit(SiteTerritories.splitCamel(l.name), bw - 24), bx + 11, cy, true, mouseX, mouseY, () -> focusPoint(l));
                    cy += 12;
                }
            }
            if (foldable(g, "bycountry", "By country (" + byCountry.size() + ")", true, mouseX, mouseY)) for (Map.Entry<String, List<WarScore.Holding>> e : byCountry.entrySet()) {
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
                String tail = list.size() + (list.size() == 1 ? " city" : " cities") + (lost > 0 ? ", " + lost + " lost" : "");
                int tw = font.width(tail);
                drawFit(g, ci >= 0 ? d.labels[ci] : e.getKey(), bw - 64 - tw, bx + 46, rowTop + 4, hover ? 0xFFDDE7EE : COL_TEXT, false);
                g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 4, lost > 0 ? COL_WAR : COL_DIM, false);
                if (vis) hits.add(new Hit(bx + 6, rowTop, bw - 12, 15, () -> {
                    if (!WAR_CLOSED.remove(key)) WAR_CLOSED.add(key);
                }, null));
                cy += 15;
                if (!closed) for (WarScore.Holding h : list) warCityRow(g, d, f, h, false, mouseX, mouseY);
            }
        }
    }

    static final java.util.Comparator<WarScore.Holding> BY_VALUE = java.util.Comparator
            .comparingDouble((WarScore.Holding h) -> -h.value()).thenComparing(h -> String.valueOf(h.locality().name), String.CASE_INSENSITIVE_ORDER);

    /** Uma cidade numa lista: bolinha da cor de quem segura, nome (clique vai até ela), país opcional, valor e estado. */
    void warCityRow(GuiGraphics g, SiteTerritories.Data d, WarFront f, WarScore.Holding h, boolean withCountry, int mouseX, int mouseY) {
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
        drawFit(g, name, bw - 56 - tw, bx + 29, rowTop + 2, hover ? 0xFFDDE7EE : COL_LINK, false);
        g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 2, status != null ? COL_WAR : COL_DIM, false);
        if (vis) {
            final SiteTerritories.Locality ll = l;
            hits.add(new Hit(bx + 6, rowTop, bw - 12, 12, () -> focusPoint(ll), null));
        }
        cy += 12;
    }

    /** Leva a câmera a uma cidade (mesmo as que o site tem e o mapa não lista) e a faz pulsar. */
    void focusPoint(SiteTerritories.Locality l) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d != null && l.idx >= 0 && l.idx < d.localities.size() && d.localities.get(l.idx) == l) {
            focusCity(l);
            return;
        }
        viewX = l.x + 0.5;
        viewZ = l.z + 0.5;
        if (scale < 0.7) scale = 0.7;
    }

    void warLaw7(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        WarFront f = viewedFront(d);
        if (f == null) {
            section(g, "Law 7");
            paragraph(g, "The site sent no connections between cities; without them fronts, In Play, encirclement and capitulation cannot be computed.", COL_DIM);
            return;
        }
        if (foldable(g, "front", "Front by side", true, mouseX, mouseY)) {
            for (int i = 0; i < w.sides.size(); i++) {
                int play = 0, enc = 0;
                for (WarFront.Unit u : f.units()) {
                    if (u.control != i) continue;
                    if (u.inPlay) play += u.members.size();
                    if (u.encircled) enc += u.members.size();
                }
                g.fill(bx + 9, cy + 1, bx + 17, cy + 9, ctlColor(w, i));
                String tail = play + " In Play  /  " + enc + " encircled";
                int tw = font.width(tail);
                drawFit(g, sideName(w, i), bw - 40 - tw, bx + 21, cy, COL_TEXT, false);
                g.drawString(font, tail, bx + bw - 10 - tw, cy, COL_DIM, false);
                cy += 12;
            }
            for (int side : f.noCapital()) {
                if (side >= 0 && side < w.sides.size()) paragraph(g, sideName(w, side) + " holds no capital: none of their cities counts as encircled.", COL_DIM);
            }
            if (!f.ties().isEmpty()) {
                paragraph(g, f.ties().size() + (f.ties().size() == 1 ? " tie" : " ties") + " on neutral land await the staff decision (Short Circuit):", COL_WAR);
                for (WarFront.Tie t : f.ties()) {
                    for (String[] pair : t.pairs()) paragraph(g, "  " + pair[0] + " - " + pair[1] + " (" + t.distance() + ")", COL_DIM);
                }
            }

        }

        // cidades In Play
        List<SiteTerritories.Locality> play = new ArrayList<>();
        for (WarFront.Unit u : f.units()) if (u.inPlay && !u.neutral()) play.addAll(u.members);
        if (foldable(g, "playcities", "In Play cities (" + play.size() + ")", true, mouseX, mouseY)) {
            if (play.isEmpty()) paragraph(g, "None right now.", COL_DIM);
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
                drawFit(g, name, bw - 56 - tw, bx + 29, rowTop + 2, hover ? 0xFFDDE7EE : COL_LINK, false);
                g.drawString(font, tail, bx + bw - 10 - tw, rowTop + 2, COL_WAR, false);
                if (vis) hits.add(new Hit(bx + 6, rowTop, bw - 12, 12, () -> focusPoint(l), null));
                cy += 12;
            }
        }

        // capitulação
        List<WarFront.Score> risk = new ArrayList<>();
        for (WarFront.Score s : f.scores()) if (s.score() > 0 || s.capitulated()) risk.add(s);
        if (foldable(g, "capit", "Capitulation (Law 7.9)", true, mouseX, mouseY)) {
            paragraph(g, "A nation capitulates with more than 100 points lost and its capital taken.", COL_DIM);
            if (risk.isEmpty()) paragraph(g, "No nation at risk.", COL_DIM);
            for (WarFront.Score s : risk) {
                int rowTop = cy;
                g.fill(bx + 9, rowTop + 1, bx + 17, rowTop + 9, ctlColor(w, s.side()));
                String tail = s.capitulated() ? "CAPITULOU" : pts(s.score()) + " / 100" + (s.capitalTaken() ? "  capital taken" : "");
                int tw = font.width(tail);
                drawFit(g, s.nation() + (s.hasCapital() ? "" : " (no capital)"), bw - 40 - tw, bx + 21, rowTop, s.capitulated() ? COL_WAR : COL_TEXT, false);
                g.drawString(font, tail, bx + bw - 10 - tw, rowTop, s.capitulated() ? COL_WAR : COL_DIM, false);
                cy += 11;
                int barW = bw - 18;
                g.fill(bx + 9, cy, bx + 9 + barW, cy + 4, 0xFF0E0C08);
                int fill = (int) Math.min(barW - 2, Math.round((barW - 2) * Math.min(1.0, s.score() / 100.0)));
                g.fill(bx + 10, cy + 1, bx + 10 + fill, cy + 3, s.capitulated() ? 0xFFC02B2B : 0xFFCFD3D1);
                cy += 8;
            }
        }

        if (foldable(g, "legend", "Map legend", false, mouseX, mouseY)) {
            paragraph(g, "Thick line: front, with arrows in the direction of the advance (at an encircled city only the side that can advance has arrows). Dotted gray line: internal link of one side. Pulsing ring: In Play city. Red ring: encircled city.", COL_DIM);
        }
    }

    // -------------------------------------------------------------- configurações

    static int indexOfStep(int value, int min, int step, int count) {
        return Math.max(0, Math.min(count - 1, Math.round((value - min) / (float) step)));
    }

    void drawSettingsPanel(GuiGraphics g, int mouseX, int mouseY) {
        Prefs.Data p = Prefs.get();
        int top = header(g, NEUTRAL, "Settings", "Saved in config/bmcatlas", null, mouseX, mouseY, () -> tab = TAB_NONE);
        beginScroll(g, top, SCROLL[TAB_SET], 0);
        final int fullX = bx, fullW = bw, colTop = cy;
        boolean twoCols = bw >= 440;
        if (twoCols) bw = fullW / 2;

        section(g, "Map");
        stepsRow(g, "Country color intensity", p.fillPct + "%", 11, indexOfStep(p.fillPct, 0, 10, 11), mouseX, mouseY, i -> {
            p.fillPct = i * 10;
            Prefs.changed();
        });
        stepsRow(g, "Border opacity", p.borderPct + "%", 11, indexOfStep(p.borderPct, 0, 10, 11), mouseX, mouseY, i -> {
            p.borderPct = i * 10;
            Prefs.changed();
        });
        toggleRow(g, "Country names", p.countryNames, mouseX, mouseY, () -> {
            p.countryNames = !p.countryNames;
            Prefs.save();
        });
        toggleRow(g, "City names", p.cityNames, mouseX, mouseY, () -> {
            p.cityNames = !p.cityNames;
            Prefs.save();
        });
        stepsRow(g, "City icon size", p.cityIconPct + "%", 11, indexOfStep(p.cityIconPct, 50, 25, 11), mouseX, mouseY, i -> {
            p.cityIconPct = 50 + i * 25;
            Prefs.save();
        });
        stepsRow(g, "Cities show when zooming in", p.zoomBiasPct + "% (more = earlier)", 7, indexOfStep(p.zoomBiasPct, 50, 25, 7), mouseX, mouseY, i -> {
            p.zoomBiasPct = 50 + i * 25;
            Prefs.save();
        });
        toggleRow(g, "Province under the cursor", p.provinceInTooltip, mouseX, mouseY, () -> {
            p.provinceInTooltip = !p.provinceInTooltip;
            Prefs.save();
        });

        section(g, "Online players");
        toggleRow(g, "Show on the map", showPlayers && SiteTerritories.showPlayers(), mouseX, mouseY, () -> {
            showPlayers = !showPlayers;
            if (showPlayers && !SiteTerritories.showPlayers()) SiteTerritories.setShowPlayers(true);
        });
        stepsRow(g, "Icon size", p.playerIconPct + "%", 13, indexOfStep(p.playerIconPct, 100, 25, 13), mouseX, mouseY, i -> {
            p.playerIconPct = 100 + i * 25;
            Prefs.save();
        });

        section(g, "Interface");
        {
            int pct = p.uiScalePct;
            String shown = pct == 0 ? "Auto (" + Math.round(ui * 100) + "%)" : pct + "%";
            stepsRow(g, "Menu size", shown, 11, pct == 0 ? 0 : indexOfStep(pct, 50, 10, 11), mouseX, mouseY, i -> {
                p.uiScalePct = i == 0 ? 0 : 50 + i * 10;      // first step = automatic, then 60% to 150%
                Prefs.save();
            });
        }

        toggleRow(g, "Tell me when a new version is out", p.updateCheck, mouseX, mouseY, () -> {
            p.updateCheck = !p.updateCheck;
            Prefs.save();
        });

        int col1End = cy;
        if (twoCols) {
            bx = fullX + fullW / 2;
            cy = colTop;
            rowIdx = 0;
        }
        section(g, "Minimap");
        toggleRow(g, "Minimap on the game screen", p.minimap, mouseX, mouseY, () -> {
            p.minimap = !p.minimap;
            Prefs.save();
        });
        stepsRow(g, "Size", p.minimapSize + " px", 13, indexOfStep(p.minimapSize, 64, 16, 13), mouseX, mouseY, i -> {
            p.minimapSize = 64 + i * 16;
            Prefs.save();
        });
        stepsRow(g, "Zoom", p.minimapZoomPct + "%", 15, indexOfStep(p.minimapZoomPct, 50, 25, 15), mouseX, mouseY, i -> {
            p.minimapZoomPct = 50 + i * 25;
            Prefs.save();
        });
        choiceRow(g, "Corner", new String[]{"Top left", "Top right", "Bottom left", "Bottom right"}, p.minimapCorner, mouseX, mouseY, i -> {
            p.minimapCorner = i;
            Prefs.save();
        });
        toggleRow(g, "Cities on the minimap", p.minimapCities, mouseX, mouseY, () -> {
            p.minimapCities = !p.minimapCities;
            Prefs.save();
        });
        toggleRow(g, "Players on the minimap", p.minimapPlayers, mouseX, mouseY, () -> {
            p.minimapPlayers = !p.minimapPlayers;
            Prefs.save();
        });
        stepsRow(g, "Opacity", p.minimapOpacityPct + "%", 7, indexOfStep(p.minimapOpacityPct, 40, 10, 7), mouseX, mouseY, i -> {
            p.minimapOpacityPct = 40 + i * 10;
            Prefs.save();
        });
        choiceRow(g, "Enlarge key", new String[]{"Hold", "Toggle"}, p.minimapHold ? 0 : 1, mouseX, mouseY, i -> {
            p.minimapHold = i == 0;
            Minimap.expanded = false;
            Prefs.save();
        });

        section(g, "War");
        toggleRow(g, "Live control (dynmap)", SiteTerritories.liveWar(), mouseX, mouseY, () -> SiteTerritories.setLiveWar(!SiteTerritories.liveWar()));
        toggleRow(g, "Record the war map (replay on the war map)", p.recordWar, mouseX, mouseY, () -> {
            p.recordWar = !p.recordWar;
            Prefs.save();
        });
        toggleRow(g, "Record live wars automatically", p.recordLiveWars, mouseX, mouseY, () -> {
            p.recordLiveWars = !p.recordLiveWars;
            Prefs.save();
        });

        List<MapCatalog.Entry> maps = MapCatalog.list();
        section(g, "Server maps");
        if (maps.isEmpty()) {
            paragraph(g, "This server has only one map (the \"url\" address in config/bmcatlas/site.json). To have several, publish a maps.json or list them under \"maps\" in site.json.", COL_DIM);
        } else {
            String cur = SiteTerritories.activeMap() == null ? "" : SiteTerritories.activeMap().key;
            for (MapCatalog.Entry e : maps) {
                int rowTop = cy;
                boolean vis = visible(rowTop, 16);
                boolean hover = vis && mouseX >= bx + 9 && mouseX < bx + bw - 9 && mouseY >= rowTop && mouseY < rowTop + 16;
                bevel(g, bx + 9, rowTop, bw - 18, 16, hover, e.key.equalsIgnoreCase(cur));
                drawFit(g, e.name, bw - 40, bx + 16, rowTop + 4, e.key.equalsIgnoreCase(cur) ? 0xFFFFF2B0 : COL_TEXT, false);
                if (vis) hits.add(new Hit(bx + 9, rowTop, bw - 18, 16, () -> SiteTerritories.selectMap(e.key), null));
                cy += 19;
            }
            textButton(g, bx + 9, cy, bw - 18, 15, "Automatic (by server)", mouseX, mouseY, true, () -> SiteTerritories.selectMap(""));
            cy += 19;
            paragraph(g, "List: " + MapCatalog.source() + (MapCatalog.error().isEmpty() ? "" : " (error: " + MapCatalog.error() + ")"), COL_DIM);
        }

        section(g, "Another server");
        paragraph(g, "In config/bmcatlas/site.json: \"url\" (territories.json), \"mapsUrl\" (maps.json), \"dynmapUrl\", \"dynmapWorld\". Without \"url\", the map works only with the chunks you explore.", COL_DIM);

        if (twoCols) {
            cy = Math.max(cy, col1End);
            bx = fullX;
            bw = fullW;
        }
        SCROLL[TAB_SET] = endScroll(g, SCROLL[TAB_SET]);
    }

    // ------------------------------------------------- notícias do servidor

    /** The news open in the News panel (its id), or 0 for the list. */
    static long newsOpen;

    /** The server's news (BmcWar plugin): the list (headline and the start of the text), or one news in full. */
    void drawNewsPanel(GuiGraphics g, int mouseX, int mouseY) {
        ServerNews.read();
        List<ServerNews.Item> items = ServerNews.items();
        ServerNews.Item open = null;
        for (ServerNews.Item it : items) if (it.id() == newsOpen) open = it;
        if (open == null) newsOpen = 0;
        int top = header(g, NEUTRAL, "News", items.isEmpty() ? null : "Latest " + items.size() + " from this server", null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            newsOpen = 0;
        });
        beginScroll(g, top, SCROLL[TAB_NEWS], 0);
        if (items.isEmpty()) {
            NewsArt.empty(g, bx + (bw - 16) / 2, cy + 8);
            cy += 30;
            String none = ServerSync.active() ? "No news yet." : "No news: this server has no BmcWar plugin, or it has not sent any yet.";
            for (String l : wrap(none, bw - 40)) {
                g.drawString(font, l, bx + (bw - font.width(l)) / 2, cy, COL_DIM, false);
                cy += 10;
            }
        } else for (ServerNews.Item it : items) drawNewsRow(g, it, mouseX, mouseY);
        SCROLL[TAB_NEWS] = endScroll(g, SCROLL[TAB_NEWS]);
        if (open != null) drawNewsArticle(g, open, mouseX, mouseY);
    }

    /** One news in the list, on a card: its icon, kind and date, the headline, the first lines of the text and a small picture; a click opens it. */
    private void drawNewsRow(GuiGraphics g, ServerNews.Item it, int mouseX, int mouseY) {
        int x0 = bx + 8, w = bw - 16, pad = 7, rowTop = cy;
        RemoteImages.Img pic = it.image() == null ? null : RemoteImages.picture(it.image());
        boolean thumb = it.image() != null;
        int inner = w - 2 * pad, textX = x0 + pad + (thumb ? 64 + 10 : 0), textW = x0 + w - pad - textX;
        List<List<Seg>> head = richWrap(it.title(), inner, true);
        String body = it.notes() != null ? it.notes() : String.join(" ", it.details());
        List<List<Seg>> lines = richWrap(body.replaceAll("\\s+", " ").strip(), textW, false);
        boolean more = lines.size() > 3;
        if (more) lines = new ArrayList<>(lines.subList(0, 3));
        int bodyH = Math.max(thumb ? 36 + 3 : 0, lines.size() * 10);
        int h = pad + 16 + 4 + head.size() * 10 + 4 + bodyH + 4 + 10 + pad - 2;
        if (rowTop + h < scrollTop || rowTop > scrollBottom) {   // out of sight: only its height counts
            cy = rowTop + h + 5;
            return;
        }
        boolean hover = visible(rowTop, h) && mouseX >= x0 && mouseX < x0 + w && mouseY >= rowTop && mouseY < rowTop + h;
        NewsArt.nine(g, hover || it.id() == newsOpen ? "card_hover" : "card", 12, 3, x0, rowTop, w, h);
        int y = rowTop + pad;
        NewsArt.icon(g, it.type(), x0 + pad, y);
        g.drawString(font, ServerNews.label(it.type()).toUpperCase(Locale.ROOT), x0 + pad + 20, y + 4, ServerNews.color(it.type()), false);
        if (it.date() != null) drawFit(g, it.date(), w / 2 - pad, x0 + w - pad - Math.min(w / 2 - pad, font.width(it.date())), y + 4, COL_DIM, false);
        y += 20;
        for (List<Seg> l : head) {
            drawRich(g, l, x0 + pad, y, 0xFFECEBE4, false);
            y += 10;
        }
        y += 4;
        if (thumb) NewsArt.framed(g, pic, x0 + pad + 3, y + 3, 58, 33);
        int ly = y;
        for (List<Seg> l : lines) {
            drawRich(g, l, textX, ly, COL_DIM, false);
            ly += 10;
        }
        y += bodyH + 4;
        g.drawString(font, more ? "Read more >" : "Open >", x0 + pad, y, hover ? 0xFFF0B429 : COL_LINK, false);
        if (visible(rowTop, h)) hits.add(new Hit(x0, rowTop, w, h, () -> {
            newsOpen = newsOpen == it.id() ? 0 : it.id();   // a second click on the same news closes it
            articleScroll = 0;
        }, null));
        cy = rowTop + h + 5;
    }

    /**
     * One news in full, in its own panel beside the list (over it when the screen is too narrow): the Gaia Guardian masthead, the kind
     * on a coloured tag, the headline, the picture as wide as the panel, the facts in a box and the text with its own paragraphs.
     */
    private void drawNewsArticle(GuiGraphics g, ServerNews.Item it, int mouseX, int mouseY) {
        int sbx = bx, sby = by, sbw = bw, sbh = bh;
        int ax = panelX + panelW + 6;
        if (ax + PW > width - barWLocal() - MARGIN) ax = panelX;
        articleX = ax;
        articleY = panelY;
        articleW = PW;
        articleH = panelH;
        articleVisible = true;
        bx = ax;
        by = panelY;
        bw = PW;
        bh = panelH;
        NewsArt.nine(g, "panel_frame", 16, 4, bx, by, bw, bh);
        hits.add(new Hit(bx, by, bw, bh, null, null));

        // masthead
        int hh = 34;
        g.fill(bx + 4, by + 4, bx + bw - 4, by + hh, 0xFF33373A);
        g.fill(bx + 4, by + hh, bx + bw - 4, by + hh + 1, COL_GOLD_DIM);
        String name = "GAIA GUARDIAN", sub = "DIPLOMACY AND WAR";
        int nw = spacedWidth(name), nx = bx + (bw - nw) / 2;
        spaced(g, name, nx, by + 9, 0xFFF0B429, true);
        diamond(g, nx - 8, by + 12, 0xFFF0B429);
        diamond(g, nx + nw + 6, by + 12, 0xFFF0B429);
        spaced(g, sub, bx + (bw - spacedWidth(sub)) / 2, by + 21, COL_DIM, false);
        int cx = bx + bw - 22, cyy = by + 7;
        boolean overX = mouseX >= cx && mouseX < cx + 14 && mouseY >= cyy && mouseY < cyy + 14;
        g.fill(cx, cyy, cx + 14, cyy + 14, 0xFF0A0B08);
        g.fill(cx + 1, cyy + 1, cx + 13, cyy + 13, overX ? 0xFF7A2A22 : 0xFF5A1F1A);
        g.drawString(font, "X", cx + 5, cyy + 3, 0xFFF4C4B4, false);
        hits.add(new Hit(cx, cyy, 14, 14, () -> newsOpen = 0, "Close"));

        beginScroll(g, by + hh + 2, articleScroll, 0);
        int x = bx + 12, w = bw - 24;
        link(g, "< All news", x, cy + 2, true, mouseX, mouseY, () -> newsOpen = 0);
        cy += 15;
        // the kind on a tag in its colour, then the date
        int col = ServerNews.color(it.type());
        String kind = ServerNews.label(it.type()).toUpperCase(Locale.ROOT);
        int tw = font.width(kind) + 8;
        g.fill(x, cy, x + tw, cy + 12, col);
        g.fill(x + 1, cy + 1, x + tw - 1, cy + 11, darken(col, 0.3f));
        g.drawString(font, kind, x + 4, cy + 2, lighten(col, 1.15f), false);
        if (it.date() != null) drawFit(g, it.date(), w - tw - 6, x + tw + 6, cy + 2, COL_DIM, false);
        cy += 17;
        for (List<Seg> l : richWrap(it.title().toUpperCase(Locale.ROOT), w, true)) {
            drawRich(g, l, x, cy, 0xFFECEBE4, true);
            cy += 11;
        }
        cy += 4;
        RemoteImages.Img pic = it.image() == null ? null : RemoteImages.picture(it.image());
        if (pic != null) {
            int pw = w - 6, ph = Math.max(1, (int) Math.round(pic.h() * (double) pw / pic.w()));
            NewsArt.framed(g, pic, x + 3, cy + 3, pw, ph);
            if (visible(cy + 3, ph)) hits.add(new Hit(x + 3, cy + 3, pw, ph, () -> viewImage = it.image(), "Click to enlarge"));
            cy += ph + 10;
        } else if (it.image() != null) {
            NewsArt.framed(g, null, x + 3, cy + 3, w - 6, 40);
            g.drawString(font, "Loading picture...", x + (w - font.width("Loading picture...")) / 2, cy + 19, COL_DIM, false);
            cy += 50;
        }
        if (!it.details().isEmpty()) {
            List<List<List<Seg>>> facts = new ArrayList<>();
            int fh = 8;
            for (String d : it.details()) {
                List<List<Seg>> ls = richWrap(d, w - 22, false);
                facts.add(ls);
                fh += ls.size() * 10;
            }
            NewsArt.nine(g, "card", 12, 3, x, cy, w, fh);
            int fy = cy + 5;
            for (List<List<Seg>> ls : facts) {
                diamond(g, x + 8, fy + 3, 0xFFF0B429);
                for (List<Seg> l : ls) {
                    drawRich(g, l, x + 15, fy, COL_TEXT, false);
                    fy += 10;
                }
            }
            cy += fh + 6;
        }
        if (it.notes() != null) {
            NewsArt.sep(g, x, cy, w);
            cy += 8;
            // the text as it was written: each line break kept, an empty line is a gap between paragraphs
            for (String para : it.notes().replace("\r", "").replace("\t", "    ").split("\n", -1)) {
                if (para.isBlank()) {
                    cy += 5;
                    continue;
                }
                for (List<Seg> l : richWrap(para, w, false)) {
                    drawRich(g, l, x, cy, COL_TEXT, false);
                    cy += 10;
                }
                cy += 2;
            }
        }
        articleScroll = endScroll(g, articleScroll);
        bx = sbx;
        by = sby;
        bw = sbw;
        bh = sbh;
    }

    /** A piece of a news line: a word, in bold, in a country's colour (rgb) or the line's own (-1). */
    record Seg(String text, int rgb, boolean bold) {}

    private static SiteTerritories.Data namesFor;
    private static java.util.regex.Pattern namesRe;
    private static Map<String, Integer> nameRgb = Map.of();

    /** The countries' names and labels found in a text (longest first, whole words, any case) with their colours. */
    private List<Seg> segments(String text, boolean bold) {
        SiteTerritories.Data d = SiteTerritories.data();
        List<Seg> out = new ArrayList<>();
        if (d == null || d.names == null) {
            out.add(new Seg(text, -1, bold));
            return out;
        }
        if (namesFor != d) {
            Map<String, Integer> m = new HashMap<>();
            for (int c = 0; c < d.names.length; c++) {
                for (String n : new String[]{d.names[c], d.labels == null ? null : d.labels[c]}) {
                    if (n != null && n.strip().length() >= 3) m.putIfAbsent(n.strip().toLowerCase(Locale.ROOT), d.rgb[c] | 0xFF000000);
                }
            }
            List<String> keys = new ArrayList<>(m.keySet());
            keys.sort((a, b) -> b.length() - a.length());
            StringBuilder re = new StringBuilder();
            for (String k : keys) re.append(re.length() == 0 ? "" : "|").append(java.util.regex.Pattern.quote(k));
            namesRe = keys.isEmpty() ? null : java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])(" + re + ")(?![\\p{L}\\p{N}])", java.util.regex.Pattern.CASE_INSENSITIVE);
            nameRgb = m;
            namesFor = d;
        }
        if (namesRe == null) {
            out.add(new Seg(text, -1, bold));
            return out;
        }
        java.util.regex.Matcher mt = namesRe.matcher(text);
        int at = 0;
        while (mt.find()) {
            if (mt.start() > at) out.add(new Seg(text.substring(at, mt.start()), -1, bold));
            out.add(new Seg(mt.group(), nameRgb.getOrDefault(mt.group().toLowerCase(Locale.ROOT), -1), true));
            at = mt.end();
        }
        if (at < text.length()) out.add(new Seg(text.substring(at), -1, bold));
        return out;
    }

    /** A country's colour made readable on the dark panel: lightened, and a very dark one (black) is mixed with white until it shows. */
    static int readable(int rgb) {
        int c = lighten(rgb, 1.25f);
        for (int i = 0; i < 6 && luma(c) < 140; i++) {
            int r = (c >> 16) & 255, gr = (c >> 8) & 255, b = c & 255;
            c = 0xFF000000 | (r + (255 - r) / 3) << 16 | (gr + (255 - gr) / 3) << 8 | (b + (255 - b) / 3);
            if (luma(rgb) < 20 && i == 0) c = 0xFF9A9A9A;   // black: a clear grey, the mix would take several steps
        }
        return c;
    }

    private static int luma(int c) {
        return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
    }

    private net.minecraft.network.chat.Component styled(Seg s, int base) {
        net.minecraft.network.chat.MutableComponent c = Component.literal(s.text());
        if (s.bold()) c = c.withStyle(ChatFormatting.BOLD);
        return c.withStyle(st -> st.withColor(s.rgb() != -1 ? readable(s.rgb()) & 0xFFFFFF : base & 0xFFFFFF));
    }

    /** Wraps a text into lines of pieces, country names kept in their colour and bold; bold = the whole text in bold (headlines). */
    List<List<Seg>> richWrap(String text, int maxW, boolean bold) {
        // the same text at the same width comes back every frame: worked out once (the list is read-only), forgotten when the site data changes
        SiteTerritories.Data d = SiteTerritories.data();
        if (wrapsFor != d) {
            WRAPS.clear();
            wrapsFor = d;
        }
        String key = maxW + (bold ? "b" : "n") + text;
        List<List<Seg>> hit = WRAPS.get(key);
        if (hit != null) return hit;
        hit = richWrap0(text, maxW, bold);
        WRAPS.put(key, hit);
        return hit;
    }

    private static SiteTerritories.Data wrapsFor;
    private static final Map<String, List<List<Seg>>> WRAPS = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<List<Seg>>> e) {
            return size() > 400;
        }
    };

    private List<List<Seg>> richWrap0(String text, int maxW, boolean bold) {
        List<List<Seg>> lines = new ArrayList<>();
        List<Seg> line = new ArrayList<>();
        int w = 0;
        for (Seg s : segments(text, bold)) {
            // words keep their spaces at the end, so the line can be cut between them
            for (String word : s.text().split("(?<= )")) {
                if (word.isEmpty()) continue;
                int ww = font.width(styled(new Seg(word, s.rgb(), s.bold()), 0xFFFFFF));
                if (w + ww > maxW + font.width(" ") && !line.isEmpty()) {
                    lines.add(line);
                    line = new ArrayList<>();
                    w = 0;
                }
                line.add(new Seg(word, s.rgb(), s.bold()));
                w += ww;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    /** Draws one line of pieces; returns nothing, the caller moves down. */
    void drawRich(GuiGraphics g, List<Seg> line, int x, int y, int color, boolean shadow) {
        for (Seg s : line) {
            net.minecraft.network.chat.Component c = styled(s, color);
            g.drawString(font, c, x, y, 0xFFFFFFFF, shadow);
            x += font.width(c);
        }
    }

    /** A small diamond (5x5) centred on (x, y). */
    static void diamond(GuiGraphics g, int x, int y, int col) {
        g.fill(x, y - 2, x + 1, y + 3, col);
        g.fill(x - 1, y - 1, x + 2, y + 2, col);
        g.fill(x - 2, y, x + 3, y + 1, col);
    }

    // ------------------------------------------------- panorama diplomático

    void drawDiploMenu(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        int top = header(g, NEUTRAL, "Diplomatic overview", "Alliances, NAPs, purge, diplolock, truces and ultimatums", null, mouseX, mouseY, () -> tab = TAB_NONE);
        top = tabStrip(g, top, new String[]{"Alliances", "NAP", "Purge", "Diplolock", "Truces", "Ultimatums"}, dmTab, mouseX, mouseY, i -> {
            dmTab = i;
            SCROLL[TAB_DIPLOMENU] = 0;
        });
        beginScroll(g, top, SCROLL[TAB_DIPLOMENU], 0);
        switch (dmTab) {
            case 0 -> dmAlliances(g, d, mouseX, mouseY);
            case 1 -> dmNaps(g, d, mouseX, mouseY);
            case 2 -> dmPurge(g, d, mouseX, mouseY);
            case 4 -> dmTruces(g, d, mouseX, mouseY);
            case 5 -> dmUltimatums(g, d, mouseX, mouseY);
            default -> dmLocks(g, d, mouseX, mouseY);
        }
        SCROLL[TAB_DIPLOMENU] = endScroll(g, SCROLL[TAB_DIPLOMENU]);
    }

    void dmAlliances(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        mineToggle(g, d, mouseX, mouseY);
        int meF = mineFilter(d);
        List<SiteTerritories.AllianceInfo> all = d.war == null || d.war.alliances == null ? List.of() : d.war.alliances;
        boolean hasMine = false;
        for (SiteTerritories.AllianceInfo a : all) if (meF < 0 || Relations.countriesOf(d, a).contains(meF)) hasMine = true;
        if (all.isEmpty() || !hasMine) {
            paragraph(g, all.isEmpty() ? "The site lists no alliances." : "Your country has no alliances.", COL_DIM);
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            int count = 0;
            for (SiteTerritories.AllianceInfo a : all) if (Relations.isBilateral(a) == (pass == 1) && (meF < 0 || Relations.countriesOf(d, a).contains(meF))) count++;
            if (count == 0) continue;
            section(g, (pass == 0 ? "Alliances" : "Bilateral alliances") + " (" + count + ")");
            for (int i = 0; i < all.size(); i++) {
                SiteTerritories.AllianceInfo a = all.get(i);
                if (Relations.isBilateral(a) != (pass == 1) || (meF >= 0 && !Relations.countriesOf(d, a).contains(meF))) continue;
                int rowTop = cy;
                if (visible(rowTop, 18)) {
                    boolean hover = mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 18;
                    zebra(g, rowTop, 18, hover, i == dipAlliance);
                    g.fill(bx + 10, rowTop + 2, bx + 32, rowTop + 16, 0xFF05080C);
                    RemoteImages.Img flag = a.flag == null ? null : RemoteImages.flag(SiteTerritories.assetUrl(a.flag, true));
                    if (flag != null) drawImage(g, flag, bx + 11, rowTop + 3, 20, 12);
                    else g.fill(bx + 11, rowTop + 3, bx + 31, rowTop + 15, 0xFF000000 | Relations.blocColor(a, i));
                    String count2 = String.valueOf(Relations.countriesOf(d, a).size());
                    drawFit(g, a.name == null ? a.id : a.name, bw - 90, bx + 38, rowTop + 5, COL_TEXT, false);
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
            section(g, a.name == null ? "Alliance" : a.name);
            textButton(g, bx + 9, cy, bw - 18, 16, "View on the map (Diplomacy mode)", mouseX, mouseY, true, () -> {
                mode = MODE_DIPLOMACY;
                dipFocus = -1;
            });
            cy += 21;
            kv(g, "Official name", a.officialName);
            kv(g, "Formed", a.formed);
            if (a.members != null) {
                for (SiteTerritories.Member m : a.members) kv(g, m.name == null ? "Member" : m.name, m.note == null ? "member" : m.note);
            }
            kv(g, "Notes", a.notes);
            List<String> names = new ArrayList<>();
            for (int c : Relations.countriesOf(d, a)) names.add(d.labels[c]);
            if (!names.isEmpty()) {
                section(g, "Countries (" + names.size() + ")  -  click to go");
                chips(g, names, mouseX, mouseY, n -> {
                    Integer ci = Relations.nameIndex(d).get(Relations.norm(n));
                    if (ci != null) centerCountry(ci);
                });
            }
        }
    }

    record NapPair(String a, String b, long until) {}

    // -- tréguas e ultimatos

    record Timed(String a, String b, String note, long until) {}

    static final long TRUCE_MS = 3 * DAY;
    static final long ULTIMATUM_MS = 72L * 3_600_000L;

    /** Uma linha com dois países clicáveis (ou um texto livre) e o prazo embaixo. */
    void timedRow(GuiGraphics g, SiteTerritories.Data d, Map<String, Integer> idx, Timed t, long now, int mouseX, int mouseY, int color) {
        int rowTop = cy;
        if (visible(rowTop, 24)) {
            zebra(g, rowTop, 24, false, false);
            int half = (bw - 24 - 40) / 2;
            int x = bx + 11;
            if (t.b() == null) {
                drawFit(g, t.a(), bw - 26, x, rowTop + 3, COL_TEXT, false);
            } else {
                Integer ia = idx.get(Relations.norm(t.a())), ib = idx.get(Relations.norm(t.b()));
                String ta = fit(ia != null ? d.labels[ia] : t.a(), half), tb = fit(ib != null ? d.labels[ib] : t.b(), half);
                x += link(g, ta, x, rowTop + 3, ia != null, mouseX, mouseY, () -> centerCountry(ia));
                g.drawString(font, t.note().startsWith("->") ? " -> " : " & ", x, rowTop + 3, COL_DIM, false);
                x += font.width(" & ");
                link(g, tb, x, rowTop + 3, ib != null, mouseX, mouseY, () -> centerCountry(ib));
            }
            String when = (t.note().startsWith("->") ? "automatically accepted " : t.note().isEmpty() ? "until " : t.note() + " until ")
                    + dateText(t.until()) + "  (" + countdown(t.until() - now) + ")";
            drawFit(g, when, bw - 26, bx + 11, rowTop + 13, color, false);
        }
        rowIdx++;
        cy += 24;
    }

    /** Tréguas: as do site, as 3 dias depois de uma guerra e as 3 dias depois de um ultimato aceito. */
    List<Timed> truceList(SiteTerritories.Data d, long now) {
        return truceList(d, now, -1);
    }

    List<Timed> truceList(SiteTerritories.Data d, long now, int me) {
        List<Timed> list = new ArrayList<>();
        if (d.war != null && d.war.truces != null) {
            for (SiteTerritories.Truce t : d.war.truces) {
                long until = parseMs(t.until), from = parseMs(t.from);
                if (until == NONE || until <= now || (from != NONE && from > now)) continue;
                if (me < 0 || nameIsMe(d, t.a, me) || nameIsMe(d, t.b, me)) list.add(new Timed(t.a, t.b, "", until));
            }
        }
        for (SiteTerritories.WarInfo w : Relations.wars(d)) {       // guerra encerrada deixa 3 dias de trégua entre os lados
            long ended = parseMs(w.ended);
            if (ended == NONE || ended > now || ended + TRUCE_MS <= now) continue;
            if (me < 0 || warInvolves(d, w, me)) list.add(new Timed("After the war " + warName(w), null, "", ended + TRUCE_MS));
        }
        if (d.war != null && d.war.ultimatums != null) {
            for (SiteTerritories.Ultimatum u : d.war.ultimatums) {
                long sent = parseMs(u.sent);
                if (sent == NONE) continue;
                long accepted = sent + ULTIMATUM_MS;
                if (accepted <= now && accepted + TRUCE_MS > now && (me < 0 || nameIsMe(d, u.from, me) || nameIsMe(d, u.to, me))) list.add(new Timed(u.from, u.to, "ultimatum accepted,", accepted + TRUCE_MS));
            }
        }
        list.sort((x, y) -> Long.compare(x.until(), y.until()));
        return list;
    }

    void dmTruces(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        Map<String, Integer> idx = Relations.nameIndex(d);
        mineToggle(g, d, mouseX, mouseY);
        List<Timed> list = truceList(d, now, mineFilter(d));
        section(g, "Truces (" + list.size() + ")");
        if (list.isEmpty()) {
            paragraph(g, mineFilter(d) >= 0 ? "Your country has no truce in force." : "No truce in force.", COL_DIM);
            return;
        }
        paragraph(g, "From the soonest to end to the latest. Click a name to go to the country.", COL_DIM);
        for (Timed t : list) timedRow(g, d, idx, t, now, mouseX, mouseY, COL_TEXT);
    }

    /** Ultimatos pendentes: quem enviou, para quem, e quando o aceite automático acontece (72 h depois). */
    List<Timed> ultimatumList(SiteTerritories.Data d, long now) {
        return ultimatumList(d, now, -1);
    }

    List<Timed> ultimatumList(SiteTerritories.Data d, long now, int me) {
        List<Timed> list = new ArrayList<>();
        if (d.war != null && d.war.ultimatums != null) {
            for (SiteTerritories.Ultimatum u : d.war.ultimatums) {
                long sent = parseMs(u.sent);
                if (sent == NONE || sent + ULTIMATUM_MS <= now) continue;
                if (me < 0 || nameIsMe(d, u.from, me) || nameIsMe(d, u.to, me)) list.add(new Timed(u.from, u.to, "->", sent + ULTIMATUM_MS));
            }
        }
        list.sort((x, y) -> Long.compare(x.until(), y.until()));
        return list;
    }

    void dmUltimatums(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        Map<String, Integer> idx = Relations.nameIndex(d);
        mineToggle(g, d, mouseX, mouseY);
        List<Timed> list = ultimatumList(d, now, mineFilter(d));
        section(g, "Pending ultimatums (" + list.size() + ")");
        if (list.isEmpty()) {
            paragraph(g, mineFilter(d) >= 0 ? "Your country has no pending ultimatum." : "No pending ultimatum.", COL_DIM);
            return;
        }
        paragraph(g, "With no answer in 72 hours, the ultimatum is accepted automatically and a 3-day truce begins.", COL_DIM);
        for (Timed t : list) timedRow(g, d, idx, t, now, mouseX, mouseY, COL_WAR);
    }

    void dmNaps(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
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
        section(g, "Active NAPs (" + list.size() + ")");
        if (list.isEmpty()) {
            paragraph(g, "No active non-aggression pact on the site.", COL_DIM);
            return;
        }
        paragraph(g, "Click a name to go to the country. Those with a deadline come first, soonest to latest.", COL_DIM);
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
                String when = n.until() == NONE ? "no deadline on the site" : countdown(n.until() - now);
                g.drawString(font, when, bx + 11, rowTop + 13, n.until() == NONE ? COL_DIM : COL_TEXT, false);
            }
            rowIdx++;
            cy += 24;
        }
    }

    record Purgeable(String site, SiteTerritories.Player p, long end) {}

    void dmPurge(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
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
        section(g, "About to be purged");
        paragraph(g, soon + " player(s) 7 days or less from purge; " + overdue + " already past the deadline. Immune players are left out.", COL_TEXT);
        textButton(g, bx + 9, cy, bw - 18, 16, dmAllPurge ? "Showing all (click: only the next ones)" : "Showing the next 7 days (click: all)",
                mouseX, mouseY, true, () -> dmAllPurge = !dmAllPurge);
        cy += 21;
        if (list.isEmpty()) {
            paragraph(g, "Nobody in this situation right now.", COL_DIM);
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
                String when = left < 0 ? "overdue " + countdown(left) : countdown(left);
                int color = left < 0 ? 0xFFE06A6A : left < 2 * DAY ? 0xFFE8B04A : COL_TEXT;
                drawFit(g, pu.site(), bw - 36 - font.width(when), bx + 11, rowTop + 3, hover ? 0xFFDDE7EE : COL_LINK, false);
                g.drawString(font, when, bx + bw - 11 - font.width(when), rowTop + 3, color, false);
                String sub = (pu.p().country == null ? "" : pu.p().country)
                        + (pu.p().purge.realm == null ? "" : "  -  realm " + pu.p().purge.realm)
                        + "  -  " + pu.p().purge.days + " days";
                drawFit(g, sub, bw - 24, bx + 11, rowTop + 13, COL_DIM, false);
                final String site = pu.site();
                hits.add(new Hit(bx + 6, rowTop, bw - 12, 24, () -> openPlayer(site), null));
            }
            rowIdx++;
            cy += 24;
        }
        if (list.size() > 150) paragraph(g, "... and " + (list.size() - 150) + " more (use the next-7-days filter).", COL_DIM);
    }

    static final class Lk {
        String name;
        int country = -1;
        final List<String> reasons = new ArrayList<>();
        long end = NONE;
    }

    void dmLocks(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
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
                l.reasons.add("Foundation".equals(lk.kind) ? "recent foundation (no ultimatums, alliances or guarantees)" : "set by staff");
                if (u != NONE && (l.end == NONE || u > l.end)) l.end = u;
            }
        }
        // beligerantes: países em guerra ainda aberta
        for (SiteTerritories.WarInfo war : Relations.wars(d)) {
            long ended = parseMs(war.ended);
            if (war.ended != null && !war.ended.isBlank() && (ended == NONE || ended <= now)) continue;
            if (war.sides == null) continue;
            for (SiteTerritories.Side side : war.sides) {
                for (int c : Relations.countriesOf(d, side)) get.apply(d.labels[c], c).reasons.add("belligerent: " + (war.name == null ? war.id : war.name));
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
                a.reasons.add("ultimatum with " + u.to);
                b.reasons.add("ultimatum from " + u.from);
                if (a.end == NONE || end > a.end) a.end = end;
                if (b.end == NONE || end > b.end) b.end = end;
            }
        }
        List<Lk> list = new ArrayList<>(map.values());
        list.removeIf(l -> l.reasons.isEmpty());
        section(g, "Diplolocked countries (" + list.size() + ")");
        if (list.isEmpty()) {
            paragraph(g, "No diplolocked country right now: no recent foundation, no open war, no pending ultimatum and no staff block.", COL_DIM);
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

    void drawGenPanel(GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        boolean remote = ChunkGen.isRemote(mc);
        boolean running = ChunkGen.state != ChunkGen.State.IDLE;
        int top = header(g, NEUTRAL, "Generate chunks", remote ? "server (teleport)" : "local world (integrated server)", null, mouseX, mouseY, () -> {
            tab = TAB_NONE;
            genConfirm = false;
        });
        beginScroll(g, top, SCROLL[TAB_GEN], 0);

        int[] lim = running ? ChunkGen.limit : ChunkGen.computeLimit(mc);
        section(g, "MAP LIMIT");
        if (lim == null) {
            paragraph(g, "Could not determine the map size (no world border and no site data). Set worldHalfSize in config/bmcatlas/site.json.", 0xFFE06A6A);
        } else {
            kv(g, "Source", ChunkGen.limitSource);
            kv(g, "X", lim[0] + " to " + lim[2]);
            kv(g, "Z", lim[1] + " to " + lim[3]);
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
            kv(g, "On the map", ChunkGen.known + " / " + ChunkGen.total);
            kv(g, "Time left", ChunkGen.eta());
            kv(g, "State", ChunkGen.state == ChunkGen.State.PAUSED ? "paused" : "running");
        }
        if (ChunkGen.message != null && !ChunkGen.message.isBlank()) paragraph(g, ChunkGen.message, 0xFFDDE7EE);

        cy += 4;
        int bxx = bx + 9;
        int full = bw - 18;
        if (running) {
            textButton(g, bxx, cy, full / 2 - 2, 18, ChunkGen.state == ChunkGen.State.PAUSED ? "Resume" : "Pause", mouseX, mouseY, true, ChunkGen::togglePause);
            textButton(g, bxx + full / 2 + 2, cy, full / 2 - 2, 18, "Stop", mouseX, mouseY, true, () -> ChunkGen.stop(mc, "Stopped by you."));
            cy += 24;
        } else if (genConfirm && remote) {
            paragraph(g, "WARNING: this uses /tp to take you across the whole map in spectator mode, and returns you at the end. It needs operator permission and is heavy on the server: only do it with the admins' permission.", 0xFFE06A6A);
            textButton(g, bxx, cy, full / 2 - 2, 18, "Confirm", mouseX, mouseY, lim != null, () -> {
                genConfirm = false;
                ChunkGen.start(mc);
            });
            textButton(g, bxx + full / 2 + 2, cy, full / 2 - 2, 18, "Cancel", mouseX, mouseY, true, () -> genConfirm = false);
            cy += 24;
        } else {
            textButton(g, bxx, cy, full, 18, "Generate all chunks", mouseX, mouseY, lim != null, () -> {
                if (remote) genConfirm = true;
                else ChunkGen.start(mc);
            });
            cy += 24;
        }

        section(g, "HOW IT WORKS");
        if (remote) {
            paragraph(g, "On a server, the mod teleports you (spectator mode) over a grid of points inside the limit, waits for the chunks to arrive and draws them on the map. At the end you return to your starting point and game mode. It can take a long time; you can pause or stop. Every teleport shows in chat.", COL_DIM);
        } else {
            paragraph(g, "In a local world, the mod asks the integrated server for chunks, nearest first, without moving you, and draws the map as they arrive. The game may get heavier while it runs. You can pause, stop and close the map: it keeps going in the background.", COL_DIM);
        }
        SCROLL[TAB_GEN] = endScroll(g, SCROLL[TAB_GEN]);
    }

    // ---------------------------------------------------------- cartão do jogador

    SiteTerritories.Player findPlayer(SiteTerritories.Data d, String name) {
        if (name == null) return null;
        SiteTerritories.Player p = d.players.get(name);
        if (p != null) return p;
        for (Map.Entry<String, SiteTerritories.Player> e : d.players.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    void drawCard(GuiGraphics g, int mouseX, int mouseY) {
        if (selPlayer == null) return;
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) {
            selPlayer = null;
            return;
        }
        SiteTerritories.Player p = findPlayer(d, selPlayer);
        cardX = panelVisible ? panelX + panelW + 6 : MARGIN;
        if (cardX + CARD_W > width - MARGIN) cardX = Math.max(MARGIN, width - CARD_W - MARGIN);
        cardY = TOP + 8;
        cardH = height - cardY - MARGIN;
        if (cardX + CARD_W > width - barWLocal() - MARGIN) cardH -= barHLocal() + 8;
        cardVisible = true;
        bx = cardX;
        by = cardY;
        bw = CARD_W;
        bh = cardH;
        box(g, bx, by, bw, bh, COL_PANEL);
        hits.add(new Hit(bx, by, bw, bh, null, null));

        String sub = p == null ? "not recorded on the site" : p.ign == null ? null : "in game: " + p.ign;
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
            g.drawString(font, skinUrl == null ? "no skin" : "...", figX + 4, figY + 44, COL_DIM, false);
        }
        int tx = figX + 60, ty = top + 8;
        int tw = bx + bw - 10 - tx;
        if (p != null) {
            if (p.ign != null) {
                drawFit(g, p.ign, tw, tx, ty, 0xFFFFFFFF, false);
                ty += 12;
            }
            if (p.country != null) {
                drawFit(g, p.country, tw, tx, ty, COL_TEXT, false);
                ty += 12;
            }
            if (p.immune != null) {
                for (String line : wrap("Immune: " + p.immune, tw)) {
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
            OnlinePlayers.Entry on = onlineEntry(d, selPlayer);
            if (on != null) {
                final OnlinePlayers.Entry fe = on;
                kvLink(g, "Now", "Online at X " + (int) Math.floor(on.toX) + ", Z " + (int) Math.floor(on.toZ) + " (click to go)", mouseX, mouseY, () -> {
                    viewX = fe.toX;
                    viewZ = fe.toZ;
                    if (scale < 0.7) scale = 0.7;
                });
            } else if (OnlinePlayers.live() && SiteTerritories.showPlayers()) {
                kv(g, "Now", "Offline");
            }
            kv(g, "Last login", p.lastJoined == null ? null : ago(p.lastJoined));
            if (p.purge != null) {
                kv(g, "Realm", p.purge.realm);
                kv(g, "Population", p.purge.population > 0 ? String.valueOf(p.purge.population) : null);
                kv(g, "Deadline (purge)", p.purge.days > 0 ? p.purge.days + " days" : null);
            }
            kv(g, "Purgeable", purgeText(p));
            if (p.current != null && !p.current.isEmpty()) {
                section(g, "CURRENT AFFILIATIONS");
                for (String s : p.current) paragraph(g, s);
            }
            if (p.affiliations != null && !p.affiliations.isEmpty()) {
                section(g, "HISTORY");
                for (String s : p.affiliations) paragraph(g, s, COL_DIM);
            }
            List<SiteTerritories.Locality> lordships = new ArrayList<>();
            for (SiteTerritories.Locality l : d.localities) {
                if (l.lord != null && l.lord.equalsIgnoreCase(selPlayer)) lordships.add(l);
            }
            if (!lordships.isEmpty()) {
                section(g, "LORDSHIPS (" + lordships.size() + ")");
                for (SiteTerritories.Locality l : lordships) {
                    String kind = pretty(l.kind);
                    String label = pretty(l.name) + (kind == null || kind.isBlank() ? "" : " (" + kind + ")");
                    int rowTop = cy;
                    boolean vis = visible(rowTop, 12);
                    boolean hover = vis && mouseX >= bx + 6 && mouseX < bx + bw - 6 && mouseY >= rowTop && mouseY < rowTop + 12;
                    zebra(g, rowTop, 12, hover, false);
                    rowIdx++;
                    drawFit(g, label, bw - 24, bx + 10, rowTop + 2, hover ? 0xFFDDE7EE : COL_LINK, false);
                    if (vis) hits.add(new Hit(bx + 6, rowTop, bw - 12, 12, () -> focusPoint(l), null));
                    cy += 12;
                }
            }
            if (p.notes != null && !p.notes.isBlank()) {
                section(g, "NOTAS");
                paragraph(g, p.notes);
            }
        } else {
            paragraph(g, "This name has no player record on the site.", COL_DIM);
        }
        cardScroll = endScroll(g, cardScroll);

        if (p != null && p.ign != null) {
            int by0 = by + bh - 22;
            boolean copied = copiedFrames > 0;
            boolean hover = mouseX >= bx + 9 && mouseX < bx + bw - 9 && mouseY >= by0 && mouseY < by0 + 18;
            g.fill(bx + 9, by0, bx + bw - 9, by0 + 18, hover ? 0xFF3A3E42 : 0xFF1D1F1E);
            g.fill(bx + 9, by0, bx + bw - 9, by0 + 1, COL_GOLD_DIM);
            g.fill(bx + 9, by0 + 17, bx + bw - 9, by0 + 18, COL_GOLD_DIM);
            String label = copied ? "Link copied!" : "Copy NameMC link";
            g.drawString(font, label, bx + (bw - font.width(label)) / 2, by0 + 5, COL_TEXT, false);
            final String ign = p.ign;
            hits.add(new Hit(bx + 9, by0, bw - 18, 18, () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard("https://namemc.com/profile/" + ign);
                copiedFrames = 90;
            }, null));
        }
    }

    // ---------------------------------------------------------- painel (modo manual)

    void cycleOwner(int dir) {
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

    void drawManualPanel(GuiGraphics g, int mouseX, int mouseY) {
        String ownerId = MapData.ownerOf(selPx, selPz);
        Countries.Country c = ownerId == null ? null : Countries.BY_ID.get(ownerId);
        int span = MapData.PROV * 16;
        int top = header(g, c == null ? NEUTRAL : c.rgb, c == null ? "No owner" : c.name,
                "Province " + selPx + ", " + selPz + "  (manual mode)", null, mouseX, mouseY, this::closePanel);

        beginScroll(g, top, 0, 0);
        section(g, "PROVINCE");
        kv(g, "ID", selPx + ", " + selPz);
        kv(g, "X", (selPx * span) + " to " + ((selPx + 1) * span - 1));
        kv(g, "Z", (selPz * span) + " to " + ((selPz + 1) * span - 1));
        int known = 0;
        for (int dx = 0; dx < MapData.PROV; dx++) {
            for (int dz = 0; dz < MapData.PROV; dz++) {
                if (MapData.chunkKnown(selPx * MapData.PROV + dx, selPz * MapData.PROV + dz)) known++;
            }
        }
        kv(g, "Explored", known + "/" + (MapData.PROV * MapData.PROV) + " chunks");
        kv(g, "Owner", c == null ? "Nobody" : c.name);

        cy += 6;
        int bxx = bx + 9;
        boolean enabled = !Countries.LIST.isEmpty();
        textButton(g, bxx, cy, 28, 18, "<", mouseX, mouseY, enabled, () -> cycleOwner(-1));
        textButton(g, bxx + 32, cy, 28, 18, ">", mouseX, mouseY, enabled, () -> cycleOwner(1));
        textButton(g, bxx + 64, cy, bw - 18 - 64, 18, "No owner", mouseX, mouseY, true,
                () -> MapData.setOwner(selPx, selPz, null));
        cy += 24;
        paragraph(g, "No site data: provinces (32x32 blocks) are assigned by hand. Edit config/bmcatlas/countries.json to change the countries.");
        endScroll(g, 0);
    }

    void drawManualList(GuiGraphics g, int mouseX, int mouseY) {
        int top = header(g, NEUTRAL, "Countries", "manual mode", null, mouseX, mouseY, this::closePanel);
        refreshStats();
        beginScroll(g, top, SCROLL[TAB_LIST], 0);
        for (Countries.Country c : Countries.LIST) {
            int rowTop = cy;
            if ((rowIdx & 1) == 0) g.fill(bx + 4, rowTop, bx + bw - 4, rowTop + 14, 0x16FFFFFF);
            g.fill(bx + 9, rowTop + 3, bx + 21, rowTop + 11, 0xFF05080C);
            g.fill(bx + 10, rowTop + 4, bx + 20, rowTop + 10, c.rgb);
            double[] s = stats.get(c.id);
            String count = String.valueOf(s == null ? 0 : (int) s[2]);
            drawFit(g, c.name, bw - 70, bx + 27, rowTop + 3, COL_TEXT, false);
            g.drawString(font, count, bx + bw - 10 - font.width(count), rowTop + 3, COL_DIM, false);
            rowIdx++;
            cy += 14;
        }
        SCROLL[TAB_LIST] = endScroll(g, SCROLL[TAB_LIST]);
    }


    static boolean siteMode() {
        return MapData.siteApplies() && SiteTerritories.active();
    }

    /** Scale of the menus over the game interface scale: 1 = as the game draws its own menus. */
    static float ui = 1f;

    static SiteTerritories.Zone zoneOf(SiteTerritories.Data d, SiteTerritories.Locality l) {
        return d.zoneByKey.get(SiteTerritories.zoneKey(l.name, l.country));
    }

    static final int COL_WAR = 0xFFE06A6A;

    static SiteTerritories.WarInfo viewedWar(SiteTerritories.Data d) {
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        return warSel >= 0 && warSel < wars.size() ? wars.get(warSel) : null;
    }

    static int ctlColor(SiteTerritories.WarInfo war, int ctl) {
        if (ctl >= 0 && war.sides != null && ctl < war.sides.size()) return 0xFF000000 | Relations.sideColor(war.sides.get(ctl), ctl);
        if (ctl == WarFront.CONTESTED) return 0xFF000000 | Relations.CONTESTED;
        if (ctl == WarFront.UNCONTROLLED) return 0xFF000000 | Relations.UNCONTROLLED;
        return 0xFF9A9A9A;
    }

    // the map side of the screen, implemented by MapScreen
    abstract int barWLocal();
    abstract int barHLocal();
    abstract void refreshStats();
    abstract RemoteImages.Img cityImage(SiteTerritories.Locality l, int size);
    abstract int mineFilter(SiteTerritories.Data d);
    abstract void mineToggle(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY);
    abstract void warSim(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY);
    abstract void warTugHeader(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, WarScore.Result r);
    abstract int activeWarCount();
    abstract WarFront viewedFront(SiteTerritories.Data d);
}
