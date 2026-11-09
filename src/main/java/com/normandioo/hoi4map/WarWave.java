package com.normandioo.hoi4map;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Onda de conquista no modo guerra: quando uma província muda para a cor de outro lado, a cor nova avança a
 * partir da fronteira de quem conquistou (ou da cidade, se não houver divisa com ele).
 * A textura do mapa já tem a cor certa: a onda só cobre, com a cor antiga, o trecho que ela ainda não alcançou.
 */
final class WarWave {
    private WarWave() {}

    static final long DURATION = 2800;     // duração da onda (ms)
    private static final long QUIET = 10_000;      // duas trocas neste intervalo: troca direto, sem onda
    private static final int MAX = 8;              // ondas ao mesmo tempo
    private static final int MAX_CELLS = 80;       // resolução máxima da grade, por lado

    static final class Wave {
        int cell, x0, z0, w, h, oldRgb, maxDist;
        int frontArgb = 0xFFF2907E;     // frente da onda: tom claro da cor de quem conquistou
        int[] dist;     // distância da origem da onda, -1 fora da província
        long start;
    }

    private static final Map<Integer, Wave> WAVES = new HashMap<>();
    private static final Map<Integer, Long> LAST_CHANGE = new HashMap<>();

    static void clear() {
        WAVES.clear();
        LAST_CHANGE.clear();
    }

    static Collection<Wave> active(long now) {
        WAVES.values().removeIf(w -> now - w.start >= DURATION);
        return WAVES.values();
    }

    private static int colorOf(SiteTerritories.Data d, Map<Integer, Integer> prov, int[] country, int p) {
        Integer c = prov.get(p);
        return c != null ? c : country[d.provCountry[p]];
    }

    private static boolean isSide(int rgb) {
        return rgb != Relations.GREY && rgb != Relations.CONTESTED && rgb != Relations.UNCONTROLLED && rgb != Relations.NEUTRAL;
    }

    /** Compara as cores antes e depois de uma leitura e cria as ondas das províncias que mudaram de lado. */
    static void changes(SiteTerritories.Data d, Map<Integer, Integer> oldProv, int[] oldCountry,
                        Map<Integer, Integer> newProv, int[] newCountry, long now) {
        if (oldCountry.length != newCountry.length) return;
        Set<Integer> touched = new HashSet<>(oldProv.keySet());
        touched.addAll(newProv.keySet());
        for (int p : touched) {
            if (p < 0 || p >= d.n) continue;
            int before = colorOf(d, oldProv, oldCountry, p), after = colorOf(d, newProv, newCountry, p);
            if (before == after) continue;
            Long last = LAST_CHANGE.put(p, now);
            if (last != null && now - last < QUIET) {       // vai e vem: não anima
                WAVES.remove(p);
                continue;
            }
            if (!isSide(after) || WAVES.size() >= MAX) continue;
            Wave w = build(d, p, before, after, newProv, newCountry);
            if (w != null) {
                w.start = now;
                WAVES.put(p, w);
            }
        }
    }

    private static Wave build(SiteTerritories.Data d, int p, int oldRgb, int conqueror, Map<Integer, Integer> prov, int[] country) {
        int[] b = d.bounds[p];
        int spanX = b[2] - b[0] + 1, spanZ = b[3] - b[1] + 1;
        Wave w = new Wave();
        w.cell = Math.max(1, (int) Math.ceil(Math.max(spanX, spanZ) / (double) MAX_CELLS));
        w.x0 = b[0];
        w.z0 = b[1];
        w.w = (int) Math.ceil(spanX / (double) w.cell);
        w.h = (int) Math.ceil(spanZ / (double) w.cell);
        w.oldRgb = oldRgb;
        {
            int cr = (conqueror >> 16) & 255, cg = (conqueror >> 8) & 255, cb = conqueror & 255;
            w.frontArgb = 0xFF000000 | (cr + (255 - cr) * 55 / 100) << 16 | (cg + (255 - cg) * 55 / 100) << 8 | (cb + (255 - cb) * 55 / 100);
        }
        w.dist = new int[w.w * w.h];

        boolean[] in = new boolean[w.dist.length];
        int inside = 0;
        for (int j = 0; j < w.h; j++) {
            for (int i = 0; i < w.w; i++) {
                in[j * w.w + i] = SiteTerritories.provinceAt(d, centerX(w, i), centerZ(w, j)) == p;
                if (in[j * w.w + i]) inside++;
            }
        }
        if (inside == 0) return null;

        // origem: células na divisa com uma província que já é de quem conquistou
        java.util.Arrays.fill(w.dist, -1);
        ArrayDeque<Integer> open = new ArrayDeque<>();
        int[] ni = {-1, 1, 0, 0}, nj = {0, 0, -1, 1};
        for (int j = 0; j < w.h; j++) {
            for (int i = 0; i < w.w; i++) {
                int at = j * w.w + i;
                if (!in[at]) continue;
                for (int k = 0; k < 4; k++) {
                    int oi = i + ni[k], oj = j + nj[k];
                    if (oi >= 0 && oj >= 0 && oi < w.w && oj < w.h && in[oj * w.w + oi]) continue;
                    int q = SiteTerritories.provinceAt(d, centerX(w, oi), centerZ(w, oj));
                    if (q >= 0 && q != p && colorOf(d, prov, country, q) == conqueror) {
                        w.dist[at] = 0;
                        open.add(at);
                        break;
                    }
                }
            }
        }
        if (open.isEmpty()) {       // sem divisa (ataque por mar, ilha): sai da cidade
            int at = nearestCell(d, w, in, p);
            w.dist[at] = 0;
            open.add(at);
        }

        // distância em grade a partir da origem
        while (!open.isEmpty()) {
            int at = open.poll(), i = at % w.w, j = at / w.w;
            w.maxDist = Math.max(w.maxDist, w.dist[at]);
            for (int k = 0; k < 4; k++) {
                int oi = i + ni[k], oj = j + nj[k];
                if (oi < 0 || oj < 0 || oi >= w.w || oj >= w.h) continue;
                int to = oj * w.w + oi;
                if (!in[to] || w.dist[to] >= 0) continue;
                w.dist[to] = w.dist[at] + 1;
                open.add(to);
            }
        }
        for (int at = 0; at < w.dist.length; at++) {        // pedaços soltos da província aparecem no fim
            if (in[at] && w.dist[at] < 0) w.dist[at] = w.maxDist;
        }
        w.maxDist = Math.max(1, w.maxDist);
        return w;
    }

    private static int centerX(Wave w, int i) {
        return w.x0 + i * w.cell + w.cell / 2;
    }

    private static int centerZ(Wave w, int j) {
        return w.z0 + j * w.cell + w.cell / 2;
    }

    /** Célula da província mais perto de uma cidade dela (ou do centro, se não houver cidade). */
    private static int nearestCell(SiteTerritories.Data d, Wave w, boolean[] in, int p) {
        int tx = w.x0 + w.w * w.cell / 2, tz = w.z0 + w.h * w.cell / 2;
        for (SiteTerritories.Locality l : d.localities) {
            if (SiteTerritories.provinceAt(d, l.x, l.z) == p) {
                tx = l.x;
                tz = l.z;
                break;
            }
        }
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int at = 0; at < in.length; at++) {
            if (!in[at]) continue;
            long dx = centerX(w, at % w.w) - tx, dz = centerZ(w, at / w.w) - tz, dd = dx * dx + dz * dz;
            if (dd < bestD) {
                bestD = dd;
                best = at;
            }
        }
        return best;
    }
}
