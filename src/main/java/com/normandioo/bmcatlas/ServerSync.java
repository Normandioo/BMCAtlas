package com.normandioo.bmcatlas;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the server's BmcWar plugin tells this mod on the "bmcatlas:sync" channel, so the mod does not have to keep asking:
 * who holds each city of the live wars, the bridges the staff opened or closed, and which site data the server uses.
 * While the map on screen is covered by it, the war markers are not read from dynmap and the site is not checked every
 * half hour (territories.json is downloaded once when the server says the site changed). On a server without the plugin
 * nothing arrives and the mod asks dynmap and the site as before. Only read here: the mod never sends anything back.
 *
 * Messages (JSON, written by the plugin's SyncMessages):
 *   {"t":"hello","v":1}
 *   {"t":"map","v":1,"map":"gaia","site":"https://.../territories.json","generated":"...",
 *    "wars":[{"id":"w1","cities":{"name(country)":0,...},"bridges":{"link":true,...}}]}
 *   cities: side holding it (0, 1, 2), -2 contested, -3 uncontrolled; a city not listed is held by its own country.
 */
final class ServerSync {
    private ServerSync() {}

    static final int CONTESTED = -2, UNCONTROLLED = -3;

    /** One war as the server sees it: city key -> holder, and link key (lower case) -> open. */
    record WarState(Map<String, Integer> cities, Map<String, Boolean> bridges) {}

    /** One map: its key and territories.json address, the site data the server uses, and its live wars by id. */
    record MapState(String key, String site, String generated, Map<String, WarState> wars) {}

    private static volatile boolean hello;
    private static final Map<String, MapState> MAPS = new ConcurrentHashMap<>();
    private static volatile long version;
    /** The site data already asked for because of the server, so the same change is not downloaded twice. */
    private static volatile String reloadedFor;

    /** Called with each message from the server (on the game thread). Anything unreadable is ignored. */
    static void receive(byte[] data) {
        JsonObject o;
        try {
            o = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            BmcAtlasClient.LOG.debug("Unreadable message from the server's war plugin: {}", e.toString());
            return;
        }
        String type = str(o, "t");
        if ("hello".equals(type)) {
            hello = true;
            version++;
            replyOnce();
            return;
        }
        if (!"map".equals(type)) return;
        MapState m = parseMap(o);
        if (m == null) return;
        hello = true;
        MAPS.put(m.key().toLowerCase(Locale.ROOT), m);
        version++;
        siteMaybeChanged(m);
    }

    private static MapState parseMap(JsonObject o) {
        String key = str(o, "map");
        if (key == null) return null;
        Map<String, WarState> wars = new HashMap<>();
        if (o.has("wars") && o.get("wars").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("wars")) {
                if (!e.isJsonObject()) continue;
                JsonObject w = e.getAsJsonObject();
                String id = str(w, "id");
                if (id == null) continue;
                Map<String, Integer> cities = new HashMap<>();
                if (w.has("cities") && w.get("cities").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> c : w.getAsJsonObject("cities").entrySet()) {
                        try {
                            cities.put(c.getKey().toLowerCase(Locale.ROOT), c.getValue().getAsInt());
                        } catch (RuntimeException ignored) {
                            // not a number: skip that city
                        }
                    }
                }
                Map<String, Boolean> bridges = new HashMap<>();
                if (w.has("bridges") && w.get("bridges").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> b : w.getAsJsonObject("bridges").entrySet()) {
                        try {
                            bridges.put(b.getKey().toLowerCase(Locale.ROOT), b.getValue().getAsBoolean());
                        } catch (RuntimeException ignored) {
                            // not true/false: skip that bridge
                        }
                    }
                }
                wars.put(id, new WarState(Map.copyOf(cities), Map.copyOf(bridges)));
            }
        }
        String site = str(o, "site");
        String generated = str(o, "generated");
        return new MapState(key, site == null ? "" : site, generated == null ? "" : generated, Map.copyOf(wars));
    }

    /** The server uses newer site data than ours: download territories.json once (only for the map on screen). */
    private static void siteMaybeChanged(MapState m) {
        if (m.generated().isEmpty() || m != current()) return;
        SiteTerritories.Data d = SiteTerritories.data();
        if (d == null || m.generated().equals(d.generated) || m.generated().equals(reloadedFor)) return;
        reloadedFor = m.generated();
        SiteTerritories.reload();
    }

    /** Sends bytes back to the server (set by BmcAtlasClient; null in tests). */
    static volatile java.util.function.Consumer<byte[]> sender;
    private static volatile boolean replied;

    /**
     * Tells the server's plugin, once per connection, this mod's version and whether it tells the player about updates itself,
     * so the plugin can warn a player whose mod is out of date (and leave alone one that does it itself). Only sent to a server
     * that said hello, that is one with the plugin.
     */
    private static void replyOnce() {
        if (replied || sender == null) return;
        String mine = UpdateCheck.installedVersion();
        if (mine == null) return;
        replied = true;
        sender.accept(helloReply(mine, Prefs.get().updateCheck).getBytes(StandardCharsets.UTF_8));
    }

    /** {"t":"hello","v":1,"mod":"1.2.6","selfCheck":true} */
    static String helloReply(String modVersion, boolean selfCheck) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "hello");
        o.addProperty("v", 1);
        o.addProperty("mod", modVersion);
        o.addProperty("selfCheck", selfCheck);
        return o.toString();
    }

    /** Leaving the server: forget everything it said. */
    static void reset() {
        replied = false;
        hello = false;
        MAPS.clear();
        reloadedFor = null;
        version++;
    }

    /** The server's state of the map on screen: matched by its territories.json address, else by the map's key. Null when none. */
    static MapState current() {
        if (!hello || MAPS.isEmpty()) return null;
        String url = SiteTerritories.url();
        for (MapState m : MAPS.values()) if (!m.site().isEmpty() && m.site().equalsIgnoreCase(url)) return m;
        MapCatalog.Entry e = SiteTerritories.activeMap();
        return e == null ? null : MAPS.get(e.key.toLowerCase(Locale.ROOT));
    }

    /** True while the server's plugin covers the map on screen: the mod then does not ask dynmap or the site by itself. */
    static boolean active() {
        return current() != null;
    }

    /** A war of the map on screen as the server sees it, or null when the server does not run it. */
    static WarState war(String warId) {
        MapState m = current();
        return m == null || warId == null ? null : m.wars().get(warId);
    }

    /** Changes with every message, so caches built from it know when to start over. */
    static long version() {
        return version;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
