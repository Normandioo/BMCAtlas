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
        List<Locality> localities;
        List<Zone> zones;
        Map<String, Player> players;
        WarData war;
        Network network;
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

    /** Cidade/localidade do site. */
    static final class Locality {
        String name, country, kind, development, lord, ethnicity;
        int x, z;
        List<String> designations;
        transient int idx, countryIdx = -1, tier;
        transient String iconKind;
    }

    /** Limite de captura de uma cidade (anéis par-ímpar, x0,z0,x1,z1,... em blocos). */
    static final class Zone {
        String name, country;
        List<double[]> rings;
        transient double[] box;   // minX, minZ, maxX, maxZ
        transient Object[] lods;           // contornos simplificados por nível de zoom (preenchido na tela)
        transient int countryIdx = -1;
    }

    static final class Purge {
        String realm;
        int population, days;
    }

    static final class Player {
        String ign, country, lastJoined, notes, skin, immune;
        List<String> current, affiliations;
        Purge purge;
    }

    static final class Member {
        String name, note;
        List<String> countries;
    }

    static final class Side {
        String name, color;
        List<Member> members;
    }

    static final class Captured {
        String name, country, state, holder, holderKey;
        int x, z;
        /** Lado que segura a cidade segundo o dynmap (guerra ao vivo); não vem do arquivo. */
        transient Integer liveSide;
    }

    /** Conexões entre localidades, pontes e estreitos (para o In Play da Lei 7). */
    static final class Network {
        Map<String, Link> links;
        List<List<Object>> edges;
    }

    static final class Link {
        String kind;
        boolean open;
        Integer x, z;
    }

    static final class WarInfo {
        String id, name, type, started, ended, notes;
        boolean live;
        String rule, theater;
        List<Side> sides;
        List<Captured> captured;
        /** Escolhas da staff para empates sobre terra neutra: pares de "Nome(País)". */
        List<List<String>> shortCircuits;
        Boolean shortCircuit;
        /** Capitais de suprimento: país -> chaves de cidade. */
        Map<String, List<String>> supplyCapitals;
        /** Ponte/estreito -> "open" / "closed", nesta guerra. */
        Map<String, String> bridges;
    }

    static final class AllianceInfo {
        String id, name, formed, officialName, color, notes, flag;
        List<Member> members;
    }

    static final class Truce {
        String a, b, from, until;
    }

    static final class Nap {
        String a, b, until;
    }

    static final class Ultimatum {
        String from, to, sent;
    }

    /** Diplolock registrado no site (Foundation ou Set by Staff), com fim opcional. */
    static final class Lock {
        String nation, kind, until;
    }

    static final class WarData {
        List<Lock> locks;
        List<WarInfo> wars;
        List<AllianceInfo> alliances;
        List<Truce> truces;
        List<Nap> naps;
        List<Ultimatum> ultimatums;
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
        /** Metade do lado do mapa do servidor, em blocos (0 = deduzir dos territórios; 2500 = mapa de 5000x5000). */
        int worldHalfSize = 0;
        /** Dynmap do servidor (o site encaminha /dynmap/): de onde vêm os jogadores online. Só leitura. */
        String dynmapUrl = "https://bmcmc.online/dynmap/";
        String dynmapWorld = "world";
        /** Milissegundos entre perguntas ao dynmap (só com o mapa aberto). Mínimo 1000. */
        int playersRefreshMs = 1000;
        boolean showPlayers = true;
        /** Guerras marcadas "ao vivo" no site: quem segura cada cidade vem dos marcadores do dynmap. */
        boolean liveWar = true;
        /** Espadas e bandeiras sobre as cidades em guerra. */
        boolean warMarkers = true;
        /** Lista de mapas do servidor (maps.json); vazio = maps.json na raiz do site. */
        String mapsUrl = "";
        /** Chave do mapa em uso; vazio = automático (pelo endereço do servidor, senão o primeiro da lista). */
        String currentMap = "";
        /** Mapas escritos aqui mesmo (mesmo formato do maps.json); valem junto com os da lista do servidor. */
        List<MapCatalog.Entry> maps = new ArrayList<>();
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
        Map<String, Integer> index = Map.of();
        // cidades, limites, jogadores e guerra
        List<Locality> localities = List.of();
        Locality[] cityOrder = new Locality[0];   // mais importantes primeiro
        List<Zone> zones = List.of();
        Map<String, Zone> zoneByKey = Map.of();
        Map<String, Player> players = Map.of();
        /** nome do jogador no site (minúsculo) -> país; nome no jogo (minúsculo) -> nome no site. */
        Map<String, Integer> countryOfPlayer = Map.of();
        Map<String, String> ignToSite = Map.of();
        WarData war;
        Network network;
        // posição do nome de cada país no mapa (calculada em segundo plano, ver LabelFit)
        volatile double[][] fit;
        boolean[] fitAsked;

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
    private static volatile String loadingUrl;
    private static volatile MapCatalog.Entry active;
    private static String lastServer = "";

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

    /** Pasta do territories.json (onde ficam flags/ e skins/). */
    /** Endereço do territories.json em uso: o do mapa escolhido, senão o de site.json. */
    static String url() {
        MapCatalog.Entry e = active;
        String u = e == null ? null : MapCatalog.territoriesUrl(e);
        return u != null && !u.isBlank() ? u : settings.url == null ? "" : settings.url;
    }

    static String mapsUrl() {
        return settings.mapsUrl;
    }

    static List<MapCatalog.Entry> localMaps() {
        return settings.maps == null ? List.of() : settings.maps;
    }

    /** O mapa em uso (null = o "url" de site.json, sem lista). */
    static MapCatalog.Entry activeMap() {
        return active;
    }

    static String baseUrl() {
        String u = url();
        int i = u.lastIndexOf('/');
        return i < 0 ? "" : u.substring(0, i + 1);
    }

    /** Raiz do site configurado em site.json (não do mapa escolhido): daqui sai o endereço padrão do maps.json. */
    static String configuredRootUrl() {
        try {
            URI u = URI.create(settings.url);
            return u.getScheme() + "://" + u.getAuthority() + "/";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Raiz do site (para caminhos como "gaia/flags/SC.png"). */
    static String rootUrl() {
        try {
            URI u = URI.create(url());
            return u.getScheme() + "://" + u.getAuthority() + "/";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Endereço completo de um arquivo citado pelo site; fromRoot = relativo à raiz do site, senão à pasta do arquivo. */
    static String assetUrl(String rel, boolean fromRoot) {
        if (rel == null || rel.isBlank()) return null;
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        return (fromRoot ? rootUrl() : baseUrl()) + rel;
    }

    static boolean showPlayers() {
        return settings.showPlayers;
    }

    static void setLiveWar(boolean v) {
        settings.liveWar = v;
        saveSettings();
    }

    static void setWarMarkers(boolean v) {
        settings.warMarkers = v;
        saveSettings();
    }

    static void setShowPlayers(boolean v) {
        settings.showPlayers = v;
        saveSettings();
    }

    static void saveSettings() {
        if (settingsFile == null) return;
        try {
            Files.writeString(settingsFile, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Hoi4MapClient.LOG.warn("Não foi possível gravar {}", settingsFile, e);
        }
    }

    /** O mapa vale para esta dimensão do mundo em que o jogador está? */
    static boolean dimensionApplies(String dim) {
        return MapCatalog.dimensionMatches(active, dim);
    }

    static boolean liveWar() {
        return settings.liveWar;
    }

    static boolean warMarkers() {
        return settings.warMarkers;
    }

    static String dynmapUrl() {
        MapCatalog.Entry e = active;
        String u = e != null && e.dynmapUrl != null ? e.dynmapUrl : settings.dynmapUrl;
        return u == null || u.isBlank() ? null : u.endsWith("/") ? u : u + "/";
    }

    static String dynmapWorld() {
        MapCatalog.Entry e = active;
        String w = e != null && e.dynmapWorld != null ? e.dynmapWorld : settings.dynmapWorld;
        return w == null || w.isBlank() ? "world" : w;
    }

    static int playersRefreshMs() {
        return Math.max(1000, settings.playersRefreshMs);
    }

    /** Limites do mapa do servidor em blocos {minX, minZ, maxX, maxZ}, ou null se não der para saber. */
    static int[] worldLimit() {
        int half = settings.worldHalfSize;
        Data d = data;
        if (half <= 0 && d != null) {
            int m = Math.max(Math.max(Math.abs(d.minX), Math.abs(d.minZ)), Math.max(d.maxX + 1, d.maxZ + 1));
            half = (int) Math.ceil(m / 100.0) * 100;
        }
        return half <= 0 ? null : new int[]{-half, -half, half - 1, half - 1};
    }

    /** Há dados do site e o jogador quer usá-los. */
    static boolean active() {
        return settings.enabled && useSite && data != null;
    }

    // ------------------------------------------------------------------- início

    private static Path dir;

    static void init() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("hoi4map");
        settingsFile = dir.resolve("site.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(settingsFile)) {
                Settings read = GSON.fromJson(Files.readString(settingsFile, StandardCharsets.UTF_8), Settings.class);
                if (read != null) settings = read;
            }
            if (settings.maps == null) settings.maps = new ArrayList<>();
            Files.writeString(settingsFile, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Hoi4MapClient.LOG.warn("Não foi possível ler {}", settingsFile, e);
        }
        if (!settings.enabled) {
            status = "desligado em site.json";
            return;
        }
        MapCatalog.init(dir);
        active = pick();
        cacheFile = cacheFileFor(active);
        loadCache();

        // site: agora e depois a cada refreshMinutes (a lista de mapas primeiro, que pode mudar o mapa em uso)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hoi4map-site");
            t.setDaemon(true);
            return t;
        });
        Runnable job = () -> {
            MapCatalog.refresh();
            reload();
        };
        if (settings.refreshMinutes > 0) {
            scheduler.scheduleWithFixedDelay(job, 0, settings.refreshMinutes, TimeUnit.MINUTES);
        } else {
            scheduler.execute(job);
        }
    }

    private static Path cacheFileFor(MapCatalog.Entry e) {
        return dir.resolve(e == null ? "territories_cache.json" : "territories_cache_" + e.key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }

    /** A cópia local do mapa em uso (e, no mapa padrão, a do outro mod de territórios), para funcionar offline. */
    private static void loadCache() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(cacheFile);
        if (active == null) candidates.add(FabricLoader.getInstance().getConfigDir().resolve("cumania_territories_cache.json"));
        for (Path p : candidates) {
            try {
                if (Files.exists(p)) {
                    apply(parse(Files.readString(p, StandardCharsets.UTF_8)), "cache");
                    return;
                }
            } catch (IOException | RuntimeException e) {
                Hoi4MapClient.LOG.warn("Cópia ilegível: {}", p, e);
            }
        }
    }

    /** O mapa a usar: o escolhido, senão o do servidor em que se está, senão o primeiro da lista (null = só o url de site.json). */
    private static MapCatalog.Entry pick() {
        MapCatalog.Entry e = MapCatalog.get(settings.currentMap);
        if (e != null || (settings.currentMap != null && !settings.currentMap.isBlank())) return e;
        e = MapCatalog.forServer(lastServer);
        if (e != null) return e;
        List<MapCatalog.Entry> list = MapCatalog.list();
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean sameMap(MapCatalog.Entry a, MapCatalog.Entry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.key.equals(b.key) && java.util.Objects.equals(MapCatalog.territoriesUrl(a), MapCatalog.territoriesUrl(b))
                && java.util.Objects.equals(a.dynmapWorld, b.dynmapWorld) && java.util.Objects.equals(a.dynmapUrl, b.dynmapUrl)
                && java.util.Objects.equals(a.dimension, b.dimension);
    }

    private static void activate(MapCatalog.Entry e) {
        if (sameMap(e, active)) {
            active = e;      // mesma coisa, objeto novo
            return;
        }
        active = e;
        cacheFile = cacheFileFor(e);
        data = null;
        version++;
        status = "carregando " + (e == null ? "" : e.name) + "...";
        OnlinePlayers.reset();
        DynmapMarkers.reset();
        WarMarkers.clear();
        loadCache();
        if (scheduler != null) scheduler.execute(SiteTerritories::reload);
    }

    /** Escolha do jogador (botão de mapas): grava em site.json e carrega o mapa. */
    static void selectMap(String key) {
        settings.currentMap = key == null ? "" : key;
        saveSettings();
        activate(pick());
    }

    /** Ao entrar num mundo: se o jogador não fixou um mapa, vale o que o servidor pede (maps.json "servers"). */
    static void serverChanged(String address) {
        String a = address == null ? "" : address;
        if (a.equals(lastServer)) return;
        lastServer = a;
        if (settings.currentMap == null || settings.currentMap.isBlank()) activate(pick());
    }

    /** A lista de mapas mudou (chegou o maps.json): reavalia qual usar. */
    static void catalogChanged() {
        activate(pick());
    }

    /** Baixa o arquivo em segundo plano; se for válido, troca os dados. */
    static void reload() {
        if (!settings.enabled) return;
        String url = url();
        if (url == null || url.isBlank() || url.equals(loadingUrl)) return;
        loadingUrl = url;
        Path cf = cacheFile;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "hoi4map-fabric-mod")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> {
                    try {
                        if (failure != null) throw new IOException(failure);
                        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                        Data d = parse(response.body());
                        if (!url.equals(url())) return;      // o jogador trocou de mapa no meio do download
                        if (cf != null) Files.writeString(cf, response.body(), StandardCharsets.UTF_8);
                        apply(d, "site");
                    } catch (IOException | RuntimeException e) {
                        if (data == null) status = "falhou: " + e;
                        Hoi4MapClient.LOG.warn("Não foi possível baixar {}: {}", url, e.toString());
                    } finally {
                        if (url.equals(loadingUrl)) loadingUrl = null;
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
        d.index = byId;
        d.fit = new double[nc][];
        d.fitAsked = new boolean[nc];
        d.war = f.war;
        d.network = f.network;
        parseExtras(d, f, byId);
        return d;
    }

    private static final java.util.regex.Pattern WAR_COLOUR = java.util.regex.Pattern.compile("(Red|Blue|Green|Contested|Uncontrolled)$");

    static int tierOf(String kind) {
        if (kind == null) return 0;
        if (kind.endsWith("Capital")) return 4;
        switch (kind) {
            case "Large": return 3;
            case "Medium": return 2;
            case "Small":
            case "Colonial":
            case "Fort": return 1;
            default: return 0;
        }
    }

    static String zoneKey(String name, String country) {
        return ((name == null ? "" : name) + "|" + (country == null ? "" : country)).toLowerCase(java.util.Locale.ROOT);
    }

    private static void parseExtras(Data d, FileModel f, Map<String, Integer> byId) {
        if (f.localities != null) {
            List<Locality> list = new ArrayList<>();
            for (Locality l : f.localities) {
                if (l == null || l.name == null) continue;
                if (l.kind != null) {
                    l.iconKind = l.kind;
                    l.kind = WAR_COLOUR.matcher(l.kind).replaceFirst("");
                }
                l.tier = tierOf(l.kind);
                Integer ci = l.country == null ? null : byId.get(l.country);
                l.countryIdx = ci == null ? -1 : ci;
                l.idx = list.size();
                list.add(l);
            }
            d.localities = list;
            Locality[] order = list.toArray(new Locality[0]);
            Arrays.sort(order, (a, b) -> a.tier != b.tier ? Integer.compare(b.tier, a.tier) : a.name.compareTo(b.name));
            d.cityOrder = order;
        }
        if (f.zones != null) {
            List<Zone> list = new ArrayList<>();
            Map<String, Zone> byKey = new HashMap<>();
            for (Zone z : f.zones) {
                if (z == null || z.rings == null || z.rings.isEmpty()) continue;
                double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (double[] r : z.rings) {
                    for (int i = 0; i + 1 < r.length; i += 2) {
                        minX = Math.min(minX, r[i]);
                        maxX = Math.max(maxX, r[i]);
                        minZ = Math.min(minZ, r[i + 1]);
                        maxZ = Math.max(maxZ, r[i + 1]);
                    }
                }
                z.box = new double[]{minX, minZ, maxX, maxZ};
                Integer ci = z.country == null ? null : byId.get(z.country);
                z.countryIdx = ci == null ? -1 : ci;
                list.add(z);
                byKey.put(zoneKey(z.name, z.country), z);
            }
            d.zones = list;
            d.zoneByKey = byKey;
        }
        if (f.players != null) d.players = f.players;
        Map<String, Integer> cop = new HashMap<>();
        for (int c = 0; c < d.people.length; c++) {
            if (d.people[c] == null) continue;
            for (String n : d.people[c]) cop.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), c);
        }
        Map<String, String> its = new HashMap<>();
        for (Map.Entry<String, Player> e : d.players.entrySet()) {
            its.putIfAbsent(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getKey());
            if (e.getValue() != null && e.getValue().ign != null) its.put(e.getValue().ign.toLowerCase(java.util.Locale.ROOT), e.getKey());
        }
        d.countryOfPlayer = cop;
        d.ignToSite = its;
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

    private static final java.util.concurrent.ConcurrentHashMap<String, String> CAMEL = new java.util.concurrent.ConcurrentHashMap<>();

    static String splitCamel(String s) {
        if (s == null) return null;
        String c = CAMEL.get(s);
        if (c == null) {
            c = splitCamelSlow(s);
            CAMEL.put(s, c);
        }
        return c;
    }

    private static String splitCamelSlow(String s) {
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

    /**
     * Preenche out (w x h) com o número da zona de captura (índice + 1, 0 = nenhuma) de cada bloco do retângulo
     * que começa em (x0, z0). Par-ímpar no centro do bloco; zonas mais abaixo na lista ficam por cima.
     * include (opcional) escolhe quais zonas entram; devolve se alguma tocou o retângulo.
     */
    static boolean rasterZones(Data d, int x0, int z0, int w, int h, short[] out, boolean[] include) {
        Arrays.fill(out, 0, w * h, (short) 0);
        boolean any = false;
        double[] xs = new double[64];
        for (int zi = 0; zi < d.zones.size(); zi++) {
            if (include != null && !include[zi]) continue;
            Zone z = d.zones.get(zi);
            double[] b = z.box;
            if (b[2] < x0 || b[0] >= x0 + w || b[3] < z0 || b[1] >= z0 + h) continue;
            short value = (short) (zi + 1);
            int zFrom = Math.max((int) Math.floor(b[1]), z0);
            int zTo = Math.min((int) Math.ceil(b[3]), z0 + h - 1);
            for (int zz = zFrom; zz <= zTo; zz++) {
                double zc = zz + 0.5;
                int cnt = 0;
                for (double[] r : z.rings) {
                    int len = r.length / 2;
                    for (int i = 0, j = len - 1; i < len; j = i++) {
                        double zi2 = r[2 * i + 1], zj = r[2 * j + 1];
                        if ((zi2 <= zc) == (zj <= zc)) continue;
                        double xi = r[2 * i], xj = r[2 * j];
                        if (cnt == xs.length) xs = Arrays.copyOf(xs, cnt * 2);
                        xs[cnt++] = xi + (zc - zi2) * (xj - xi) / (zj - zi2);
                    }
                }
                Arrays.sort(xs, 0, cnt);
                int row = (zz - z0) * w;
                for (int k = 0; k + 1 < cnt; k += 2) {
                    int from = (int) Math.ceil(xs[k] - 0.5);
                    int to = (int) Math.ceil(xs[k + 1] - 0.5) - 1;
                    for (int x = Math.max(from, x0); x <= Math.min(to, x0 + w - 1); x++) {
                        out[row + (x - x0)] = value;
                        any = true;
                    }
                }
            }
        }
        return any;
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
