package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BmcAtlasClient implements ClientModInitializer {
    public static final String MOD_ID = "bmcatlas";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping OPEN_KEY;
    public static KeyMapping MINIMAP_KEY;
    public static KeyMapping ZOOM_IN_KEY;
    public static KeyMapping ZOOM_OUT_KEY;
    private int ticks;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_KEY = Compat.key("key.bmcatlas.open", InputConstants.KEY_N, category);

        MINIMAP_KEY = Compat.key("key.bmcatlas.minimap", InputConstants.KEY_Z, category);

        ZOOM_IN_KEY = Compat.key("key.bmcatlas.minimap_zoom_in", InputConstants.KEY_EQUALS, category);
        ZOOM_OUT_KEY = Compat.key("key.bmcatlas.minimap_zoom_out", InputConstants.KEY_MINUS, category);

        Prefs.load();
        Countries.load();
        SiteTerritories.init();

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "minimap"), (graphics, tickCounter) -> Minimap.render(graphics, tickCounter.getGameTimeDeltaPartialTick(false)));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ChunkGen.tick(mc);
            if (mc.level == null || mc.player == null) return;
            while (OPEN_KEY.consumeClick()) {
                if (Compat.screen(mc) == null) Compat.setScreen(mc, new MapScreen());
            }
            boolean free = Compat.screen(mc) == null;
            if (Prefs.get().minimapHold) {       // enlarged while the key is held
                while (MINIMAP_KEY.consumeClick()) {
                    // nothing: the key state below decides
                }
                Minimap.expanded = free && MINIMAP_KEY.isDown();
            } else {                             // the key switches it
                while (MINIMAP_KEY.consumeClick()) if (free) Minimap.expanded = !Minimap.expanded;
            }
            int zoom = 0;
            while (ZOOM_IN_KEY.consumeClick()) zoom++;
            while (ZOOM_OUT_KEY.consumeClick()) zoom--;
            if (zoom != 0 && free) {
                Prefs.Data p = Prefs.get();
                p.minimapZoomPct = Math.max(50, Math.min(400, p.minimapZoomPct + zoom * 25));
                Prefs.save();
            }
            MapData.tick(mc);
            RemoteImages.pump();
            if (++ticks % 6000 == 0 && MapData.isDirty()) MapData.save();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            ChunkGen.reset();
            MapData.unload();
        });
    }
}
