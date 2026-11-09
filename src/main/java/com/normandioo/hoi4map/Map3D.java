package com.normandioo.hoi4map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.stream.IntStream;

/**
 * O mapa em relevo dentro do jogo: junta o {@link Relief} (que desenha por software) com as regiões de MapData.
 * Desenha numa imagem do tamanho da tela (vezes a resolução escolhida) e a estica por cima do mapa.
 * A mesma câmera serve para desenhar, projetar pontos (ícones, limites) e achar o bloco sob o mouse.
 */
final class Map3D {
    private Map3D() {}

    /** Giro (rad; 0 = norte para cima) e inclinação (rad; 1,57 = de cima). */
    static double yaw = 0.0, pitch = 0.95;

    private static final int BG = 0xFF0B1628;

    private static NativeImage img;
    private static DynamicTexture tex;
    private static Identifier id;
    private static int rw, rh;
    private static int[] frame = new int[0];

    private static Relief cam;
    private static double q = 1.0;              // pixels da imagem por pixel da interface
    private static double guiW, guiH;
    private static double ty = Double.NaN;      // altura do alvo, suavizada
    private static Relief.Plan plan;

    static double projX, projY;
    static boolean projOk;

    private static final Relief.Source SRC = new Relief.Source() {
        @Override
        public int[] tex(int rx, int rz, int level) {
            MapData.Region r = MapData.region(rx, rz, false);
            return r == null ? null : r.colours(level);
        }

        @Override
        public short[] hgt(int rx, int rz, int level) {
            MapData.Region r = MapData.region(rx, rz, false);
            return r == null ? null : r.heights(level);
        }
    };

    /** Posiciona a câmera olhando para (tx, tz); scale = pixels da interface por bloco. */
    static void setup(double tx, double tz, double scale, int guiWidth, int guiHeight) {
        guiW = guiWidth;
        guiH = guiHeight;
        q = Math.max(0.3, Prefs.get().quality3dPct / 100.0 * Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale()));
        rw = Math.max(64, (int) (guiW * q));
        rh = Math.max(64, (int) (guiH * q));
        double target = Relief.heightY(SRC, tx, tz);
        ty = Double.isNaN(ty) || Math.abs(ty - target) > 60 ? target : ty + (target - ty) * 0.2;
        cam = new Relief(rw, rh, tx, tz, ty, scale * q, yaw, pitch, Prefs.get().height3dPct / 100.0);
        plan = cam.plan();
    }

    /** As regiões que o quadro vai ler: {rx0, rz0, rx1, rz1}. */
    static int[] regions() {
        return new int[]{Math.floorDiv(plan.x0, Relief.REGION), Math.floorDiv(plan.z0, Relief.REGION),
                Math.floorDiv(plan.x1, Relief.REGION), Math.floorDiv(plan.z1, Relief.REGION)};
    }

    /** Onde o bloco (bx, bz) aparece na tela (projX, projY), na altura do terreno. */
    static void project(double bx, double bz) {
        if (cam == null) {
            projOk = false;
            projX = projY = -9999;
            return;
        }
        double[] p = new double[3];
        cam.project(bx, Relief.heightY(SRC, bx, bz), bz, p);
        projOk = true;
        projX = p[0] / q;
        projY = p[1] / q;
    }

    /** O bloco (x, z) do terreno sob um pixel da tela. */
    static double[] pick(double sx, double sy) {
        if (cam == null) return null;
        return cam.pick(sx * q, sy * q, SRC);
    }

    /** Quanto o mundo anda (x, z) quando o mapa é arrastado dx, dy pixels da tela. */
    static double[] drag(double dx, double dy) {
        if (cam == null) return new double[]{dx, dy};
        return cam.drag(dx * q, dy * q);
    }

    /** Desenha o relevo na tela inteira. */
    static void render(GuiGraphics g, int guiWidth, int guiHeight) {
        if (cam == null) return;
        if (img == null || rw != img.getWidth() || rh != img.getHeight()) {
            if (tex != null) Minecraft.getInstance().getTextureManager().release(id);
            img = new NativeImage(NativeImage.Format.RGBA, rw, rh, true);
            tex = new DynamicTexture(() -> "hoi4map relief", img);
            id = Identifier.fromNamespaceAndPath(Hoi4MapClient.MOD_ID, "relief");
            Minecraft.getInstance().getTextureManager().register(id, tex);
            frame = new int[rw * rh];
        }
        // as reduções (mipmaps) são feitas aqui, antes de os threads do desenho lerem
        for (int rx = Math.floorDiv(plan.x0, Relief.REGION); rx <= Math.floorDiv(plan.x1, Relief.REGION); rx++) {
            for (int rz = Math.floorDiv(plan.z0, Relief.REGION); rz <= Math.floorDiv(plan.z1, Relief.REGION); rz++) {
                MapData.Region r = MapData.region(rx, rz, false);
                if (r == null) continue;
                r.heights(plan.lh);
                r.colours(plan.lt);
            }
        }
        cam.render(frame, SRC, BG);
        final int w = rw;
        IntStream.range(0, rh).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) img.setPixel(x, y, frame[y * w + x]);
        });
        tex.upload();
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, guiWidth, guiHeight, rw, rh, rw, rh);
    }
}
