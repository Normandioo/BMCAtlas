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

    /**
     * Cor (ARGB; o alfa é a força da mistura) com que cada zona de cidade (índice em Data.zones) é tingida
     * por dentro; 0 = não tinge. null = sem camada de zonas. Mude junto com overrideVersion.
     */
    static volatile int[] zoneTint;

    private static final short[] ZONE_GRID = new short[MapData.REGION_PX * MapData.REGION_PX];

    static int[] paint(MapData.Region r, int mode) {
        boolean site = MapData.siteApplies() && SiteTerritories.active();
        int[] out;
        if (mode != MODE_PHYSICAL && site) out = paintSite(r, mode, SiteTerritories.data());
        else out = paintGrid(r, mode);
        int[] tint = zoneTint;
        if (site && tint != null) applyZones(r, out, SiteTerritories.data(), tint);
        return out;
    }

    /** Clareia (ou tinge) por dentro as zonas de cidade escolhidas; a linha do limite é desenhada pela tela. */
    private static void applyZones(MapData.Region r, int[] out, SiteTerritories.Data d, int[] tint) {
        int nz = Math.min(tint.length, d.zones.size());
        boolean[] include = new boolean[d.zones.size()];
        boolean anyZone = false;
        for (int i = 0; i < nz; i++) {
            if (tint[i] != 0) {
                include[i] = true;
                anyZone = true;
            }
        }
        if (!anyZone) return;
        int size = MapData.REGION_PX;
        if (!SiteTerritories.rasterZones(d, r.rx * size, r.rz * size, size, size, ZONE_GRID, include)) return;
        for (int i = 0; i < size * size; i++) {
            int z = ZONE_GRID[i];
            if (z == 0 || out[i] == 0) continue;
            int t = tint[z - 1];
            out[i] = mix(out[i], 0xFF000000 | t, (t >>> 24) / 255f);
        }
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
                    // a província escolhida vai até onde o território dela vai, também sobre a água
                    if (p == selected && p != 0) {
                        out = mix(out, 0xFFFFE27A, 0.30f);
                        if (PROV_GRID[gi - 1] != p || PROV_GRID[gi + 1] != p || PROV_GRID[gi - W] != p || PROV_GRID[gi + W] != p) {
                            out = mix(out, 0xFFFFE27A, 0.9f);
                        }
                    }
                } else {
                    int c;
                    if (raw != 0) c = land(r.px, size, x, y, raw);
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
                            if (dist == 0) c = mix(c, 0xFF070B10, 0.6f * Prefs.border());
                        } else if (political) {
                            c = tint(c, country, 0.72f * Prefs.fill());
                            if (dist == 0) c = mix(c, 0xFF070B10, 0.85f * Prefs.border());
                            else if (dist <= 8) c = mix(c, lighten(country, 1.45f), 0.5f * Prefs.border() * (1f - (dist - 1) / 7f));
                        } else {
                            c = tint(c, country, 0.30f * Prefs.fill());
                            if (dist == 0) c = mix(c, 0xFF05080C, 0.5f * Prefs.border());
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
                int c = land(r.px, size, x, y, raw);
                if (political || provinces) {
                    int idx = ((y / SPAN) + 1) * N + ((x / SPAN) + 1);
                    int country = rgb[idx];
                    if (country != 0) {
                        c = tint(c, country, (political ? 0.72f : 0.30f) * Prefs.fill());
                        if (political) {
                            int lx = x % SPAN;
                            int lz = y % SPAN;
                            int d = 99;
                            if (dl[idx]) d = Math.min(d, lx);
                            if (dr[idx]) d = Math.min(d, SPAN - 1 - lx);
                            if (du[idx]) d = Math.min(d, lz);
                            if (dd[idx]) d = Math.min(d, SPAN - 1 - lz);
                            if (d <= 1) {
                                c = mix(c, 0xFF070B10, 0.85f * Prefs.border());
                            } else if (d <= 8) {
                                float a = 0.5f * Prefs.border() * (1f - (d - 2) / 7f);
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
    /**
     * Terra. Relevo: a diferença de brilho de cada bloco para os vizinhos é ampliada (só brilho, sem mexer na saturação).
     * Granulado: só o ruído fraco entre blocos parecidos é achatado; os degraus do relevo (diferenças grandes) ficam nítidos.
     */
    private static int land(int[] px, int size, int x, int y, int raw) {
        Prefs.Data p = Prefs.get();
        float w = p.smoothPct / 100f * 0.85f;
        float k = p.reliefPct / 100f;
        float r0 = (raw >> 16) & 0xFF, g0 = (raw >> 8) & 0xFF, b0 = raw & 0xFF;
        float lum0 = 0.30f * r0 + 0.59f * g0 + 0.11f * b0;
        float sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int d = 0; d < 4; d++) {
            int nx = x + (d == 0 ? -1 : d == 1 ? 1 : 0), ny = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
            if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue;
            int q = px[ny * size + nx];
            if (q == 0 || isWater(q)) continue;
            sr += (q >> 16) & 0xFF;
            sg += (q >> 8) & 0xFF;
            sb += q & 0xFF;
            n++;
        }
        float delta = 0f;
        if (n > 0) {
            sr /= n;
            sg /= n;
            sb /= n;
            float lumAvg = 0.30f * sr + 0.59f * sg + 0.11f * sb;
            delta = lum0 - lumAvg;
            // ruído fraco (diferença pequena de brilho): mistura com os vizinhos; degraus de relevo ficam como estão
            if (w > 0f && Math.abs(delta) < 9f) {
                float f = w * (1f - Math.abs(delta) / 9f);
                raw = pack(r0 + (sr - r0) * f, g0 + (sg - g0) * f, b0 + (sb - b0) * f);
            }
        }
        int c = landBase(raw);
        if (k == 1f || n == 0) return c;
        float shift = delta * (k - 1f) * 1.6f;
        return pack(((c >> 16) & 0xFF) + shift, ((c >> 8) & 0xFF) + shift, (c & 0xFF) + shift);
    }

    private static int landBase(int raw) {
        if (Prefs.get().mapStyle == 1) return 0xFF000000 | (raw & 0xFFFFFF);   // limpo: a cor do bloco como está
        float r = (raw >> 16) & 0xFF, g = (raw >> 8) & 0xFF, b = raw & 0xFF;
        float lum = 0.30f * r + 0.59f * g + 0.11f * b;
        r = lum + (r - lum) * 0.98f;
        g = lum + (g - lum) * 0.98f;
        b = lum + (b - lum) * 0.98f;
        r = (r - 128f) * 1.06f + 128f;
        g = (g - 128f) * 1.06f + 128f;
        b = (b - 128f) * 1.06f + 128f;
        return pack(r * 0.98f, g * 0.99f, b * 1.0f);
    }

    /** Terra sem dono: cinza-azulado apagado. */
    private static int muted(int c) {
        float r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        float lum = 0.30f * r + 0.59f * g + 0.11f * b;
        float keep = Prefs.get().mapStyle == 1 ? 0.75f : 0.62f;
        r = lum + (r - lum) * keep;
        g = lum + (g - lum) * keep;
        b = lum + (b - lum) * keep;
        return pack(r * 0.90f, g * 0.92f, b * 0.96f);
    }

    // profundidade (blocos abaixo da superfície) -> cor do mar: do turquesa raso ao azul-marinho profundo
    private static final float[] SEA_D = {0, 3, 8, 16, 28, 44, 60};
    private static final int[] SEA_C = {0x4C86C2, 0x3F75B4, 0x33649F, 0x28528A, 0x1E4075, 0x16315F, 0x0F2448};

    /** Mar com degradê de profundidade, ondas suaves de brilho, espuma na costa e um grão fino. */
    private static int water(int raw, int wx, int wz) {
        int c = waterBase(raw, wx, wz);
        float sat = Prefs.get().waterSatPct / 100f;
        if (sat >= 0.999f) return c;
        float r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        float lum = 0.30f * r + 0.59f * g + 0.11f * b;
        return pack(lum + (r - lum) * sat, lum + (g - lum) * sat, lum + (b - lum) * sat);
    }

    private static int waterBase(int raw, int wx, int wz) {
        int depth = Math.min(63, (raw >>> 24) - 0x80);
        if (depth < 0) depth = 0;
        if (Prefs.get().mapStyle == 1) {
            // limpo: azul liso, só escurece um pouco com a profundidade
            float t = Math.min(1f, depth / 26f);
            t = (float) Math.sqrt(t);
            return pack(lerpf(0x5E, 0x1A, t), lerpf(0x9C, 0x36, t), lerpf(0xE0, 0x84, t));
        }
        int k = 1;
        while (k < SEA_D.length - 1 && depth > SEA_D[k]) k++;
        float t = Math.max(0f, Math.min(1f, (depth - SEA_D[k - 1]) / (SEA_D[k] - SEA_D[k - 1])));
        t = t * t * (3f - 2f * t);
        int c0 = SEA_C[k - 1], c1 = SEA_C[k];
        float r = lerpf((c0 >> 16) & 0xFF, (c1 >> 16) & 0xFF, t);
        float g = lerpf((c0 >> 8) & 0xFF, (c1 >> 8) & 0xFF, t);
        float b = lerpf(c0 & 0xFF, c1 & 0xFF, t);

        // brilho: manchas largas + ondulação média; menos contraste no fundo
        float calm = 1f - Math.min(depth, 48) / 80f;
        float big = vnoise(wx / 34f, wz / 34f) - 0.5f;
        float mid = vnoise(wx / 9f + 17.3f, wz / 9f - 5.1f) - 0.5f;
        float f = 1f + (big * 0.07f + mid * 0.03f) * (0.55f + calm * 0.45f);
        r *= f;
        g *= f;
        b *= f;

        // estrias de ondas na água rasa
        if (false) {
            float wave = (float) Math.sin((wx * 0.55f + wz * 0.32f) * 0.9f + big * 9f);
            float streak = Math.max(0f, wave - 0.86f) / 0.14f * (1f - depth / 22f);
            r += 22f * streak;
            g += 30f * streak;
            b += 34f * streak;
        }
        // espuma junto à costa
        if (depth <= 2) {
            float foam = depth == 0 ? 0.12f : depth == 1 ? 0.06f : 0.02f;
            r = lerpf(r, 0xB8, foam);
            g = lerpf(g, 0xE0, foam);
            b = lerpf(b, 0xEA, foam);
        }
        float grain = ((hash(wx, wz) & 7) - 3.5f) * 0.06f;
        return pack(r + grain, g + grain, b + grain * 1.2f);
    }

    /** Ruído de valor suave (0..1) em coordenadas já escaladas. */
    private static float vnoise(float x, float z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        float fx = x - xi, fz = z - zi;
        fx = fx * fx * (3f - 2f * fx);
        fz = fz * fz * (3f - 2f * fz);
        float a = (hash(xi, zi) & 0xFFFF) / 65535f;
        float b = (hash(xi + 1, zi) & 0xFFFF) / 65535f;
        float c = (hash(xi, zi + 1) & 0xFFFF) / 65535f;
        float d = (hash(xi + 1, zi + 1) & 0xFFFF) / 65535f;
        return lerpf(lerpf(a, b, fx), lerpf(c, d, fx), fz);
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
