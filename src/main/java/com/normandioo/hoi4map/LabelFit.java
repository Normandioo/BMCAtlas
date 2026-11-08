package com.normandioo.hoi4map;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Acha, para cada país, onde o nome cabe inteiro dentro do território (como o HOI4 faz com os estados):
 * a maior caixa do tamanho do texto, em qualquer inclinação até 30 graus, que fica toda dentro da terra do país.
 * Roda em segundo plano; o resultado vai para Data.fit[país] = {centroX, centroZ, ângulo (rad), blocos por pixel de fonte}.
 */
final class LabelFit {
    /** Altura da linha de texto, em pixels de fonte, já com a margem do contorno. */
    static final double TEXT_H = 10.0;
    private static final double[] NONE = new double[0];
    private static final int[] ANGLES = {0, -10, 10, -20, 20, -30, 30};

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hoi4map-labels");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private LabelFit() {}

    static void request(SiteTerritories.Data d, int country, int textWidth) {
        EXEC.execute(() -> {
            double[] r;
            try {
                r = compute(d, country, Math.max(8, textWidth));
            } catch (RuntimeException e) {
                r = NONE;
            }
            d.fit[country] = r;
        });
    }

    private static double[] compute(SiteTerritories.Data d, int country, int tw) {
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        boolean any = false;
        for (int p = 0; p < d.n; p++) {
            if (d.provCountry[p] != country) continue;
            int[] b = d.bounds[p];
            minX = Math.min(minX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxX = Math.max(maxX, b[2] + 1);
            maxZ = Math.max(maxZ, b[3] + 1);
            any = true;
        }
        if (!any) return NONE;
        double bw = maxX - minX, bh = maxZ - minZ;
        double cell = Math.max(2.0, Math.max(bw, bh) / 100.0);
        int gw = (int) Math.ceil(bw / cell) + 2, gh = (int) Math.ceil(bh / cell) + 2;
        double ox = minX - cell, oz = minZ - cell;
        boolean[] in = new boolean[gw * gh];

        // terra do país, por varredura (par-ímpar) no centro de cada célula
        double[] xs = new double[64];
        for (int p = 0; p < d.n; p++) {
            if (d.provCountry[p] != country) continue;
            int[] b = d.bounds[p];
            List<int[]> rings = d.rings[p];
            int g0 = Math.max(0, (int) Math.floor((b[1] - oz) / cell) - 1);
            int g1 = Math.min(gh - 1, (int) Math.ceil((b[3] + 1 - oz) / cell) + 1);
            for (int gz = g0; gz <= g1; gz++) {
                double zc = oz + (gz + 0.5) * cell;
                int cnt = 0;
                for (int[] r : rings) {
                    int len = r.length / 2;
                    for (int i = 0, j = len - 1; i < len; j = i++) {
                        double zi = r[2 * i + 1], zj = r[2 * j + 1];
                        if ((zi <= zc) == (zj <= zc)) continue;
                        double xi = r[2 * i], xj = r[2 * j];
                        if (cnt == xs.length) xs = Arrays.copyOf(xs, cnt * 2);
                        xs[cnt++] = xi + (zc - zi) * (xj - xi) / (zj - zi);
                    }
                }
                Arrays.sort(xs, 0, cnt);
                for (int k = 0; k + 1 < cnt; k += 2) {
                    int from = (int) Math.ceil((xs[k] - ox) / cell - 0.5);
                    int to = (int) Math.ceil((xs[k + 1] - ox) / cell - 0.5) - 1;
                    for (int gx = Math.max(from, 0); gx <= Math.min(to, gw - 1); gx++) in[gz * gw + gx] = true;
                }
            }
        }

        // distância (chamfer 3-4) até a borda
        final int inf = 1 << 20;
        int[] dist = new int[gw * gh];
        for (int i = 0; i < dist.length; i++) dist[i] = in[i] ? inf : 0;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int idx = y * gw + x;
                int v = dist[idx];
                if (x > 0) v = Math.min(v, dist[idx - 1] + 3);
                if (y > 0) {
                    v = Math.min(v, dist[idx - gw] + 3);
                    if (x > 0) v = Math.min(v, dist[idx - gw - 1] + 4);
                    if (x < gw - 1) v = Math.min(v, dist[idx - gw + 1] + 4);
                }
                dist[idx] = v;
            }
        }
        int maxD = 0;
        for (int y = gh - 1; y >= 0; y--) {
            for (int x = gw - 1; x >= 0; x--) {
                int idx = y * gw + x;
                int v = dist[idx];
                if (x < gw - 1) v = Math.min(v, dist[idx + 1] + 3);
                if (y < gh - 1) {
                    v = Math.min(v, dist[idx + gw] + 3);
                    if (x < gw - 1) v = Math.min(v, dist[idx + gw + 1] + 4);
                    if (x > 0) v = Math.min(v, dist[idx + gw - 1] + 4);
                }
                dist[idx] = v;
                if (v < inf) maxD = Math.max(maxD, v);
            }
        }
        if (maxD <= 0) return NONE;

        // candidatos: as células mais "para dentro" do país
        int thresh = Math.max(3, (int) (maxD * 0.30));
        int count = 0;
        for (int v : dist) if (v >= thresh && v < inf) count++;
        int stride = Math.max(1, count / 450);
        int[] cand = new int[count / stride + 1];
        int nc = 0, seen = 0;
        for (int i = 0; i < dist.length; i++) {
            if (dist[i] >= thresh && dist[i] < inf && (seen++ % stride) == 0) cand[nc++] = i;
        }

        double rho = TEXT_H / tw;                 // meia-altura = meia-largura * rho
        double hiLimit = 0.5 * Math.hypot(bw, bh);
        double bestScore = -1, bestA = 0, bestX = 0, bestZ = 0, bestAng = 0;
        for (int ci = 0; ci < nc; ci++) {
            int gx = cand[ci] % gw, gz = cand[ci] / gw;
            double px = ox + (gx + 0.5) * cell, pz = oz + (gz + 0.5) * cell;
            for (int deg : ANGLES) {
                double ang = Math.toRadians(deg);
                double ux = Math.cos(ang), uz = Math.sin(ang);
                double lo = cell * 0.5, hi = hiLimit;
                if (!fits(in, gw, gh, ox, oz, cell, px, pz, ux, uz, lo, rho)) continue;
                for (int it = 0; it < 9; it++) {
                    double mid = (lo + hi) / 2;
                    if (fits(in, gw, gh, ox, oz, cell, px, pz, ux, uz, mid, rho)) lo = mid;
                    else hi = mid;
                }
                double score = lo * (1.0 - 0.04 * Math.abs(deg) / 10.0);
                if (score > bestScore) {
                    bestScore = score;
                    bestA = lo;
                    bestX = px;
                    bestZ = pz;
                    bestAng = ang;
                }
            }
        }
        if (bestScore <= 0) return NONE;
        return new double[]{bestX, bestZ, bestAng, 2.0 * bestA / tw};
    }

    /** A caixa (meia-largura a, meia-altura a*rho) inclinada por (ux,uz) cabe toda dentro da terra? */
    private static boolean fits(boolean[] in, int gw, int gh, double ox, double oz, double cell,
                                double px, double pz, double ux, double uz, double a, double rho) {
        double nx = -uz, nz = ux;
        double b = a * rho;
        double step = cell * 0.7;
        int steps = Math.max(1, (int) Math.ceil(2 * a / step));
        for (int k = -1; k <= 1; k++) {
            double ox2 = px + nx * b * k, oz2 = pz + nz * b * k;
            for (int s = 0; s <= steps; s++) {
                double t = -a + 2 * a * s / steps;
                int gx = (int) Math.floor((ox2 + ux * t - ox) / cell);
                int gz = (int) Math.floor((oz2 + uz * t - oz) / cell);
                if (gx < 0 || gz < 0 || gx >= gw || gz >= gh || !in[gz * gw + gx]) return false;
            }
        }
        return true;
    }
}
