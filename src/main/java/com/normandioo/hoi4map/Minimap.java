package com.normandioo.hoi4map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.List;
import java.util.Locale;

/**
 * Minimapa próprio na tela do jogo: o mesmo terreno e os mesmos países do mapa grande, centrado em você, com as cidades
 * do site e os jogadores online. Tamanho, zoom, canto e o que mostrar vêm das configurações (Prefs).
 */
final class Minimap {
    private Minimap() {}

    private static final int MARGIN = 6;

    static void render(GuiGraphics g, float partial) {
        Prefs.Data p = Prefs.get();
        Minecraft mc = Minecraft.getInstance();
        if (!p.minimap || mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (mc.screen instanceof MapScreen) return;       // o mapa grande já está na tela
        if (debugShown(mc)) return;                      // F3 aberto: não cobrir as informações
        Font font = mc.font;
        int size = p.minimapSize;
        int sw = g.guiWidth(), sh = g.guiHeight();
        int x = (p.minimapCorner & 1) == 0 ? MARGIN : sw - size - MARGIN;
        int y = p.minimapCorner < 2 ? MARGIN : sh - size - MARGIN - 22;
        // o vanilla ocupa o canto superior direito (efeitos); desce um pouco
        if (p.minimapCorner == 1) y += 24;

        // escala e posição alinhadas aos pixels físicos: o terreno desliza sem tremer
        double gs = Math.max(1.0, mc.getWindow().getGuiScale());
        double scale = p.minimapZoomPct / 100.0;
        if (scale * gs >= 1.0) scale = Math.max(1.0, Math.round(scale * gs)) / gs;
        double cx = net.minecraft.util.Mth.lerp(partial, mc.player.xo, mc.player.getX());
        double cz = net.minecraft.util.Mth.lerp(partial, mc.player.zo, mc.player.getZ());
        int mode = MapScreen.minimapMode();

        // moldura
        g.fill(x - 3, y - 3, x + size + 3, y + size + 3, 0xFF0E0C08);
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF7A6538);
        g.fill(x - 2, y - 2, x + size + 2, y - 1, 0xFFB89C5C);
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF0B1628);

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
        g.pose().translate((float) (Math.round((ox - cx * scale) * gs) / gs), (float) (Math.round((oy - cz * scale) * gs) / gs));
        g.pose().scale((float) scale, (float) scale);
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                MapData.Region r = MapData.region(rx, rz, false);
                if (r == null && d != null && touchesSite(d, rx, rz)) r = MapData.viewRegion(rx, rz);
                if (r == null) continue;
                boolean stale = r.id == null || r.dirty || r.styledMode != mode || r.styledVer != MapStyle.overrideVersion;
                if (stale && uploads < 1) {
                    r.ensureUploaded(mode);
                    uploads++;
                }
                if (r.id == null) continue;
                MapData.markUsed(r);
                int rs = MapData.REGION_PX;
                g.blit(RenderPipelines.GUI_TEXTURED, r.id, rx * rs, rz * rs, 0f, 0f, rs, rs, rs, rs, rs, rs);
            }
        }
        g.pose().popMatrix();

        if (d != null && p.minimapLimits) drawLimits(g, d, ox, oy, cx, cz, scale, size);
        if (d != null && p.minimapCities) drawCities(g, d, ox, oy, cx, cz, scale, size);
        if (p.minimapPlayers && SiteTerritories.showPlayers()) drawPlayers(g, d, mc, ox, oy, cx, cz, scale, size);
        drawSelf(g, mc, ox, oy, partial);
        g.disableScissor();

        // norte e coordenadas
        g.drawString(font, "N", x + size / 2 - 2, y + 2, 0xFFF2E3B0, true);
        String coords = (int) Math.floor(cx) + ", " + (int) Math.floor(cz);
        g.drawString(font, coords, x + (size - font.width(coords)) / 2, y + size + 5, 0xFFE8E2D0, true);
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

    /** Limites de captura das cidades como linhas finas (tracejadas), na cor do país. */
    private static void drawLimits(GuiGraphics g, SiteTerritories.Data d, int ox, int oy, double cx, double cz, double scale, int size) {
        if (d.zones == null) return;
        double half = size / 2.0 / scale + 8;
        double l = cx - half, r = cx + half, t = cz - half, b = cz + half;
        for (SiteTerritories.Zone z : d.zones) {
            double[] box = z.box;
            if (box == null || box[2] < l || box[0] > r || box[3] < t || box[1] > b) continue;
            int rgb = z.countryIdx >= 0 ? (d.rgb[z.countryIdx] | 0xFF000000) : 0xFFF4F1E8;
            for (double[] ring : z.rings) {
                int n = ring.length / 2;
                if (n < 2) continue;
                double px = ox + (ring[0] - cx) * scale, py = oy + (ring[1] - cz) * scale;
                double fx = px, fy = py;
                for (int i = 1; i <= n; i++) {
                    double x = i == n ? fx : ox + (ring[2 * i] - cx) * scale, y = i == n ? fy : oy + (ring[2 * i + 1] - cz) * scale;
                    double dx = x - px, dy = y - py, len = Math.hypot(dx, dy);
                    if (len < 3 && i < n) continue;
                    if (!((px < ox - half * scale && x < ox - half * scale) || (px > ox + half * scale && x > ox + half * scale)
                            || (py < oy - half * scale && y < oy - half * scale) || (py > oy + half * scale && y > oy + half * scale))) {
                        g.pose().pushMatrix();
                        g.pose().translate((float) px, (float) py);
                        g.pose().rotate((float) Math.atan2(dy, dx));
                        g.fill(0, 0, (int) Math.ceil(len) + 1, 1, rgb);
                        g.pose().popMatrix();
                    }
                    px = x;
                    py = y;
                }
            }
        }
    }

    private static boolean touchesSite(SiteTerritories.Data d, int rx, int rz) {
        int x0 = rx * MapData.REGION_PX, z0 = rz * MapData.REGION_PX;
        return x0 + MapData.REGION_PX > d.minX && x0 <= d.maxX && z0 + MapData.REGION_PX > d.minZ && z0 <= d.maxZ;
    }

    private static void drawCities(GuiGraphics g, SiteTerritories.Data d, int ox, int oy, double cx, double cz, double scale, int size) {
        int half = size / 2 + 4;
        for (SiteTerritories.Locality l : d.cityOrder) {
            // só as maiores quando o zoom é pequeno
            if (l.tier < 1 && scale < 1.0) continue;
            if (l.tier < 2 && scale < 0.6) continue;
            if (l.tier < 3 && scale < 0.35) continue;
            double sx = ox + (l.x + 0.5 - cx) * scale, sy = oy + (l.z + 0.5 - cz) * scale;
            if (Math.abs(sx - ox) > half || Math.abs(sy - oy) > half) continue;
            int isz = l.tier >= 3 ? 12 : l.tier == 2 ? 10 : 8;
            RemoteImages.Img img = cityImage(l, isz);
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            if (img != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, img.id(), -isz / 2, -isz / 2, 0f, 0f, isz, isz, img.w(), img.h(), img.w(), img.h());
            } else {
                int r = l.tier >= 3 ? 2 : 1;
                int rgb = l.countryIdx >= 0 ? d.rgb[l.countryIdx] | 0xFF000000 : 0xFF909090;
                g.fill(-r - 1, -r - 1, r + 2, r + 2, 0xFF05080C);
                g.fill(-r, -r, r + 1, r + 1, l.tier == 4 ? 0xFFD9B45A : rgb);
            }
            g.pose().popMatrix();
        }
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

    private static void drawPlayers(GuiGraphics g, SiteTerritories.Data d, Minecraft mc, int ox, int oy, double cx, double cz, double scale, int size) {
        OnlinePlayers.tick();
        String self = mc.player.getName().getString();
        long now = System.currentTimeMillis();
        List<OnlinePlayers.Entry> list = OnlinePlayers.list();
        int half = size / 2 + 4;
        for (OnlinePlayers.Entry e : list) {
            if (e.account.equalsIgnoreCase(self)) continue;
            double sx = ox + (e.x(now) - cx) * scale, sy = oy + (e.z(now) - cz) * scale;
            if (Math.abs(sx - ox) > half || Math.abs(sy - oy) > half) continue;
            int rgb = 0xFFFFFFFF;
            if (d != null) {
                String site = d.ignToSite.get(e.account.toLowerCase(Locale.ROOT));
                Integer ci = d.countryOfPlayer.get(site != null ? site.toLowerCase(Locale.ROOT) : e.account.toLowerCase(Locale.ROOT));
                if (ci != null) rgb = d.rgb[ci] | 0xFF000000;
            }
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            g.fill(-2, -2, 3, 3, 0xFF05080C);
            g.fill(-1, -1, 2, 2, rgb);
            g.pose().popMatrix();
        }
    }

    /** Você: uma seta branca apontando para onde olha (o norte é sempre para cima). */
    private static void drawSelf(GuiGraphics g, Minecraft mc, int ox, int oy, float partial) {
        float yaw = (float) Math.toRadians(net.minecraft.util.Mth.rotLerp(partial, mc.player.yRotO, mc.player.getYRot()) + 180f);   // 0 = sul no jogo; seta desenhada para cima = norte
        g.pose().pushMatrix();
        g.pose().translate(ox, oy);
        g.pose().rotate(yaw);
        g.fill(-3, -1, 4, 5, 0xFF05080C);
        g.fill(-1, -5, 2, 5, 0xFF05080C);
        g.fill(0, -4, 1, -3, 0xFFFFFFFF);
        g.fill(-1, -3, 2, -2, 0xFFFFFFFF);
        g.fill(-2, -2, 3, -1, 0xFFFFFFFF);
        g.fill(-3, -1, 4, 0, 0xFFE03C3C);
        g.fill(0, 0, 1, 4, 0xFFE03C3C);
        g.pose().popMatrix();
    }
}
