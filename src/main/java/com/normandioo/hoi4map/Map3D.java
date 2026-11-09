package com.normandioo.hoi4map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Mapa em 3D: um renderizador de campo de alturas (por colunas, de perto para longe) feito no processador.
 * Usa a altura de cada bloco guardada em MapData e as cores já pintadas (países, limites, guerra...), e permite
 * girar e inclinar livremente. O resultado é uma imagem pequena que a tela estica por cima do mapa.
 */
final class Map3D {
    private Map3D() {}

    /** Giro (rad; 0 = olhando para o norte) e inclinação (rad; 0 = horizontal, 1.57 = de cima). */
    static double yaw = 0.0, pitch = 0.95;

    private static final int GRID = 5;             // regiões em cada direção do alvo que entram no desenho
    private static final int BG = 0xFF0B1628;
    /** Usa as texturas dos blocos do jogo (e do pacote de texturas) nos blocos próximos. */
    static boolean TEXTURES = true;
    private static final int UNKNOWN_H = 126;      // y 62 quando a região ainda não tem alturas

    private static NativeImage img;
    private static DynamicTexture tex;
    private static Identifier id;
    private static int rw, rh;
    private static int[] frame = new int[0];

    // câmera deste quadro
    private static double camX, camY, camZ, fx, fy, fz, rx, rz, ux, uy, uz, focalGui, guiW, guiH;
    private static double targetY, camDistH;
    private static MapData.Region[] cache = new MapData.Region[(2 * GRID + 1) * (2 * GRID + 1)];
    private static int cacheRx, cacheRz;

    /** Posiciona a câmera olhando para o ponto (tx, tz); scale = pixels da tela por bloco no ponto. */
    static void setup(double tx, double tz, double scale, int guiWidth, int guiHeight) {
        guiW = guiWidth;
        guiH = guiHeight;
        focalGui = 0.9 * guiWidth;
        cacheRx = Math.floorDiv((int) Math.floor(tx), MapData.REGION_PX);
        cacheRz = Math.floorDiv((int) Math.floor(tz), MapData.REGION_PX);
        for (int dx = -GRID; dx <= GRID; dx++) {
            for (int dz = -GRID; dz <= GRID; dz++) {
                cache[(dz + GRID) * (2 * GRID + 1) + dx + GRID] = MapData.region(cacheRx + dx, cacheRz + dz, false);
            }
        }
        targetY = heightAt((int) Math.floor(tx), (int) Math.floor(tz)) - 64.0;
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double fhx = Math.sin(yaw), fhz = -Math.cos(yaw);
        double dist = focalGui / Math.max(0.05, scale);
        camDistH = dist * cp;
        camX = tx - fhx * cp * dist;
        camZ = tz - fhz * cp * dist;
        camY = targetY + sp * dist;
        fx = cp * fhx;
        fy = -sp;
        fz = cp * fhz;
        rx = Math.cos(yaw);
        rz = Math.sin(yaw);
        ux = sp * fhx;
        uy = cp;
        uz = sp * fhz;
    }

    private static MapData.Region regionAt(int wx, int wz) {
        int gx = Math.floorDiv(wx, MapData.REGION_PX) - cacheRx + GRID;
        int gz = Math.floorDiv(wz, MapData.REGION_PX) - cacheRz + GRID;
        if (gx < 0 || gz < 0 || gx > 2 * GRID || gz > 2 * GRID) return null;
        return cache[gz * (2 * GRID + 1) + gx];
    }

    /** Altura (y + 64) do bloco; a de regiões sem alturas é uma média plana. */
    private static int heightAt(int wx, int wz) {
        MapData.Region r = regionAt(wx, wz);
        if (r == null || r.hgt == null) return UNKNOWN_H;
        int h = r.hgt[Math.floorMod(wz, MapData.REGION_PX) * MapData.REGION_PX + Math.floorMod(wx, MapData.REGION_PX)];
        return h == 0 ? UNKNOWN_H : h;
    }

    // projeção (coordenadas da tela do jogo)
    static double projX, projY;
    static boolean projOk;

    static void project(double bx, double bz) {
        double by = heightAt((int) Math.floor(bx), (int) Math.floor(bz)) - 64.0;
        double vx = bx - camX, vy = by - camY, vz = bz - camZ;
        double zc = vx * fx + vy * fy + vz * fz;
        if (zc < 1) {
            projOk = false;
            projX = -9999;
            projY = -9999;
            return;
        }
        projOk = true;
        projX = guiW / 2 + focalGui * (vx * rx + vz * rz) / zc;
        projY = guiH / 2 - focalGui * (vx * ux + vy * uy + vz * uz) / zc;
    }

    /** O ponto do chão (na altura do alvo) sob um pixel da tela; null se o pixel olha acima do horizonte. */
    static double[] pick(double sx, double sy) {
        double a = (sx - guiW / 2) / focalGui, b = (guiH / 2 - sy) / focalGui;
        double vx = fx + a * rx + b * ux, vy = fy + b * uy, vz = fz + a * rz + b * uz;
        if (vy >= -1e-4) return null;
        double t = (targetY - camY) / vy;
        // refina com a altura real do terreno naquele ponto
        double px = camX + vx * t, pz = camZ + vz * t;
        for (int i = 0; i < 3; i++) {
            double ty = heightAt((int) Math.floor(px), (int) Math.floor(pz)) - 64.0;
            t = (ty - camY) / vy;
            px = camX + vx * t;
            pz = camZ + vz * t;
        }
        return new double[]{px, pz};
    }

    /** Desenha o terreno em 3D na tela inteira. */
    static void render(GuiGraphics g, int guiWidth, int guiHeight) {
        double q = Prefs.get().quality3dPct / 100.0 * Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        int w = Math.max(64, (int) (guiWidth * q)), h = Math.max(64, (int) (guiHeight * q));
        if (img == null || w != rw || h != rh) {
            if (tex != null) Minecraft.getInstance().getTextureManager().release(id);
            rw = w;
            rh = h;
            img = new NativeImage(NativeImage.Format.RGBA, rw, rh, true);
            tex = new DynamicTexture(() -> "hoi4map 3d", img);
            id = Identifier.fromNamespaceAndPath(Hoi4MapClient.MOD_ID, "view3d");
            Minecraft.getInstance().getTextureManager().register(id, tex);
            frame = new int[rw * rh];
        }
        java.util.Arrays.fill(frame, BG);
        double focal = 0.9 * rw;
        double halfX = rw / 2.0, halfY = rh / 2.0;
        double cy = camY;
        final int rwF = rw, rhF = rh;
        java.util.stream.IntStream.range(0, rw).parallel().forEach(col -> {
            double a = Math.atan((col - halfX) / focal);
            double dirx = Math.sin(yaw + a), dirz = -Math.cos(yaw + a);
            int top = rh;
            double r0 = Math.max(1, camDistH - 1500), rEnd = camDistH + 1500;
            double prevX = camX + dirx * r0, prevZ = camZ + dirz * r0;
            for (double r = r0; r < rEnd && top > 0; r += Math.max(0.7, 3.0 * r / focal)) {
                double wx = camX + dirx * r, wz = camZ + dirz * r;
                int ix = (int) Math.floor(wx), iz = (int) Math.floor(wz);
                MapData.Region reg = regionAt(ix, iz);
                if (reg == null) continue;
                int lx = Math.floorMod(ix, MapData.REGION_PX), lz = Math.floorMod(iz, MapData.REGION_PX);
                int idx = lz * MapData.REGION_PX + lx;
                int color = reg.styledCpu == null ? 0 : reg.styledCpu[idx];
                if (color == 0) continue;
                int hv = reg.hgt == null ? 0 : reg.hgt[idx];
                double by = (hv == 0 ? UNKNOWN_H : hv) - 64.0;
                double vx = wx - camX, vy = by - cy, vz = wz - camZ;
                double zc = vx * fx + vy * fy + vz * fz;
                if (zc < 1) continue;
                double yc = vx * ux + vy * uy + vz * uz;
                int ys = (int) Math.floor(halfY - focal * yc / zc);
                if (ys >= top) {
                    continue;
                }
                int ys0 = ys;
                if (ys < 0) ys = 0;
                float fog = (float) Math.min(0.55, r / 9000.0);
                MapData.Tex tt = null, st = null;
                double pxb = focal * uy / zc;                 // pixels da imagem por bloco, na vertical
                int blk = reg.blk == null ? 0 : reg.blk[idx];
                if (TEXTURES && blk > 0 && pxb >= 2.5 && (reg.px[idx] >>> 24) == 0xFF) {
                    tt = MapData.topTexture(blk - 1);
                    st = tt == null ? null : MapData.sideTexture(blk - 1);
                }
                // paredes dos cubos: tom diferente para cada eixo, como nas faces do Minecraft
                float wallShade = Math.abs(dirx) > Math.abs(dirz) ? (dirx > 0 ? 0.72f : 0.56f) : (dirz > 0 ? 0.86f : 0.68f);
                if (tt == null) {
                    int topRowsFlat = Math.max(1, (int) (pxb * 0.55));
                    int cTop = fog <= 0.01f ? color : mixFog(color, fog);
                    int wc = color;
                    if (reg.wall != null && (reg.px[idx] >>> 24) == 0xFF) {       // de longe a parede tem a cor da fachada, não a do telhado
                        int wid = (reg.wall[idx * MapData.WALL_R] & 0xFFFF) >> 5;
                        MapData.Tex wt = wid > 0 ? MapData.sideTexture(wid - 1) : null;
                        if (wt != null) wc = 0xFF000000 | (wt.avg & 0xFFFFFF);
                    }
                    int wallC = shade(wc, wallShade);
                    int cWall = fog <= 0.01f ? wallC : mixFog(wallC, fog);
                    for (int y = ys; y < top; y++) frame[y * rw + col] = (y - ys0) < topRowsFlat ? cTop : cWall;
                } else {
                    // textura de verdade, mapeada exatamente: cada pixel é o ponto onde o raio da câmera toca o bloco
                    int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
                    double rr = clamp(cr / Math.max(8.0, (tt.avg >> 16) & 0xFF)), rg = clamp(cg / Math.max(8.0, (tt.avg >> 8) & 0xFF)), rb = clamp(cb / Math.max(8.0, tt.avg & 0xFF));
                    double lum = (rr + rg + rb) / 3.0;
                    rr /= lum; rg /= lum; rb /= lum;                 // só o tom do mapa (bioma); o relevo vem da luz das faces
                    float topLight = (float) Math.max(0.86, Math.min(1.08, lum));
                    boolean sameTex = st == null || st.avg == tt.avg;
                    double mip = Math.max(0.0, Math.min(1.0, (pxb - 2.5) / 6.0));   // longe: cor média, perto: textura nítida
                    int avgC = 0xFF000000 | ((int) Math.min(255, ((tt.avg >> 16) & 0xFF) * rr) << 16) | ((int) Math.min(255, ((tt.avg >> 8) & 0xFF) * rg) << 8) | (int) Math.min(255, (tt.avg & 0xFF) * rb);
                    double dcx = fx * focal + rx * (col - halfX), dcy = fy * focal, dcz = fz * focal + rz * (col - halfX);
                    boolean alongZ = Math.abs(dirx) > Math.abs(dirz);
                    double planeC = alongZ ? (dirx > 0 ? ix : ix + 1) : (dirz > 0 ? iz : iz + 1);
                    int lastDi = -1;
                    MapData.Tex lastTex = null;
                    for (int y = ys; y < top; y++) {
                        double vv = halfY - y;
                        double ddx = dcx + ux * vv, ddy = dcy + uy * vv, ddz = dcz + uz * vv;
                        boolean topHit = false;
                        double hx = 0, hz = 0;
                        if (ddy < -1e-9) {
                            double tt0 = (by - cy) / ddy;
                            if (tt0 > 0) {
                                hx = camX + ddx * tt0;
                                hz = camZ + ddz * tt0;
                                topHit = hx >= ix - 0.02 && hx < ix + 1.02 && hz >= iz - 0.02 && hz < iz + 1.02;
                            }
                        }
                        int out;
                        if (topHit) {
                            int tx = Math.max(0, Math.min(tt.w - 1, (int) ((hx - ix) * tt.w))), tz = Math.max(0, Math.min(tt.w - 1, (int) ((hz - iz) * tt.w)));
                            int tc = tt.px[tz * tt.w + tx];
                            int tcol;
                            if ((tc >>> 24) < 32) tcol = color;
                            else tcol = 0xFF000000 | ((int) Math.min(255, ((tc >> 16) & 0xFF) * rr) << 16) | ((int) Math.min(255, ((tc >> 8) & 0xFF) * rg) << 8) | (int) Math.min(255, (tc & 0xFF) * rb);
                            out = shade(mip >= 0.999 ? tcol : mixRgb(avgC, tcol, (float) mip), topLight);
                        } else {
                            double along, depth;
                            double den = alongZ ? ddx : ddz;
                            if (Math.abs(den) < 1e-9) { along = alongZ ? wz : wx; depth = 0; }
                            else {
                                double t2 = ((alongZ ? planeC - camX : planeC - camZ)) / den;
                                along = alongZ ? camZ + ddz * t2 : camX + ddx * t2;
                                depth = by - (cy + ddy * t2);
                                if (depth < 0) depth = 0;
                            }
                            int di = (int) depth;
                            MapData.Tex sx = st == null ? tt : st;
                            boolean deep = false;
                            if (di >= 1 && reg.wall != null) {
                                if (di != lastDi) {
                                    int wid = 0, acc = 1;
                                    for (int qq = 0; qq < MapData.WALL_R; qq++) {
                                        int e = reg.wall[idx * MapData.WALL_R + qq] & 0xFFFF;
                                        if (e == 0) break;
                                        int ln = e & 31;
                                        wid = e >> 5;
                                        if (ln == 0 || di < acc + ln) break;
                                        acc += ln;
                                    }
                                    lastTex = wid > 0 ? MapData.sideTexture(wid - 1) : null;
                                    lastDi = di;
                                }
                                if (lastTex != null) { sx = lastTex; deep = true; }
                            }
                            int tx = Math.max(0, Math.min(sx.w - 1, (int) ((along - Math.floor(along)) * sx.w)));
                            int ty = Math.max(0, Math.min(sx.w - 1, (int) ((depth - di) * sx.w)));
                            int tc = sx.px[ty * sx.w + tx];
                            int base;
                            if ((tc >>> 24) < 32) base = color;
                            else if (deep) base = tc | 0xFF000000;
                            else if (sameTex) base = 0xFF000000 | ((int) Math.min(255, ((tc >> 16) & 0xFF) * rr) << 16) | ((int) Math.min(255, ((tc >> 8) & 0xFF) * rg) << 8) | (int) Math.min(255, (tc & 0xFF) * rb);
                            else base = mixRgb(tc, color, 0.3f);
                            if (mip < 0.999) base = mixRgb(shade(avgC, 1f), base, (float) mip);
                            out = shade(base, (float) (wallShade * Math.max(0.6, 1.0 - depth * 0.010)));
                        }
                        frame[y * rw + col] = fog <= 0.01f ? out : mixFog(out, fog);
                    }
                }
                prevX = wx;
                prevZ = wz;
                top = ys;
            }
        });
        java.util.stream.IntStream.range(0, rh).parallel().forEach(y -> {
            for (int x = 0; x < rwF; x++) img.setPixel(x, y, frame[y * rwF + x]);
        });
        tex.upload();
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, guiWidth, guiHeight, rw, rh, rw, rh);
    }

    private static double clamp(double v) {
        return Math.max(0.3, Math.min(3.0, v));
    }

    private static int shade(int c, float k) {
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * k) << 16) | ((int) (((c >> 8) & 0xFF) * k) << 8) | (int) ((c & 0xFF) * k);
    }

    private static int mixRgb(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int mixFog(int c, float t) {
        int r = (int) (((c >> 16) & 0xFF) * (1 - t) + 0x0B * t);
        int gg = (int) (((c >> 8) & 0xFF) * (1 - t) + 0x16 * t);
        int b = (int) ((c & 0xFF) * (1 - t) + 0x28 * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }
}
