package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
    private int ticks;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.bmcatlas.open", InputConstants.KEY_N, category));

        MINIMAP_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.bmcatlas.minimap", InputConstants.KEY_Z, category));

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
            while (MINIMAP_KEY.consumeClick()) {
                // o minimapa aumenta enquanto a tecla está segurada (ver abaixo)
            }
            Minimap.expanded = Compat.screen(mc) == null && MINIMAP_KEY.isDown();
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
