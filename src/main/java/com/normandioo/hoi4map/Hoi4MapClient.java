package com.normandioo.hoi4map;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

        Countries.load();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null || mc.player == null) return;
            while (OPEN_KEY.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new MapScreen());
            }
            if (++ticks % 100 == 0) MapData.scan(mc);
            if (ticks % 2400 == 0 && MapData.isDirty()) MapData.save();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> MapData.unload());
    }
}
