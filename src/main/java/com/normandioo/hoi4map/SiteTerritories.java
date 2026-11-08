package com.normandioo.hoi4map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Países e províncias do site (somente leitura): baixa o territories.json que o site já publica
 * (as províncias em coordenadas de bloco) e o coloca na grade de blocos do Minecraft.
 * Nada do site é alterado; é só um GET.
 *
 * Regra de bloco, igual à do mod de territórios: um bloco pertence à província que contém o centro
 * dele (x + 0.5, z + 0.5); províncias mais abaixo no arquivo ficam por cima das anteriores.
 */
final class SiteTerritories {

    // ----------------------------------------------------------- arquivo (JSON)

    private static final class FileModel {
        int format;
        String generated;
        Map<String, CountryJson> countries;
        List<ProvinceJson> provinces;
    }

    private static final class CountryJson {
        String name;
        String color;
        Map<String, String> info;
        List<String> population;
        Diplomacy diplomacy;
    }

    static final class WarRef {
        String name, side; // side: attacker / defender
    }

    static final class Diplomacy {
        List<String> overlord, subject, allies, nap, alliances;
        List<WarRef> wars;
    }

    private static final class ProvinceJson {
        String id;
        String country;
        List<int[]> rings;
    }

    /** config/hoi4map/site.json */
    static final class Settings {
        String url = "https://bmcmc.online/gaia/territories.json";
        int refreshMinutes = 30;
        boolean enabled = true;
    }

    // ------------------------------------------------------------------- dados

    /** Os territórios já preparados. Imutável depois de criado. */
    static final class Data {
        String generated = "";
        String[] ids, names, labels;
        int[] rgb;
        Map<String, String>[] info;
        Diplomacy[] diplomacy;
        List<String>[] people;
        int[] population;
        int[] provinceCount;
        // por província
        int n;
        int[] provCountry;
        String[] provLabel;
        List<int[]>[] rings;
        int[][] bounds; // minX, minZ, maxX, maxZ
        // por país: centro e variâncias em blocos, para o nome no mapa
        double[][] shape; // {mx, mz, vxx, vzz, vxz, area}
        int minX, minZ, maxX, maxZ;

        int countryOfProvince(int province) {
            return province < 0 ? -1 : provCountry[province];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static volatile Data data;
    private static volatile int version;
    private static volatile String status = "sem dados";
    private static Settings settings = new Settings();
    private static Path settingsFile, cacheFile;
    private static ScheduledExecutorService scheduler;
    private static final AtomicBoolean LOADING = new AtomicBoolean();

    /** O jogador pode desligar o uso do site e voltar ao modo manual (botão do painel). */
    static volatile boolean useSite = true;

    private SiteTerritories() {}

    static Data data() {
        return data;
    }

    static int version() {
        return version;
    }

    static String status() {
        return status;
    }

    /** Há dados do site e o jogador quer usá-los. */
    static boolean active() {
        return settings.enabled && useSite && data != null;
    }

    // ------------------------------------------------------------------- início

    static void init() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("hoi4map");
        settingsFile = dir.resolve("site.json");
        cacheFile = dir.resolve("territories_cache.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(settingsFile)) {
                Settings read = GSON.fromJson(Files.readString(settingsFile, StandardCharsets.UTF_8), Settings.class);
                if (read != null) settings = read;
            }
            Files.writeString(settingsFile, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Hoi4MapClient.LOG.warn("Não foi possível ler {}", settingsFile, e);
        }
        if (!settings.enabled) {
            status = "desligado em site.json";
            return;
        }

        // 1) cópia local: a nossa, ou a do outro mod (Cumania Territories), para funcionar offline
        Path other = FabricLoader.getInstance().getConfigDir().resolve("cumania_territories_cache.json");
        for (Path p : new Path[]{cacheFile, other}) {
            try {
                if (Files.exists(p)) {
                    apply(parse(Files.readString(p, StandardCharsets.UTF_8)), "cache");
                    break;
                }
            } catch (IOException | RuntimeException e) {
                Hoi4MapClient.LOG.warn("Cópia ilegível: {}", p, e);
            }
        }

        // 2) site: agora e depois a cada refreshMinutes
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hoi4map-site");
            t.setDaemon(true);
            return t;
        });
        if (settings.refreshMinutes > 0) {
            scheduler.scheduleWithFixedDelay(SiteTerritories::reload, 0, settings.refreshMinutes, TimeUnit.MINUTES);
        } else {
            scheduler.execute(SiteTerritories::reload);
        }
    }

    /** Baixa o arquivo em segundo plano; se for válido, troca os dados. */
    static void reload() {
        if (!settings.enabled || settings.url == null || settings.url.isBlank()) return;
        if (!LOADING.compareAndSet(false, true)) return;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(settings.url))
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "hoi4map-fabric-mod")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> {
                    try {
                        if (failure != null) throw new IOException(failure);
                        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                        Data d = parse(response.body());
                        Files.writeString(cacheFile, response.body(), StandardCharsets.UTF_8);
                        apply(d, "site");
                    } catch (IOException | RuntimeException e) {
                        if (data == null) status = "falhou: " + e;
                        Hoi4MapClient.LOG.warn("Não foi possível baixar {}: {}", settings.url, e.toString());
                    } finally {
                        LOADING.set(false);
                    }
                });
    }

    private static void apply(Data d, String source) {
        data = d;
        version++;
        status = source + ": " + d.ids.length + " países, " + d.n + " províncias";
        Hoi4MapClient.LOG.info("Territórios ({}): {} países, {} províncias {}", source, d.ids.length, d.n, d.generated);
    }

    // ------------------------------------------------------------------ leitura

    @SuppressWarnings("unchecked")
    private static Data parse(String json) {
        FileModel f = GSON.fromJson(json, FileModel.class);
        if (f == null || f.countries == null || f.provinces == null) throw new IllegalArgumentException("não é um territories.json");
        if (f.format != 1) throw new IllegalArgumentException("formato " + f.format + " não suportado");

        Data d = new Data();
        d.generated = f.generated == null ? "" : f.generated;
        int nc = f.countries.size();
        d.ids = new String[nc];
        d.names = new String[nc];
        d.labels = new String[nc];
        d.rgb = new int[nc];
        d.info = new Map[nc];
        d.diplomacy = new Diplomacy[nc];
        d.people = new List[nc];
        d.population = new int[nc];
        Map<String, Integer> byId = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, CountryJson> e : f.countries.entrySet()) {
            CountryJson c = e.getValue();
            d.ids[i] = e.getKey();
            d.names[i] = c == null || c.name == null || c.name.isBlank() ? e.getKey() : c.name;
            d.rgb[i] = c == null ? 0xFF888888 : Countries.parseColor(c.color);
            d.info[i] = c == null || c.info == null ? new LinkedHashMap<>() : c.info;
            d.population[i] = c == null || c.population == null ? 0 : c.population.size();
            d.people[i] = c == null || c.population == null ? List.of() : c.population;
            d.diplomacy[i] = c == null ? null : c.diplomacy;
            String disp = d.info[i].get("displayName");
            d.labels[i] = disp != null && !disp.isBlank() ? disp : splitCamel(e.getKey());
            byId.put(e.getKey(), i);
            i++;
        }

        List<ProvinceJson> provs = new ArrayList<>();
        for (ProvinceJson p : f.provinces) {
            if (p != null && p.rings != null && !p.rings.isEmpty() && byId.containsKey(p.country)) provs.add(p);
        }
        int n = provs.size();
        d.n = n;
        d.provCountry = new int[n];
        d.provLabel = new String[n];
        d.rings = new List[n];
        d.bounds = new int[n][];
        d.provinceCount = new int[nc];
        d.minX = Integer.MAX_VALUE;
        d.minZ = Integer.MAX_VALUE;
        d.maxX = Integer.MIN_VALUE;
        d.maxZ = Integer.MIN_VALUE;
        double[] w = new double[nc], sx = new double[nc], sz = new double[nc];
        double[] sxx = new double[nc], szz = new double[nc], sxz = new double[nc];
        for (int k = 0; k < n; k++) {
            ProvinceJson p = provs.get(k);
            d.provCountry[k] = byId.get(p.country);
            d.provLabel[k] = label(p.id, p.country);
            d.rings[k] = p.rings;
            int[] b = bounds(p.rings);
            d.bounds[k] = b;
            d.minX = Math.min(d.minX, b[0]);
            d.minZ = Math.min(d.minZ, b[1]);
            d.maxX = Math.max(d.maxX, b[2]);
            d.maxZ = Math.max(d.maxZ, b[3]);
            int c = d.provCountry[k];
            d.provinceCount[c]++;

            // momentos do país: cada província conta como uma caixa com o peso da sua área
            double bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
            double area = Math.abs(signedArea(p.rings));
            if (area < 1) area = bw * bh * 0.5;
            double cx = (b[0] + b[2] + 1) / 2.0, cz = (b[1] + b[3] + 1) / 2.0;
            w[c] += area;
            sx[c] += area * cx;
            sz[c] += area * cz;
            sxx[c] += area * (cx * cx + bw * bw / 12.0);
            szz[c] += area * (cz * cz + bh * bh / 12.0);
            sxz[c] += area * cx * cz;
        }
        d.shape = new double[nc][];
        for (int c = 0; c < nc; c++) {
            if (w[c] <= 0) {
                d.shape[c] = null;
                continue;
            }
            double mx = sx[c] / w[c], mz = sz[c] / w[c];
            d.shape[c] = new double[]{mx, mz, sxx[c] / w[c] - mx * mx, szz[c] / w[c] - mz * mz, sxz[c] / w[c] - mx * mz, w[c]};
        }
        if (n == 0) throw new IllegalArgumentException("sem províncias");
        return d;
    }

    private static double signedArea(List<int[]> rings) {
        double total = 0;
        for (int[] r : rings) {
            int len = r.length / 2;
            double a = 0;
            for (int i = 0, j = len - 1; i < len; j = i++) {
                a += (double) r[2 * j] * r[2 * i + 1] - (double) r[2 * i] * r[2 * j + 1];
            }
            total += a / 2.0;
        }
        return total;
    }

    private static int[] bounds(List<int[]> rings) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] r : rings) {
            for (int i = 0; i + 1 < r.length; i += 2) {
                minX = Math.min(minX, r[i]);
                maxX = Math.max(maxX, r[i]);
                minZ = Math.min(minZ, r[i + 1]);
                maxZ = Math.max(maxZ, r[i + 1]);
            }
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    private static String splitCamel(String s) {
        return s.replaceAll("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])|(?<=[A-Z])(?=[A-Z][a-z])", " ").trim();
    }

    /** "SynorWestern64Province" no país "Synor" vira "Western 64". */
    private static String label(String id, String country) {
        String s = id == null ? "" : id;
        if (s.startsWith(country) && s.length() > country.length()) s = s.substring(country.length());
        if (s.endsWith("Province") && s.length() > "Province".length()) s = s.substring(0, s.length() - "Province".length());
        return s.replaceAll("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])|(?<=[A-Z])(?=[A-Z][a-z])", " ").trim();
    }

    // ------------------------------------------------------------------- grade

    /**
     * Preenche out (w x h) com o número da província (índice + 1, 0 = ninguém) de cada bloco
     * do retângulo que começa em (x0, z0). Preenchimento par-ímpar, testando o centro do bloco.
     */
    static void raster(Data d, int x0, int z0, int w, int h, short[] out) {
        Arrays.fill(out, 0, w * h, (short) 0);
        double[] xs = new double[64];
        for (int pi = 0; pi < d.n; pi++) {
            int[] b = d.bounds[pi];
            if (b[2] < x0 || b[0] >= x0 + w || b[3] < z0 || b[1] >= z0 + h) continue;
            List<int[]> rings = d.rings[pi];
            short value = (short) (pi + 1);
            int zFrom = Math.max(b[1], z0);
            int zTo = Math.min(b[3], z0 + h - 1);
            for (int z = zFrom; z <= zTo; z++) {
                double zc = z + 0.5;
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
                int row = (z - z0) * w;
                for (int k = 0; k + 1 < cnt; k += 2) {
                    int from = (int) Math.ceil(xs[k] - 0.5);
                    int to = (int) Math.ceil(xs[k + 1] - 0.5) - 1;
                    for (int x = Math.max(from, x0); x <= Math.min(to, x0 + w - 1); x++) out[row + (x - x0)] = value;
                }
            }
        }
    }

    /** Província que contém o bloco (teste exato do polígono; a mais "de cima" vence), ou -1. */
    static int provinceAt(Data d, int x, int z) {
        double px = x + 0.5, pz = z + 0.5;
        for (int i = d.n - 1; i >= 0; i--) {
            int[] b = d.bounds[i];
            if (x < b[0] || x > b[2] || z < b[1] || z > b[3]) continue;
            boolean inside = false;
            for (int[] r : d.rings[i]) {
                int len = r.length / 2;
                for (int a = 0, c = len - 1; a < len; c = a++) {
                    double za = r[2 * a + 1], zc = r[2 * c + 1];
                    if ((za > pz) != (zc > pz) && px < (r[2 * c] - r[2 * a]) * (pz - za) / (zc - za) + r[2 * a]) inside = !inside;
                }
            }
            if (inside) return i;
        }
        return -1;
    }
}
