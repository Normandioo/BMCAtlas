package com.normandioo.bmcatlas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the player when a newer version of the mod is out. Once per game start, the latest release is read from GitHub
 * (one GET to the public releases API, nothing is sent but the request); if it is newer than the installed one, a chat
 * message with a link to the download page shows once, the first time the player is in a world. Prefs.updateCheck
 * turns it off. Any failure (no connection, rate limit) is silent: the mod works the same.
 */
final class UpdateCheck {
    private UpdateCheck() {}

    static final String LATEST = "https://api.github.com/repos/Normandioo/BMCAtlas/releases/latest";
    static final String RELEASES_PAGE = "https://github.com/Normandioo/BMCAtlas/releases/latest";

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static volatile String latest;      // e.g. "1.2.5", set when the release is newer than ours
    private static volatile String page = RELEASES_PAGE;
    private static long shownAt;   // when the notice started (ms): each time the player joins a world, 0 before the first
    private static final long SHOW_MS = 30_000;

    /** Asks GitHub for the latest release, off the game thread. */
    static void start() {
        if (!Prefs.get().updateCheck) return;
        String mine = installedVersion();
        if (mine == null) return;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "bmcatlas-fabric-mod").header("Accept", "application/vnd.github+json").GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            try {
                if (error != null || response.statusCode() != 200) return;
                JsonObject o = JsonParser.parseString(response.body()).getAsJsonObject();
                String tag = o.has("tag_name") ? o.get("tag_name").getAsString() : null;
                String theirs = versionIn(tag);
                if (theirs != null && newer(theirs, mine)) {
                    if (o.has("html_url")) page = o.get("html_url").getAsString();
                    latest = theirs;
                }
            } catch (RuntimeException e) {
                BmcAtlasClient.LOG.debug("Could not read the latest release: {}", e.toString());
            }
        });
    }

    /** Called each time the player joins a world or server: the notice starts again, if there is a newer version. */
    static void joined() {
        if (latest != null && Prefs.get().updateCheck) shownAt = System.currentTimeMillis();
    }

    /** The notice across the top of the screen for SHOW_MS after it starts. */
    static void render(GuiGraphics g) {
        if (shownAt == 0 || latest == null || System.currentTimeMillis() - shownAt > SHOW_MS) return;
        Minecraft mc = Minecraft.getInstance();
        String one = "BMC Atlas " + latest + " is out (you have " + installedVersion() + ").";
        String two = "Download: " + page;
        int w = Math.max(mc.font.width(one), mc.font.width(two)) + 12, x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
        g.fill(x, 6, x + w, 28, 0xC0101214);
        g.drawString(mc.font, one, x + 6, 10, 0xFFF0EAD6, false);
        g.drawString(mc.font, two, x + 6, 19, 0xFFF0B429, false);
    }

    /** The installed version, without the build suffix ("1.2.4+mc26.x" -> "1.2.4"), or null. */
    static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(BmcAtlasClient.MOD_ID)
                .map(c -> versionIn(c.getMetadata().getVersion().getFriendlyString())).orElse(null);
    }

    /** The first "a.b.c" in a text ("v1.2.5-build.160" -> "1.2.5"), or null. */
    static String versionIn(String text) {
        if (text == null) return null;
        Matcher m = VERSION.matcher(text);
        return m.find() ? m.group(1) + "." + m.group(2) + "." + m.group(3) : null;
    }

    /** True when version a ("1.2.10") is newer than b ("1.2.9"). */
    static boolean newer(String a, String b) {
        Matcher x = VERSION.matcher(a), y = VERSION.matcher(b);
        if (!x.find() || !y.find()) return false;
        for (int i = 1; i <= 3; i++) {
            int p = Integer.parseInt(x.group(i)), q = Integer.parseInt(y.group(i));
            if (p != q) return p > q;
        }
        return false;
    }
}
