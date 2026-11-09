package com.normandioo.bmcatlas;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
}
