package com.normandioo.hoi4map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/** The small pixel pictures the war effects draw: a sword and a flag, each with a dark outline so they read over any colour. */
final class WarArt {
    private WarArt() {}

    record Art(int w, int h, int[] argb) {}
    interface Rects { void rect(int x0, int y0, int x1, int y1, int argb); }

    private static final int OUTLINE = 0xFF101010;

    /** Every cell as rectangles (a run of one colour along a row is one). */
    static void rects(Art a, int ox, int oy, Rects out) { rects(a, ox, oy, 1, out); }

    /** The same, each cell `scale` units wide. */
    static void rects(Art a, int ox, int oy, int scale, Rects out) {
        for (int row = 0; row < a.h(); row++) {
            int col = 0;
            while (col < a.w()) {
                int c = a.argb()[row * a.w() + col], end = col + 1;
                while (end < a.w() && a.argb()[row * a.w() + end] == c) end++;
                if (c != 0) out.rect(ox + col * scale, oy + row * scale, ox + end * scale, oy + (row + 1) * scale, c);
                col = end;
            }
        }
    }

    /** Puts a 1-cell outline round every filled cell, on a canvas with a 1-cell margin. */
    private static Art outlined(int w, int h, int[] cells) {
        int W = w + 2, H = h + 2;
        int[] out = new int[W * H];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out[(y + 1) * W + x + 1] = cells[y * w + x];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (out[y * W + x] != 0) continue;
            boolean next = (x > 0 && out[y * W + x - 1] != 0 && out[y * W + x - 1] != OUTLINE) || (x < W - 1 && out[y * W + x + 1] != 0 && out[y * W + x + 1] != OUTLINE)
                    || (y > 0 && out[(y - 1) * W + x] != 0 && out[(y - 1) * W + x] != OUTLINE) || (y < H - 1 && out[(y + 1) * W + x] != 0 && out[(y + 1) * W + x] != OUTLINE);
            if (next) out[y * W + x] = OUTLINE;
        }
        return new Art(W, H, out);
    }

    /** A minimal diagonal sword, tip at the top right. */
    static final Art SWORD = buildSword();

    private static Art buildSword() {
        int w = 9, h = 9;
        int[] c = new int[w * h];
        int blade = 0xFFE8EEF4, tip = 0xFFFFFFFF, gold = 0xFFE8B83C, grip = 0xFF8A5A2A;
        c[0 * w + 8] = tip;
        c[1 * w + 7] = blade; c[2 * w + 6] = blade; c[3 * w + 5] = blade; c[4 * w + 4] = blade;
        c[4 * w + 2] = gold; c[5 * w + 3] = gold; c[6 * w + 4] = gold;   // the guard
        c[6 * w + 2] = grip; c[7 * w + 1] = grip;
        c[8 * w + 0] = gold;                                              // the pommel
        return outlined(w, h, c);
    }

    /** A flag on a pole in the given colour; phase 0/1 ripples the banner. */
    static Art flag(int rgb, int phase) {
        int w = 9, h = 11;
        int[] c = new int[w * h];
        int body = 0xFF000000 | rgb;
        int shade = 0xFF000000 | darker(rgb);
        for (int y = 0; y < h; y++) c[y * w + 1] = 0xFFE4E4E4;   // the pole
        for (int x = 0; x < 6; x++) {
            int dy = ((x / 2) + phase) % 2;
            for (int y = 0; y < 4; y++) c[(y + dy) * w + 2 + x] = y == 3 ? shade : body;
        }
        return outlined(w, h, c);
    }

    private static int darker(int rgb) {
        int r = (rgb >> 16 & 255) * 7 / 10, g = (rgb >> 8 & 255) * 7 / 10, b = (rgb & 255) * 7 / 10;
        return (r << 16) | (g << 8) | b;
    }

    record Tex(Identifier id, int w, int h) {}

    private static final Map<String, Tex> TEXTURES = new HashMap<>();
    private static final Map<Long, Art> FLAGS = new HashMap<>();
    private static int counter;

    /** A bandeira dessa cor e fase, montada uma vez só. */
    static Art cachedFlag(int rgb, int phase) {
        return FLAGS.computeIfAbsent(((long) rgb << 1) | phase, k -> flag(rgb, phase));
    }

    /** A figura vira uma textura (criada uma vez): desenhar é um único blit em vez de dezenas de retângulos por quadro. */
    static Tex texture(Art a, String key) {
        Tex t = TEXTURES.get(key);
        if (t != null) return t;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, a.w(), a.h(), true);
        for (int y = 0; y < a.h(); y++) {
            for (int x = 0; x < a.w(); x++) img.setPixel(x, y, a.argb()[y * a.w() + x]);
        }
        DynamicTexture tex = new DynamicTexture(() -> "hoi4map war art", img);
        Identifier id = Identifier.fromNamespaceAndPath(Hoi4MapClient.MOD_ID, "art/a" + (counter++));
        Minecraft.getInstance().getTextureManager().register(id, tex);
        tex.upload();
        t = new Tex(id, a.w(), a.h());
        TEXTURES.put(key, t);
        return t;
    }
}
