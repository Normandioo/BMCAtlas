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

    /** The "bmcatlas:sync" channel: what the server's BmcWar plugin sends this mod (see ServerSync). The bytes are a JSON text. */
    record SyncPayload(byte[] data) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        static final Type<SyncPayload> TYPE = new Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(BmcAtlasClient.MOD_ID, "sync"));
        static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, SyncPayload> CODEC = net.minecraft.network.codec.StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.data()),
                buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new SyncPayload(b);
                });

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Listens on the "bmcatlas:sync" channel; the handler runs on the game thread with each message's bytes. */
    static void registerSync(java.util.function.Consumer<byte[]> handler) {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(SyncPayload.TYPE, SyncPayload.CODEC);   // the mod's version, back to the plugin
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> handler.accept(payload.data()));
    }

    /** A clickable link: underlined, opens the address, shows it on hover. */
    static Component link(String text, String url) {
        return Component.literal(text).withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url)))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(url))));
    }

    /** A chat line only this player sees: the text, then a clickable link. */
    static void chat(Minecraft mc, String text, String linkText, String url) {
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal(text).withStyle(net.minecraft.ChatFormatting.YELLOW).append(link(linkText, url)), false);
    }

    /** Sends bytes to the server on the "bmcatlas:sync" channel, when the server listens on it (it has the BmcWar plugin). */
    static void sendSync(byte[] data) {
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SyncPayload(data));
        }
    }
}
