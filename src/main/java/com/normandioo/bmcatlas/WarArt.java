package com.normandioo.bmcatlas;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.HashMap;
import java.util.Map;

/** Pixel-art images used by the war effects and the map bar: each one is uploaded once as a texture. */
final class WarArt {
    private WarArt() {}

    record Art(int w, int h, int[] argb) {}

    record Tex(Identifier id, int w, int h) {}

    private static final Map<String, Tex> TEXTURES = new HashMap<>();
    private static int counter;

    /** The figure becomes a texture (created once): drawing it is one blit instead of dozens of rectangles per frame. */
    static Tex texture(Art a, String key) {
        Tex t = TEXTURES.get(key);
        if (t != null) return t;
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, a.w(), a.h(), true);
        for (int y = 0; y < a.h(); y++) {
            for (int x = 0; x < a.w(); x++) img.setPixel(x, y, a.argb()[y * a.w() + x]);
        }
        DynamicTexture tex = new DynamicTexture(() -> "bmcatlas war art", img);
        Identifier id = Identifier.fromNamespaceAndPath(BmcAtlasClient.MOD_ID, "art/a" + (counter++));
        Minecraft.getInstance().getTextureManager().register(id, tex);
        tex.upload();
        t = new Tex(id, a.w(), a.h());
        TEXTURES.put(key, t);
        return t;
    }
}
