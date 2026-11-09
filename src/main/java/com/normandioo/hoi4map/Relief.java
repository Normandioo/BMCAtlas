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
    /** a partir de quantos pixels por bloco o relevo vira blocos de verdade */
    static final double BLOCK_MIN_S = 3.0;

    private float[] gx = new float[0], gy = new float[0], gq = new float[0], gl = new float[0];
    private float[] zbuf = new float[0], hyBuf = new float[0];

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
        /** cada coluna de blocos vira um prisma de verdade (célula = 1 bloco, de perto) */
        boolean blocky;
    }

    Plan plan() {
        Plan pl = new Plan();
        int[] b = visibleBlocks();
        pl.x0 = b[0]; pl.z0 = b[1]; pl.x1 = b[2]; pl.z1 = b[3];
        // tamanho da célula: potência de 2, de modo que cada uma tenha uns 4 a 9 pixels
        int step = 1;
        if (s < BLOCK_MIN_S) while (step < 64 && step * s < 4.5) step <<= 1;
        while (((long) (b[2] - b[0]) / step + 2) * ((b[3] - b[1]) / step + 2) > MAX_VERTICES && step < 256) step <<= 1;
        pl.step = step;
        pl.blocky = step == 1 && s >= BLOCK_MIN_S;
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
        if (hyBuf.length < n) hyBuf = new float[n];
        final float[] hy = hyBuf;
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
                hy[j * nx + i] = v == 0 ? Float.NaN : v - 64f;
            }
        }
        cleanHeights(hy, nx, nz, step, !pl.blocky);
        if (pl.blocky) { renderBlocky(out, src, pl, hy, nx, nz, i0, j0); return; }
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


    /** Preenche alturas desconhecidas pelos vizinhos, tira torres finas (prédios, árvores) e limita a inclinação. */
    private static void cleanHeights(float[] hy, int nx, int nz, int step, boolean smooth) {
        int n = nx * nz;
        // 1) desconhecidos: média dos vizinhos conhecidos, espalhando
        for (int pass = 0; pass < 24; pass++) {
            boolean any = false;
            for (int j = 0; j < nz; j++) for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                if (!Float.isNaN(hy[k])) continue;
                float sum = 0; int c = 0;
                if (i > 0 && !Float.isNaN(hy[k - 1])) { sum += hy[k - 1]; c++; }
                if (i < nx - 1 && !Float.isNaN(hy[k + 1])) { sum += hy[k + 1]; c++; }
                if (j > 0 && !Float.isNaN(hy[k - nx])) { sum += hy[k - nx]; c++; }
                if (j < nz - 1 && !Float.isNaN(hy[k + nx])) { sum += hy[k + nx]; c++; }
                if (c > 0) hy[k] = sum / c; else any = true;
            }
            if (!any) break;
        }
        for (int k = 0; k < n; k++) if (Float.isNaN(hy[k])) hy[k] = UNKNOWN_H - 64f;
        if (!smooth) return;
        // 2) abertura (erode + dilata): some o que for mais estreito que ~5 blocos
        int r = Math.max(1, (int) Math.ceil(2.5 / step));
        float[] t = new float[n];
        morph(hy, t, nx, nz, r, true);
        morph(t, hy, nx, nz, r, false);
        // 3) inclinação máxima entre vértices vizinhos
        float d = 1.6f * step;
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < nz; j++) for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                float m = hy[k];
                if (i > 0) m = Math.min(m, hy[k - 1] + d);
                if (j > 0) m = Math.min(m, hy[k - nx] + d);
                hy[k] = m;
            }
            for (int j = nz - 1; j >= 0; j--) for (int i = nx - 1; i >= 0; i--) {
                int k = j * nx + i;
                float m = hy[k];
                if (i < nx - 1) m = Math.min(m, hy[k + 1] + d);
                if (j < nz - 1) m = Math.min(m, hy[k + nx] + d);
                hy[k] = m;
            }
        }
    }

    /** Mínimo (erode) ou máximo (dilata) numa janela quadrada de raio r, em duas passadas. */
    private static void morph(float[] in, float[] out, int nx, int nz, int r, boolean min) {
        float[] tmp = new float[in.length];
        for (int j = 0; j < nz; j++) for (int i = 0; i < nx; i++) {
            float m = in[j * nx + i];
            for (int a = Math.max(0, i - r); a <= Math.min(nx - 1, i + r); a++) { float v = in[j * nx + a]; m = min ? Math.min(m, v) : Math.max(m, v); }
            tmp[j * nx + i] = m;
        }
        for (int j = 0; j < nz; j++) for (int i = 0; i < nx; i++) {
            float m = tmp[j * nx + i];
            for (int b = Math.max(0, j - r); b <= Math.min(nz - 1, j + r); b++) { float v = tmp[b * nx + i]; m = min ? Math.min(m, v) : Math.max(m, v); }
            out[j * nx + i] = m;
        }
    }


    // ------------------------------------------------------------------ blocos de verdade

    private static final float TOP_L = 1.0f, WEST_L = 0.82f, EAST_L = 0.62f, NORTH_L = 0.76f, SOUTH_L = 0.56f;

    /** De perto: cada coluna é um prisma, com o topo e as paredes até o vizinho mais baixo, no bloco exato. */
    private void renderBlocky(int[] out, Source src, Plan pl, float[] hy, int nx, int nz, int i0, int j0) {
        // projeção de cada canto da grade no plano y = ty; a altura entra depois, linearmente
        if (gx.length < nx * nz) { gx = new float[nx * nz]; gy = new float[nx * nz]; gq = new float[nx * nz]; gl = new float[nx * nz]; }
        final float[] px = gx, py = gy, pq = gq;
        IntStream.range(0, nz).parallel().forEach(j -> {
            double[] p = new double[3];
            for (int i = 0; i < nx; i++) {
                project(i0 + i, ty, j0 + j, p);
                int k = j * nx + i;
                px[k] = (float) p[0]; py[k] = (float) p[1]; pq[k] = (float) p[2];
            }
        });
        final float kY = (float) (exag * cosP * s), kQ = (float) (exag * sinP), fty = (float) ty;
        int bands = Math.max(1, Math.min(32, h / 16));
        IntStream.range(0, bands).parallel().forEach(band -> {
            int ya = h * band / bands, yb = h * (band + 1) / bands;
            int curRx = Integer.MIN_VALUE, curRz = Integer.MIN_VALUE;
            int[] tex = null;
            for (int j = 0; j < nz - 1; j++) {
                for (int i = 0; i < nx - 1; i++) {
                    int a = j * nx + i, bI = a + 1, c = a + nx, d = c + 1;
                    float t = hy[a] + 1;
                    float low = t;
                    if (i > 0) low = Math.min(low, hy[a - 1] + 1);
                    if (i < nx - 2) low = Math.min(low, hy[a + 1] + 1);
                    if (j > 0) low = Math.min(low, hy[a - nx] + 1);
                    if (j < nz - 2) low = Math.min(low, hy[a + nx] + 1);
                    float sTop = Math.min(Math.min(py[a], py[bI]), Math.min(py[c], py[d])) - (t - fty) * kY;
                    float sLow = Math.max(Math.max(py[a], py[bI]), Math.max(py[c], py[d])) - (low - fty) * kY;
                    if (sLow < ya || sTop >= yb) continue;
                    float minX = Math.min(Math.min(px[a], px[bI]), Math.min(px[c], px[d])), maxX = Math.max(Math.max(px[a], px[bI]), Math.max(px[c], px[d]));
                    if (maxX < 0 || minX >= w) continue;
                    int wx = i0 + i, wz = j0 + j;
                    int rx = Math.floorDiv(wx, REGION), rz = Math.floorDiv(wz, REGION);
                    if (rx != curRx || rz != curRz) { tex = src.tex(rx, rz, 0); curRx = rx; curRz = rz; }
                    if (tex == null) continue;
                    int col = tex[Math.floorMod(wz, REGION) * REGION + Math.floorMod(wx, REGION)];
                    if ((col >>> 24) < 128) continue;
                    float dt = t - fty;
                    // topo
                    float ax = px[a], ay = py[a] - dt * kY, aq = pq[a] + dt * kQ;
                    float bx = px[bI], by = py[bI] - dt * kY, bq = pq[bI] + dt * kQ;
                    float cx_ = px[c], cy_ = py[c] - dt * kY, cq = pq[c] + dt * kQ;
                    float dx = px[d], dy = py[d] - dt * kY, dq = pq[d] + dt * kQ;
                    int top = shade(col, TOP_L);
                    triFlat(out, ya, yb, ax, ay, aq, bx, by, bq, cx_, cy_, cq, top);
                    triFlat(out, ya, yb, bx, by, bq, dx, dy, dq, cx_, cy_, cq, top);
                    // paredes até o vizinho mais baixo
                    if (i > 0 && hy[a - 1] + 1 < t) wall(out, ya, yb, px, py, pq, a, c, t, hy[a - 1] + 1, fty, kY, kQ, shade(col, WEST_L));
                    if (i < nx - 2 && hy[a + 1] + 1 < t) wall(out, ya, yb, px, py, pq, bI, d, t, hy[a + 1] + 1, fty, kY, kQ, shade(col, EAST_L));
                    if (j > 0 && hy[a - nx] + 1 < t) wall(out, ya, yb, px, py, pq, a, bI, t, hy[a - nx] + 1, fty, kY, kQ, shade(col, NORTH_L));
                    if (j < nz - 2 && hy[a + nx] + 1 < t) wall(out, ya, yb, px, py, pq, c, d, t, hy[a + nx] + 1, fty, kY, kQ, shade(col, SOUTH_L));
                }
            }
        });
    }

    private static int shade(int c, float l) {
        int r = (int) (((c >> 16) & 255) * l), g = (int) (((c >> 8) & 255) * l), b = (int) ((c & 255) * l);
        return 0xFF000000 | (r > 255 ? 255 : r) << 16 | (g > 255 ? 255 : g) << 8 | (b > 255 ? 255 : b);
    }

    /** Parede entre os cantos p1 e p2 da grade, do y {@code top} até {@code bottom}. */
    private void wall(int[] out, int ya, int yb, float[] px, float[] py, float[] pq, int p1, int p2,
                      float top, float bottom, float fty, float kY, float kQ, int colour) {
        float dt = top - fty, db = bottom - fty;
        float x1 = px[p1], y1t = py[p1] - dt * kY, q1t = pq[p1] + dt * kQ, y1b = py[p1] - db * kY, q1b = pq[p1] + db * kQ;
        float x2 = px[p2], y2t = py[p2] - dt * kY, q2t = pq[p2] + dt * kQ, y2b = py[p2] - db * kY, q2b = pq[p2] + db * kQ;
        triFlat(out, ya, yb, x1, y1t, q1t, x2, y2t, q2t, x1, y1b, q1b, colour);
        triFlat(out, ya, yb, x2, y2t, q2t, x2, y2b, q2b, x1, y1b, q1b, colour);
    }

    /** Triângulo de uma cor só, com teste de profundidade, nas linhas [ya, yb). */
    private void triFlat(int[] out, int ya, int yb, float x0, float y0, float q0, float x1, float y1, float q1,
                         float x2, float y2, float q2, int colour) {
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
        for (int y = minY; y <= maxY; y++) {
            float pyy = y + 0.5f - y0, pxs = minX + 0.5f - x0;
            float b1 = pxs * b1x + pyy * b1y, b2 = pxs * b2x + pyy * b2y;
            int row = y * w;
            for (int x = minX; x <= maxX; x++, b1 += b1x, b2 += b2x) {
                float b0 = 1f - b1 - b2;
                if (b0 < eps || b1 < eps || b2 < eps) continue;
                float q = q0 + (q1 - q0) * b1 + (q2 - q0) * b2;
                int idx = row + x;
                if (q <= zbuf[idx]) continue;
                out[idx] = colour;
                zbuf[idx] = q;
            }
        }
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
