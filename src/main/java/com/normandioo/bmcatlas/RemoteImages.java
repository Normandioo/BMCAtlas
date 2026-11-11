package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bandeiras e skins que o site publica, baixadas sob demanda (somente leitura) e viradas em texturas.
 * A skin vira uma vista frontal de 16x32 pixels (cabeça, corpo, braços e pernas, com a segunda camada).
 */
final class RemoteImages {
    record Img(Identifier id, int w, int h) {}

    private record Ready(String url, int w, int h, int[] argb) {}

    /** A texture in use, with the last time somebody asked for it. */
    private static final class Entry {
        final Img img;
        volatile long used;

        Entry(Img img) {
            this.img = img;
            this.used = System.currentTimeMillis();
        }
    }

    /** A download that failed: tried again after retryAt (each failure waits longer). */
    private record Failed(long retryAt, int count) {}

    private interface Converter {
        Ready apply(NativeImage image);
    }

    private static final Object PENDING = new Object();
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Queue<Ready> READY = new ConcurrentLinkedQueue<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ExecutorService WORK = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "bmcatlas-images");
        t.setDaemon(true);
        return t;
    });
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int MAX_TEXTURES = 300;
    private static final int MAX_W = 96, MAX_H = 64;
    private static int counter;
    private static int pumps;
    private static Path cacheDir;

    private RemoteImages() {}

    /** A bandeira desse endereço, ou null enquanto baixa (ou se não existir). */
    static Img flag(String url) {
        return get(url, url, image -> shrink(url, image));
    }

    /** A vista frontal da skin desse endereço (16x32), ou null enquanto baixa. */
    static Img skin(String url) {
        return get(url, url, image -> front(url, image));
    }

    /** Ícone pequeno (cidade, cara de jogador) reduzido para size x size com bordas suaves; null enquanto baixa. */
    static Img icon(String url, int size) {
        if (url == null) return null;
        String key = url + "@" + size;
        return get(url, key, image -> shrinkSoft(key, image, size));
    }

    /** Verdadeiro se o download desse ícone falhou (não existe / sem rede). */
    static boolean failed(String url, int size) {
        return CACHE.get(url + "@" + size) instanceof Failed;
    }

    private static Img get(String url, String key, Converter conv) {
        if (url == null || url.isBlank()) return null;
        Object o = CACHE.get(key);
        if (o instanceof Entry e) {
            e.used = System.currentTimeMillis();
            return e.img;
        }
        int fails = 0;
        if (o instanceof Failed f) {
            if (System.currentTimeMillis() < f.retryAt) return null;
            if (!CACHE.replace(key, f, PENDING)) return null;
            fails = f.count;
        } else if (o != null || CACHE.putIfAbsent(key, PENDING) != null) {
            return null;
        }
        final int failsSoFar = fails;
        try {
            WORK.execute(() -> fetch(url, key, failsSoFar, conv));
        } catch (RuntimeException e) {
            CACHE.put(key, new Failed(Long.MAX_VALUE, failsSoFar + 1));
        }
        return null;
    }

    // ------------------------------------------------------------ disk cache

    // Each image is kept as downloaded in config/bmcatlas/cache/img. A copy checked less than a day ago (6 hours for
    // player faces, which change with the skin) is used with no request at all; an older one is confirmed with a
    // conditional request (the site answers "not modified" without sending the image again). If the site does not
    // answer, the old copy is used.

    /** The downloaded copy of an image on disk (as the site sent it), or null when there is none yet. Used by WarRecorder for the faces. */
    static Path cachedOnDisk(String url) {
        Path f = url == null ? null : cacheFile(url);
        return f != null && Files.exists(f) ? f : null;
    }

    private static synchronized Path cacheFile(String url) {
        try {
            if (cacheDir == null) {
                cacheDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("bmcatlas").resolve("cache").resolve("img");
                Files.createDirectories(cacheDir);
            }
            byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return cacheDir.resolve(sb + ".img");
        } catch (Exception e) {
            return null;
        }
    }

    private static void decode(String key, byte[] bytes, Converter conv) throws Exception {
        try (NativeImage src = NativeImage.read(new ByteArrayInputStream(bytes))) {
            READY.add(conv.apply(src));
        }
    }

    private static void fail(String url, String key, int fails, boolean missing, Object why) {
        BmcAtlasClient.LOG.debug("No image at {}: {}", url, why);
        long wait = missing ? 30L * 60_000 : Math.min(10L * 60_000, 30_000L << Math.min(fails, 5));
        CACHE.put(key, new Failed(System.currentTimeMillis() + wait, fails + 1));
    }

    private static void fetch(String url, String key, int fails, Converter conv) {
        Path file = cacheFile(url);
        byte[] cached = null;
        long checked = 0;
        try {
            if (file != null && Files.exists(file)) {
                cached = Files.readAllBytes(file);
                checked = Files.getLastModifiedTime(file).toMillis();
            }
        } catch (IOException e) {
            cached = null;
        }
        long ttl = url.contains("/faces/") ? DAY / 4 : DAY;
        if (cached != null && System.currentTimeMillis() - checked < ttl) {
            try {
                decode(key, cached, conv);
                return;
            } catch (Exception e) {
                cached = null;      // broken copy: download again
            }
        }
        final byte[] old = cached;
        HttpRequest.Builder req;
        try {
            req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "bmcatlas-fabric-mod").GET();
            if (old != null) req.header("If-Modified-Since", HTTP_DATE.format(Instant.ofEpochMilli(checked)));
        } catch (RuntimeException e) {
            fail(url, key, fails, true, e);
            return;
        }
        HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofByteArray()).whenComplete((response, error) -> {
            try {
                int code = error != null ? -1 : response.statusCode();
                if (code == 200) {
                    decode(key, response.body(), conv);
                    if (file != null) {
                        Path tmp = file.resolveSibling(file.getFileName() + "." + System.nanoTime() + ".tmp");
                        Files.write(tmp, response.body());
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return;
                }
                if (old != null && (code == 304 || code == -1 || code >= 500)) {
                    decode(key, old, conv);
                    if (code == 304) Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                    return;
                }
                fail(url, key, fails, code == 404 || code == 403 || code == 410, error != null ? error : "HTTP " + code);
            } catch (Exception e) {
                if (CACHE.get(key) == PENDING) fail(url, key, fails, false, e);
            }
        });
    }

    /** Chamado no thread principal: cria as texturas das imagens que acabaram de baixar. */
    static void pump() {
        if (++pumps % 1200 == 0) releaseUnused();
        for (int i = 0; i < 3; i++) {
            Ready r = READY.poll();
            if (r == null) return;
            try {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, r.w, r.h, true);
                for (int y = 0; y < r.h; y++) {
                    for (int x = 0; x < r.w; x++) img.setPixel(x, y, r.argb[y * r.w + x]);
                }
                DynamicTexture tex = new DynamicTexture(() -> "bmcatlas remote image", img);
                Identifier id = Identifier.fromNamespaceAndPath(BmcAtlasClient.MOD_ID, "img/i" + (counter++));
                Minecraft.getInstance().getTextureManager().register(id, tex);
                tex.upload();
                CACHE.put(r.url, new Entry(new Img(id, r.w, r.h)));
            } catch (RuntimeException e) {
                CACHE.put(r.url, new Failed(System.currentTimeMillis() + 10L * 60_000, 1));
            }
        }
    }

    /** With many textures alive, the ones nobody asked for in the last 5 minutes are released (they come back from the disk cache). */
    private static void releaseUnused() {
        int alive = 0;
        for (Object o : CACHE.values()) if (o instanceof Entry) alive++;
        if (alive <= MAX_TEXTURES) return;
        long limit = System.currentTimeMillis() - 5L * 60_000;
        for (Map.Entry<String, Object> e : CACHE.entrySet()) {
            if (e.getValue() instanceof Entry en && en.used < limit && CACHE.remove(e.getKey(), en)) {
                Minecraft.getInstance().getTextureManager().release(en.img.id());
            }
        }
    }

    /** Reduz a imagem (média por área) para no máximo MAX_W x MAX_H, mantendo a proporção. */
    private static Ready shrink(String url, NativeImage image) {
        int iw = image.getWidth(), ih = image.getHeight();
        double scale = Math.min(1.0, Math.min((double) MAX_W / iw, (double) MAX_H / ih));
        int w = Math.max(1, (int) Math.round(iw * scale)), h = Math.max(1, (int) Math.round(ih * scale));
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = x * iw / w, x1 = Math.max(x0 + 1, (x + 1) * iw / w);
                int y0 = y * ih / h, y1 = Math.max(y0 + 1, (y + 1) * ih / h);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = image.getPixel(xx, yy), pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 255) * pa;
                        g += ((p >> 8) & 255) * pa;
                        b += (p & 255) * pa;
                        n++;
                    }
                }
                if (a == 0 || a / n < 128) continue;
                out[y * w + x] = 0xFF000000 | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
            }
        }
        return new Ready(url, w, h, out);
    }

    /** Reduz um ícone mantendo o alfa (bordas suaves); não amplia. */
    private static Ready shrinkSoft(String url, NativeImage image, int size) {
        int iw = image.getWidth(), ih = image.getHeight();
        // reduz com média por área; se o ícone original for menor que o pedido, amplia por vizinho mais próximo
        // (bordas nítidas em vez de borradas). O tamanho pedido é em pixels da tela, não em pixels da interface.
        double scale = Math.min((double) size / iw, (double) size / ih);
        int w = Math.max(1, (int) Math.round(iw * scale)), h = Math.max(1, (int) Math.round(ih * scale));
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = x * iw / w, x1 = Math.max(x0 + 1, (x + 1) * iw / w);
                int y0 = y * ih / h, y1 = Math.max(y0 + 1, (y + 1) * ih / h);
                x1 = Math.min(x1, iw);
                y1 = Math.min(y1, ih);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = image.getPixel(xx, yy), pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 255) * pa;
                        g += ((p >> 8) & 255) * pa;
                        b += (p & 255) * pa;
                        n++;
                    }
                }
                if (a == 0) continue;
                out[y * w + x] = ((int) (a / n) << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
            }
        }
        return new Ready(url, w, h, out);
    }

    /** Extra scale of the screen being drawn (the map sets it to its menu scale), so icons stay one texel per screen pixel. */
    static float extraScale = 1f;

    /** Desenha a imagem pixel a pixel da tela, centrada em (cx, cy) da interface, sem borrar. */
    static void drawCrisp(net.minecraft.client.gui.GuiGraphics g, Img img, double cx, double cy) {
        double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale()) * extraScale;
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        g.pose().scale((float) (1.0 / gs), (float) (1.0 / gs));
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, img.id(), -img.w() / 2, -img.h() / 2, 0f, 0f, img.w(), img.h(), img.w(), img.h(), img.w(), img.h());
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------- skins

    private static Ready front(String url, NativeImage skin) {
        if (skin.getWidth() != 64 || (skin.getHeight() != 64 && skin.getHeight() != 32)) throw new IllegalArgumentException("not a skin");
        boolean modern = skin.getHeight() == 64;
        int[] out = new int[16 * 32];
        part(skin, out, 8, 8, 8, 8, 4, 0, false);                 // cabeça
        part(skin, out, 20, 20, 8, 12, 4, 8, false);              // corpo
        part(skin, out, 44, 20, 4, 12, 0, 8, false);              // braço direito
        part(skin, out, 4, 20, 4, 12, 4, 20, false);              // perna direita
        if (modern) {
            part(skin, out, 36, 52, 4, 12, 12, 8, false);         // braço esquerdo
            part(skin, out, 20, 52, 4, 12, 8, 20, false);         // perna esquerda
        } else {
            part(skin, out, 44, 20, 4, 12, 12, 8, true);          // skins antigas espelham os membros
            part(skin, out, 4, 20, 4, 12, 8, 20, true);
        }
        part(skin, out, 40, 8, 8, 8, 4, 0, false);                // chapéu
        if (modern) {
            part(skin, out, 20, 36, 8, 12, 4, 8, false);          // jaqueta
            part(skin, out, 44, 36, 4, 12, 0, 8, false);
            part(skin, out, 4, 36, 4, 12, 4, 20, false);
            part(skin, out, 52, 52, 4, 12, 12, 8, false);
            part(skin, out, 4, 52, 4, 12, 8, 20, false);
        }
        return new Ready(url, 16, 32, out);
    }

    private static void part(NativeImage skin, int[] out, int sx, int sy, int w, int h, int dx, int dy, boolean mirror) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = skin.getPixel(sx + (mirror ? w - 1 - x : x), sy + y);
                if ((p >>> 24) < 128) continue;
                out[(dy + y) * 16 + dx + x] = 0xFF000000 | (p & 0xFFFFFF);
            }
        }
    }
}
