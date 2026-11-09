package com.normandioo.bmcatlas;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Os marcadores do dynmap (tiles/_markers_/marker_&lt;mundo&gt;.json): o ícone de cada cidade diz quem a segura numa
 * guerra ao vivo. Um GET só de leitura, feito só enquanto uma guerra ao vivo está sendo olhada.
 */
final class DynmapMarkers {
    record Marker(String id, String label, String icon, double x, double z) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile List<Marker> markers = List.of();
    private static volatile long markersAt;
    private static long lastPoll;
    private static volatile int failures;
    private static volatile long wanted;

    private DynmapMarkers() {}

    static List<Marker> markers() {
        return markers;
    }

    /** Quando a última leitura chegou (0 = nunca); muda a cada resposta, serve para invalidar caches. */
    static long markersAt() {
        return markersAt;
    }

    static boolean known() {
        return markersAt != 0 && System.currentTimeMillis() - markersAt < 120_000;
    }

    /** Uma guerra ao vivo está sendo olhada: mantém os marcadores frescos (a cada ~3 s). */
    static void want() {
        wanted = System.currentTimeMillis();
    }

    /** Chamado a cada quadro da tela do mapa. */
    static void tick() {
        long now = System.currentTimeMillis();
        // 3 s with the map open, 6 s for the minimap; doubles after each error (up to 1 min); nothing while unfocused
        long every = now - OnlinePlayers.mapOpenAt < 1000 ? 3000 : 6000;
        if (failures > 0) every = Math.min(60_000L, every << Math.min(failures, 4));
        if (now - wanted > 3000 || now - lastPoll < every || !net.minecraft.client.Minecraft.getInstance().isWindowActive()) return;
        String base = SiteTerritories.dynmapUrl();
        if (base == null || !BUSY.compareAndSet(false, true)) return;
        lastPoll = now;
        String url = base + "tiles/_markers_/marker_" + SiteTerritories.dynmapWorld() + ".json";
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "bmcatlas-fabric-mod").header("Cache-Control", "no-cache").GET().build(),
                HttpResponse.BodyHandlers.ofString()).whenComplete((response, err) -> {
            try {
                if (err != null || response.statusCode() != 200) throw new RuntimeException(err != null ? err.toString() : "HTTP " + response.statusCode());
                markers = parse(response.body());
                markersAt = System.currentTimeMillis();
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                BmcAtlasClient.LOG.debug("Marcadores do dynmap sem resposta: {}", e.toString());
            } finally {
                BUSY.set(false);
            }
        });
    }

    static void reset() {
        markers = List.of();
        markersAt = 0;
        lastPoll = 0;
        wanted = 0;
        failures = 0;
    }

    static String clean(String raw) {
        return raw == null ? "" : raw.replaceAll("<[^>]*>", "").replaceAll("§.", "").replaceAll("&[0-9a-fk-or]", "").trim();
    }

    static List<Marker> parse(String json) {
        List<Marker> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("sets") || !root.get("sets").isJsonObject()) return List.of();
        for (Map.Entry<String, JsonElement> set : root.getAsJsonObject("sets").entrySet()) {
            if (!set.getValue().isJsonObject() || !set.getValue().getAsJsonObject().has("markers")) continue;
            JsonObject ms = set.getValue().getAsJsonObject().getAsJsonObject("markers");
            for (Map.Entry<String, JsonElement> m : ms.entrySet()) {
                if (!m.getValue().isJsonObject()) continue;
                JsonObject o = m.getValue().getAsJsonObject();
                try {
                    out.add(new Marker(m.getKey(), clean(str(o, "label")), str(o, "icon"), num(o, "x"), num(o, "z")));
                } catch (RuntimeException ignored) {
                    // marcador ilegível: ignora
                }
            }
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
    }
}
