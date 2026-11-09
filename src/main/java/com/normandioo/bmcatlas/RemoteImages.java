package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
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

    private static final Object PENDING = new Object();
    private static final Object FAILED = new Object();
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Queue<Ready> READY = new ConcurrentLinkedQueue<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int MAX_W = 96, MAX_H = 64;
    private static int counter;

    private RemoteImages() {}

    /** A bandeira desse endereço, ou null enquanto baixa (ou se não existir). */
    static Img flag(String url) {
        return get(url, false);
    }

    /** A vista frontal da skin desse endereço (16x32), ou null enquanto baixa. */
    static Img skin(String url) {
        return get(url, true);
    }

    /** Ícone pequeno (cidade, cara de jogador) reduzido para size x size com bordas suaves; null enquanto baixa. */
    static Img icon(String url, int size) {
        if (url == null || url.isBlank()) return null;
        String key = url + "@" + size;
        Object o = CACHE.get(key);
        if (o instanceof Img img) return img;
        if (o != null) return null;
        if (CACHE.putIfAbsent(key, PENDING) != null) return null;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20)).header("User-Agent", "bmcatlas-fabric-mod").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).whenComplete((response, error) -> {
            try {
                if (error != null) throw new RuntimeException(error);
                if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode());
                try (InputStream in = response.body(); NativeImage src = NativeImage.read(in)) {
                    READY.add(shrinkSoft(key, src, size));
                }
            } catch (Exception e) {
                BmcAtlasClient.LOG.debug("Sem imagem em {}: {}", url, e.toString());
                CACHE.put(key, FAILED);
            }
        });
        return null;
    }

    /** Verdadeiro se o download desse ícone falhou (não existe / sem rede). */
    static boolean failed(String url, int size) {
        return CACHE.get(url + "@" + size) == FAILED;
    }

    private static Img get(String url, boolean skin) {
        return get(url, skin ? 1 : 0);
    }

    private static Img get(String url, int kind) {
        if (url == null || url.isBlank()) return null;
        Object o = CACHE.get(url);
        if (o instanceof Img img) return img;
        if (o != null) return null;
        if (CACHE.putIfAbsent(url, PENDING) != null) return null;
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20)).header("User-Agent", "bmcatlas-fabric-mod").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()).whenComplete((response, error) -> {
            try {
                if (error != null) throw new RuntimeException(error);
                if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode());
                try (InputStream in = response.body(); NativeImage src = NativeImage.read(in)) {
                    READY.add(kind == 1 ? front(url, src) : shrink(url, src));
                }
            } catch (Exception e) {
                BmcAtlasClient.LOG.debug("Sem imagem em {}: {}", url, e.toString());
                CACHE.put(url, FAILED);
            }
        });
        return null;
    }

    /** Chamado no thread principal: cria as texturas das imagens que acabaram de baixar. */
    static void pump() {
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
                CACHE.put(r.url, new Img(id, r.w, r.h));
            } catch (RuntimeException e) {
                CACHE.put(r.url, FAILED);
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
        double scale = Math.min(1.0, Math.min((double) size / iw, (double) size / ih));
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
                if (a == 0) continue;
                out[y * w + x] = ((int) (a / n) << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
            }
        }
        return new Ready(url, w, h, out);
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
