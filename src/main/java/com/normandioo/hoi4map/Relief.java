package com.normandioo.hoi4map;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Mapa em relevo (3D): um campo de alturas desenhado por software, com a textura já pintada de cada região
 * (países, fronteiras, mar, relevo) esticada sobre a malha. Câmera ortográfica; nada aqui usa o Minecraft,
 * então dá para testar fora do jogo.
 *
 * Convenções: x = leste, z = sul, y = para cima. Com yaw 0 o norte fica em cima da tela; pitch 1,57 olha de cima.
 * Alturas guardadas como "y + 64" (0 = desconhecida), como em MapData.
 */
final class Relief {
    /** Onde estão os dados do mundo. Os arrays são de uma região de 256x256 reduzida {@code level} vezes (tamanho 256 >> level). */
    interface Source {
        /** Pixels ARGB (já pintados) da região no nível de redução, ou null se ainda não há. */
        int[] tex(int rx, int rz, int level);

        /** Alturas (y + 64) da região no nível de redução, ou null se não há. */
        short[] hgt(int rx, int rz, int level);
    }

    static final int REGION = 256;
    /** altura usada onde não há dados: y 62 */
    static final int UNKNOWN_H = 126;
    static final int MAX_LEVEL = 6;

    final int w, h;
    final double cx, cz, ty, s, yaw, pitch, exag;
    private final double cosY, sinY, cosP, sinP;

    /**
     * @param s     pixels da imagem por bloco
     * @param ty    altura (y do mundo) do ponto que fica no meio da tela
     * @param exag  exagero vertical (1 = escala real)
     */
    Relief(int w, int h, double cx, double cz, double ty, double s, double yaw, double pitch, double exag) {
        this.w = w;
        this.h = h;
        this.cx = cx;
        this.cz = cz;
        this.ty = ty;
        this.s = s;
        this.yaw = yaw;
        this.pitch = pitch;
        this.exag = exag;
        cosY = Math.cos(yaw);
        sinY = Math.sin(yaw);
        cosP = Math.cos(pitch);
        sinP = Math.sin(pitch);
    }

    // ------------------------------------------------------------------ câmera

    /** Posição na imagem (out[0], out[1]) e profundidade (out[2], maior = mais perto) de um ponto do mundo. */
    void project(double wx, double wy, double wz, double[] out) {
        double dx = wx - cx, dy = (wy - ty) * exag, dz = wz - cz;
        double x1 = dx * cosY + dz * sinY, z1 = -dx * sinY + dz * cosY;
        out[0] = w / 2.0 + x1 * s;
        out[1] = h / 2.0 - (dy * cosP - z1 * sinP) * s;
        out[2] = dy * sinP + z1 * cosP;
    }

    /** O ponto do mundo (x, z) cuja superfície, na altura {@code wy}, aparece no pixel (px, py). */
    private void ground(double px, double py, double wy, double[] out) {
        double dy = (wy - ty) * exag;
        double x1 = (px - w / 2.0) / s, v = (h / 2.0 - py) / s;
        double z1 = (dy * cosP - v) / sinP;
        out[0] = cx + x1 * cosY - z1 * sinY;
        out[1] = cz + x1 * sinY + z1 * cosY;
    }

    /** O ponto do terreno sob um pixel (refinado com as alturas de verdade). */
    double[] pick(double px, double py, Source src) {
        double[] g = new double[2];
        double wy = ty;
        for (int i = 0; i < 6; i++) {
            ground(px, py, wy, g);
            wy = heightY(src, g[0], g[1]);
        }
        return g;
    }

    /** Quanto o mundo (x, z) se move quando se arrasta o mapa (dx, dy) pixels da imagem, no chão do alvo. */
    double[] drag(double dxPx, double dyPx) {
        double x1 = dxPx / s, z1 = dyPx / (s * sinP);
        return new double[]{x1 * cosY - z1 * sinY, x1 * sinY + z1 * cosY};
    }

    /** y do mundo da superfície em (wx, wz). */
    static double heightY(Source src, double wx, double wz) {
        int bx = (int) Math.floor(wx), bz = (int) Math.floor(wz);
        int rx = Math.floorDiv(bx, REGION), rz = Math.floorDiv(bz, REGION);
        short[] hs = src.hgt(rx, rz, 0);
        if (hs == null) return UNKNOWN_H - 64.0;
        int v = hs[Math.floorMod(bz, REGION) * REGION + Math.floorMod(bx, REGION)];
        return (v == 0 ? UNKNOWN_H : v) - 64.0;
    }

    /** Menor e maior bloco (x, z) que podem aparecer na tela: {x0, z0, x1, z1}. */
    int[] visibleBlocks() {
        double[] g = new double[2];
        double minX = 1e18, minZ = 1e18, maxX = -1e18, maxZ = -1e18;
        double[] ys = {0.0, 200.0};
        double[] pxs = {0, w}, pys = {0, h};
        for (double wy : ys) for (double px : pxs) for (double py : pys) {
            ground(px, py, wy, g);
            minX = Math.min(minX, g[0]); maxX = Math.max(maxX, g[0]);
            minZ = Math.min(minZ, g[1]); maxZ = Math.max(maxZ, g[1]);
        }
        return new int[]{(int) Math.floor(minX) - 1, (int) Math.floor(minZ) - 1, (int) Math.ceil(maxX) + 1, (int) Math.ceil(maxZ) + 1};
    }

    // ------------------------------------------------------------------ desenho

    private static final double LIGHT_X = -0.6, LIGHT_Y = 1.0, LIGHT_Z = -0.6;   // de noroeste, de cima
    private static final int MAX_VERTICES = 600_000;

    private float[] gx = new float[0], gy = new float[0], gq = new float[0], gl = new float[0];
    private float[] zbuf = new float[0];

    // ------------------------------------------------------------------ reduções (mipmaps) de uma região

    /** Metade do tamanho: a média das alturas conhecidas de cada 2x2 (0 se nenhuma é conhecida). {@code size} = lado do array de entrada. */
    static short[] halveHeights(short[] a, int size) {
        int n = size / 2;
        short[] r = new short[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int sum = 0, cnt = 0;
                for (int k = 0; k < 4; k++) {
                    int v = a[(2 * y + (k >> 1)) * size + 2 * x + (k & 1)];
                    if (v != 0) { sum += v; cnt++; }
                }
                r[y * n + x] = (short) (cnt == 0 ? 0 : (sum + cnt / 2) / cnt);
            }
        }
        return r;
    }

    /** Metade do tamanho: a cor média dos pixels visíveis de cada 2x2 (transparente se nenhum é visível). */
    static int[] halveColours(int[] a, int size) {
        int n = size / 2;
        int[] r = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int k = 0; k < 4; k++) {
                    int c = a[(2 * y + (k >> 1)) * size + 2 * x + (k & 1)];
                    if ((c >>> 24) < 128) continue;
                    sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++;
                }
                r[y * n + x] = cnt == 0 ? 0 : 0xFF000000 | ((sr / cnt) << 16) | ((sg / cnt) << 8) | (sb / cnt);
            }
        }
        return r;
    }

    /** O que o quadro precisa: a área do mundo, o tamanho da célula (potência de 2, em blocos) e os níveis de redução das alturas e das texturas. */
    static final class Plan {
        int x0, z0, x1, z1, step, lh, lt;
    }

    Plan plan() {
        Plan pl = new Plan();
        int[] b = visibleBlocks();
        pl.x0 = b[0]; pl.z0 = b[1]; pl.x1 = b[2]; pl.z1 = b[3];
        // tamanho da célula: potência de 2, de modo que cada uma tenha uns 4 a 9 pixels
        int step = 1;
        while (step < 64 && step * s < 4.5) step <<= 1;
        while (((long) (b[2] - b[0]) / step + 2) * ((b[3] - b[1]) / step + 2) > MAX_VERTICES && step < 256) step <<= 1;
        pl.step = step;
        pl.lh = Math.min(MAX_LEVEL, Integer.numberOfTrailingZeros(step));
        pl.lt = (int) Math.max(0, Math.min(MAX_LEVEL, Math.floor(Math.log(1.0 / s) / Math.log(2))));
        return pl;
    }

    /** Desenha o relevo em {@code out} (w*h pixels ARGB). {@code bg} é a cor de onde não há terreno. */
    void render(int[] out, Source src, int bg) {
        Arrays.fill(out, 0, w * h, bg);
        if (zbuf.length < w * h) zbuf = new float[w * h];
        Arrays.fill(zbuf, 0, w * h, Float.NEGATIVE_INFINITY);

        Plan pl = plan();
        int[] b = {pl.x0, pl.z0, pl.x1, pl.z1};
        int step = pl.step, lh = pl.lh, lt = pl.lt;

        int i0 = Math.floorDiv(b[0], step), j0 = Math.floorDiv(b[1], step);
        int nx = Math.floorDiv(b[2], step) - i0 + 2, nz = Math.floorDiv(b[3], step) - j0 + 2;
        int n = nx * nz;
        if (gx.length < n) { gx = new float[n]; gy = new float[n]; gq = new float[n]; gl = new float[n]; }

        // alturas dos vértices (y do mundo) e, com elas, a luz
        float[] hy = new float[n];
        int lastRx = Integer.MIN_VALUE, lastRz = Integer.MIN_VALUE;
        short[] hs = null;
        int hw = REGION >> lh;
        for (int j = 0; j < nz; j++) {
            int wz = (j0 + j) * step;
            int rz = Math.floorDiv(wz, REGION);
            for (int i = 0; i < nx; i++) {
                int wx = (i0 + i) * step;
                int rx = Math.floorDiv(wx, REGION);
                if (rx != lastRx || rz != lastRz) { hs = src.hgt(rx, rz, lh); lastRx = rx; lastRz = rz; }
                int v = hs == null ? 0 : hs[(Math.floorMod(wz, REGION) >> lh) * hw + (Math.floorMod(wx, REGION) >> lh)];
                hy[j * nx + i] = (v == 0 ? UNKNOWN_H : v) - 64f;
            }
        }
        double ll = Math.sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + LIGHT_Z * LIGHT_Z);
        double dot0 = LIGHT_Y / ll;
        double[] p = new double[3];
        int kern = Math.max(1, (int) Math.ceil(3.0 / step));   // normais com uns 3 blocos de cada lado: as alturas são em degraus de 1 bloco
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                int il = Math.max(0, i - kern), ir = Math.min(nx - 1, i + kern), jt = Math.max(0, j - kern), jb = Math.min(nz - 1, j + kern);
                double dhdx = (hy[j * nx + ir] - hy[j * nx + il]) * exag / (double) (step * (ir - il));
                double dhdz = (hy[jb * nx + i] - hy[jt * nx + i]) * exag / (double) (step * (jb - jt));
                double nl = Math.sqrt(dhdx * dhdx + 1 + dhdz * dhdz);
                double dot = (-dhdx * LIGHT_X + LIGHT_Y - dhdz * LIGHT_Z) / (nl * ll);
                gl[k] = (float) Math.max(0.55, Math.min(1.3, 1.0 + 0.8 * (dot - dot0)));
                project((i0 + i) * (double) step, hy[k], (j0 + j) * (double) step, p);
                gx[k] = (float) p[0];
                gy[k] = (float) p[1];
                gq[k] = (float) p[2];
            }
        }

        final int fstep = step, flt = lt, fi0 = i0, fj0 = j0, fnx = nx, fnz = nz;
        int bands = Math.max(1, Math.min(32, h / 16));
        IntStream.range(0, bands).parallel().forEach(band -> {
            int ya = h * band / bands, yb = h * (band + 1) / bands;
            int curRx = Integer.MIN_VALUE, curRz = Integer.MIN_VALUE;
            int[] tex = null;
            int tw = REGION >> flt;
            float texScale = 1f / (1 << flt);
            for (int j = 0; j < fnz - 1; j++) {
                for (int i = 0; i < fnx - 1; i++) {
                    int a = j * fnx + i, bI = a + 1, c = a + fnx, d = c + 1;
                    float minY = Math.min(Math.min(gy[a], gy[bI]), Math.min(gy[c], gy[d]));
                    float maxY = Math.max(Math.max(gy[a], gy[bI]), Math.max(gy[c], gy[d]));
                    if (maxY < ya || minY >= yb) continue;
                    float minX = Math.min(Math.min(gx[a], gx[bI]), Math.min(gx[c], gx[d]));
                    float maxX = Math.max(Math.max(gx[a], gx[bI]), Math.max(gx[c], gx[d]));
                    if (maxX < 0 || minX >= w) continue;
                    int wx = (fi0 + i) * fstep, wz = (fj0 + j) * fstep;
                    int rx = Math.floorDiv(wx, REGION), rz = Math.floorDiv(wz, REGION);
                    if (rx != curRx || rz != curRz) { tex = src.tex(rx, rz, flt); curRx = rx; curRz = rz; }
                    if (tex == null) continue;
                    float u0 = (wx - rx * REGION) * texScale, v0 = (wz - rz * REGION) * texScale;
                    float du = fstep * texScale;
                    tri(out, tex, tw, ya, yb, gx[a], gy[a], gq[a], gl[a], u0, v0,
                            gx[bI], gy[bI], gq[bI], gl[bI], u0 + du, v0,
                            gx[c], gy[c], gq[c], gl[c], u0, v0 + du);
                    tri(out, tex, tw, ya, yb, gx[bI], gy[bI], gq[bI], gl[bI], u0 + du, v0,
                            gx[d], gy[d], gq[d], gl[d], u0 + du, v0 + du,
                            gx[c], gy[c], gq[c], gl[c], u0, v0 + du);
                }
            }
        });
    }

    /** Um triângulo com textura, luz e teste de profundidade, nas linhas [ya, yb). */
    private void tri(int[] out, int[] tex, int tw, int ya, int yb,
                     float x0, float y0, float q0, float l0, float u0, float v0,
                     float x1, float y1, float q1, float l1, float u1, float v1,
                     float x2, float y2, float q2, float l2, float u2, float v2) {
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-9f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(ya, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(yb - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float inv = 1f / area;
        float b1x = (y2 - y0) * inv, b1y = -(x2 - x0) * inv;
        float b2x = -(y1 - y0) * inv, b2y = (x1 - x0) * inv;
        final float eps = -0.0015f;
        int tmax = tw - 1;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f - y0;
            float pxs = minX + 0.5f - x0;
            float b1 = pxs * b1x + py * b1y, b2 = pxs * b2x + py * b2y;
            int row = y * w;
            for (int x = minX; x <= maxX; x++, b1 += b1x, b2 += b2x) {
                float b0 = 1f - b1 - b2;
                if (b0 < eps || b1 < eps || b2 < eps) continue;
                float q = q0 + (q1 - q0) * b1 + (q2 - q0) * b2;
                int idx = row + x;
                if (q <= zbuf[idx]) continue;
                int tu = (int) (u0 + (u1 - u0) * b1 + (u2 - u0) * b2), tv = (int) (v0 + (v1 - v0) * b1 + (v2 - v0) * b2);
                tu = tu < 0 ? 0 : Math.min(tu, tmax);
                tv = tv < 0 ? 0 : Math.min(tv, tmax);
                int c = tex[tv * tw + tu];
                if ((c >>> 24) < 128) continue;
                float l = l0 + (l1 - l0) * b1 + (l2 - l0) * b2;
                int r = (int) (((c >> 16) & 255) * l), g = (int) (((c >> 8) & 255) * l), bl = (int) ((c & 255) * l);
                out[idx] = 0xFF000000 | (r > 255 ? 255 : r) << 16 | (g > 255 ? 255 : g) << 8 | (bl > 255 ? 255 : bl);
                zbuf[idx] = q;
            }
        }
    }
}
