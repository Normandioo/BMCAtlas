package com.normandioo.bmcatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Minimapa próprio na tela do jogo: o mesmo terreno e os mesmos países do mapa grande, centrado em você, com as cidades
 * do site e os jogadores online. Tamanho, zoom, canto e o que mostrar vêm das configurações (Prefs).
 */
final class Minimap {
    private Minimap() {}

    private static final int MARGIN = 6;

    /** Z alterna o minimapa ampliado (centro da tela). */
    static boolean expanded;
    private static final String[] HEADING = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    static void render(GuiGraphics g, float partial) {
        Prefs.Data p = Prefs.get();
        Minecraft mc = Minecraft.getInstance();
        if (!p.minimap || mc.player == null || mc.level == null || Compat.hideGui(mc)) return;
        if (Compat.screen(mc) instanceof MapScreen) return;       // o mapa grande já está na tela
        if (debugShown(mc)) return;                      // F3 aberto: não cobrir as informações
        MapScreen.idleTint();                             // limites das cidades gravados no terreno
        Font font = mc.font;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int size = expanded ? Math.max(p.minimapSize, Math.min(sh - 70, (int) (sw * 0.6))) : p.minimapSize;
        int x, y;
        if (expanded) {
            x = (sw - size) / 2;
            y = (sh - size) / 2 - 8;
        } else {
            x = (p.minimapCorner & 1) == 0 ? MARGIN : sw - size - MARGIN;
            y = p.minimapCorner < 2 ? MARGIN : sh - size - MARGIN - 22;
            // o vanilla ocupa o canto superior direito (efeitos); desce um pouco
            if (p.minimapCorner == 1) y += 24;
        }

        // escala e posição alinhadas aos pixels físicos: o terreno desliza sem tremer
        double gs = Math.max(1.0, mc.getWindow().getGuiScale());
        double scale = p.minimapZoomPct / 100.0;
        if (scale * gs >= 1.0) scale = Math.max(1.0, Math.round(scale * gs)) / gs;
        double cx = net.minecraft.util.Mth.lerp(partial, mc.player.xo, mc.player.getX());
        double cz = net.minecraft.util.Mth.lerp(partial, mc.player.zo, mc.player.getZ());
        if (!(Compat.screen(mc) instanceof MapScreen)) MapScreen.liveTick();
        int mode = MapScreen.minimapMode();

        // moldura
        g.fill(x - 3, y - 3, x + size + 3, y + size + 3, 0xFF0E0C08);
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF6A6F72);
        g.fill(x - 2, y - 2, x + size + 2, y - 1, 0xFF7E8387);
        final int alpha = Math.max(40, Math.min(100, p.minimapOpacityPct)) * 255 / 100;
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, (alpha << 24) | 0x0B1628);

        g.enableScissor(x, y, x + size, y + size);
        double halfBlocks = size / 2.0 / scale;
        int rx0 = (int) Math.floor((cx - halfBlocks) / MapData.REGION_PX);
        int rx1 = (int) Math.floor((cx + halfBlocks) / MapData.REGION_PX);
        int rz0 = (int) Math.floor((cz - halfBlocks) / MapData.REGION_PX);
        int rz1 = (int) Math.floor((cz + halfBlocks) / MapData.REGION_PX);
        int ox = x + size / 2, oy = y + size / 2;
        SiteTerritories.Data d = MapData.siteApplies() && SiteTerritories.active() ? SiteTerritories.data() : null;
        int uploads = 0;
        g.pose().pushMatrix();
        final double tx = Math.round((ox - cx * scale) * gs) / gs, ty = Math.round((oy - cz * scale) * gs) / gs;
        g.pose().translate((float) tx, (float) ty);
        g.pose().scale((float) scale, (float) scale);
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                MapData.Region r = MapData.region(rx, rz, false);
                if (r == null && d != null && touchesSite(d, rx, rz)) r = MapData.viewRegion(rx, rz);
                if (r == null) continue;
                boolean stale = r.id == null || r.dirty || r.styledMode != mode || r.styledVer != MapStyle.overrideVersion;
                if (stale && uploads < 1) {
                    if (r.ensureUploaded(mode)) uploads++;
                }
                if (r.id == null) continue;
                MapData.markUsed(r);
                int rs = MapData.REGION_PX;
                g.blit(RenderPipelines.GUI_TEXTURED, r.id, rx * rs, rz * rs, 0f, 0f, rs, rs, rs, rs, rs, rs, (alpha << 24) | 0xFFFFFF);
            }
        }
        g.pose().popMatrix();

        if (d != null && p.minimapCities) drawFront(g, d, tx, ty, scale, gs);
        if (d != null && p.minimapCities) drawCities(g, d, tx, ty, ox, oy, scale, size, gs);
        drawMarks(g, font, tx, ty, ox, oy, scale, size, gs);
        if (p.minimapPlayers) drawPlayers(g, d, mc, ox, oy, cx, cz, scale, size, partial, gs);
        drawSelf(g, mc, ox, oy, partial);
        g.disableScissor();

        // norte e coordenadas
        g.drawString(font, "N", x + size / 2 - 2, y + 2, 0xFFECEBE4, true);
        g.fill(x - 3, y + size + 3, x + size + 3, y + size + (d != null ? 26 : 16), 0xE01E2123);
        g.drawString(font, "S", x + size / 2 - 2, y + size - 10, 0xFFB9B8B0, true);
        g.drawString(font, "E", x + size - 9, y + size / 2 - 4, 0xFFB9B8B0, true);
        g.drawString(font, "W", x + 4, y + size / 2 - 4, 0xFFB9B8B0, true);
        // direção para onde você olha (yaw 0 = sul, 90 = oeste, 180 = norte, 270 = leste)
        int dir = Math.floorMod(Math.round(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()) / 45f), 8);
        String coords = (int) Math.floor(cx) + ", " + (int) Math.floor(cz) + "  " + HEADING[dir];
        g.drawString(font, coords, x + (size - font.width(coords)) / 2, y + size + 5, 0xFFE8E2D0, true);
        if (d != null && !expanded) {
            SiteTerritories.WarInfo watched = MapScreen.minimapWar(d);
            if (watched != null) CaptureNotice.observe(d, watched);
            boolean noticeUp = p.minimapCorner >= 2;
            CaptureNotice.render(g, font, mc, x, size, noticeUp ? y - 3 : y + size + 26, noticeUp, sw);
        }
        if (d != null) {
            int prov = SiteTerritories.provinceAt(d, (int) Math.floor(cx), (int) Math.floor(cz));
            if (prov >= 0) {
                String country = d.labels[d.provCountry[prov]];
                while (country.length() > 1 && font.width(country) > size + 8) country = country.substring(0, country.length() - 1);
                int tw = font.width(country);
                g.drawString(font, country, x + (size - tw) / 2, y + size + 15, 0xFF000000 | d.rgb[d.provCountry[prov]], true);
            }
        }
    }

    /** Verdadeiro enquanto a tela de depuração (F3) está aberta. */
    private static boolean debugShown(Minecraft mc) {
        try {
            return mc.getDebugOverlay().showDebugScreen();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean touchesSite(SiteTerritories.Data d, int rx, int rz) {
        int x0 = rx * MapData.REGION_PX, z0 = rz * MapData.REGION_PX;
        return x0 + MapData.REGION_PX > d.minX && x0 <= d.maxX && z0 + MapData.REGION_PX > d.minZ && z0 <= d.maxZ;
    }

    /** Conexões da guerra (frentes quentes, metade na cor de cada lado) por baixo dos ícones das cidades. */
    private static void drawFront(GuiGraphics g, SiteTerritories.Data d, double tx, double ty, double scale, double gs) {
        WarFront f = MapScreen.minimapFront(d);
        if (f == null) return;
        for (WarFront.Hot h : f.innerLines()) {
            line(g, snap(tx + (h.a().x + 0.5) * scale, gs), snap(ty + (h.a().z + 0.5) * scale, gs), snap(tx + (h.b().x + 0.5) * scale, gs), snap(ty + (h.b().z + 0.5) * scale, gs), 0x80C8C8C8, 1f);
        }
        for (WarFront.Hot h : f.hotLines()) {
            double x0 = snap(tx + (h.a().x + 0.5) * scale, gs), y0 = snap(ty + (h.a().z + 0.5) * scale, gs);
            double x1 = snap(tx + (h.b().x + 0.5) * scale, gs), y1 = snap(ty + (h.b().z + 0.5) * scale, gs);
            double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            WarFront.Unit ua = f.unitOf(h.a()), ub = f.unitOf(h.b());
            boolean ea = ua != null && ua.encircled, eb = ub != null && ub.encircled;
            int ca = MapScreen.minimapCtlColor(d, h.controlA()), cb = MapScreen.minimapCtlColor(d, h.controlB());
            line(g, x0, y0, x1, y1, 0xB0000000, 4f);
            if (ea != eb) {       // só uma cercada: a ligação toda na cor de quem está de fora
                line(g, x0, y0, x1, y1, ea ? cb : ca, 2.5f);
            } else {
                line(g, x0, y0, mx, my, ca, 2.5f);
                line(g, mx, my, x1, y1, cb, 2.5f);
            }
        }
    }

    private static double snap(double v, double gs) {
        return Math.round(v * gs) / gs;
    }

    private static void line(GuiGraphics g, double x0, double y0, double x1, double y1, int color, float thick) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 0.3) return;
        g.pose().pushMatrix();
        g.pose().translate((float) x0, (float) y0);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.pose().translate(0f, -thick / 2f);
        g.pose().scale(1f, thick);
        g.fill(0, 0, (int) Math.ceil(len) + 1, 1, color);
        g.pose().popMatrix();
    }

    private static void drawCities(GuiGraphics g, SiteTerritories.Data d, double tx, double ty, int ox, int oy, double scale, int size, double gs) {
        int half = size / 2 + 6;
        for (SiteTerritories.Locality l : d.cityOrder) {
            // só as maiores quando o zoom é pequeno
            if (l.tier < 1 && scale < 1.0) continue;
            if (l.tier < 2 && scale < 0.6) continue;
            if (l.tier < 3 && scale < 0.35) continue;
            // mesma grade de pixels físicos do terreno: o símbolo não balança quando você anda
            double sx = Math.round((tx + (l.x + 0.5) * scale) * gs) / gs, sy = Math.round((ty + (l.z + 0.5) * scale) * gs) / gs;
            if (Math.abs(sx - ox) > half || Math.abs(sy - oy) > half) continue;
            int isz = Math.max(5, (int) Math.round((l.tier >= 3 ? 12 : l.tier == 2 ? 10 : 8) * Prefs.get().cityIconPct / 100.0));
            RemoteImages.Img img = cityImage(l, (int) Math.round(isz * gs));
            if (img != null) {
                RemoteImages.drawCrisp(g, img, sx, sy);
                continue;
            }
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            {
                int r = l.tier >= 3 ? 2 : 1;
                int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] | 0xFF000000 : 0xFF909090;
                g.fill(-r - 1, -r - 1, r + 2, r + 2, 0xFF05080C);
                g.fill(-r, -r, r + 1, r + 1, l.tier == 4 ? 0xFFD9B45A : rgb);
            }
            g.pose().popMatrix();
        }
    }

    /** The points you marked on the map (name shown while the minimap is enlarged). */
    private static void drawMarks(GuiGraphics g, Font font, double tx, double ty, int ox, int oy, double scale, int size, double gs) {
        String map = MapScreen.activeMapKey();
        int half = size / 2 + 4;
        for (Prefs.Mark m : Prefs.get().marks) {
            if (!map.equals(m.map)) continue;
            double sx = Math.round((tx + (m.x + 0.5) * scale) * gs) / gs, sy = Math.round((ty + (m.z + 0.5) * scale) * gs) / gs;
            if (Math.abs(sx - ox) > half || Math.abs(sy - oy) > half) continue;
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            g.fill(-3, -3, 4, 4, 0xFF0A0B08);
            g.fill(-2, -2, 3, 3, Prefs.markColor(m));
            if (expanded && m.name != null) g.drawString(font, m.name, 6, -4, Prefs.markColor(m), true);
            g.pose().popMatrix();
        }
    }

    /** Frame color of a player: green on your side of the followed war, red on the other, else the given color. */
    private static int relationColor(SiteTerritories.Data d, SiteTerritories.WarInfo war, int mySide, String account, int fallback) {
        if (d == null || war == null || mySide < 0) return fallback;
        String low = account.toLowerCase(Locale.ROOT);
        String site = d.ignToSite.get(low);
        Integer ci = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : low);
        if (ci == null) return fallback;
        int side = WarScore.sideOfCountry(war, d.ids[ci]);
        return side < 0 ? fallback : side == mySide ? 0xFF57C26B : 0xFFE5534B;
    }

    private static RemoteImages.Img cityImage(SiteTerritories.Locality l, int size) {
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

    /** Cabeças dos jogadores: quem está carregado por perto usa a skin real; o resto, a cabeça do dynmap. */
    private static void drawPlayers(GuiGraphics g, SiteTerritories.Data d, Minecraft mc, int ox, int oy, double cx, double cz, double scale, int size, float partial, double gs) {
        String self = mc.player.getName().getString();
        double half = size / 2.0 + 6;
        java.util.Set<String> drawn = new java.util.HashSet<>();
        SiteTerritories.WarInfo war = d == null ? null : MapScreen.minimapWar(d);
        int mySide = -1;
        if (war != null) {
            String low = self.toLowerCase(Locale.ROOT);
            String site = d.ignToSite.get(low);
            Integer mine = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : low);
            if (mine != null) mySide = WarScore.sideOfCountry(war, d.ids[mine]);
        }
        for (AbstractClientPlayer pl : mc.level.players()) {
            if (pl == mc.player) continue;
            drawn.add(pl.getName().getString().toLowerCase(Locale.ROOT));
            double px = net.minecraft.util.Mth.lerp(partial, pl.xo, pl.getX()), pz = net.minecraft.util.Mth.lerp(partial, pl.zo, pl.getZ());
            double sx = Math.round((ox + (px - cx) * scale) * gs) / gs, sy = Math.round((oy + (pz - cz) * scale) * gs) / gs;
            if (Math.abs(sx - ox) > half || Math.abs(sy - oy) > half) continue;
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            g.fill(-6, -6, 6, 6, relationColor(d, war, mySide, pl.getName().getString(), 0xFF05080C));
            Compat.face(g, pl, -5, -5, 10);
            g.pose().popMatrix();
        }
        if (!SiteTerritories.showPlayers()) return;
        OnlinePlayers.tick();
        long now = System.currentTimeMillis();
        String dyn = SiteTerritories.dynmapUrl();
        for (OnlinePlayers.Entry e : OnlinePlayers.list()) {
            if (e.account.equalsIgnoreCase(self) || drawn.contains(e.account.toLowerCase(Locale.ROOT))) continue;
            double sx = Math.round((ox + (e.x(now) - cx) * scale) * gs) / gs, sy = Math.round((oy + (e.z(now) - cz) * scale) * gs) / gs;
            if (Math.abs(sx - ox) > half || Math.abs(sy - oy) > half) continue;
            int rgb = 0xFFFFFFFF;
            if (d != null) {
                String site = d.ignToSite.get(e.account.toLowerCase(Locale.ROOT));
                Integer ci = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : e.account.toLowerCase(Locale.ROOT));
                if (ci != null) rgb = d.rgb[ci] | 0xFF000000;
            }
            rgb = relationColor(d, war, mySide, e.account, rgb);
            RemoteImages.Img face = dyn == null ? null : RemoteImages.icon(dyn + "tiles/faces/32x32/" + e.account + ".png", 10);
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            if (face != null) {
                g.fill(-6, -6, 6, 6, rgb);
                g.blit(RenderPipelines.GUI_TEXTURED, face.id(), -5, -5, 0f, 0f, 10, 10, face.w(), face.h(), face.w(), face.h());
            } else {
                g.fill(-2, -2, 3, 3, 0xFF05080C);
                g.fill(-1, -1, 2, 2, rgb);
            }
            g.pose().popMatrix();
        }
    }

    /** Você: a cabeça da sua skin, com moldura clara para se destacar dos outros jogadores. */
    private static void drawSelf(GuiGraphics g, Minecraft mc, int ox, int oy, float partial) {
        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        g.fill(-7, -7, 7, 7, 0xFF05080C);
        g.fill(-6, -6, 6, 6, 0xFFFFF2B0);
        Compat.face(g, mc.player, -5, -5, 10);
        g.pose().popMatrix();
        // where you are looking (yaw 0 = south = down on the map)
        double yaw = Math.toRadians(mc.player.getYRot());
        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        g.pose().rotate((float) Math.atan2(Math.cos(yaw), -Math.sin(yaw)));
        for (int i = 0; i < 5; i++) g.fill(8 + i, -(5 - i), 9 + i, 5 - i, 0xFF05080C);
        for (int i = 0; i < 4; i++) g.fill(9 + i, -(3 - i), 10 + i, 3 - i, 0xFFFFF2B0);
        g.pose().popMatrix();
    }
}
