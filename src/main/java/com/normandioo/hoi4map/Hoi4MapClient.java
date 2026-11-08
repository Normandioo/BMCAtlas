package com.normandioo.hoi4map;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hoi4MapClient implements ClientModInitializer {
    public static final String MOD_ID = "hoi4map";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static KeyMapping OPEN_KEY;
    private int ticks;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_KEY = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.hoi4map.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));

        Prefs.load();
        Countries.load();
        SiteTerritories.init();

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "minimap"), (graphics, tickCounter) -> Minimap.render(graphics, tickCounter.getGameTimeDeltaPartialTick(false)));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ChunkGen.tick(mc);
            if (mc.level == null || mc.player == null) return;
            while (OPEN_KEY.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new MapScreen());
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
