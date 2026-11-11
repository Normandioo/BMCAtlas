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

/** Tela cheia do mapa, with a bronze-framed grand-strategy look. */
public class MapScreen extends MapEditor {

    public MapScreen() {
        super(Component.literal("Map"));
    }

    /** O modo com que o minimapa pinta o terreno: o do mapa grande, mas os de relações viram o político. */
    static int minimapMode() {
        // guerra/diplomacia ativas no mapa também pintam o minimapa (as cores ficam guardadas em MapStyle)
        return mode;
    }

    /** Camada de limites quando a tela do mapa está fechada (o minimapa usa as mesmas texturas). */
    static void idleTint() {
        if (mode == MODE_WAR) return;     // no modo guerra quem pinta as zonas é o liveTick; os dois brigavam e repintavam o minimapa a cada quadro
        SiteTerritories.Data d = SiteTerritories.data();
        int[] t = null;
        if (d != null && siteMode() && !d.zones.isEmpty() && showLimits) {
            t = new int[d.zones.size()];
            Arrays.fill(t, 0x1AFFFFFF);
        }
        if (!Arrays.equals(t, lastTint)) {
            lastTint = t;
            MapStyle.zoneTint = t;
            MapStyle.overrideVersion++;
        }
    }

    private static boolean relationalMode() {
        return MapStyle.relational(mode);
    }

    // ------------------------------------------------------------------ init

    @Override
    protected void init() {
        applyUi();
        String oldQuery = searchBox == null ? "" : searchBox.getValue();
        searchBox = new EditBox(font, 0, 0, 200, 12, Component.literal("Search"));
        searchBox.setMaxLength(40);
        searchBox.setBordered(false);
        searchBox.setValue(oldQuery);
        searchBox.setResponder(q -> updateSearch());
        if (searchOpen) setFocused(searchBox);
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
        closeEditor();
        WarRecorder.stop();
        Prefs.Data pr = Prefs.get();
        if (pr.mapMode != mode || pr.mapScale != zoomTarget || pr.showCities != showCities || pr.showPlayers != showPlayers) {
            pr.mapMode = mode;
            pr.mapScale = zoomTarget;
            pr.showCities = showCities;
            pr.showPlayers = showPlayers;
            Prefs.save();
        }
        // os marcadores da guerra ficam na memória: ao reabrir o mapa o estado atual aparece na hora
        // online players stay in memory: the minimap keeps using them, with no extra request
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

    private double sx(double bx, double bz) {
        return toScreenX(bx);
    }

    private double sy(double bx, double bz) {
        return toScreenY(bz);
    }

    private double blockX(double mx, double my) {
        return toBlockX(mx);
    }

    private double blockZ(double mx, double my) {
        return toBlockZ(my);
    }

    private boolean blockedAt(List<Hit> list, double mx, double my) {
        for (Hit h : list) if (h.contains(mx, my)) return true;
        return false;
    }

    // ----------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // the whole screen is drawn in a virtual size (menu size setting, or automatic from the resolution)
        applyUi();
        mouseX = (int) (mouseX / ui);
        mouseY = (int) (mouseY / ui);
        RemoteImages.extraScale = ui;
        g.pose().pushMatrix();
        g.pose().scale(ui, ui);
        try {
            renderScaled(g, mouseX, mouseY, delta);
        } finally {
            g.pose().popMatrix();
            RemoteImages.extraScale = 1f;
        }
    }

    /**
     * The scale in use. Automatic: the screen is laid out for about 520 interface pixels of height, so on a small
     * window (or a large game interface scale) everything shrinks to fit, and on a large one it grows.
     */
    static float uiScaleFor(int guiHeight) {
        int pct = Prefs.get().uiScalePct;
        if (pct > 0) return pct / 100f;
        float auto = Math.round(guiHeight / 520f * 20f) / 20f;
        return Math.max(0.6f, Math.min(1.5f, auto));
    }

    // ---- menus resized one by one, by dragging a corner (like an image in an image editor)

    private static final int G_PANEL = 0, G_OUT = 1, G_BAR = 2;
    /** Scale of the group being drawn right now (1 outside a group). */
    private static float groupScale = 1f;
    private int gSaveW, gSaveH, gFirstHit, gFirstCut;
    private int gDrag = -1;
    private float gDragFrom;
    private double gDragAX, gDragAY, gDragDist;
    private int cardW = CARD_W;

    private static float groupPct(int id) {
        Prefs.Data p = Prefs.get();
        return (id == G_PANEL ? p.panelScalePct : id == G_OUT ? p.outlinerScalePct : p.barScalePct) / 100f;
    }

    private static void setGroupPct(int id, int pct) {
        Prefs.Data p = Prefs.get();
        pct = Math.max(50, Math.min(200, pct));
        if (id == G_PANEL) p.panelScalePct = pct;
        else if (id == G_OUT) p.outlinerScalePct = pct;
        else p.barScalePct = pct;
    }

    /** Size of the mode bar seen from the group being drawn (it may have another scale). */
    int barWLocal() {
        return Math.round(barW * groupPct(G_BAR) / groupScale);
    }

    int barHLocal() {
        return Math.round(barH * groupPct(G_BAR) / groupScale);
    }

    /**
     * Starts a group of menus drawn in their own scale: inside it the screen is width/scale by height/scale, so what
     * is anchored to an edge of the screen stays there. Returns the scale (divide the mouse by it).
     */
    private float beginGroup(GuiGraphics g, int id) {
        float s = groupPct(id);
        groupScale = s;
        gSaveW = width;
        gSaveH = height;
        width = (int) Math.ceil(width / s);
        height = (int) Math.ceil(height / s);
        RemoteImages.extraScale = ui * s;
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        gFirstHit = hits.size();
        gFirstCut = cuts.size();
        return s;
    }

    /** Ends the group: its clickable areas go to screen coordinates, and the resize handle is drawn on its corner. */
    private void endGroup(GuiGraphics g, int id, int mouseX, int mouseY) {
        float s = groupScale;
        g.pose().popMatrix();
        width = gSaveW;
        height = gSaveH;
        groupScale = 1f;
        RemoteImages.extraScale = ui;
        for (int i = gFirstCut; i < cuts.size(); i++) {   // the texts cut short, to screen coordinates too
            Cut c = cuts.get(i);
            cuts.set(i, new Cut(Math.round(c.x() * s), Math.round(c.y() * s), Math.round(c.w() * s), Math.round(c.h() * s), c.full()));
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int i = gFirstHit; i < hits.size(); i++) {
            Hit h = hits.get(i);
            int hx = Math.round(h.x() * s), hy = Math.round(h.y() * s), hw = Math.round(h.w() * s), hh = Math.round(h.h() * s);
            hits.set(i, new Hit(hx, hy, hw, hh, h.action(), h.tip()));
            x0 = Math.min(x0, hx);
            y0 = Math.min(y0, hy);
            x1 = Math.max(x1, hx + hw);
            y1 = Math.max(y1, hy + hh);
        }
        if (x1 <= x0) {
            if (gDrag == id) gDrag = -1;
            return;
        }
        // the corner that points to the middle of the screen is the one to drag; the opposite one stays in place
        boolean right = (x0 + x1) / 2 < width / 2, bottom = (y0 + y1) / 2 <= height / 2 + 10;
        int hx = Math.max(4, Math.min(width - 4, right ? x1 : x0)), hy = Math.max(4, Math.min(height - 4, bottom ? y1 : y0));
        int ax = right ? x0 : x1, ay = bottom ? y0 : y1;
        boolean dragging = gDrag == id;
        boolean near = mouseX >= x0 - 8 && mouseX < x1 + 8 && mouseY >= y0 - 8 && mouseY < y1 + 8;
        if (!dragging && (!near || gDrag >= 0)) return;
        if (dragging) {       // outline of what is being resized, and the size
            g.fill(x0, y0, x1, y0 + 1, 0xFFFFF2B0);
            g.fill(x0, y1 - 1, x1, y1, 0xFFFFF2B0);
            g.fill(x0, y0, x0 + 1, y1, 0xFFFFF2B0);
            g.fill(x1 - 1, y0, x1, y1, 0xFFFFF2B0);
            String t = Math.round(s * 100) + "%";
            int tw = font.width(t) + 8, tx = Math.max(2, Math.min(width - tw - 2, hx - tw / 2)), ty = bottom ? hy + 8 : hy - 22;
            g.fill(tx, ty, tx + tw, ty + 14, 0xF21E2123);
            g.drawString(font, t, tx + 4, ty + 3, COL_TEXT, false);
        }
        boolean hover = dragging || (Math.abs(mouseX - hx) <= 6 && Math.abs(mouseY - hy) <= 6);
        g.fill(hx - 5, hy - 5, hx + 5, hy + 5, 0xFF0A0B08);
        g.fill(hx - 4, hy - 4, hx + 4, hy + 4, hover ? 0xFFFFF2B0 : 0xFFCFD3D1);
        hits.add(new Hit(hx - 6, hy - 6, 12, 12, () -> {
            gDrag = id;
            gDragFrom = s;
            gDragAX = ax;
            gDragAY = ay;
            gDragDist = Math.max(20, Math.hypot(hoverMX - ax, hoverMY - ay));
        }, "Drag to resize"));
    }

    private void applyUi() {
        com.mojang.blaze3d.platform.Window win = Minecraft.getInstance().getWindow();
        ui = uiScaleFor(win.getGuiScaledHeight());
        width = (int) Math.ceil(win.getGuiScaledWidth() / ui);
        height = (int) Math.ceil(win.getGuiScaledHeight() / ui);
    }

    private void renderScaled(GuiGraphics g, int mouseX, int mouseY, float delta) {
        MapData.nextFrame();
        OnlinePlayers.mapOpenAt = System.currentTimeMillis();
        RemoteImages.pump();
        hoverMX = mouseX;
        hoverMY = mouseY;
        animateZoom();
        if (SiteTerritories.showPlayers()) OnlinePlayers.tick();
        Perf.begin("tick");
        tickWar();
        Perf.end("tick");
        {   // the mode bar is laid out in its own size
            int fullW = width;
            width = (int) Math.ceil(width / groupPct(G_BAR));
            layoutBar();
            width = fullW;
        }
        if (pendingFocus != null) {
            focusCity(pendingFocus);
            tab = TAB_CITY;
            pendingFocus = null;
        }
        List<Hit> t = prevHits;
        prevHits = hits;
        hits = t;
        hits.clear();
        cuts.clear();
        panelVisible = false;
        cardVisible = false;
        if (relationalMode() && !siteMode()) mode = MODE_POLITICAL;
        if (editing()) exportBeforeFrame();   // a video export sets the moment and the view of its next frame
        Perf.begin("colors");
        refreshOverrides();
        Perf.end("colors");

        g.fill(0, 0, width, height, 0xFF0B1628);
        Perf.begin("map total");
        drawMap(g, mouseX, mouseY);
        Perf.end("map total");
        if (editing()) {   // the replay editor: the map, then its own timeline instead of the menus
            drawEditor(g, mouseX, mouseY);
            if (!exportBusy()) {
                drawSearch(g, mouseX, mouseY, delta);
                drawTooltip(g, mouseX, mouseY);
            }
            exportAfterFrame();   // the frame just drawn is captured at the start of the next one
            return;
        }
        if (photoMode) {
            drawPhotoHint(g);
            return;
        }

        Perf.begin("bar");
        float gs1 = beginGroup(g, G_BAR);
        drawBar(g, (int) (mouseX / gs1), (int) (mouseY / gs1));
        endGroup(g, G_BAR, mouseX, mouseY);
        Perf.end("bar");
        Perf.begin("top bar");
        drawTopBar(g, mouseX, mouseY);
        Perf.end("top bar");
        Perf.begin("panel");
        float gs2 = beginGroup(g, G_PANEL);
        drawPanel(g, (int) (mouseX / gs2), (int) (mouseY / gs2));
        Perf.end("panel");
        Perf.begin("card");
        drawCard(g, (int) (mouseX / gs2), (int) (mouseY / gs2));
        endGroup(g, G_PANEL, mouseX, mouseY);
        // from here on the panel and the card are known in screen coordinates
        if (panelVisible) {
            panelX = Math.round(panelX * gs2);
            panelY = Math.round(panelY * gs2);
            panelW = Math.round(panelW * gs2);
            panelH = Math.round(panelH * gs2);
        }
        if (cardVisible) {
            cardX = Math.round(cardX * gs2);
            cardY = Math.round(cardY * gs2);
            cardH = Math.round(cardH * gs2);
        }
        cardW = Math.round(CARD_W * gs2);
        Perf.end("card");
        Perf.begin("outliner");
        float gs3 = beginGroup(g, G_OUT);
        drawOutliner(g, (int) (mouseX / gs3), (int) (mouseY / gs3));
        endGroup(g, G_OUT, mouseX, mouseY);
        Perf.end("outliner");
        drawRecorder(g, mouseX, mouseY);
        drawRecordingsList(g, mouseX, mouseY);
        drawHover(g, mouseX, mouseY);
        drawToolBanner(g);
        drawMarkMenu(g, mouseX, mouseY);
        drawSearch(g, mouseX, mouseY, delta);
        drawTooltip(g, mouseX, mouseY);
        drawToast(g);
        drawHelp(g);
        drawPerf(g);
        if (copiedFrames > 0) copiedFrames--;
    }

    /** Diagnóstico (F7): quanto cada parte do desenho custa, em milissegundos por quadro. */
    private void drawPerf(GuiGraphics g) {
        if (!Perf.on) return;
        int y = height - 14 - Perf.values().size() * 10 - 12, w = 190;
        g.fill(6, y - 4, 6 + w, height - 6, 0xD0101214);
        g.drawString(font, "Performance (F7 closes), ms per frame", 10, y, COL_TEXT, false);
        y += 11;
        for (Map.Entry<String, double[]> e : Perf.values().entrySet()) {
            double ms = e.getValue()[0];
            g.drawString(font, e.getKey(), 10, y, COL_DIM, false);
            String v = String.format(Locale.ROOT, "%.2f", ms);
            g.drawString(font, v, 6 + w - 6 - font.width(v), y, ms > 3 ? 0xFFE8403A : ms > 1 ? 0xFFE0BE62 : COL_TEXT, false);
            y += 10;
        }
    }

    /** Chamado pelo minimapa a cada quadro: mantém a guerra atualizada mesmo com o mapa grande fechado. */
    static void liveTick() {
        tickWar();
        if (mode == MODE_WAR) refreshOverrides();
    }

    /** Abre o mapa na cidade, no modo guerra, com o destaque pulsante. */
    static void openAt(SiteTerritories.Locality city) {
        pendingFocus = city;
        mode = MODE_WAR;
        Compat.setScreen(Minecraft.getInstance(), new MapScreen());
    }

    /** A guerra que o minimapa acompanha (a escolhida no mapa, senão a primeira em andamento). */
    static SiteTerritories.WarInfo minimapWar(SiteTerritories.Data d) {
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        SiteTerritories.WarInfo w = warSel >= 0 && warSel < wars.size() ? wars.get(warSel) : WarStatus.followed(d, null);
        return w != null && w.sides != null && w.sides.size() >= 2 && WarStatus.running(w) ? w : null;
    }

    static RemoteImages.Img noticeFlag(SiteTerritories.Data d, int country) {
        if (country < 0 || d.info[country] == null) return null;
        return RemoteImages.flag(SiteTerritories.assetUrl(d.info[country].get("flag"), false));
    }

    private static int[] lastTint;

    static void refreshOverrides() {
        refreshRelations();
        refreshZoneTint();
    }

    /** Cor de dentro das zonas de cidade (limites ligados, cidade escolhida, guerra); repinta as regiões se mudou. */
    static void refreshZoneTint() {
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
    static void refreshRelations() {
        SiteTerritories.Data d = SiteTerritories.data();
        boolean rel = relationalMode() && siteMode() && d != null;
        if (d != null) {
            if (dipFocus >= d.ids.length) dipFocus = -1;
            if (selCity >= d.localities.size()) selCity = -1;
            if (dipAlliance >= (d.war == null || d.war.alliances == null ? 0 : d.war.alliances.size())) dipAlliance = -1;
            if (warSel >= Relations.wars(d).size()) warSel = -1;
        }
        int sig = Objects.hash(mode, dipFocus, dipAlliance, warSel, SiteTerritories.version(), rel, mode == MODE_WAR ? DynmapMarkers.markersAt() + 31 * ServerSync.version() : 0L, WarRecorder.index());
        if (sig == ovSig) return;
        ovSig = sig;
        if (!rel) {
            MapStyle.countryOverride = null;
            MapStyle.provinceOverride = null;
            legend = List.of();
            zoneColors = Map.of();
            WarWave.clear();
            lastWaveKey = null;
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
        // só repinta o mapa se as cores realmente mudaram (a leitura do dynmap chega a cada poucos segundos)
        boolean same = Arrays.equals(MapStyle.countryOverride, r.country()) && Objects.equals(MapStyle.provinceOverride, r.provinces())
                && Objects.equals(zoneColors, r.zones());
        // onda de conquista: só entre duas leituras da mesma guerra no mesmo mapa (nada anima ao entrar)
        MapCatalog.Entry waveMap = SiteTerritories.activeMap();
        String waveKey = mode == MODE_WAR ? warSel + "|" + (waveMap == null ? "" : waveMap.key) : null;
        boolean waveOk = WarRecorder.waveAllowed();   // no wave after a jump in a replay, going back in time, or back to live
        if (waveKey == null || !waveKey.equals(lastWaveKey)) WarWave.clear();
        else if (!waveOk) WarWave.clear();
        else if (!same && MapStyle.provinceOverride != null && MapStyle.countryOverride != null) {
            WarWave.changes(d, MapStyle.provinceOverride, MapStyle.countryOverride, r.provinces(), r.country(), MapClock.anim());
        }
        lastWaveKey = waveKey;
        MapStyle.countryOverride = r.country();
        MapStyle.provinceOverride = r.provinces();
        if (!same) MapStyle.overrideVersion++;
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
        Perf.begin("terrain");
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                MapData.Region r = MapData.region(rx, rz, false);
                if (r == null && sd != null && regionTouchesSite(sd, rx, rz)) r = MapData.viewRegion(rx, rz);
                if (r == null) continue;
                MapData.markUsed(r);
                boolean stale = r.id == null || r.dirty || r.styledMode != mode || r.styledVer != MapStyle.overrideVersion;
                if (stale && uploads < 2) {
                    if (r.ensureUploaded(mode)) uploads++;
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

        Perf.end("terrain");

        SiteTerritories.Data d = SiteTerritories.data();
        if (!site) {
            if (mode == MODE_PROVINCES) drawGrid(g, leftB, rightB, topB, botB);
            if (mode == MODE_POLITICAL) drawManualNames(g);
            drawManualSelection(g);
        } else if (d != null) {
            Perf.begin("waves");
            if (mode == MODE_WAR) drawWaves(g);
            Perf.end("waves");
            if (mode == MODE_POLITICAL || rel) drawSiteNames(g, d);
            Perf.begin("front");
            drawWarFront(g, d);
            Perf.end("front");
            Perf.begin("cities");
            drawCities(g, d, mouseX, mouseY);
            Perf.end("cities");
            Perf.begin("markers");
            Perf.end("markers");
        }
        if (site && d != null && simActive()) drawSimRings(g, d);
        drawMarksAndMeasure(g, mouseX, mouseY);
        Perf.begin("players");
        drawOnlinePlayers(g, site ? d : null, mouseX, mouseY);
        Perf.end("players");
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
        if (!Prefs.get().countryNames || scale > 1.2) return; // desligado, ou muito perto: os nomes só atrapalham
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
            double sx = sx(f[0], f[1]);
            double sy = sy(f[0], f[1]);
            if (sx < -400 || sx > width + 400 || sy < -200 || sy > height + 200) continue;
            drawLabel(g, text, (float) sx, (float) sy, (float) f[2], sc);
        }
    }

    private record LabelParts(Component[] comps, int[] widths, int total) {}

    private static final Map<String, LabelParts> LABELS = new HashMap<>();

    private void drawLabel(GuiGraphics g, String text, float sx, float sy, float angle, float sc) {
        int spacing = 2;
        LabelParts parts = LABELS.get(text);
        if (parts == null) {
            int total = 0;
            int[] w = new int[text.length()];
            Component[] cs = new Component[text.length()];
            for (int i = 0; i < text.length(); i++) {
                cs[i] = Component.literal(String.valueOf(text.charAt(i))).withStyle(ChatFormatting.BOLD);
                w[i] = font.width(cs[i]);
                total += w[i] + (i > 0 ? spacing : 0);
            }
            parts = new LabelParts(cs, w, total);
            LABELS.put(text, parts);
        }
        int tw = parts.total();
        int[] widths = parts.widths();
        Component[] comps = parts.comps();
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

    void refreshStats() {
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
        if (!Prefs.get().countryNames) return;
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
        int px = (int) sx(mc.player.getX(), mc.player.getZ());
        int pz = (int) sy(mc.player.getX(), mc.player.getZ());
        g.fill(px - 4, pz - 4, px + 4, pz + 4, 0xFF000000);
        g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFFE03C3C);
    }

    // ----------------------------------------------------------- limites e cidades

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
            } else if (scale < 0.5) {
                for (double[] ring : ringsFor(z)) drawRing(g, ring, color, thick);      // longe: linha contínua (bem mais barata)
            } else {
                for (double[] ring : ringsFor(z)) drawRingDashed(g, ring, color, thick, 8, 5);
            }
        }
    }

    private static SiteTerritories.Zone selZone(SiteTerritories.Data d) {
        return selCity >= 0 && selCity < d.localities.size() ? zoneOf(d, d.localities.get(selCity)) : null;
    }

    private void drawRing(GuiGraphics g, double[] ring, int color, float thick) {
        int n = ring.length / 2;
        if (n < 2) return;
        double fx = sx(ring[0], ring[1]), fy = sy(ring[0], ring[1]);
        double px = fx, py = fy;
        for (int i = 1; i < n; i++) {
            double x = sx(ring[2 * i], ring[2 * i + 1]), y = sy(ring[2 * i], ring[2 * i + 1]);
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
        double px = sx(ring[0], ring[1]), py = sy(ring[0], ring[1]);
        double fx = px, fy = py;
        for (int i = 1; i <= n; i++) {
            double x = i == n ? fx : sx(ring[2 * i], ring[2 * i + 1]), y = i == n ? fy : sy(ring[2 * i], ring[2 * i + 1]);
            double dx = x - px, dy = y - py, len = Math.hypot(dx, dy);
            if (len < 0.5 && i < n) continue;
            if ((px < 0 && x < 0) || (px > width && x > width) || (py < 0 && y < 0) || (py > height && y > height)) {
                pos += len;                       // fora da tela: só avança a fase
                px = x;
                py = y;
                continue;
            }
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

    private static final double[] ICON_MIN = {0.35, 0.16, 0.08, 0.04, 0.025};
    private static final double[] NAME_MIN = {0.9, 0.45, 0.22, 0.12, 0.07};
    private static final int[] ICON_PX = {10, 13, 15, 18, 22};

    private boolean cityVisible(SiteTerritories.Locality l) {
        return showCities && scale >= ICON_MIN[l.tier] * 100.0 / Prefs.get().zoomBiasPct;
    }

    /** A cidade mais importante sob o ponto da tela, ou null. */
    private SiteTerritories.Locality cityAt(double mx, double my) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null || !siteMode() || !showCities) return null;
        for (SiteTerritories.Locality l : d.cityOrder) {
            if (!cityVisible(l)) continue;
            double sx = sx(l.x + 0.5, l.z + 0.5), sy = sy(l.x + 0.5, l.z + 0.5);
            double r = iconRadius(l) + 3;
            if (Math.abs(mx - sx) <= r && Math.abs(my - sy) <= r) return l;
        }
        return null;
    }

    private static int iconSize(SiteTerritories.Locality l) {
        int base = "Fort".equals(l.kind) ? 14 : ICON_PX[l.tier];
        return Math.max(6, (int) Math.round(base * Prefs.get().cityIconPct / 100.0));
    }

    private static double guiScale() {
        return Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale()) * ui * groupScale;
    }

    private int iconRadius(SiteTerritories.Locality l) {
        return iconSize(l) / 2;
    }

    /** O ícone que o próprio site usa para essa localidade (icons/<tipo>.png), ou null enquanto baixa/ausente. */
    RemoteImages.Img cityImage(SiteTerritories.Locality l, int size) {
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
            int sx = (int) Math.round(sx(l.x + 0.5, l.z + 0.5));
            int sy = (int) Math.round(sy(l.x + 0.5, l.z + 0.5));
            if (sx < -30 || sx > width + 30 || sy < -30 || sy > height + 30) continue;
            int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] : 0xFF909090;
            boolean pulsing = l.idx == pulseCity && nowMs - pulseStart < PULSE_MS;
            boolean hot = l == hover || l.idx == selCity || pulsing;
            if (pulsing) drawPulse(g, sx, sy, iconRadius(l), nowMs - pulseStart);
            RemoteImages.Img img = Prefs.get().simpleMarkers ? null : cityImage(l, (int) Math.round(iconSize(l) * guiScale()));
            if (img != null) {
                if (hot) {
                    int r = iconRadius(l) + 3;
                    g.fill(sx - r, sy - r, sx + r + 1, sy + r + 1, 0x70FFE27A);
                }
                RemoteImages.drawCrisp(g, img, sx, sy);
            } else {
                drawIcon(g, sx, sy, l, rgb, hot);
            }
            if ((Prefs.get().cityNames && scale >= NAME_MIN[l.tier] * 100.0 / Prefs.get().zoomBiasPct) || hot) {
                String name = SiteTerritories.splitCamel(l.name);
                int w = nameWidth(name) + 2;
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

    private static final Map<String, Integer> NAME_W = new HashMap<>();

    private int nameWidth(String s) {
        return NAME_W.computeIfAbsent(s, k -> font.width(k));
    }

    /** Reserva: quadradinho colorido enquanto o ícone do site não chega. */
    private void drawIcon(GuiGraphics g, int x, int y, SiteTerritories.Locality l, int rgb, boolean hot) {
        int r = Math.max(1, iconRadius(l) / 2);
        int fill = lighten(rgb, 1.3f);
        int dark = 0xFF05080C;
        if (hot) g.fill(x - r - 2, y - r - 2, x + r + 3, y + r + 3, 0xFFFFF2B0);
        g.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2, l.tier == 4 ? 0xFFD9B45A : dark);
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
            double f = Prefs.get().playerIconPct / 100.0;
            if (mx >= sp.sx() - 10 * f && mx < sp.sx() + 30 * f && my >= sp.sy() - 10 * f && my < sp.sy() + 10 * f) return sp;
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
            if (!WarRecorder.replaying() && e.account.equalsIgnoreCase(self)) continue;   // in a replay you are one of the recorded players
            int sx = (int) Math.round(sx(e.x(now), e.z(now)));
            int sy = (int) Math.round(sy(e.x(now), e.z(now)));
            if (sx < -40 || sx > width + 40 || sy < -40 || sy > height + 40) continue;
            String low = e.account.toLowerCase(Locale.ROOT);
            String site = d == null ? null : d.ignToSite.get(low);
            Integer ci = d == null ? null : d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : low);
            int country = ci == null ? -1 : ci;
            Spot sp = new Spot(e, sx, sy, country, site);
            spots.add(sp);
            boolean hot = hover != null && hover.e() == e;
            int rgb = country >= 0 ? 0xFF000000 | d.rgb[country] : 0xFF909090;

            float pf = Prefs.get().playerIconPct / 100f;
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            g.pose().scale(pf, pf);
            // cabeça 16x16 com moldura na cor do país
            g.fill(0 - 10, 0 - 10, 0 + 10, 0 + 10, 0xFF05080C);
            g.fill(0 - 9, 0 - 9, 0 + 9, 0 + 9, hot ? 0xFFFFE27A : rgb);
            Replay rp = WarRecorder.current();   // in a replay: the face kept with the recording, else today's
            RemoteImages.Img face = rp != null ? RemoteImages.localIcon(rp.face(e.account), 16) : null;
            if (face == null) face = dyn == null ? null : RemoteImages.icon(dyn + "tiles/faces/32x32/" + e.account + ".png", 16);
            boolean drawn = false;
            if (face != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, face.id(), 0 - 8, 0 - 8, 0f, 0f, 16, 16, face.w(), face.h(), face.w(), face.h());
                drawn = true;
            } else if (d != null && site != null) {
                SiteTerritories.Player p = d.players.get(site);
                RemoteImages.Img skin = p == null ? null : RemoteImages.skin(SiteTerritories.assetUrl(p.skin, false));
                if (skin != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, skin.id(), 0 - 8, 0 - 8, 4f, 0f, 16, 16, 8, 8, skin.w(), skin.h());
                    drawn = true;
                }
            }
            if (!drawn) {
                g.fill(0 - 8, 0 - 8, 0 + 8, 0 + 8, 0xFF3A3F45);
                g.drawString(font, e.account.substring(0, 1).toUpperCase(Locale.ROOT), 0 - 2, 0 - 4, COL_TEXT, false);
            }
            // bandeira do país ao lado
            if (country >= 0) {
                g.fill(0 + 10, 0 - 7, 0 + 30, 0 + 7, 0xFF05080C);
                RemoteImages.Img flag = RemoteImages.flag(flagOf(d, country));
                if (flag != null) drawImage(g, flag, 0 + 11, 0 - 6, 18, 12);
                else g.fill(0 + 11, 0 - 6, 0 + 29, 0 + 6, rgb);
            }
            g.pose().popMatrix();
            if (scale >= 0.5 || hot) {
                String name = e.account;
                int w = font.width(name);
                g.drawString(font, name, sx - w / 2 + (country >= 0 ? Math.round(10 * pf) : 0), sy + Math.round(12 * pf), hot ? 0xFFFFF2B0 : 0xFFF0EAD6, true);
            }
        }
    }

    // ------------------------------------------------------------- coordenadas


    // ------------------------------------------------------------ zoom, toast, help, data age

    private void animateZoom() {
        long now = System.nanoTime();
        double dt = Math.min(50.0, (now - zoomAt) / 1e6);
        zoomAt = now;
        if (scale != zoomSeen) {
            zoomTarget = scale;                    // set somewhere else (focus on a city, fit a country)
        } else if (scale != zoomTarget) {
            double k = 1 - Math.exp(-dt / 45.0);
            scale = Math.abs(zoomTarget - scale) < scale * 0.004 ? zoomTarget : scale * Math.pow(zoomTarget / scale, k);
            viewX = zoomBX - (zoomMX - areaCx()) / scale;
            viewZ = zoomBZ - (zoomMY - areaCy()) / scale;
        }
        zoomSeen = scale;
    }

    /** Zooms by a factor around a point of the screen. */
    private void zoomBy(double factor, double mx, double my) {
        zoomBX = toBlockX(mx);
        zoomBZ = toBlockZ(my);
        zoomMX = mx;
        zoomMY = my;
        zoomTarget = Math.max(0.03, Math.min(8.0, zoomTarget * factor));
    }

    private void toast(String text) {
        toastText = text;
        toastUntil = System.currentTimeMillis() + 1800;
    }

    private void drawToast(GuiGraphics g) {
        if (toastText == null || System.currentTimeMillis() > toastUntil) return;
        int w = font.width(toastText) + 14, x = (width - w) / 2, y = height - 84;
        g.fill(x, y, x + w, y + 16, 0xF21E2123);
        g.fill(x, y + 15, x + w, y + 16, 0xFF7E8387);
        g.drawString(font, toastText, x + 7, y + 4, COL_TEXT, false);
    }

    private void copyCoords() {
        if (blockedAt(hits, hoverMX, hoverMY)) return;
        String text = (int) Math.floor(blockX(hoverMX, hoverMY)) + " " + (int) Math.floor(blockZ(hoverMX, hoverMY));
        try {
            Compat.setClipboard(Minecraft.getInstance(), text);
            toast("Copied: " + text);
        } catch (RuntimeException e) {
            toast("Could not copy");
        }
    }

    private static final String[][] HELP = {
            {"Drag", "Move the map"}, {"Wheel, + / -", "Zoom"}, {"Arrows, W A S D", "Move the map"},
            {"Space", "Center on you"}, {"F or /", "Search"}, {"C", "Copy the coordinates under the cursor"},
            {"M", "Mark a point (click a point to rename, recolor or delete it)"}, {"R", "Measure a route"},
            {"Backspace", "Remove the last point of the route"}, {"1 - 5", "Map modes"},
            {"Right click", "Close the panel / leave a tool"}, {"F2", "Screenshot (use Photo in the search box for a clean map)"},
            {"F7", "Performance overlay"}, {"H", "This help"},
    };

    private void drawHelp(GuiGraphics g) {
        if (!helpOpen) return;
        int keyW = 0, textW = 0;
        for (String[] row : HELP) {
            keyW = Math.max(keyW, font.width(row[0]));
            textW = Math.max(textW, font.width(row[1]));
        }
        int w = Math.min(width - 16, keyW + textW + 34), h = HELP.length * 12 + 30;
        int x = (width - w) / 2, y = Math.max(4, (height - h) / 2);
        g.fill(0, 0, width, height, 0x90000000);
        box(g, x, y, w, h, COL_PANEL);
        g.drawString(font, "Keys", x + 10, y + 8, COL_TEXT, false);
        int ry = y + 24;
        for (String[] row : HELP) {
            g.drawString(font, row[0], x + 10, ry, 0xFFE8D9A0, false);
            drawFit(g, row[1], w - keyW - 30, x + 22 + keyW, ry, COL_TEXT, false);
            ry += 12;
        }
    }

    /** Bottom right: how old the site data is, in orange when the site cannot be reached. */
    private void drawDataAge(GuiGraphics g) {
        if (SiteTerritories.data() == null) return;
        long at = SiteTerritories.checkedAt();
        if (at <= 0) return;
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        String age = min < 1 ? "just now" : min < 90 ? min + " min ago" : min < 2880 ? (min / 60) + " h ago" : (min / 1440) + " days ago";
        String err = SiteTerritories.lastError();
        boolean stale = err != null && min > Math.max(5, SiteTerritories.refreshMinutes());
        String text = stale ? "Site unreachable (" + err + "). Data from " + age : "Site data checked " + age;
        g.drawString(font, text, width - MARGIN - font.width(text), height - 12, stale ? 0xFFF0A040 : COL_DIM, true);
    }

    // ------------------------------------------------------------ marked points

    private Prefs.Mark markAt(double bx0, double bz0) {
        String map = activeMapKey();
        double radius = 7 / Math.max(scale, 0.01);
        for (Prefs.Mark m : Prefs.get().marks) {
            if (map.equals(m.map) && Math.hypot(m.x + 0.5 - bx0, m.z + 0.5 - bz0) <= radius) return m;
        }
        return null;
    }

    /** A name asked by the replay editor in the search box (Enter saves, Esc cancels). */
    private java.util.function.Consumer<String> renameTo;

    @Override
    void askName(String current, java.util.function.Consumer<String> done) {
        searchOpen = true;
        renameMark = null;
        renameTo = done;
        searchBox.setValue(current == null ? "" : current);
        setFocused(searchBox);
        searchHits = List.of();
    }

    private void startRename(Prefs.Mark m) {
        searchOpen = true;
        renameMark = m;
        searchBox.setValue(m.name == null ? "" : m.name);
        setFocused(searchBox);
        searchHits = List.of();
    }

    /** Rename / color / delete, next to the marked point that was clicked. */
    private void drawMarkMenu(GuiGraphics g, int mouseX, int mouseY) {
        Prefs.Mark m = markMenu;
        if (m == null) return;
        if (!Prefs.get().marks.contains(m) || searchOpen) {
            if (!searchOpen) markMenu = null;
            return;
        }
        int sx = (int) Math.round(toScreenX(m.x + 0.5)), sy = (int) Math.round(toScreenY(m.z + 0.5));
        int w = 170, h = 38;
        int x = Math.max(4, Math.min(width - w - 4, sx + 8)), y = Math.max(TOP + 4, Math.min(height - h - 4, sy + 8));
        box(g, x, y, w, h, COL_PANEL);
        hits.add(new Hit(x, y, w, h, null, null));
        g.fill(x + 7, y + 6, x + 13, y + 12, Prefs.markColor(m));
        drawFit(g, (m.name == null ? "Point" : m.name) + "  " + m.x + ", " + m.z, w - 24, x + 17, y + 5, COL_TEXT, false);
        int bw3 = (w - 18) / 3;
        textButtonFixed(g, x + 7, y + 18, bw3, 16, "Rename", mouseX, mouseY, () -> startRename(m));
        textButtonFixed(g, x + 9 + bw3, y + 18, bw3, 16, "Color", mouseX, mouseY, () -> {
            m.color = (m.color + 1) % Prefs.MARK_COLORS.length;
            Prefs.save();
        });
        textButtonFixed(g, x + 11 + 2 * bw3, y + 18, bw3, 16, "Delete", mouseX, mouseY, () -> {
            Prefs.get().marks.remove(m);
            Prefs.save();
            markMenu = null;
        });
    }

    // ------------------------------------------------------------ war recording

    /** The timeline under the recorder bar while a replay is on screen (where a drag seeks), or 0 wide. */
    private int timelineX, timelineW;
    private boolean timelineDrag;
    private SiteTerritories.WarInfo timelineWar;

    private void seekTimeline(double mx) {
        if (timelineW <= 0 || timelineWar == null) return;
        WarRecorder.seekFraction(timelineWar, (mx - timelineX) / timelineW);
    }

    /** Bar at the bottom of the war map: record the war over time and play the recording back. */
    private void drawRecorder(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        SiteTerritories.WarInfo w = mode == MODE_WAR && siteMode() && d != null ? viewedWar(d) : null;
        WarRecorder.tick(w);
        timelineW = 0;
        timelineWar = w;
        if (w == null) return;
        int n = WarRecorder.count(w);
        boolean rec = WarRecorder.recording(w), replay = WarRecorder.replaying();
        String dot = "● REC " + ReplayEditor.clock(WarRecorder.sittingMs());
        if (!Prefs.get().recorderOpen && !replay) {   // folded: one small button, with the red dot while recording
            String label = "Recorder ▴";
            int bw = font.width(label) + 12 + (rec ? font.width(dot) + 10 : 0), bx2 = (width - bw) / 2, by2 = height - 58;
            box(g, bx2, by2, bw, 22, COL_PANEL);
            hits.add(new Hit(bx2, by2, bw, 22, null, null));
            textButtonFixed(g, bx2 + 4, by2 + 3, font.width(label) + 8, 16, label, mouseX, mouseY, () -> {
                Prefs.get().recorderOpen = true;
                Prefs.save();
            });
            if (rec) g.drawString(font, dot, bx2 + font.width(label) + 16, by2 + 7, 0xFFE8403A, false);
            return;
        }
        Replay r = WarRecorder.current();
        String info = replay && r != null
                ? "Replay  " + dateText(WarRecorder.time())
                : rec ? dot : n >= 1 ? n + (n == 1 ? " moment recorded" : " moments recorded") : "Start recording to play this war back later";
        String speed = r == null ? "1x" : r.speedText();
        String recLabel = rec ? "Stop recording" : "Start recording";
        int recW = font.width(recLabel) + 12 + (rec ? 0 : 8);
        int speedW = font.width("0.25x") + 10;
        int bwn = n >= 1 ? 30 * 3 + 44 + (replay ? 18 + speedW + 18 + 2 + 40 : 0) + 10 : 0;
        int wBar = Math.max(20 + recW + font.width("Recordings") + 16 + bwn + font.width(info) + 26, replay ? 320 : 0);
        int barH = replay ? 34 : 22;
        int x = Math.max(4, (width - wBar) / 2), y = height - 58 - (barH - 22);
        box(g, x, y, wBar, barH, COL_PANEL);
        hits.add(new Hit(x, y, wBar, barH, null, null));
        int cx = x + 4;
        textButtonFixed(g, cx, y + 3, 16, 16, "▾", mouseX, mouseY, () -> {   // fold the bar
            Prefs.get().recorderOpen = false;
            Prefs.save();
        });
        cx += 18;
        textButtonFixed(g, cx, y + 3, recW, 16, rec ? recLabel : "  " + recLabel, mouseX, mouseY, () -> WarRecorder.startStop(w));
        if (!rec) g.fill(cx + 6, y + 8, cx + 11, y + 13, 0xFFE8403A);   // the red dot of "start"
        else g.fill(cx + recW - 10, y + 8, cx + recW - 5, y + 13, 0xFFE8403A);
        cx += recW + 4;
        int listW = font.width("Recordings") + 12;
        textButtonFixed(g, cx, y + 3, listW, 16, "Recordings", mouseX, mouseY, this::openList);
        cx += listW + 4;
        if (n >= 1) {
            textButtonFixed(g, cx, y + 3, 28, 16, "<<", mouseX, mouseY, () -> {
                WarRecorder.pause();
                WarRecorder.show(w, 0);
            });
            cx += 30;
            textButtonFixed(g, cx, y + 3, 28, 16, " <", mouseX, mouseY, () -> {
                WarRecorder.pause();
                if (WarRecorder.replaying()) WarRecorder.previousChange();
                else WarRecorder.show(w, n - 1);
            });
            cx += 30;
            textButtonFixed(g, cx, y + 3, 42, 16, WarRecorder.playing() ? "Pause" : "Play", mouseX, mouseY, () -> {
                if (WarRecorder.playing()) WarRecorder.pause();
                else WarRecorder.play(w);
            });
            cx += 44;
            textButtonFixed(g, cx, y + 3, 28, 16, " >", mouseX, mouseY, () -> {
                WarRecorder.pause();
                if (WarRecorder.replaying()) WarRecorder.nextChange();
                else WarRecorder.show(w, n - 1);
            });
            cx += 30;
            if (replay) {   // speed: - slower, + faster
                textButtonFixed(g, cx, y + 3, 16, 16, "-", mouseX, mouseY, () -> WarRecorder.changeSpeed(-1));
                cx += 17;
                box(g, cx, y + 3, speedW, 16, COL_PANEL);
                g.drawString(font, speed, cx + (speedW - font.width(speed)) / 2, y + 7, COL_TEXT, false);
                cx += speedW + 1;
                textButtonFixed(g, cx, y + 3, 16, 16, "+", mouseX, mouseY, () -> WarRecorder.changeSpeed(1));
                cx += 18;
                textButtonFixed(g, cx, y + 3, 38, 16, "Live", mouseX, mouseY, WarRecorder::stop);
                cx += 40;
            }
        }
        g.drawString(font, info, cx + 6, y + 7, replay ? 0xFFF0B429 : COL_DIM, false);
        if (replay && r != null && r.to() > r.from()) {   // timeline of the most recent session: click or drag to go to any moment
            int tx = x + 6, tw = wBar - 12, ty = y + 24;
            timelineX = tx;
            timelineW = tw;
            g.fill(tx, ty, tx + tw, ty + 6, 0xFF4A5056);
            long from = r.from(), span = r.to() - from;
            int px = tx + (int) (tw * (r.time() - from) / (double) span);
            g.fill(tx, ty + 2, px, ty + 4, 0xFFF0B429);
            for (RecFormat.Frame f : r.frames) {   // where who holds the cities changed
                if (f.t <= from || f.t > r.to()) continue;
                int fx = tx + (int) (tw * (f.t - from) / (double) span);
                g.fill(fx, ty - 1, fx + 1, ty + 7, 0xFFCFD3D1);
            }
            g.fill(px - 1, ty - 2, px + 2, ty + 8, 0xFFFFF2B0);
            hits.add(new Hit(tx, ty - 3, tw, 12, () -> {
                timelineDrag = true;
                seekTimeline(hoverMX);
            }, null));
        }
    }

    private void drawHover(GuiGraphics g, int mouseX, int mouseY) {
        if (panelVisible && tab == TAB_SET) return;
        boolean side = panelVisible && panelX <= MARGIN * 3;
        int hx = side ? panelX + panelW + 8 : MARGIN;
        String hint = "Drag: move  |  Wheel: zoom  |  Click: select  |  1-5: modes  |  H: all keys";
        if (!side) g.drawString(font, hint, MARGIN, height - 12, COL_DIM, true);
        drawDataAge(g);
        if (blockedAt(hits, mouseX, mouseY)) return;
        int bxx = (int) Math.floor(blockX(mouseX, mouseY));
        int bzz = (int) Math.floor(blockZ(mouseX, mouseY));
        String text = "X " + bxx + "  Z " + bzz;
        SiteTerritories.Data d = SiteTerritories.data();
        if (siteMode() && d != null) {
            SiteTerritories.Locality city = cityAt(mouseX, mouseY);
            if (city != null) {
                text += "  |  " + SiteTerritories.splitCamel(city.name);
            } else if (Prefs.get().provinceInTooltip) {
                int p = SiteTerritories.provinceAt(d, bxx, bzz);
                if (p >= 0) text += "  |  " + d.labels[d.provCountry[p]] + " - " + d.provLabel[p];
            }
        }
        int w = font.width(text) + 10;
        int y = side ? TOP + 11 : height - 34;
        g.fill(hx - 2, y - 3, hx - 2 + w, y + 12, 0xFF0E0C08);
        g.fill(hx - 1, y - 2, hx - 3 + w, y + 11, 0xF226292B);
        g.fill(hx - 1, y - 2, hx - 3 + w, y - 1, 0xFF6A6F72);
        g.drawString(font, text, hx + 3, y, COL_TEXT, false);
    }

    // ------------------------------------------------------------ barra superior e resumo

    /** País do jogador local, ou -1. */
    private int myCountry(SiteTerritories.Data d) {
        Minecraft mc = Minecraft.getInstance();
        if (d == null || mc.player == null) return -1;
        String low = mc.player.getName().getString().toLowerCase(Locale.ROOT);
        String site = d.ignToSite.get(low);
        Integer ci = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : low);
        return ci == null ? -1 : ci;
    }

    private void refreshOutline(SiteTerritories.Data d) {
        long now = System.currentTimeMillis();
        if (now - outlineAt < 1000 && outlineVer == SiteTerritories.version()) return;
        outlineAt = now;
        outlineVer = SiteTerritories.version();
        List<SiteTerritories.WarInfo> running = new ArrayList<>();
        for (SiteTerritories.WarInfo w : Relations.wars(d)) if (warRunning(w)) running.add(w);
        outWars = running;
        outTruces = truceList(d, now);
        outUlts = ultimatumList(d, now);
        int me = myCountry(d);
        myWarsN = 0;
        for (SiteTerritories.WarInfo w : running) if (me < 0 || warInvolves(d, w, me)) myWarsN++;
        myAlliesN = 0;
        if (d.war != null && d.war.alliances != null) {
            for (SiteTerritories.AllianceInfo a : d.war.alliances) if (me < 0 || Relations.countriesOf(d, a).contains(me)) myAlliesN++;
        }
        myTrucesN = truceList(d, now, me).size();
        myUltsN = ultimatumList(d, now, me).size();
        myPlayersN = 0;
        for (OnlinePlayers.Entry e : OnlinePlayers.list()) {
            String low = e.account.toLowerCase(Locale.ROOT);
            String site = d.ignToSite.get(low);
            Integer ci = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : low);
            if (me < 0 || (ci != null && ci == me)) myPlayersN++;
        }
    }

    /** Só o país do jogador quando a lista foi aberta pela barra de cima; -1 = tudo. */
    int mineFilter(SiteTerritories.Data d) {
        return mineOnly ? myCountry(d) : -1;
    }

    void mineToggle(GuiGraphics g, SiteTerritories.Data d, int mouseX, int mouseY) {
        if (myCountry(d) < 0) return;
        textButton(g, bx + 9, cy, bw - 18, 16, mineOnly ? "Showing: your country (click for all)" : "Showing: everyone (click for your country)", mouseX, mouseY, true, () -> mineOnly = !mineOnly);
        cy += 21;
    }

    /** Mostrador da barra superior; devolve o x seguinte (pula se não couber). */
    private int stat(GuiGraphics g, int x, int limit, String glyph, int value, int color, String tip, Runnable action, int mouseX, int mouseY) {
        String t = String.valueOf(value);
        int w = 27 + font.width(t), y = 4, h = 22;
        if (x + w > limit) return x;
        boolean hover = action != null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, 0xFF0A0B08);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? 0xFF22272B : 0xFF131517);
        Glyphs.draw(g, glyph, x + 4, y + 4, 0xFFD6D6D0, 0xFF8F959A, 0xFF4A4F52);
        g.drawString(font, t, x + 21, y + 7, color, false);
        hits.add(new Hit(x, y, w, h, action, tip));
        return x + w + 3;
    }

    private void openPanorama(int sub) {
        openPanorama(sub, false);
    }

    private void openPanorama(int sub, boolean mine) {
        mineOnly = mine;
        tab = TAB_DIPLOMENU;
        dmTab = sub;
        SCROLL[TAB_DIPLOMENU] = 0;
    }

    private void drawTopBar(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, width, TOP - 2, 0xFF1E2123);
        g.fill(0, TOP - 2, width, TOP, 0xFF7E8387);
        g.fill(0, TOP, width, TOP + 1, 0xFF0A0B08);
        hits.add(new Hit(0, 0, width, TOP + 1, null, null));
        SiteTerritories.Data d = SiteTerritories.data();
        boolean site = siteMode() && d != null;
        if (site) refreshOutline(d);

        int x = 4;
        final int me = site ? myCountry(d) : -1;
        if (me >= 0) {
            g.fill(x, 3, x + 38, 27, 0xFF0A0B08);
            g.fill(x + 1, 4, x + 37, 26, 0xFF6A6F72);
            g.fill(x + 2, 5, x + 36, 25, 0xFF05080C);
            RemoteImages.Img flag = RemoteImages.flag(flagOf(d, me));
            if (flag != null) drawImage(g, flag, x + 2, 5, 34, 20);
            else g.fill(x + 2, 5, x + 36, 25, 0xFF000000 | d.rgb[me]);
            drawFit(g, d.labels[me].toUpperCase(Locale.ROOT), 62, x + 42, 6, 0xFFECEBE4, false);
            g.drawString(font, "Your country", x + 42, 17, COL_DIM, false);
            hits.add(new Hit(x, 3, 106, 24, () -> selectCountry(me), d.names[me]));
            x += 108;
            g.fill(x, 5, x + 1, 25, 0xFF3E4245);
            x += 4;
        }
        for (Btn b : nav) {
            iconButton(g, x, 2, BTN, b.glyph(), b.active(), b.enabled(), mouseX, mouseY, b.tip(), b.action());
            if (b.glyph().equals("war") && b.enabled()) {
                int n = myWarsN;
                if (n > 0) {
                    String txt = String.valueOf(n);
                    int tw = font.width(txt) + 5;
                    g.fill(x + BTN - tw - 1, 0, x + BTN + 2, 12, 0xFF0E0C08);
                    g.fill(x + BTN - tw, 1, x + BTN + 1, 11, 0xFFC02B2B);
                    g.drawString(font, txt, x + BTN - tw + 3, 3, 0xFFFFFFFF, false);
                }
            }
            x += BTN + 4;
        }
        g.fill(x, 5, x + 1, 25, 0xFF3E4245);
        x += 4;

        int rx = width - 4;
        for (int i = tools.size() - 1; i >= 0; i--) {
            Btn b = tools.get(i);
            rx -= 22;
            iconButton(g, rx, 4, 22, b.glyph(), b.active(), b.enabled(), mouseX, mouseY, b.tip(), b.action());
            rx -= 3;
        }
        MapCatalog.Entry cur = SiteTerritories.activeMap();
        String mapName = fit(cur == null ? "MAPA" : cur.name.toUpperCase(Locale.ROOT), 80);
        String src = site ? "Source: site" : "Source: manual";
        int nameW = Math.max(font.width(mapName), font.width(src));
        rx -= 3;
        g.drawString(font, mapName, rx - font.width(mapName), 6, 0xFFECEBE4, false);
        g.drawString(font, src, rx - font.width(src), 17, COL_DIM, false);
        int limit = rx - nameW - 8;

        if (site) {
            boolean mine = myCountry(d) >= 0;
            String of = mine ? "Your country: " : "";
            x = stat(g, x, limit, "war", myWarsN, 0xFFF0A090, of + "wars in progress", () -> {
                mineOnly = true;
                enterWar();
            }, mouseX, mouseY);
            x = stat(g, x, limit, "diplomacy", myAlliesN, COL_TEXT, of + "alliances", () -> openPanorama(0, true), mouseX, mouseY);
            x = stat(g, x, limit, "treaty", myTrucesN, COL_TEXT, of + "truces", () -> openPanorama(4, true), mouseX, mouseY);
            x = stat(g, x, limit, "political", myUltsN, COL_LINK, of + "pending ultimatums", () -> openPanorama(5, true), mouseX, mouseY);
            if (SiteTerritories.showPlayers() && showPlayers) stat(g, x, limit, "players", myPlayersN, 0xFF8FD08F, mine ? "Online players from your country" : "Online players", null, mouseX, mouseY);
        }
    }

    /** Moldura de um grupo do resumo; devolve o y da primeira linha. */
    private int outlineFrame(GuiGraphics g, int x, int y, int w, int h, String title, String count) {
        g.fill(x, y, x + w, y + h, 0xFF0A0B08);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF4A4F52);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xF21E2123);
        g.fill(x + 2, y + 2, x + w - 2, y + 14, 0xFF33373A);
        g.fill(x + 2, y + 14, x + w - 2, y + 15, 0xFF0A0B08);
        spaced(g, fitSpaced(title, w - 30), x + 6, y + 4, 0xFFCFD3D1, false);
        g.drawString(font, count, x + w - 6 - font.width(count), y + 4, 0xFFCFD3D1, false);
        hits.add(new Hit(x, y, w, h, null, null));
        return y + 16;
    }

    private int outlineTimed(GuiGraphics g, SiteTerritories.Data d, String title, List<Timed> list, int sub, int x, int y, int w, int maxY, long now, int mouseX, int mouseY) {
        if (list.isEmpty()) return y;
        int n = Math.min(3, list.size()), h = 18 + n * 14;
        if (y + h > maxY) return y;
        Map<String, Integer> idx = Relations.nameIndex(d);
        int ry = outlineFrame(g, x, y, w, h, title, String.valueOf(list.size()));
        for (int i = 0; i < n; i++) {
            Timed t = list.get(i);
            boolean hover = mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= ry && mouseY < ry + 14;
            if (hover) g.fill(x + 2, ry, x + w - 2, ry + 14, 0x308F959A);
            Integer ia = idx.get(Relations.norm(t.a())), ib = t.b() == null ? null : idx.get(Relations.norm(t.b()));
            if (ia != null) miniFlag(g, d, ia, x + 5, ry + 1);
            String left = countdown(t.until() - now).replace("in ", "");
            String name = (ia != null ? d.labels[ia] : t.a()) + (t.b() == null ? "" : " and " + (ib != null ? d.labels[ib] : t.b()));
            int lw = font.width(left);
            drawFit(g, name, w - 40 - lw, x + 30, ry + 3, COL_TEXT, false);
            g.drawString(font, left, x + w - 6 - lw, ry + 3, COL_LINK, false);
            hits.add(new Hit(x + 2, ry, w - 4, 14, () -> openPanorama(sub), name));
            ry += 14;
        }
        return y + h + 3;
    }

    /** Resumo à direita: guerras, ultimatos, tréguas e a legenda dos modos diplomacia e guerra. */
    private void drawOutliner(GuiGraphics g, int mouseX, int mouseY) {
        SiteTerritories.Data d = SiteTerritories.data();
        if (!siteMode() || d == null || tab == TAB_SET) return;
        int w = OUTLINE_W, x = width - w - MARGIN, y = TOP + 8, maxY = height - barHLocal() - MARGIN - 6;
        if (panelVisible && (panelX + panelW + 6) / groupScale > x) return;
        if (cardVisible && (cardX + cardW + 6) / groupScale > x) return;
        long now = System.currentTimeMillis();

        if (!outWars.isEmpty()) {
            int n = Math.min(3, outWars.size()), h = 18 + n * 28;
            if (y + h <= maxY) {
                List<SiteTerritories.WarInfo> all = Relations.wars(d);
                int ry = outlineFrame(g, x, y, w, h, "WARS", String.valueOf(outWars.size()));
                for (int i = 0; i < n; i++) {
                    SiteTerritories.WarInfo war = outWars.get(i);
                    boolean hover = mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= ry && mouseY < ry + 28;
                    if (hover) g.fill(x + 2, ry, x + w - 2, ry + 28, 0x308F959A);
                    long end = parseMs(war.ended);
                    String left = end != NONE && end > now ? countdown(end - now).replace("in ", "") : "";
                    int lw = font.width(left);
                    drawFit(g, warName(war), w - 14 - lw, x + 6, ry + 2, COL_TEXT, false);
                    g.drawString(font, left, x + w - 6 - lw, ry + 2, COL_LINK, false);
                    sideBar(g, war, WarScore.of(d, war).points(), x + 6, ry + 13, w - 12);
                    final int at = all.indexOf(war);
                    hits.add(new Hit(x + 2, ry, w - 4, 28, () -> {
                        enterWar();
                        warSel = at;
                    }, warName(war)));
                    ry += 28;
                }
                y += h + 3;
            }
        }
        y = outlineTimed(g, d, "ULTIMATUMS", outUlts, 5, x, y, w, maxY, now, mouseX, mouseY);
        y = outlineTimed(g, d, "TRUCES", outTruces, 4, x, y, w, maxY, now, mouseX, mouseY);

        if (relationalMode() && !legend.isEmpty()) {
            int rows = Math.min(Math.min(legend.size(), 9), (maxY - y - 18) / 12);
            if (rows > 0) {
                int ry = outlineFrame(g, x, y, w, 18 + rows * 12, "LEGEND", "");
                for (int i = 0; i < rows; i++) {
                    Relations.Rel rel = legend.get(i);
                    g.fill(x + 6, ry + 1, x + 20, ry + 10, 0xFF05080C);
                    g.fill(x + 7, ry + 2, x + 19, ry + 9, 0xFF000000 | rel.rgb());
                    String label = rel.label();
                    if (rel.countries().size() > 1) label += " (" + rel.countries().size() + ")";
                    drawFit(g, label, w - 32, x + 25, ry + 2, COL_TEXT, false);
                    ry += 12;
                }
            }
        }
    }

    // ------------------------------------------------------------ peças de UI

    // --------------------------------------------------------------- barra de ícones


    private List<Btn> barButtons() {
        boolean site = siteMode();
        boolean haveSite = SiteTerritories.data() != null;
        String needSite = "\nRequires site data.";
        List<Btn> b = new ArrayList<>();
        b.add(new Btn("physical", mode == MODE_PHYSICAL, true, "Physical map\nReal relief and terrain. (1)", () -> mode = MODE_PHYSICAL, false));
        b.add(new Btn("political", mode == MODE_POLITICAL, true, "Political map\nTinted countries and names. (2)", () -> mode = MODE_POLITICAL, false));
        b.add(new Btn("provinces", mode == MODE_PROVINCES, true, "Provinces\nDivisions of each country. (3)", () -> mode = MODE_PROVINCES, false));
        b.add(new Btn("diplomacy", mode == MODE_DIPLOMACY, site, "Diplomacy\nRelations of a country or an alliance. (4)" + (site ? "" : needSite), this::enterDiplomacy, false));
        b.add(new Btn("war", mode == MODE_WAR, site, "War\nSides, taken and contested cities. (5)" + (site ? "" : needSite), this::enterWar, false));

        b.add(new Btn("list", tab == TAB_LIST, true, "Country list", () -> tab = tab == TAB_LIST ? TAB_NONE : TAB_LIST, true));
        b.add(new Btn("treaty", tab == TAB_DIPLOMENU, site, "Diplomatic overview\nAlliances, active NAPs, purge and diplolock." + (site ? "" : needSite),
                () -> {
                    mineOnly = false;
                    tab = tab == TAB_DIPLOMENU ? TAB_NONE : TAB_DIPLOMENU;
                    SCROLL[TAB_DIPLOMENU] = 0;
                }, false));
        b.add(new Btn("cities", showCities, site, "Cities\n" + (showCities ? "On" : "Off") + (site ? "" : needSite), () -> showCities = !showCities, true));
        b.add(new Btn("limits", showLimits, site, "City limits\n" + (showLimits ? "On" : "Off") + (site ? "" : needSite), () -> {
            showLimits = !showLimits;
            Prefs.get().limits = showLimits;
            Prefs.save();
        }, false));
        boolean dyn = SiteTerritories.showPlayers();
        String playersTip = "Online players\n" + (!dyn ? "Disabled in site.json" : !showPlayers ? "Off"
                : OnlinePlayers.live() ? "On (" + OnlinePlayers.list().size() + " on the map, via dynmap)"
                : OnlinePlayers.error() != null ? "On (dynmap not responding: only nearby players)" : "On (loading...)");
        b.add(new Btn("players", showPlayers && dyn, dyn, playersTip, () -> showPlayers = !showPlayers, false));

        b.add(new Btn("center", false, true, "Center on you", this::centerOnPlayer, true));
        String sourceTip = !haveSite ? "Source: manual\nNo site data."
                : SiteTerritories.useSite ? "Source: site\nClick to use manual mode." : "Source: manual\nClick to use the site.";
        b.add(new Btn("source", haveSite && SiteTerritories.useSite, haveSite, sourceTip, () -> {
            SiteTerritories.useSite = !SiteTerritories.useSite;
            tab = TAB_NONE;
            selCity = -1;
            MapData.ownersChanged();
        }, false));
        b.add(new Btn("reload", false, true, "Reload countries and site", () -> {
            Countries.load();
            SiteTerritories.reload();
        }, false));

        List<MapCatalog.Entry> maps = MapCatalog.list();
        if (maps.size() > 1) {
            MapCatalog.Entry cur = SiteTerritories.activeMap();
            int at = -1;
            for (int i = 0; i < maps.size(); i++) if (cur != null && maps.get(i).key.equalsIgnoreCase(cur.key)) at = i;
            final MapCatalog.Entry next = maps.get((at + 1) % maps.size());
            b.add(new Btn("maps", false, true, "Map: " + (cur == null ? "default" : cur.name) + "\nClick to switch to " + next.name + ".", () -> {
                selCity = -1;
                setSite(-1);
                SiteTerritories.selectMap(next.key);
            }, false));
        }
        b.add(new Btn("settings", tab == TAB_SET, true, "Settings\nAppearance, players, war, minimap and maps.", () -> {
            tab = tab == TAB_SET ? TAB_NONE : TAB_SET;
            SCROLL[TAB_SET] = 0;
        }, false));

        boolean genRunning = ChunkGen.state != ChunkGen.State.IDLE;
        b.add(new Btn("gen", tab == TAB_GEN || genRunning, true,
                genRunning ? "Chunk generator\nIn progress." : "Generate all map chunks", () -> {
                    genConfirm = false;
                    tab = tab == TAB_GEN ? TAB_NONE : TAB_GEN;
                }, false));
        return b;
    }

    private List<Btn> bar = List.of();
    private int[] barX = new int[0], barRow = new int[0];
    private int barW;

    /** Distribui os botões em linhas conforme a largura da janela e calcula a altura da barra. */
    private void layoutBar() {
        List<Btn> dock = new ArrayList<>(), top = new ArrayList<>(), right = new ArrayList<>();
        for (Btn b : barButtons()) {
            switch (b.glyph()) {
                case "list", "treaty" -> top.add(b);
                case "source", "reload", "maps", "settings", "gen" -> right.add(b);
                case "diplomacy" -> { }      // continua na tecla 4 e no painel
                default -> dock.add(b);
            }
        }
        boolean site = siteMode();
        top.add(new Btn("war", tab == TAB_WAR, site, "Wars\nList, scoreboard, cities and Law 7." + (site ? "" : "\nRequires site data."), () -> {
            if (tab == TAB_WAR) tab = TAB_NONE;
            else {
                mineOnly = false;
                enterWar();
            }
        }, false));
        right.add(0, new Btn("search", searchOpen, true, "Search\nCountries, cities and players. Also measures distances and marks points.", () -> {
            if (searchOpen) closeSearch();
            else openSearch();
        }, false));
        bar = dock;
        nav = top;
        tools = right;
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
        barH = (row + 1) * (BTN + 4) + 25;
    }

    private void drawBar(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = width - barW - MARGIN;
        int y0 = height - barH - MARGIN;
        box(g, x0, y0, barW, barH, COL_PANEL);
        hits.add(new Hit(x0, y0, barW, barH, null, null));
        g.fill(x0 + 4, y0 + 4, x0 + barW - 4, y0 + 16, 0xFF33373A);
        g.fill(x0 + 4, y0 + 16, x0 + barW - 4, y0 + 17, 0xFF0A0B08);
        String name = !siteMode() ? "MANUAL MODE" : switch (mode) {
            case MODE_PHYSICAL -> "PHYSICAL MAP";
            case MODE_PROVINCES -> "PROVINCES";
            case MODE_DIPLOMACY -> "DIPLOMACY";
            case MODE_WAR -> "WAR";
            default -> "POLITICAL MAP";
        };
        spaced(g, name, x0 + (barW - spacedWidth(name)) / 2, y0 + 6, 0xFFCFD3D1, false);
        for (int i = 0; i < bar.size(); i++) {
            Btn b = bar.get(i);
            int bx0 = x0 + 8 + barX[i], by0 = y0 + 21 + barRow[i] * (BTN + 4);
            iconButton(g, bx0, by0, BTN, b.glyph(), b.active(), b.enabled(), mouseX, mouseY, b.tip(), b.action());
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
        if (tip == null) tip = cutAt(mouseX, mouseY);   // a text cut short: the whole of it
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
                    if (d != null && mode == MODE_WAR) tip += warTip(d, city);
                }
            }
        }
        if (tip == null) return;
        String[] lines = tip.split("\n");
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 18;
        int h = lines.length * 11 + 12;
        int x = Math.min(mouseX + 10, width - w - 4);
        int y = Math.max(4, Math.min(mouseY + 12, height - h - 4));
        box(g, x, y, w, h, 0xFF1A1C1E);
        if (lines.length > 1) g.fill(x + 4, y + 4, x + w - 4, y + 17, 0xFF33373A);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 && lines.length > 1 ? COL_GOLD : i == 0 ? COL_TEXT : COL_DIM;
            g.drawString(font, lines[i], x + 9, y + 7 + i * 11 + (i > 0 ? 1 : 0), color, false);
        }
    }

    // ------------------------------------------------------------ busca e ferramentas

    private void openSearch() {
        renameTo = null;
        renameMark = null;
        markMenu = null;
        searchOpen = true;
        searchBox.setValue("");
        setFocused(searchBox);
        updateSearch();
    }

    private void closeSearch() {
        renameTo = null;
        renameMark = null;
        searchOpen = false;
        setFocused(null);
    }

    private void runSearchHit(SearchHit h) {
        closeSearch();
        h.action().run();
    }

    private void updateSearch() {
        searchIdx = 0;
        if (renameMark != null || renameTo != null) {
            searchHits = List.of();
            return;
        }
        String raw = searchBox.getValue();
        // the key that opened the box (F or /) arrives here as a typed character: drop it
        if (System.currentTimeMillis() - searchOpenedAt < 250 && raw.length() == 1 && "fF/".contains(raw)) {
            searchOpenedAt = 0;
            searchBox.setValue("");
            return;
        }
        String q = fold(raw.trim());
        SiteTerritories.Data d = SiteTerritories.data();
        List<SearchHit> first = new ArrayList<>(), rest = new ArrayList<>();
        java.util.function.BiConsumer<String, SearchHit> add = (text, hit) -> {
            String low = fold(text);
            if (low.startsWith(q)) first.add(hit);
            else if (low.contains(q)) rest.add(hit);
        };
        if (q.isEmpty()) {
            searchHits = List.of();
            return;
        }
        String map = activeMapKey();
        for (Prefs.Mark m : Prefs.get().marks) {
            if (!map.equals(m.map) || m.name == null) continue;
            add.accept(m.name, new SearchHit(m.name, "Point", () -> {
                viewX = m.x + 0.5;
                viewZ = m.z + 0.5;
                if (scale < 0.7) scale = 0.7;
                markMenu = m;
            }));
        }
        if (d != null && siteMode()) {
            for (int i = 0; i < d.ids.length; i++) {
                if (d.provinceCount[i] <= 0) continue;
                final int c = i;
                add.accept(d.labels[i], new SearchHit(d.labels[i], "Country", () -> selectCountry(c)));
            }
            for (SiteTerritories.Locality l : d.localities) {
                String name = SiteTerritories.splitCamel(l.name);
                add.accept(name, new SearchHit(name, "City", () -> {
                    focusCity(l);
                    tab = TAB_CITY;
                    SCROLL[TAB_CITY] = 0;
                }));
            }
            for (String name : d.players.keySet()) add.accept(name, new SearchHit(name, "Player", () -> openPlayer(name)));
            List<SiteTerritories.AllianceInfo> alliances = d.war == null || d.war.alliances == null ? List.of() : d.war.alliances;
            for (int i = 0; i < alliances.size(); i++) {
                SiteTerritories.AllianceInfo a = alliances.get(i);
                if (a == null || a.name == null || Relations.isBilateral(a)) continue;
                final int idx = i;
                add.accept(a.name, new SearchHit(a.name, "Alliance", () -> {
                    enterDiplomacy();
                    mineOnly = false;
                    tab = TAB_DIPLOMENU;
                    dmTab = 0;
                    dipAlliance = idx;
                }));
            }
            if (q.length() >= 2 && first.size() + rest.size() < 12) {
                for (int i = 0; i < d.n; i++) {
                    String label = d.provLabel[i];
                    if (label == null) continue;
                    String low = fold(label);
                    if (!low.contains(q)) continue;
                    final int prov = i;
                    String shown = label + " (" + d.labels[d.provCountry[i]] + ")";
                    SearchHit hit = new SearchHit(shown, "Province", () -> {
                        int[] bb = d.bounds[prov];
                        viewX = (bb[0] + bb[2] + 1) / 2.0;
                        viewZ = (bb[1] + bb[3] + 1) / 2.0;
                        if (scale < 0.7) scale = 0.7;
                        setSite(prov);
                        selCity = -1;
                        tab = TAB_INFO;
                        SCROLL[TAB_INFO] = 0;
                    });
                    if (low.startsWith(q)) first.add(hit);
                    else rest.add(hit);
                    if (first.size() + rest.size() >= 40) break;
                }
            }
        }
        first.addAll(rest);
        searchHits = first.size() > 12 ? List.copyOf(first.subList(0, 12)) : List.copyOf(first);
    }

    private void drawSearch(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!searchOpen) return;
        searchW = Math.min(280, width - 16);
        searchX = (width - searchW) / 2;
        searchY = TOP + 6;
        searchH = 50 + searchHits.size() * 14;
        box(g, searchX, searchY, searchW, searchH, COL_PANEL);
        hits.add(new Hit(searchX, searchY, searchW, searchH, null, null));
        g.fill(searchX + 7, searchY + 7, searchX + searchW - 7, searchY + 21, 0xFF131517);
        searchBox.setX(searchX + 11);
        searchBox.setY(searchY + 10);
        searchBox.setWidth(searchW - 22);
        if (searchBox.getValue().isEmpty()) g.drawString(font, renameMark != null ? "Name of the point" : renameTo != null ? "Name" : "Country, city, player, alliance, province or point", searchX + 11, searchY + 10, COL_DIM, false);
        searchBox.render(g, mouseX, mouseY, delta);
        int ry = searchY + 24;
        for (int i = 0; i < searchHits.size(); i++) {
            SearchHit h = searchHits.get(i);
            boolean hover = mouseX >= searchX + 6 && mouseX < searchX + searchW - 6 && mouseY >= ry && mouseY < ry + 14;
            if (hover || i == searchIdx) g.fill(searchX + 6, ry, searchX + searchW - 6, ry + 14, 0x508F959A);
            int kw = font.width(h.kind());
            drawFit(g, h.label(), searchW - 30 - kw, searchX + 11, ry + 3, COL_TEXT, false);
            g.drawString(font, h.kind(), searchX + searchW - 11 - kw, ry + 3, COL_DIM, false);
            hits.add(new Hit(searchX + 6, ry, searchW - 12, 14, () -> runSearchHit(h), null));
            ry += 14;
        }
        if (renameMark != null || renameTo != null) {
            g.drawString(font, "Enter: save    Esc: cancel", searchX + 11, ry + 7, COL_DIM, false);
            return;
        }
        int btnW = (searchW - 18) / 3;
        toolButton(g, searchX + 7, ry + 3, btnW, "Measure", 1, mouseX, mouseY);
        toolButton(g, searchX + 9 + btnW, ry + 3, btnW, "Mark point", 2, mouseX, mouseY);
        textButtonFixed(g, searchX + 11 + 2 * btnW, ry + 3, btnW, 16, "Photo", mouseX, mouseY, () -> {
            closeSearch();
            photoMode = true;
            photoAt = System.currentTimeMillis();
        });
    }

    private void toolButton(GuiGraphics g, int x, int y, int w, String label, int tool, int mouseX, int mouseY) {
        textButtonFixed(g, x, y, w, 16, label, mouseX, mouseY, () -> {
            closeSearch();
            toolMode = tool;
            measurePts.clear();
        });
    }

    private void drawPhotoHint(GuiGraphics g) {
        long age = System.currentTimeMillis() - photoAt;
        if (age > 2500) return;
        String t = "Press F2 to save the image. Click to go back.";
        int w = font.width(t) + 12, x = (width - w) / 2;
        g.fill(x, 8, x + w, 24, 0xE01E2123);
        g.drawString(font, t, x + 6, 12, COL_TEXT, false);
    }

    private void drawToolBanner(GuiGraphics g) {
        if (toolMode == 0 || searchOpen) return;
        String t = toolMode == 1 ? "Measure: click to add points. Backspace removes the last. Right click exits." : "Mark: click the map to add a point. Click a point to rename, recolor or delete it. Right click exits.";
        int w = font.width(t) + 12, x = (width - w) / 2, y = TOP + 8;
        g.fill(x, y, x + w, y + 15, 0xF21E2123);
        g.fill(x, y + 14, x + w, y + 15, 0xFF7E8387);
        g.drawString(font, t, x + 6, y + 3, COL_TEXT, false);
    }

    static String activeMapKey() {
        MapCatalog.Entry cur = SiteTerritories.activeMap();
        return cur == null ? "padrao" : cur.key;
    }

    private void toolClick(int bx0, int bz0) {
        if (toolMode == 1) {
            measurePts.add(new int[]{bx0, bz0});
            return;
        }
        Prefs.Data p = Prefs.get();
        String map = activeMapKey();
        Prefs.Mark hit = markAt(bx0 + 0.5, bz0 + 0.5);
        if (hit != null) {
            markMenu = hit;
            return;
        }
        int n = 1;
        for (Prefs.Mark m : p.marks) if (map.equals(m.map)) n++;
        Prefs.Mark m = new Prefs.Mark();
        m.map = map;
        m.x = bx0;
        m.z = bz0;
        m.name = "Point " + n;
        p.marks.add(m);
        Prefs.save();
        startRename(m);
    }

    private void routeLabel(GuiGraphics g, double sx, double sy, double dist, boolean dim) {
        String a = String.format(Locale.ROOT, "%,d blocks", Math.round(dist));
        String b = "walk " + travelTime(dist, 4.317) + "  |  sprint " + travelTime(dist, 5.612) + "  |  boat " + travelTime(dist, 8.0) + "  |  elytra ~" + travelTime(dist, 30.0);
        int w = Math.max(font.width(a), font.width(b)) + 10, lx = (int) sx + 8, ly = (int) sy - 12;
        lx = Math.max(2, Math.min(width - w - 2, lx));
        g.fill(lx, ly, lx + w, ly + 25, dim ? 0xC01E2123 : 0xF21E2123);
        g.fill(lx, ly + 24, lx + w, ly + 25, 0xFF7E8387);
        g.drawString(font, a, lx + 5, ly + 3, COL_TEXT, false);
        g.drawString(font, b, lx + 5, ly + 14, COL_DIM, false);
    }

    private void drawMarksAndMeasure(GuiGraphics g, int mouseX, int mouseY) {
        String map = activeMapKey();
        for (Prefs.Mark m : Prefs.get().marks) {
            if (!map.equals(m.map)) continue;
            int sx = (int) Math.round(toScreenX(m.x + 0.5)), sy = (int) Math.round(toScreenY(m.z + 0.5));
            if (sx < -40 || sx > width + 40 || sy < -20 || sy > height + 20) continue;
            int color = Prefs.markColor(m);
            g.fill(sx - 4, sy - 4, sx + 5, sy + 5, m == markMenu ? 0xFFFFFFFF : 0xFF0A0B08);
            g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF0A0B08);
            g.fill(sx - 2, sy - 2, sx + 3, sy + 3, color);
            if (m.name != null) g.drawString(font, m.name, sx + 7, sy - 4, color, true);
        }
        if (toolMode != 1 || measurePts.isEmpty()) return;
        // the route so far, then a lighter leg up to the cursor
        double fixed = 0;
        for (int i = 0; i < measurePts.size(); i++) {
            int[] a = measurePts.get(i);
            double x0 = toScreenX(a[0] + 0.5), y0 = toScreenY(a[1] + 0.5);
            squareRing(g, (int) Math.round(x0), (int) Math.round(y0), 3, 0xFFECEBE4);
            if (i == 0) continue;
            int[] b = measurePts.get(i - 1);
            double x1 = toScreenX(b[0] + 0.5), y1 = toScreenY(b[1] + 0.5);
            segment(g, x1, y1, x0, y0, 0xA0000000, 3f);
            dottedLine(g, x1, y1, x0, y0, 0xFFECEBE4);
            fixed += Math.hypot(a[0] - b[0], a[1] - b[1]);
        }
        int[] last = measurePts.get(measurePts.size() - 1);
        double lx = toScreenX(last[0] + 0.5), ly = toScreenY(last[1] + 0.5);
        boolean free = !blockedAt(hits, mouseX, mouseY);
        if (free) {
            int cbx = (int) Math.floor(toBlockX(mouseX)), cbz = (int) Math.floor(toBlockZ(mouseY));
            double cxs = toScreenX(cbx + 0.5), cys = toScreenY(cbz + 0.5);
            dottedLine(g, lx, ly, cxs, cys, 0xFFB9B8B0);
            routeLabel(g, cxs, cys + 22, fixed + Math.hypot(cbx - last[0], cbz - last[1]), true);
        }
        if (measurePts.size() >= 2) routeLabel(g, lx, ly, fixed, false);
    }

    // ------------------------------------------------------------ simulador de guerra

    private boolean simActive() {
        return tab == TAB_WAR && warTab == 3 && mode == MODE_WAR;
    }

    private void simToggle(SiteTerritories.Locality l) {
        String k = WarScore.key(l.name, l.country);
        if (simCities.remove(k) == null) simCities.put(k, l);
        simDirty = true;
    }

    /** Calcula a guerra com as cidades simuladas trocadas de dono, numa cópia que não toca nos dados reais. */
    private void simUpdate(SiteTerritories.Data d, SiteTerritories.WarInfo w) {
        if (simWar != w || simVersion != SiteTerritories.version()) {
            simWar = w;
            simVersion = SiteTerritories.version();
            simCities.clear();
            simDirty = true;
        }
        if (!simDirty) return;
        simDirty = false;
        simScore = null;
        simFront = null;
        if (simCities.isEmpty() || w.sides == null || w.sides.size() < 2) return;
        WarScore.Result base = WarScore.of(d, w);
        Map<String, WarScore.Holding> byKey = new HashMap<>();
        for (WarScore.Holding h : base.holdings()) byKey.put(WarScore.key(h.locality().name, h.locality().country), h);
        Map<String, SiteTerritories.Captured> cap = new java.util.LinkedHashMap<>();
        for (SiteTerritories.Captured c : LiveControl.captured(d, w)) cap.put(WarScore.key(c.name, c.country), c);
        for (Map.Entry<String, SiteTerritories.Locality> e : simCities.entrySet()) {
            WarScore.Holding h = byKey.get(e.getKey());
            if (h == null) continue;
            int from = h.holder() >= 0 ? h.holder() : h.owner();
            int to = from == 0 ? 1 : 0;
            if (to == h.owner()) {
                cap.remove(e.getKey());
                continue;
            }
            SiteTerritories.Captured c = new SiteTerritories.Captured();
            c.name = e.getValue().name;
            c.country = e.getValue().country;
            c.x = e.getValue().x;
            c.z = e.getValue().z;
            c.state = "held";
            c.liveSide = to;
            cap.put(e.getKey(), c);
        }
        SiteTerritories.WarInfo copy = new SiteTerritories.WarInfo();
        copy.id = w.id;
        copy.name = w.name;
        copy.type = w.type;
        copy.started = w.started;
        copy.ended = w.ended;
        copy.notes = w.notes;
        copy.live = false;
        copy.rule = w.rule;
        copy.theater = w.theater;
        copy.sides = w.sides;
        copy.captured = new ArrayList<>(cap.values());
        copy.shortCircuits = w.shortCircuits;
        copy.shortCircuit = w.shortCircuit;
        copy.supplyCapitals = w.supplyCapitals;
        copy.bridges = w.bridges;
        simScore = WarScore.compute(d, copy, copy.captured);
        simFront = WarFront.solve(d, copy, simScore);
    }

    private void compareRow(GuiGraphics g, String label, String before, String after) {
        int rowTop = cy;
        zebra(g, rowTop, 14, false, false);
        int aw = font.width(after), bwid = font.width(before + "  to  ");
        drawFit(g, label, bw - 34 - aw - bwid, bx + 11, rowTop + 3, COL_TEXT, false);
        g.drawString(font, after, bx + bw - 11 - aw, rowTop + 3, 0xFFECEBE4, false);
        g.drawString(font, before + "  to  ", bx + bw - 11 - aw - bwid, rowTop + 3, COL_DIM, false);
        rowIdx++;
        cy += 14;
    }

    private static int count(WarFront f, boolean encircled) {
        int n = 0;
        for (WarFront.Unit u : f.units()) if (!u.neutral() && (encircled ? u.encircled : u.inPlay)) n += u.members.size();
        return n;
    }

    void warSim(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, int mouseX, int mouseY) {
        simUpdate(d, w);
        section(g, "Simulated cities");
        if (simCities.isEmpty()) {
            paragraph(g, "Click cities on the map to simulate them falling. Nothing changes in the game.", COL_DIM);
            return;
        }
        Map<String, String> byName = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, SiteTerritories.Locality> e : simCities.entrySet()) {
            String label = SiteTerritories.splitCamel(e.getValue().name);
            names.add(label);
            byName.put(label, e.getKey());
        }
        chips(g, names, mouseX, mouseY, n -> {
            simCities.remove(byName.get(n));
            simDirty = true;
        });
        WarFront real = viewedFront(d);
        if (simScore == null || simFront == null || real == null) return;
        WarScore.Result base = WarScore.of(d, w);
        section(g, "Before and after");
        for (int i = 0; i < w.sides.size(); i++) compareRow(g, "Points: " + sideName(w, i), pts(base.points()[i]), pts(simScore.points()[i]));
        Map<String, Double> before = new HashMap<>();
        for (WarFront.Score s : real.scores()) before.put(s.nation(), s.score());
        for (WarFront.Score s : simFront.scores()) {
            double b = before.getOrDefault(s.nation(), 0.0);
            if (b != s.score()) compareRow(g, "Capitulation: " + s.nation(), pts(b), pts(s.score()) + (s.capitulated() ? " CAPITULATES" : " / 100"));
        }
        compareRow(g, "Encircled cities", String.valueOf(count(real, true)), String.valueOf(count(simFront, true)));
        compareRow(g, "In Play cities", String.valueOf(count(real, false)), String.valueOf(count(simFront, false)));
        cy += 4;
        textButton(g, bx + 9, cy, bw - 18, 16, "Clear simulation", mouseX, mouseY, true, () -> {
            simCities.clear();
            simDirty = true;
        });
        cy += 20;
    }

    private void drawSimRings(GuiGraphics g, SiteTerritories.Data d) {
        SiteTerritories.WarInfo w = viewedWar(d);
        if (w == null) return;
        simUpdate(d, w);
        for (SiteTerritories.Locality l : simCities.values()) {
            int sx = (int) Math.round(toScreenX(l.x + 0.5)), sy = (int) Math.round(toScreenY(l.z + 0.5));
            squareRing(g, sx, sy, iconRadius(l) + 5, 0xFFECEBE4);
            squareRing(g, sx, sy, iconRadius(l) + 6, 0xFFECEBE4);
        }
        if (simFront == null) return;
        for (WarFront.Unit u : simFront.units()) {
            if (!u.encircled || u.neutral()) continue;
            for (SiteTerritories.Locality l : u.members) {
                int sx = (int) Math.round(toScreenX(l.x + 0.5)), sy = (int) Math.round(toScreenY(l.z + 0.5));
                squareRing(g, sx, sy, iconRadius(l) + 3, 0xFFE8403A);
            }
        }
    }

    void warTugHeader(GuiGraphics g, SiteTerritories.Data d, SiteTerritories.WarInfo w, WarScore.Result r) {
        int y = cy;
        for (int i = 0; i < 2; i++) {
            java.util.Iterator<Integer> it = Relations.countriesOf(d, w.sides.get(i)).iterator();
            int flag = it.hasNext() ? it.next() : -1;
            String name = fit(sideName(w, i), bw / 2 - 42), value = pts(r.points()[i]) + " pts";
            if (i == 0) {
                miniFlag(g, d, flag, bx + 9, y + 4);
                g.drawString(font, name, bx + 35, y, COL_TEXT, false);
                g.drawString(font, value, bx + 35, y + 11, 0xFFECEBE4, false);
            } else {
                miniFlag(g, d, flag, bx + bw - 31, y + 4);
                g.drawString(font, name, bx + bw - 35 - font.width(name), y, COL_TEXT, false);
                g.drawString(font, value, bx + bw - 35 - font.width(value), y + 11, 0xFFECEBE4, false);
            }
        }
        cy += 25;
    }

    // ---------------------------------------------------------------------- guerra

    private WarFront frontCache;
    private SiteTerritories.WarInfo frontCacheWar;

    int activeWarCount() {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null) return 0;
        int n = 0;
        for (SiteTerritories.WarInfo w : Relations.wars(d)) if (warRunning(w)) n++;
        return n;
    }

    /** A frente da guerra em exibição (null se nenhuma ou se o site não manda as conexões). */
    WarFront viewedFront(SiteTerritories.Data d) {
        SiteTerritories.WarInfo w = viewedWar(d);
        if (w == null || w.sides == null || w.sides.size() < 2) return null;
        WarFront f = WarFront.of(d, w);
        return f.hasNetwork() ? f : null;
    }

    /** Uma vez por quadro: marcadores do dynmap (só com guerra ao vivo à vista) e efeitos de guerra. */
    static void tickWar() {
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null || !siteMode()) return;
        SiteTerritories.WarInfo viewed = viewedWar(d);
        SiteTerritories.WarInfo watched = viewed != null ? viewed : minimapWar(d);   // sem guerra escolhida, segue a primeira em andamento
        // a live war the server's war plugin covers needs no dynmap markers: the plugin says who holds what
        if (watched != null && watched.live && watched.ended == null && SiteTerritories.liveWar() && ServerSync.war(watched.id) == null) DynmapMarkers.want();
        if (watched != null && d != null) WarRecorder.observe(d, watched);
        DynmapMarkers.tick();
    }

    /** Linhas extras da dica de uma cidade na guerra em exibição: quem a segura e o estado na frente. */
    private String warTip(SiteTerritories.Data d, SiteTerritories.Locality city) {
        SiteTerritories.WarInfo w = viewedWar(d);
        if (w == null || w.sides == null) return "";
        WarScore.Result r = WarScore.of(d, w);
        String extra = "";
        for (WarScore.Holding h : r.holdings()) {
            if (h.locality() != city && !(h.locality().name != null && h.locality().name.equals(city.name)
                    && h.locality().country != null && h.locality().country.equals(city.country))) continue;
            extra += "\nValue: " + pts(h.value()) + " pts";
            if ("contested".equals(h.state())) extra += "\nContested";
            else if ("uncontrolled".equals(h.state())) extra += "\nUncontrolled";
            else if (h.holder() >= 0) extra += "\nHeld by: " + sideName(w, h.holder());
            break;
        }
        WarFront f = viewedFront(d);
        String st = f == null ? null : f.statusOf(city);
        if (st != null) extra += "\n" + st;
        return extra;
    }

    /** Para o minimapa: guerra em exibição e a frente dela (null fora do modo guerra ou sem conexões). */
    static WarFront minimapFront(SiteTerritories.Data d) {
        if (mode != MODE_WAR || d == null) return null;
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        SiteTerritories.WarInfo w = warSel >= 0 && warSel < wars.size() ? wars.get(warSel) : null;
        if (w == null || w.sides == null || w.sides.size() < 2) return null;
        WarFront f = WarFront.of(d, w);
        return f.hasNetwork() ? f : null;
    }

    static int minimapCtlColor(SiteTerritories.Data d, int ctl) {
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        SiteTerritories.WarInfo w = warSel >= 0 && warSel < wars.size() ? wars.get(warSel) : null;
        return w == null ? 0xFF9A9A9A : ctlColor(w, ctl);
    }

    private static final double ARROW_SCALE = 0.3;     // zoom a partir do qual as setas das conexões aparecem
    private static final int CHEVRON_STEP = 7;         // distância entre as setas, em pixels da interface

    /** Setas ">" de tamanho fixo na tela, deslizando de (x0, y0) para (x1, y1) e ganhando força no caminho. */
    private static WarArt.Tex chevronTex(int tone, int half, int level) {
        int w = half + 2, h = 2 * half + 1;
        int argb = (int) Math.round(255 * level / 7.0) << 24 | tone;
        int[] px = new int[w * h];
        for (int k = -half; k <= half; k++) {
            int c = half - Math.abs(k);
            px[(k + half) * w + c] = argb;
            px[(k + half) * w + c + 1] = argb;
        }
        return WarArt.texture(new WarArt.Art(w, h, px), "chev" + tone + "_" + half + "_" + level);
    }

    private void chevrons(GuiGraphics g, double x0, double y0, double x1, double y1, int color, float thick, long now) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        if (len < 10) return;
        double ux = (x1 - x0) / len, uy = (y1 - y0) / len;
        float angle = (float) Math.atan2(uy, ux);
        int r = (color >> 16) & 255, gr = (color >> 8) & 255, b = color & 255;
        int tone = (r + (255 - r) * 3 / 5) << 16 | (gr + (255 - gr) * 3 / 5) << 8 | (b + (255 - b) * 3 / 5);
        int half = Math.max(1, ((int) thick - 3) / 2);
        double offset = (now / 300.0) % CHEVRON_STEP;
        // um só giro da matriz para a linha inteira; cada seta é desenhada ao longo do eixo x
        boolean pushed = false;
        for (double s = 4 + offset; s <= len - 3; s += CHEVRON_STEP) {
            double x = x0 + ux * s, y = y0 + uy * s;
            if (x < -8 || x > width + 8 || y < -8 || y > height + 8) continue;
            double alpha = Math.min(0.3 + 0.7 * s / len, (len - s) / 6.0);
            if (alpha <= 0.02) continue;
            if (!pushed) {
                g.pose().pushMatrix();
                g.pose().translate((float) x0, (float) y0);
                g.pose().rotate(angle);
                pushed = true;
            }
            // uma seta é uma só textura (a cor e 8 níveis de opacidade ficam em cache): antes eram até 7 retângulos por seta
            int level = Math.max(1, (int) Math.round(7 * Math.min(1, alpha)));
            WarArt.Tex tex = chevronTex(tone, half, level);
            int cx = (int) Math.round(s);
            g.blit(RenderPipelines.GUI_TEXTURED, tex.id(), cx - half - 1, -half, 0f, 0f, tex.w(), tex.h(), tex.w(), tex.h(), tex.w(), tex.h());
        }
        if (pushed) g.pose().popMatrix();
    }

    /** The capture wave: what it has not reached yet keeps the old colour (as a tint, like the map), fading into the new one. */
    private void drawWaves(GuiGraphics g) {
        long now = MapClock.anim();
        // the old colour is laid as a tint of the strength the map paints countries with (MapStyle: 0.72 x Fill), so the
        // terrain still shows through; a side that was grey before is greyed the way the map greys it
        float fill = Math.max(0f, Math.min(1f, 0.72f * Prefs.fill()));
        for (WarWave.Wave w : WarWave.active(now)) {
            if (w.dist == null || w.speed <= 0) continue;
            boolean side = w.oldRgb >= 0 && WarWave.isSide(w.oldRgb);
            int rgb = side ? w.oldRgb & 0xFFFFFF : 0x5A6066;
            float strength = side ? fill : 0.55f;
            if (strength <= 0.01f) continue;
            double front = w.front(now);
            for (int j = 0; j < w.h; j++) {
                int y0 = (int) Math.floor(toScreenY(w.z0 + j * (double) w.cell));
                int y1 = (int) Math.floor(toScreenY(w.z0 + (j + 1) * (double) w.cell));
                if (y1 <= y0 || y1 < 0 || y0 > height) continue;
                int i = 0;
                while (i < w.w) {   // runs of cells with the same shade: one fill each
                    int level = waveLevel(w, j * w.w + i, front), end = i + 1;
                    while (end < w.w && waveLevel(w, j * w.w + end, front) == level) end++;
                    if (level > 0) {
                        int x0 = (int) Math.floor(toScreenX(w.x0 + i * (double) w.cell));
                        int x1 = (int) Math.floor(toScreenX(w.x0 + end * (double) w.cell));
                        int a = Math.round(strength * 255f * level / WAVE_LEVELS);
                        g.fill(x0, y0, x1, y1, (a << 24) | rgb);
                    }
                    i = end;
                }
            }
        }
    }

    /** Shades of the wave's edge: enough for a smooth gradient, few enough to keep the fills in long runs. */
    private static final int WAVE_LEVELS = 24;

    /** How much old colour a cell keeps (0 = none, WAVE_LEVELS = all), or 0 outside the province. */
    private static int waveLevel(WarWave.Wave w, int at, double front) {
        double d = w.dist[at];
        if (d < 0) return 0;
        return (int) Math.round(WarWave.oldShare(d, front, w.band) * WAVE_LEVELS);
    }

    /** Linhas de frente (grossas, cada metade na cor de quem segura), ligações internas (pontilhadas) e anéis nas cidades In Play. */
    private void drawWarFront(GuiGraphics g, SiteTerritories.Data d) {
        if (mode != MODE_WAR || scale < 0.04) return;
        SiteTerritories.WarInfo war = viewedWar(d);
        WarFront f = viewedFront(d);
        if (war == null || f == null) return;
        for (WarFront.Hot h : f.innerLines()) {
            double x0 = sx(h.a().x + 0.5, h.a().z + 0.5), y0 = sy(h.a().x + 0.5, h.a().z + 0.5), x1 = sx(h.b().x + 0.5, h.b().z + 0.5), y1 = sy(h.b().x + 0.5, h.b().z + 0.5);
            dottedLine(g, x0, y0, x1, y1, 0xC8C8C8C8);
        }
        boolean arrows = scale >= ARROW_SCALE;
        float thick = arrows ? (float) Math.max(7, Math.min(9, 3 * scale)) : 2f;
        long now = MapClock.anim();
        for (WarFront.Hot h : f.hotLines()) {
            double x0 = sx(h.a().x + 0.5, h.a().z + 0.5), y0 = sy(h.a().x + 0.5, h.a().z + 0.5), x1 = sx(h.b().x + 0.5, h.b().z + 0.5), y1 = sy(h.b().x + 0.5, h.b().z + 0.5);
            double len = Math.hypot(x1 - x0, y1 - y0);
            if (len < 1) continue;
            // a barra para antes do ícone das cidades
            double ux = (x1 - x0) / len, uy = (y1 - y0) / len, gapA = iconRadius(h.a()) + 4, gapB = iconRadius(h.b()) + 4;
            if (len > gapA + gapB + 6) {
                x0 += ux * gapA;
                y0 += uy * gapA;
                x1 -= ux * gapB;
                y1 -= uy * gapB;
            }
            WarFront.Unit ua = f.unitOf(h.a()), ub = f.unitOf(h.b());
            boolean ea = ua != null && ua.encircled, eb = ub != null && ub.encircled;
            int ca = ctlColor(war, h.controlA()), cb = ctlColor(war, h.controlB());
            segment(g, x0, y0, x1, y1, 0xB0000000, thick + 2);
            if (ea != eb) {       // só uma cercada: só quem está fora avança, apontando para ela
                int c = ea ? cb : ca;
                segment(g, x0, y0, x1, y1, c, thick);
                if (arrows) {
                    if (ea) chevrons(g, x1, y1, x0, y0, c, thick, now);
                    else chevrons(g, x0, y0, x1, y1, c, thick, now);
                }
            } else {
                double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
                segment(g, x0, y0, mx, my, ca, thick);
                segment(g, mx, my, x1, y1, cb, thick);
                if (arrows) {
                    chevrons(g, x0, y0, mx, my, ca, thick, now);
                    chevrons(g, x1, y1, mx, my, cb, thick, now);
                }
            }
        }
        double phase = (now % 2400) / 2400.0;
        for (WarFront.Unit u : f.units()) {
            if (u.neutral() || (!u.inPlay && !u.encircled)) continue;
            for (SiteTerritories.Locality l : u.members) {
                int sx = (int) Math.round(sx(l.x + 0.5, l.z + 0.5)), sy = (int) Math.round(sy(l.x + 0.5, l.z + 0.5));
                if (sx < -30 || sx > width + 30 || sy < -30 || sy > height + 30 || !cityVisible(l)) continue;
                if (u.inPlay) {
                    int a = (int) Math.round(190 * (1 - phase));
                    squareRing(g, sx, sy, iconRadius(l) + 2 + (int) Math.round(phase * 10), (a << 24) | (u.encircled ? 0xE8403A : 0xECEBE4));
                }
                if (u.encircled) {       // anel fixo da cidade cercada
                    squareRing(g, sx, sy, iconRadius(l) + 3, 0xFFE8403A);
                    squareRing(g, sx, sy, iconRadius(l) + 4, 0xFFE8403A);
                }
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = (event.x() / ui), my = (event.y() / ui);
        if (helpOpen) {
            helpOpen = false;
            return true;
        }
        if (photoMode) {
            photoMode = false;
            return true;
        }
        if (searchOpen && !(mx >= searchX && mx < searchX + searchW && my >= searchY && my < searchY + searchH)) {
            closeSearch();
            return true;
        }
        if (toolMode != 0 && event.button() == 1) {
            toolMode = 0;
            measurePts.clear();
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(mx, my)) continue;
            if (h.action() != null && event.button() == 0) h.action().run();
            return true;
        }
        if (event.button() == 1) {
            if (markMenu != null) {
                markMenu = null;
                return true;
            }
            closePanel();
            selPlayer = null;
            return true;
        }
        if (exportBusy()) return true;   // the view belongs to the export
        dragging = true;
        moved = false;
        pressX = mx;
        pressY = my;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (editorDrag(event.x() / ui, event.y() / ui)) return true;
        if (timelineDrag) {
            seekTimeline(event.x() / ui);
            return true;
        }
        if (gDrag >= 0) {
            double f = Math.hypot((event.x() / ui) - gDragAX, (event.y() / ui) - gDragAY) / gDragDist;
            setGroupPct(gDrag, (int) (Math.round(gDragFrom * f * 20) * 5));
            return true;
        }
        dx /= ui;
        dy /= ui;
        if (dragging) {
            if (Math.abs((event.x() / ui) - pressX) > 3 || Math.abs((event.y() / ui) - pressY) > 3) moved = true;
            if (moved) {
                zoomTarget = scale;          // dragging ends a zoom in progress
                viewX -= dx / scale;
                viewZ -= dy / scale;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (editorRelease()) return true;
        if (timelineDrag) {
            timelineDrag = false;
            return true;
        }
        if (gDrag >= 0) {
            gDrag = -1;
            Prefs.save();
            return true;
        }
        if (dragging) {
            dragging = false;
            if (!moved) select(blockX((event.x() / ui), (event.y() / ui)), blockZ((event.x() / ui), (event.y() / ui)), (event.x() / ui), (event.y() / ui));
            return true;
        }
        return super.mouseReleased(event);
    }

    private void select(double bxx, double bzz, double sx, double sy) {
        if (toolMode != 0) {
            toolClick((int) Math.floor(bxx), (int) Math.floor(bzz));
            return;
        }
        Prefs.Mark mark = markAt(bxx, bzz);
        if (mark != null) {
            markMenu = mark;
            return;
        }
        markMenu = null;
        if (siteMode() && simActive()) {
            SiteTerritories.Locality simCity = cityAt(sx, sy);
            if (simCity != null) {
                simToggle(simCity);
                return;
            }
        }
        if (siteMode()) {
            SiteTerritories.Data d = SiteTerritories.data();
            Spot sp = playerAt(sx, sy);
            if (sp != null) {
                pickedAccount = sp.e().account;
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
            pickedAccount = sp.e().account;
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
        if (exportBusy()) return true;   // the view belongs to the export
        mouseX /= ui;
        mouseY /= ui;
        if (cardVisible && mouseX >= cardX && mouseX < cardX + cardW && mouseY >= cardY && mouseY < cardY + cardH) {
            cardScroll = Math.max(0, cardScroll - scrollY * 20);
            return true;
        }
        if (panelVisible && mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH) {
            SCROLL[tab] = Math.max(0, SCROLL[tab] - scrollY * 20);
            return true;
        }
        if (blockedAt(hits, mouseX, mouseY)) return true;
        zoomBy(Math.pow(1.2, scrollY), mouseX, mouseY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key0 = event.key();
        if (helpOpen) {
            helpOpen = false;
            return true;
        }
        if (photoMode) {
            if (key0 == InputConstants.KEY_ESCAPE) {
                photoMode = false;
                return true;
            }
            return false;
        }
        if (!searchOpen && editorKey(key0, event.hasShiftDown())) return true;   // the replay editor and the list of recordings
        if (key0 == InputConstants.KEY_F7) {
            Perf.on = !Perf.on;
            return true;
        }
        if (searchOpen) {
            if (key0 == InputConstants.KEY_ESCAPE) {
                closeSearch();
                return true;
            }
            if (renameTo != null && (key0 == InputConstants.KEY_RETURN || key0 == InputConstants.KEY_NUMPADENTER)) {   // a name asked by the editor
                String name = searchBox.getValue().trim();
                java.util.function.Consumer<String> done = renameTo;
                closeSearch();
                if (!name.isEmpty()) done.accept(name);
                return true;
            }
            if (renameMark != null && (key0 == InputConstants.KEY_RETURN || key0 == InputConstants.KEY_NUMPADENTER)) {
                String name = searchBox.getValue().trim();
                if (!name.isEmpty()) {
                    renameMark.name = name;
                    Prefs.save();
                }
                markMenu = renameMark;
                closeSearch();
                return true;
            }
            if (key0 == InputConstants.KEY_RETURN || key0 == InputConstants.KEY_NUMPADENTER) {
                if (searchIdx >= 0 && searchIdx < searchHits.size()) runSearchHit(searchHits.get(searchIdx));
                return true;
            }
            if (key0 == InputConstants.KEY_UP || key0 == InputConstants.KEY_DOWN) {
                if (!searchHits.isEmpty()) searchIdx = Math.floorMod(searchIdx + (key0 == InputConstants.KEY_UP ? -1 : 1), searchHits.size());
                return true;
            }
            return super.keyPressed(event);
        }
        if (toolMode != 0 && key0 == InputConstants.KEY_ESCAPE) {
            toolMode = 0;
            measurePts.clear();
            return true;
        }
        if (BmcAtlasClient.OPEN_KEY.matches(event)) {
            onClose();
            return true;
        }
        int k = event.key();
        if (toolMode == 1 && k == InputConstants.KEY_BACKSPACE) {
            if (!measurePts.isEmpty()) measurePts.remove(measurePts.size() - 1);
            return true;
        }
        if (markMenu != null && k == InputConstants.KEY_ESCAPE) {
            markMenu = null;
            return true;
        }
        if (k == InputConstants.KEY_F || k == InputConstants.KEY_SLASH) {
            openSearch();
            searchOpenedAt = System.currentTimeMillis();
            return true;
        }
        if (k == InputConstants.KEY_SPACE) {
            centerOnPlayer();
            return true;
        }
        if (k == InputConstants.KEY_H) {
            helpOpen = true;
            return true;
        }
        if (k == InputConstants.KEY_C) {
            copyCoords();
            return true;
        }
        if (k == InputConstants.KEY_M || k == InputConstants.KEY_R) {
            toolMode = k == InputConstants.KEY_M ? 2 : 1;
            measurePts.clear();
            markMenu = null;
            return true;
        }
        if (k == InputConstants.KEY_EQUALS || k == InputConstants.KEY_ADD || k == InputConstants.KEY_MINUS) {
            zoomBy(k == InputConstants.KEY_MINUS ? 1 / 1.3 : 1.3, areaCx(), areaCy());
            return true;
        }
        double step = 60 / Math.max(scale, 0.01);
        if (k == InputConstants.KEY_LEFT || k == InputConstants.KEY_A) {
            zoomTarget = scale;
            viewX -= step;
            return true;
        }
        if (k == InputConstants.KEY_RIGHT || k == InputConstants.KEY_D) {
            zoomTarget = scale;
            viewX += step;
            return true;
        }
        if (k == InputConstants.KEY_UP || k == InputConstants.KEY_W) {
            zoomTarget = scale;
            viewZ -= step;
            return true;
        }
        if (k == InputConstants.KEY_DOWN || k == InputConstants.KEY_S) {
            zoomTarget = scale;
            viewZ += step;
            return true;
        }
        if (k == InputConstants.KEY_1) {
            mode = MODE_PHYSICAL;
            return true;
        }
        if (k == InputConstants.KEY_2) {
            mode = MODE_POLITICAL;
            return true;
        }
        if (k == InputConstants.KEY_3) {
            mode = MODE_PROVINCES;
            return true;
        }
        if (k == InputConstants.KEY_4 && siteMode()) {
            enterDiplomacy();
            return true;
        }
        if (k == InputConstants.KEY_5 && siteMode()) {
            enterWar();
            return true;
        }
        return super.keyPressed(event);
    }
}
