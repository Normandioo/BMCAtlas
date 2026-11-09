package com.normandioo.bmcatlas;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

/**
 * The few game calls that differ between Minecraft versions. This is the 1.21.11 version; the 26.x build swaps this
 * file for port26/Compat.java (see tools/port26.py), so the rest of the mod is the same source for every version.
 */
final class Compat {
    private Compat() {}

    static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    static void setScreen(Minecraft mc, Screen s) {
        mc.setScreen(s);
    }

    static void overlay(Minecraft mc, Component c) {
        mc.gui.setOverlayMessage(c, false);
    }

    static boolean hideGui(Minecraft mc) {
        return mc.options.hideGui;
    }

    /** Raw left mouse button state, polled from the window system (works while the cursor is grabbed). */
    static boolean leftDown(Minecraft mc) {
        return GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    static KeyMapping key(String name, int key, KeyMapping.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, key, category));
    }

    static void face(GuiGraphics g, AbstractClientPlayer player, int x, int y, int size) {
        PlayerFaceRenderer.draw(g, player.getSkin(), x, y, size);
    }

    /** Biome tint of a block, or -1. */
    static int tint(BlockState state, Level level, BlockPos pos) {
        return Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
    }

    static void setClipboard(Minecraft mc, String text) {
        mc.keyboardHandler.setClipboard(text);
    }
}
