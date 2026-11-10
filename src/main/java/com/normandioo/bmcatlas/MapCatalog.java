package com.normandioo.bmcatlas;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

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
import java.util.List;
import java.util.Locale;

/**
 * A lista de mapas de um servidor (um mundo, uma dimensão, outra temporada...): qual territories.json cada um tem, qual
 * é o mundo dele no dynmap e em que dimensão vive. Vem de um "maps.json" publicado pelo servidor (endereço em
 * site.json, "mapsUrl"; vazio = maps.json na raiz do site), com cópia local; entradas escritas direto em site.json
 * ("maps") também valem. Nada aqui é de um servidor específico: sem lista, existe um único mapa, o do "url" de site.json.
 */
final class MapCatalog {
    private MapCatalog() {}

    static final class Entry {
        String key, name;
        /** Endereço do territories.json, absoluto ou relativo ao maps.json. */
        String territories;
        /** Mundo no dynmap; vazio = o de site.json. */
        String dynmapWorld;
        /** Outro dynmap para este mapa; vazio = o de site.json. */
        String dynmapUrl;
        /** "overworld" (padrão), "nether", "end" ou "any" (qualquer dimensão que não seja Nether nem End). "config" vale como overworld. */
        String dimension = "overworld";
        /** Endereços de servidor em que este é o mapa a mostrar (opcional). */
        List<String> servers = new ArrayList<>();
    }

    private static final class FileModel {
        int version;
        List<Entry> maps = new ArrayList<>();
    }

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static volatile List<Entry> downloaded = List.of();
    private static volatile String source = "none";
    private static volatile String error = "";
    private static Path cache;

    static String error() {
        return error;
    }

    static String source() {
        return source;
    }

    static void init(Path configDir) {
        cache = configDir.resolve("maps_cache.json");
        try {
            if (Files.exists(cache)) {
                downloaded = parse(Files.readString(cache, StandardCharsets.UTF_8));
                source = "local copy";
            }
        } catch (IOException | RuntimeException e) {
            BmcAtlasClient.LOG.warn("Unreadable copy of the map list: {}", e.toString());
        }
    }

    /** Os mapas disponíveis: os de site.json primeiro, depois os do catálogo do servidor. Pode ser vazia. */
    static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : SiteTerritories.localMaps()) {
            if (valid(e)) out.add(e);
        }
        for (Entry e : downloaded) {
            boolean dup = false;
            for (Entry o : out) dup |= o.key.equalsIgnoreCase(e.key);
            if (!dup) out.add(e);
        }
        return out;
    }

    static Entry get(String key) {
        if (key == null || key.isBlank()) return null;
        for (Entry e : list()) if (e.key.equalsIgnoreCase(key)) return e;
        return null;
    }

    /** O mapa que quer este endereço de servidor, ou null. */
    static Entry forServer(String address) {
        if (address == null || address.isBlank()) return null;
        String host = address.toLowerCase(Locale.ROOT).trim();
        for (Entry e : list()) {
            if (e.servers == null) continue;
            for (String s : e.servers) if (s != null && !s.isBlank() && host.equals(s.toLowerCase(Locale.ROOT).trim())) return e;
        }
        return null;
    }

    private static boolean valid(Entry e) {
        if (e == null || e.key == null || e.key.isBlank() || e.territories == null || e.territories.isBlank()) return false;
        if (e.name == null || e.name.isBlank()) e.name = e.key;
        if (e.dynmapWorld != null && e.dynmapWorld.isBlank()) e.dynmapWorld = null;
        if (e.dynmapUrl != null && e.dynmapUrl.isBlank()) e.dynmapUrl = null;
        if (e.dimension == null || e.dimension.isBlank()) e.dimension = "overworld";
        if (e.servers == null) e.servers = new ArrayList<>();
        return true;
    }

    /** Onde está o maps.json: o "mapsUrl" de site.json, senão maps.json na raiz do site. */
    static String catalogUrl() {
        String u = SiteTerritories.mapsUrl();
        if (u != null && !u.isBlank()) return u;
        String root = SiteTerritories.configuredRootUrl();
        return root.isEmpty() ? null : root + "maps.json";
    }

    /** O endereço do territories.json de um mapa, resolvido contra o do catálogo quando é relativo. */
    static String territoriesUrl(Entry e) {
        String t = e.territories;
        if (t == null || t.isBlank()) return null;
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        String base = catalogUrl();
        if (base == null) return t;
        try {
            return URI.create(base).resolve(t).toString();
        } catch (RuntimeException ex) {
            return t;
        }
    }

    /** Se este mapa vale para a dimensão do mundo em que o jogador está (texto de ResourceKey). */
    static boolean dimensionMatches(Entry e, String dim) {
        String want = e == null || e.dimension == null ? "overworld" : e.dimension.toLowerCase(Locale.ROOT);
        String d = dim == null ? "" : dim.toLowerCase(Locale.ROOT);
        return switch (want) {
            case "nether" -> d.contains("nether");
            case "end" -> d.contains("the_end");
            case "any" -> !d.contains("nether") && !d.contains("the_end");
            default -> d.contains("overworld");
        };
    }

    /** Baixa o maps.json em segundo plano; se for bom, troca a lista em uso. */
    static void refresh() {
        String url = catalogUrl();
        if (url == null || url.isBlank()) return;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "bmcatlas-fabric-mod").GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> {
                    try {
                        if (failure != null) throw new IOException(failure);
                        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                        List<Entry> list = parse(response.body());
                        if (cache != null) Files.writeString(cache, response.body(), StandardCharsets.UTF_8);
                        boolean same = GSON.toJson(list).equals(GSON.toJson(downloaded));
                        downloaded = list;
                        source = "server";
                        error = "";
                        if (!same) SiteTerritories.catalogChanged();
                    } catch (IOException | RuntimeException e) {
                        error = e.getMessage() == null ? e.toString() : e.getMessage();
                        // sem catálogo (404 etc.) é normal: o servidor só tem um mapa
                        BmcAtlasClient.LOG.debug("No map list at {}: {}", url, error);
                    }
                });
    }

    /** Lê o maps.json; qualquer defeito (não é JSON, sem mapas, entrada sem key/territories) recusa o arquivo todo. */
    static List<Entry> parse(String json) {
        FileModel file;
        try {
            file = GSON.fromJson(json, FileModel.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("maps.json is not valid JSON: " + e.getMessage());
        }
        if (file == null || file.maps == null || file.maps.isEmpty()) throw new IllegalArgumentException("maps.json lists no maps");
        List<Entry> out = new ArrayList<>();
        for (Entry e : file.maps) {
            if (!valid(e)) throw new IllegalArgumentException("a map in maps.json has no key or no territories address");
            out.add(e);
        }
        return List.copyOf(out);
    }
}
