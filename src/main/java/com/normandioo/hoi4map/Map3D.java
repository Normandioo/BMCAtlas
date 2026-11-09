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
    static double targetY;
    private static double camDistH;
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

    static MapData.Region regionAt(int wx, int wz) {
        int gx = Math.floorDiv(wx, MapData.REGION_PX) - cacheRx + GRID;
        int gz = Math.floorDiv(wz, MapData.REGION_PX) - cacheRz + GRID;
        if (gx < 0 || gz < 0 || gx > 2 * GRID || gz > 2 * GRID) return null;
        return cache[gz * (2 * GRID + 1) + gx];
    }

    /** Altura (y + 64) real da coluna, 0 se desconhecida. */
    private static int knownH(int wx, int wz) {
        MapData.Region r = regionAt(wx, wz);
        if (r == null || r.hgt == null) return 0;
        return r.hgt[Math.floorMod(wz, MapData.REGION_PX) * MapData.REGION_PX + Math.floorMod(wx, MapData.REGION_PX)];
    }

    /**
     * Fundo de uma coluna sem camadas: só até onde ela aparece (abaixo do vizinho mais baixo). Sem isso, na borda do
     * que foi explorado o lado da coluna desce até o fundo do mundo e mostra o subsolo.
     */
    private static int columnBottom(int ix, int iz, int hv) {
        int mn = hv;
        int[] d = {1, 0, -1, 0, 0, 1, 0, -1};
        for (int i = 0; i < 8; i += 2) {
            int h = knownH(ix + d[i], iz + d[i + 1]);
            if (h == 0) { mn = Math.min(mn, hv - 3); } else mn = Math.min(mn, h);
        }
        return Math.max(1, Math.min(hv - 1, mn - 1));
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
        java.util.Arrays.fill(frame, 0);
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
                int newTop = top;
                for (int si = 0; si < 3; si++) {
                double topPlane, botPlane;
                int blk;
                if (si == 0) {
                    topPlane = hv == 0 ? UNKNOWN_H : hv;
                    botPlane = hv == 0 ? UNKNOWN_H - 3 : (reg.bot0 == null || !MapData.layers ? columnBottom(ix, iz, hv) : reg.bot0[idx]);
                    blk = reg.blk == null ? 0 : reg.blk[idx];
                } else {
                    if (reg.sp == null || !MapData.layers) break;
                    int sb = idx * MapData.SP_N + (si - 1) * 3;
                    topPlane = reg.sp[sb];
                    if (topPlane <= 0) break;
                    botPlane = reg.sp[sb + 1];
                    blk = reg.sp[sb + 2] & 0xFFFF;
                    if (blk == 0) break;
                }
                int colorS = color;
                double by = topPlane - 64.0;
                double vx = wx - camX, vy = by - cy, vz = wz - camZ;
                double zc = vx * fx + vy * fy + vz * fz;
                if (zc < 1) continue;
                double yc = vx * ux + vy * uy + vz * uz;
                int ysC = (int) Math.floor(halfY - focal * yc / zc);      // linha do topo no centro da célula
                // borda de trás do cubo: de lá até a de frente é a face de cima
                double fvx = vx + dirx * 1.45, fvz = vz + dirz * 1.45;
                double zcF = fvx * fx + vy * fy + fvz * fz;
                int ys = zcF >= 1 ? (int) Math.floor(halfY - focal * (fvx * ux + vy * uy + fvz * uz) / zcF) : ysC - 2;
                double nvx = vx + dirx * 0.55, nvz = vz + dirz * 0.55;
                double zcN = nvx * fx + vy * fy + nvz * fz;
                int ysTop = zcN >= 1 ? (int) Math.floor(halfY - focal * (nvx * ux + vy * uy + nvz * uz) / zcN) : ysC;
                if (ysTop >= top) {
                    continue;
                }
                int ys0 = ys;
                if (ys < 0) ys = 0;
                if (ysTop < 0) ysTop = 0;
                double pxb = focal * uy / zc;                 // pixels da imagem por bloco, na vertical
                int yEnd = top;
                if (botPlane != 0) {
                    double vyB = botPlane - 64.0 - cy;
                    double zcB = vx * fx + vyB * fy + vz * fz;
                    if (zcB >= 1) {
                        double yB = halfY - focal * (vx * ux + vyB * uy + vz * uz) / zcB;
                        yEnd = (int) Math.min(top, Math.ceil(yB + pxb * 0.6 + 2));
                    }
                }
                final double thick = botPlane == 0 ? 1e9 : topPlane - botPlane + 0.05;
                float fog = (float) Math.min(0.55, r / 9000.0);
                MapData.Tex tt = null, st = null;
                if (TEXTURES && blk > 0 && pxb >= 2.5 && (si > 0 || (reg.px[idx] >>> 24) == 0xFF)) {
                    tt = MapData.topTexture(blk - 1);
                    st = tt == null ? null : MapData.sideTexture(blk - 1);
                    if (tt != null && si > 0) colorS = 0xFF000000 | (tt.avg & 0xFFFFFF);
                }
                if (si > 0 && tt == null) {
                    MapData.Tex fl = blk > 0 ? MapData.sideTexture(blk - 1) : null;
                    colorS = fl == null ? 0xFF707070 : (0xFF000000 | (fl.avg & 0xFFFFFF));
                }
                // paredes dos cubos: tom diferente para cada eixo, como nas faces do Minecraft
                float wallShade = (float) (((dirx > 0 ? 0.72 : 0.56) * Math.abs(dirx) + (dirz > 0 ? 0.86 : 0.68) * Math.abs(dirz)) / (Math.abs(dirx) + Math.abs(dirz)));
                if (tt == null) {
                    int topRowsFlat = Math.max(1, (int) (pxb * 0.55));
                    int cTop = fog <= 0.01f ? colorS : mixFog(colorS, fog);
                    int wc = colorS;
                    if (si == 0 && reg.wall != null && (reg.px[idx] >>> 24) == 0xFF) {       // de longe a parede tem a cor da fachada, não a do telhado
                        int wid = (reg.wall[idx * MapData.WALL_R] & 0xFFFF) >> 5;
                        MapData.Tex wt = wid > 0 ? MapData.sideTexture(wid - 1) : null;
                        if (wt != null) wc = 0xFF000000 | (wt.avg & 0xFFFFFF);
                    }
                    int wallC = shade(wc, wallShade);
                    int cWall = fog <= 0.01f ? wallC : mixFog(wallC, fog);
                    for (int y = ys; y < yEnd; y++) { if (frame[y * rw + col] == 0) frame[y * rw + col] = (y - ys0) < topRowsFlat ? cTop : cWall; }
                } else {
                    // textura de verdade, mapeada exatamente: cada pixel é o ponto onde o raio da câmera toca o bloco
                    int cr = (colorS >> 16) & 0xFF, cg = (colorS >> 8) & 0xFF, cb = colorS & 0xFF;
                    double rr = clamp(cr / Math.max(8.0, (tt.avg >> 16) & 0xFF)), rg = clamp(cg / Math.max(8.0, (tt.avg >> 8) & 0xFF)), rb = clamp(cb / Math.max(8.0, tt.avg & 0xFF));
                    double lum = (rr + rg + rb) / 3.0;
                    rr /= lum; rg /= lum; rb /= lum;                 // só o tom do mapa (bioma); o relevo vem da luz das faces
                    float topLight = (float) Math.max(0.86, Math.min(1.08, lum));
                    boolean sameTex = st == null || st.avg == tt.avg;
                    double mip = Math.max(0.0, Math.min(1.0, (pxb - 2.5) / 6.0));   // longe: cor média, perto: textura nítida
                    int avgC = 0xFF000000 | ((int) Math.min(255, ((tt.avg >> 16) & 0xFF) * rr) << 16) | ((int) Math.min(255, ((tt.avg >> 8) & 0xFF) * rg) << 8) | (int) Math.min(255, (tt.avg & 0xFF) * rb);
                    double dcx = fx * focal + rx * (col - halfX), dcy = fy * focal, dcz = fz * focal + rz * (col - halfX);
                    double boxBot = botPlane == 0 ? -1.0e4 : botPlane - 64.0;
                    double bx0 = ix, bx1 = ix + 1.0, bz0 = iz, bz1 = iz + 1.0;
                    int lastDi = -1;
                    MapData.Tex lastTex = null;
                    for (int y = ys; y < yEnd; y++) {
                        if (frame[y * rw + col] != 0) continue;
                        double vv = halfY - y;
                        double ddx = dcx + ux * vv, ddy = dcy + uy * vv, ddz = dcz + uz * vv;
                        // raio contra o cubo (método das lajes): acha a face tocada e o ponto exato
                        double tmin = 0, tmax = 1.0e9;
                        int face = -1;
                        boolean miss = false;
                        for (int ax = 0; ax < 3 && !miss; ax++) {
                            double o = ax == 0 ? camX : ax == 1 ? cy : camZ;
                            double dd = ax == 0 ? ddx : ax == 1 ? ddy : ddz;
                            double lo = ax == 0 ? bx0 : ax == 1 ? boxBot : bz0, hi = ax == 0 ? bx1 : ax == 1 ? by : bz1;
                            if (Math.abs(dd) < 1e-12) {
                                if (o < lo || o > hi) miss = true;
                                continue;
                            }
                            double t1 = (lo - o) / dd, t2 = (hi - o) / dd;
                            double near = Math.min(t1, t2), far = Math.max(t1, t2);
                            if (near > tmin) { tmin = near; face = ax; }
                            if (far < tmax) tmax = far;
                        }
                        if (miss || face < 0 || tmin > tmax) continue;
                        double hx = camX + ddx * tmin, hy = cy + ddy * tmin, hz = camZ + ddz * tmin;
                        int out;
                        if (face == 1) {
                            if (ddy > 0) continue;       // face de baixo: não aparece vista de cima
                            int tx = Math.max(0, Math.min(tt.w - 1, (int) ((hx - ix) * tt.w))), tz = Math.max(0, Math.min(tt.w - 1, (int) ((hz - iz) * tt.w)));
                            int tc = tt.px[tz * tt.w + tx];
                            int tcol;
                            if ((tc >>> 24) < 32) tcol = colorS;
                            else tcol = 0xFF000000 | ((int) Math.min(255, ((tc >> 16) & 0xFF) * rr) << 16) | ((int) Math.min(255, ((tc >> 8) & 0xFF) * rg) << 8) | (int) Math.min(255, (tc & 0xFF) * rb);
                            out = shade(mip >= 0.999 ? tcol : mixRgb(avgC, tcol, (float) mip), topLight);
                        } else {
                            double along = face == 0 ? hz : hx;
                            double depth = by - hy;
                            if (depth < 0) depth = 0;
                            float faceShade = face == 0 ? (ddx > 0 ? 0.72f : 0.56f) : (ddz > 0 ? 0.86f : 0.68f);
                            int di = (int) depth;
                            MapData.Tex sx = st == null ? tt : st;
                            boolean deep = false;
                            if (di >= 1 && si == 0 && reg.wall != null) {
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
                            if ((tc >>> 24) < 32) base = colorS;
                            else if (deep) base = tc | 0xFF000000;
                            else if (sameTex) base = 0xFF000000 | ((int) Math.min(255, ((tc >> 16) & 0xFF) * rr) << 16) | ((int) Math.min(255, ((tc >> 8) & 0xFF) * rg) << 8) | (int) Math.min(255, (tc & 0xFF) * rb);
                            else base = mixRgb(tc, colorS, 0.3f);
                            if (mip < 0.999) base = mixRgb(shade(avgC, 1f), base, (float) mip);
                            out = shade(base, (float) (faceShade * Math.max(0.6, 1.0 - depth * 0.010)));
                        }
                        frame[y * rw + col] = fog <= 0.01f ? out : mixFog(out, fog);
                    }
                }
                if (botPlane == 0 && ysTop < newTop) newTop = ysTop;
                }
                top = newTop;
            }
        });
        java.util.stream.IntStream.range(0, rh).parallel().forEach(y -> {
            for (int x = 0; x < rwF; x++) { int v = frame[y * rwF + x]; img.setPixel(x, y, v == 0 ? BG : v); }
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
