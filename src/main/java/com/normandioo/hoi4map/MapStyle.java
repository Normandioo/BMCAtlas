package com.normandioo.hoi4map;

import java.util.Objects;

/**
 * Converte os pixels brutos de uma região (cores reais do terreno) na imagem final,
 * no estilo do mapa de Hearts of Iron IV: mar azul-marinho escuro, terra dessaturada
 * com relevo forte, países tingidos e brilho ao longo das fronteiras.
 */
final class MapStyle {
    static final int MODE_PHYSICAL = 0;
    static final int MODE_POLITICAL = 1;
    static final int MODE_PROVINCES = 2;
    static final int MODE_DIPLOMACY = 3;
    static final int MODE_WAR = 4;

    /** Modos que recolorem os países por relação (diplomacia/guerra). */
    static boolean relational(int mode) {
        return mode == MODE_DIPLOMACY || mode == MODE_WAR;
    }

    /** Por país: cor RGB, ou Relations.GREY (-1) para apagado. null = cores normais. */
    static volatile int[] countryOverride;
    /** Por província (índice no arquivo do site): cor que foge da cor do país. */
    static volatile java.util.Map<Integer, Integer> provinceOverride;
    /** Sobe a cada mudança das cores acima, para as regiões se repintarem. */
    static volatile int overrideVersion;

    private static final int SPAN = MapData.PROV * 16;                 // blocos por província
    private static final int PROVS = MapData.REGION_PX / SPAN;         // províncias por lado da região
    private static final int N = PROVS + 2;                            // com margem de 1 província

    private static final int[] OUT = new int[MapData.REGION_PX * MapData.REGION_PX];

    private MapStyle() {}

    /** Água é marcada no byte alto: 0x80 | profundidade (0..63). Terra = 0xFF. */
    static boolean isWater(int raw) {
        return (raw >>> 24) != 0xFF;
    }

    /** Província selecionada (índice no arquivo do site), ou -1. */
    static int selectedProvince = -1;

    private static final int M = 8; // margem para o brilho de fronteira cruzar regiões
    private static final int W = MapData.REGION_PX + 2 * M;
    private static final short[] PROV_GRID = new short[W * W];
    private static final int[] DIST = new int[W * W];

    static int[] paint(MapData.Region r, int mode) {
        if (mode != MODE_PHYSICAL && MapData.siteApplies() && SiteTerritories.active()) {
            return paintSite(r, mode, SiteTerritories.data());
        }
        return paintGrid(r, mode);
    }

    /** Pinta usando os países/províncias do site, bloco a bloco. */
    private static int[] paintSite(MapData.Region r, int mode, SiteTerritories.Data d) {
        int size = MapData.REGION_PX;
        SiteTerritories.raster(d, r.rx * size - M, r.rz * size - M, W, W, PROV_GRID);
        boolean political = mode != MODE_PROVINCES;
        boolean relational = relational(mode);
        final int[] cOv = relational ? countryOverride : null;
        final java.util.Map<Integer, Integer> pOv = relational ? provinceOverride : null;

        // chave de comparação: país (político) ou província (modo províncias; províncias recoloridas também têm borda)
        int[] key = new int[d.n + 1];
        for (int i = 1; i <= d.n; i++) {
            if (!political) key[i] = i;
            else if (pOv != null && pOv.containsKey(i - 1)) key[i] = (d.provCountry[i - 1] + 1) | (i << 8);
            else key[i] = d.provCountry[i - 1] + 1;
        }

        final int inf = 1 << 20;
        java.util.Arrays.fill(DIST, inf);
        for (int y = 0; y < W - 1; y++) {
            for (int x = 0; x < W - 1; x++) {
                int idx = y * W + x;
                int k = key[PROV_GRID[idx]];
                if (k != key[PROV_GRID[idx + 1]]) {
                    DIST[idx] = 0;
                    DIST[idx + 1] = 0;
                }
                if (k != key[PROV_GRID[idx + W]]) {
                    DIST[idx] = 0;
                    DIST[idx + W] = 0;
                }
            }
        }
        // distância (chamfer 3-4) até a fronteira mais próxima
        for (int y = 0; y < W; y++) {
            for (int x = 0; x < W; x++) {
                int idx = y * W + x;
                int v = DIST[idx];
                if (x > 0) v = Math.min(v, DIST[idx - 1] + 3);
                if (y > 0) {
                    v = Math.min(v, DIST[idx - W] + 3);
                    if (x > 0) v = Math.min(v, DIST[idx - W - 1] + 4);
                    if (x < W - 1) v = Math.min(v, DIST[idx - W + 1] + 4);
                }
                DIST[idx] = v;
            }
        }
        for (int y = W - 1; y >= 0; y--) {
            for (int x = W - 1; x >= 0; x--) {
                int idx = y * W + x;
                int v = DIST[idx];
                if (x < W - 1) v = Math.min(v, DIST[idx + 1] + 3);
                if (y < W - 1) {
                    v = Math.min(v, DIST[idx + W] + 3);
                    if (x < W - 1) v = Math.min(v, DIST[idx + W + 1] + 4);
                    if (x > 0) v = Math.min(v, DIST[idx + W - 1] + 4);
                }
                DIST[idx] = v;
            }
        }

        int baseX = r.rx * size;
        int baseZ = r.rz * size;
        int selected = selectedProvince + 1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int gi = (y + M) * W + (x + M);
                int p = PROV_GRID[gi];
                int raw = r.px[y * size + x];
                int out;
                if (raw != 0 && isWater(raw)) {
                    out = water(raw, baseX + x, baseZ + y);
                } else {
                    int c;
                    if (raw != 0) c = land(raw);
                    else if (p != 0) c = 0xFF39424C;     // terra do país ainda não explorada
                    else {
                        OUT[y * size + x] = 0;
                        continue;
                    }
                    if (p != 0) {
                        int ci = d.provCountry[p - 1];
                        int country = d.rgb[ci] | 0xFF000000;
                        boolean grey = false;
                        if (relational) {
                            int ov = cOv != null && ci < cOv.length ? cOv[ci] : Relations.GREY;
                            Integer po = pOv == null ? null : pOv.get(p - 1);
                            if (po != null) ov = po;
                            if (ov == Relations.GREY) grey = true;
                            else country = ov | 0xFF000000;
                        }
                        int dist = DIST[gi] / 3;
                        if (grey) {
                            c = mix(muted(c), country, 0.10f);
                            if (dist == 0) c = mix(c, 0xFF070B10, 0.6f);
                        } else if (political) {
                            c = tint(c, country, 0.72f);
                            if (dist == 0) c = mix(c, 0xFF070B10, 0.85f);
                            else if (dist <= 8) c = mix(c, lighten(country, 1.45f), 0.5f * (1f - (dist - 1) / 7f));
                        } else {
                            c = tint(c, country, 0.30f);
                            if (dist == 0) c = mix(c, 0xFF05080C, 0.5f);
                        }
                        if (p == selected) {
                            c = mix(c, 0xFFFFE27A, 0.28f);
                            if (PROV_GRID[gi - 1] != p || PROV_GRID[gi + 1] != p || PROV_GRID[gi - W] != p || PROV_GRID[gi + W] != p) {
                                c = mix(c, 0xFFFFE27A, 0.9f);
                            }
                        }
                    } else if (political) {
                        c = muted(c);
                    }
                    out = c;
                }
                OUT[y * size + x] = out;
            }
        }
        return OUT;
    }

    static int[] paintGrid(MapData.Region r, int mode) {
        String[] ids = new String[N * N];
        int[] rgb = new int[N * N];
        for (int gz = -1; gz <= PROVS; gz++) {
            for (int gx = -1; gx <= PROVS; gx++) {
                String id = MapData.ownerOf(r.rx * PROVS + gx, r.rz * PROVS + gz);
                int idx = (gz + 1) * N + (gx + 1);
                ids[idx] = id;
                Countries.Country c = id == null ? null : Countries.BY_ID.get(id);
                rgb[idx] = c == null ? 0 : c.rgb;
            }
        }

        // diferenças com os 4 vizinhos, por província
        boolean[] dl = new boolean[N * N], dr = new boolean[N * N], du = new boolean[N * N], dd = new boolean[N * N];
        for (int gz = 0; gz < PROVS; gz++) {
            for (int gx = 0; gx < PROVS; gx++) {
                int idx = (gz + 1) * N + (gx + 1);
                dl[idx] = !Objects.equals(ids[idx], ids[idx - 1]);
                dr[idx] = !Objects.equals(ids[idx], ids[idx + 1]);
                du[idx] = !Objects.equals(ids[idx], ids[idx - N]);
                dd[idx] = !Objects.equals(ids[idx], ids[idx + N]);
            }
        }

        boolean political = mode == MODE_POLITICAL;
        boolean provinces = mode == MODE_PROVINCES;
        int size = MapData.REGION_PX;
        int baseX = r.rx * size;
        int baseZ = r.rz * size;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int raw = r.px[y * size + x];
                if (raw == 0) {
                    OUT[y * size + x] = 0;
                    continue;
                }
                if (isWater(raw)) {
                    OUT[y * size + x] = water(raw, baseX + x, baseZ + y);
                    continue;
                }
                int c = land(raw);
                if (political || provinces) {
                    int idx = ((y / SPAN) + 1) * N + ((x / SPAN) + 1);
                    int country = rgb[idx];
                    if (country != 0) {
                        c = tint(c, country, political ? 0.72f : 0.30f);
                        if (political) {
                            int lx = x % SPAN;
                            int lz = y % SPAN;
                            int d = 99;
                            if (dl[idx]) d = Math.min(d, lx);
                            if (dr[idx]) d = Math.min(d, SPAN - 1 - lx);
                            if (du[idx]) d = Math.min(d, lz);
                            if (dd[idx]) d = Math.min(d, SPAN - 1 - lz);
                            if (d <= 1) {
                                c = mix(c, 0xFF070B10, 0.85f);
                            } else if (d <= 8) {
                                float a = 0.5f * (1f - (d - 2) / 7f);
                                c = mix(c, lighten(country, 1.45f), a);
                            }
                        }
                    } else if (political) {
                        c = muted(c);
                    }
                }
                OUT[y * size + x] = c;
            }
        }
        return OUT;
    }

    // ------------------------------------------------------------------- cores

    /** Terra: um pouco dessaturada, mais escura e com mais contraste. */
    private static int land(int raw) {
        float r = (raw >> 16) & 0xFF, g = (raw >> 8) & 0xFF, b = raw & 0xFF;
        float lum = 0.30f * r + 0.59f * g + 0.11f * b;
        r = lum + (r - lum) * 0.78f;
        g = lum + (g - lum) * 0.78f;
        b = lum + (b - lum) * 0.78f;
        r = (r - 128f) * 1.10f + 128f;
        g = (g - 128f) * 1.10f + 128f;
        b = (b - 128f) * 1.10f + 128f;
        return pack(r * 0.88f, g * 0.90f, b * 0.92f);
    }

    /** Terra sem dono: cinza-azulado apagado. */
    private static int muted(int c) {
        float r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        float lum = 0.30f * r + 0.59f * g + 0.11f * b;
        r = lum + (r - lum) * 0.35f;
        g = lum + (g - lum) * 0.35f;
        b = lum + (b - lum) * 0.35f;
        return pack(r * 0.78f, g * 0.80f, b * 0.86f);
    }

    /** Mar: azul-marinho escuro, um pouco mais claro perto da costa. */
    private static int water(int raw, int wx, int wz) {
        int depth = Math.min(63, (raw >>> 24) - 0x80);
        if (depth < 0) depth = 0;
        float t = Math.min(depth, 26) / 26f;
        float r = lerpf(0x34, 0x09, t);
        float g = lerpf(0x58, 0x14, t);
        float b = lerpf(0x8E, 0x2C, t);
        int h = hash(wx, wz);
        float n = ((h & 7) - 3) * 0.9f;
        return pack(r + n, g + n, b + n * 1.2f);
    }

    /** Cor do país aplicada sobre o terreno, mantendo o relevo (luminância). */
    private static int tint(int base, int country, float amount) {
        float lum = (0.30f * ((base >> 16) & 0xFF) + 0.59f * ((base >> 8) & 0xFF) + 0.11f * (base & 0xFF)) / 255f;
        float k = 0.42f + 0.95f * lum;
        int scaled = pack(((country >> 16) & 0xFF) * k, ((country >> 8) & 0xFF) * k, (country & 0xFF) * k);
        return mix(base, scaled, amount);
    }

    private static int lighten(int c, float k) {
        return pack(((c >> 16) & 0xFF) * k + 18, ((c >> 8) & 0xFF) * k + 18, (c & 0xFF) * k + 18);
    }

    private static int mix(int a, int b, float t) {
        return pack(lerpf((a >> 16) & 0xFF, (b >> 16) & 0xFF, t),
                lerpf((a >> 8) & 0xFF, (b >> 8) & 0xFF, t),
                lerpf(a & 0xFF, b & 0xFF, t));
    }

    private static float lerpf(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int pack(float r, float g, float b) {
        int ri = Math.max(0, Math.min(255, (int) r));
        int gi = Math.max(0, Math.min(255, (int) g));
        int bi = Math.max(0, Math.min(255, (int) b));
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int hash(int a, int b) {
        int h = a * 73856093 ^ b * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h;
    }
}
