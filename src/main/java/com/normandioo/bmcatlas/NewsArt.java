package com.normandioo.bmcatlas;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The news art (assets/bmcatlas/textures/gui/news, from art/gazeta/png/mod of the BmcWar repo): frames drawn as 9-slices, icons, separator. */
final class NewsArt {
    private NewsArt() {}

    static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath(BmcAtlasClient.MOD_ID, "textures/gui/news/" + name + ".png");
    }

    /** A square texture of side size whose border b stays as drawn while its edges and middle stretch to w x h. */
    static void nine(GuiGraphics g, String name, int size, int b, int x, int y, int w, int h) {
        Identifier id = tex(name);
        int m = size - 2 * b, iw = w - 2 * b, ih = h - 2 * b;
        part(g, id, size, x, y, b, b, 0, 0, b, b);
        part(g, id, size, x + w - b, y, b, b, size - b, 0, b, b);
        part(g, id, size, x, y + h - b, b, b, 0, size - b, b, b);
        part(g, id, size, x + w - b, y + h - b, b, b, size - b, size - b, b, b);
        part(g, id, size, x + b, y, iw, b, b, 0, m, b);
        part(g, id, size, x + b, y + h - b, iw, b, b, size - b, m, b);
        part(g, id, size, x, y + b, b, ih, 0, b, b, m);
        part(g, id, size, x + w - b, y + b, b, ih, size - b, b, b, m);
        part(g, id, size, x + b, y + b, iw, ih, b, b, m, m);
    }

    private static void part(GuiGraphics g, Identifier id, int size, int x, int y, int w, int h, int u, int v, int uw, int vh) {
        if (w > 0 && h > 0) g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, uw, vh, size, size);
    }

    /** A picture inside the image frame; (x, y, w, h) is the picture itself, the frame goes 3 px around it. */
    static void framed(GuiGraphics g, RemoteImages.Img pic, int x, int y, int w, int h) {
        nine(g, "image_frame", 12, 3, x - 3, y - 3, w + 6, h + 6);
        if (pic != null) g.blit(RenderPipelines.GUI_TEXTURED, pic.id(), x, y, 0f, 0f, w, h, pic.w(), pic.h(), pic.w(), pic.h());
    }

    /** The 16x16 icon of a kind of news. */
    static void icon(GuiGraphics g, String type, int x, int y) {
        String t = type == null ? "" : type;
        String name = switch (t) {
            case "breaking" -> "icon_breaking";
            case "post" -> "icon_post";
            case "war", "peace", "alliance", "truce", "nap", "ultimatum", "vassal", "independence" -> "type_" + t;
            case "alliance-join", "alliance-leave", "alliance-end" -> "type_" + t.replace('-', '_');
            default -> "icon_post";
        };
        g.blit(RenderPipelines.GUI_TEXTURED, tex(name), x, y, 0f, 0f, 16, 16, 16, 16);
    }

    /** The empty-list icon (16x16). */
    static void empty(GuiGraphics g, int x, int y) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex("icon_empty"), x, y, 0f, 0f, 16, 16, 16, 16);
    }

    /** The dashed separator, w wide (3 px tall). */
    static void sep(GuiGraphics g, int x, int y, int w) {
        for (int i = 0; i < w; i += 5) g.blit(RenderPipelines.GUI_TEXTURED, tex("sep"), x + i, y, 0f, 0f, Math.min(5, w - i), 3, 5, 3);
    }

    /** The map bar's News button (14x14), with the mark when there is unread news. */
    static void button(GuiGraphics g, boolean unread, int x, int y) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex(unread ? "news_btn_unread" : "news_btn"), x, y, 0f, 0f, 14, 14, 14, 14);
    }
}
