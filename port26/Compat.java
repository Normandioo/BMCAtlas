package com.normandioo.bmcatlas;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/** Runtime shims so one jar runs on every 26.x release (the game moved a few APIs in 26.2 / 26.3). */
final class Compat {
    private Compat() {}

    private static Field screenField;
    private static Method screenGetter, setScreen, overlayMsg, hudHidden;
    private static Field hideGuiField, hudField;
    private static Method glfwMouse, sdlMouse;
    private static boolean init;

    private static void init() {
        if (init) return;
        init = true;
        Class<?> mcC = Minecraft.class;
        try { screenField = mcC.getField("screen"); } catch (Throwable ignored) {}
        try { setScreen = mcC.getMethod("setScreen", Screen.class); } catch (Throwable ignored) {}
        if (setScreen == null) try { setScreen = mcC.getMethod("setScreenAndShow", Screen.class); } catch (Throwable ignored) {}
        Class<?> gui = Minecraft.getInstance().gui.getClass();
        if (screenField == null) try { screenGetter = gui.getMethod("screen"); } catch (Throwable ignored) {}
        try { overlayMsg = gui.getMethod("setOverlayMessage", Component.class, boolean.class); } catch (Throwable ignored) {}
        if (overlayMsg == null) try {
            hudField = gui.getField("hud");
            overlayMsg = hudField.getType().getMethod("setOverlayMessage", Component.class, boolean.class);
        } catch (Throwable ignored) {}
        try { hideGuiField = Minecraft.getInstance().options.getClass().getField("hideGui"); } catch (Throwable ignored) {}
        if (hideGuiField == null) try {
            if (hudField == null) hudField = gui.getField("hud");
            hudHidden = hudField.getType().getMethod("isHidden");
        } catch (Throwable ignored) {}
        try {
            Class<?> g = Class.forName("org.lwjgl.glfw.GLFW");
            glfwMouse = g.getMethod("glfwGetMouseButton", long.class, int.class);
        } catch (Throwable ignored) {}
        if (glfwMouse == null) try {
            Class<?> s = Class.forName("org.lwjgl.sdl.SDLMouse");
            sdlMouse = s.getMethod("SDL_GetMouseState", float[].class, float[].class);
        } catch (Throwable ignored) {}
    }

    static Screen screen(Minecraft mc) {
        init();
        try {
            if (screenField != null) return (Screen) screenField.get(mc);
            if (screenGetter != null) return (Screen) screenGetter.invoke(mc.gui);
        } catch (Throwable ignored) {}
        return null;
    }

    static void setScreen(Minecraft mc, Screen s) {
        init();
        try { if (setScreen != null) setScreen.invoke(mc, s); } catch (Throwable ignored) {}
    }

    static void overlay(Minecraft mc, Component c) {
        init();
        try {
            Object target = hudField != null ? hudField.get(mc.gui) : mc.gui;
            if (overlayMsg != null) overlayMsg.invoke(target, c, false);
        } catch (Throwable ignored) {}
    }

    static boolean hideGui(Minecraft mc) {
        init();
        try {
            if (hideGuiField != null) return hideGuiField.getBoolean(mc.options);
            if (hudHidden != null) return (Boolean) hudHidden.invoke(hudField.get(mc.gui));
        } catch (Throwable ignored) {}
        return false;
    }

    /** Raw left mouse button state, polled from the window system (works while the cursor is grabbed). */
    static boolean leftDown(Minecraft mc) {
        init();
        try {
            if (glfwMouse != null) return (Integer) glfwMouse.invoke(null, mc.getWindow().handle(), 0) == 1;
            if (sdlMouse != null) return (((Number) sdlMouse.invoke(null, null, null)).intValue() & 1) != 0;
        } catch (Throwable ignored) {}
        return mc.mouseHandler.isLeftPressed();
    }

    static KeyMapping key(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, key, category));
    }

    static void face(GuiGraphicsExtractor g, AbstractClientPlayer player, int x, int y, int size) {
        PlayerFaceExtractor.extractRenderState(g, player.getSkin(), x, y, size);
    }

    /** Biome tint of a block, or -1. */
    static int tint(BlockState state, Level level, BlockPos pos) {
        java.util.List<net.minecraft.client.color.block.BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
        return sources.isEmpty() || !(level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter bt) ? -1 : sources.get(0).colorInWorld(state, bt, pos);
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
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);   // the mod's version, back to the plugin
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> handler.accept(payload.data()));
    }

    /** A clickable link: underlined, opens the address, shows it on hover. */
    static Component link(String text, String url) {
        return Component.literal(text).withStyle(s -> s.withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url)))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(url))));
    }

    /** A chat line only this player sees: the text, then a clickable link. Looked up at run time (the game moved it in 26.x). */
    static void chat(Minecraft mc, String text, String linkText, String url) {
        if (mc.player == null) return;
        Component line = Component.literal(text).withStyle(net.minecraft.ChatFormatting.YELLOW).append(link(linkText, url));
        try {
            Method m = mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class);
            m.invoke(mc.player, line, false);
            return;
        } catch (Throwable ignored) {}
        try {
            Method m = mc.player.getClass().getMethod("sendSystemMessage", Component.class);
            m.invoke(mc.player, line);
        } catch (Throwable ignored) {}
    }

    /** Sends bytes to the server on the "bmcatlas:sync" channel, when the server listens on it (it has the BmcWar plugin). */
    static void sendSync(byte[] data) {
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new SyncPayload(data));
        }
    }

    // ---- video export (plan phase 0: these are what the export will call; nothing calls them yet)

    private static Method mainTargetMc, mainTargetGame;
    private static boolean mainTargetLooked;

    /**
     * The image the game draws each frame into, interface included: what F2 saves. 26.1 keeps it in Minecraft
     * (getMainRenderTarget); 26.2 and 26.3 moved it to GameRenderer (mainRenderTarget), which is also what their F2 reads.
     */
    static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(Minecraft mc) {
        if (!mainTargetLooked) {
            mainTargetLooked = true;
            try { mainTargetMc = Minecraft.class.getMethod("getMainRenderTarget"); } catch (Throwable ignored) {}
            if (mainTargetMc == null) try { mainTargetGame = mc.gameRenderer.getClass().getMethod("mainRenderTarget"); } catch (Throwable ignored) {}
        }
        try {
            if (mainTargetMc != null) return (com.mojang.blaze3d.pipeline.RenderTarget) mainTargetMc.invoke(mc);
            if (mainTargetGame != null) return (com.mojang.blaze3d.pipeline.RenderTarget) mainTargetGame.invoke(mc.gameRenderer);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Reads the frame in the main target, asynchronously (the GPU copies it). The consumer owns the image and must close it. */
    static void grabFrame(Minecraft mc, java.util.function.Consumer<com.mojang.blaze3d.platform.NativeImage> done) {
        com.mojang.blaze3d.pipeline.RenderTarget target = mainTarget(mc);
        if (target != null) net.minecraft.client.Screenshot.takeScreenshot(target, done);
    }

    /**
     * Runs the task at the start of the next frame: the game runs its queued tasks before it draws again, so the frame drawn
     * now (with its interface) is complete in the target. schedule() always queues; execute() would run it at once.
     */
    static void afterFrame(Minecraft mc, Runnable task) {
        mc.schedule(task);
    }

    /** All the pixels in one array, as stored (ABGR), row by row. */
    static int[] pixels(com.mojang.blaze3d.platform.NativeImage image) {
        return image.getPixelsABGR();
    }
}
