package com.normandioo.hoi4map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Mapa em 3D: um renderizador de campo de alturas (por colunas, de perto para longe) feito no processador.
 * Usa a altura de cada bloco guardada em MapData e as cores já pintadas (países, limites, guerra...), e permite
 * girar e inclinar livremente. O resultado é uma imagem pequena que a tela estica por cima do mapa.
 */
final class Map3D {
    private Map3D() {}

    /** Giro (rad; 0 = olhando para o norte) e inclinação (rad; 0 = horizontal, 1.57 = de cima). */
    static double yaw = 0.0, pitch = 0.95;

    private static final double QUALITY = 0.4;     // pixels da imagem por pixel da tela
    private static final int GRID = 5;             // regiões em cada direção do alvo que entram no desenho
    private static final int BG = 0xFF0B1628;
    private static final int UNKNOWN_H = 126;      // y 62 quando a região ainda não tem alturas

    private static NativeImage img;
    private static DynamicTexture tex;
    private static Identifier id;
    private static int rw, rh;
    private static int[] frame = new int[0];

    // câmera deste quadro
    private static double camX, camY, camZ, fx, fy, fz, rx, rz, ux, uy, uz, focalGui, guiW, guiH;
    private static double targetY, camDistH;
    private static MapData.Region[] cache = new MapData.Region[(2 * GRID + 1) * (2 * GRID + 1)];
    private static int cacheRx, cacheRz;

    /** Posiciona a câmera olhando para o ponto (tx, tz); scale = pixels da tela por bloco no ponto. */
    static void setup(double tx, double tz, double scale, int guiWidth, int guiHeight) {
        guiW = guiWidth;
        guiH = guiHeight;
        focalGui = 0.9 * guiWidth;
        cacheRx = Math.floorDiv((int) Math.floor(tx), MapData.REGION_PX);
        cacheRz = Math.floorDiv((int) Math.floor(tz), MapData.REGION_PX);
        for (int dx = -GRID; dx <= GRID; dx++) {
            for (int dz = -GRID; dz <= GRID; dz++) {
                cache[(dz + GRID) * (2 * GRID + 1) + dx + GRID] = MapData.region(cacheRx + dx, cacheRz + dz, false);
            }
        }
        targetY = heightAt((int) Math.floor(tx), (int) Math.floor(tz)) - 64.0;
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double fhx = Math.sin(yaw), fhz = -Math.cos(yaw);
        double dist = focalGui / Math.max(0.05, scale);
        camDistH = dist * cp;
        camX = tx - fhx * cp * dist;
        camZ = tz - fhz * cp * dist;
        camY = targetY + sp * dist;
        fx = cp * fhx;
        fy = -sp;
        fz = cp * fhz;
        rx = Math.cos(yaw);
        rz = Math.sin(yaw);
        ux = sp * fhx;
        uy = cp;
        uz = sp * fhz;
    }

    private static MapData.Region regionAt(int wx, int wz) {
        int gx = Math.floorDiv(wx, MapData.REGION_PX) - cacheRx + GRID;
        int gz = Math.floorDiv(wz, MapData.REGION_PX) - cacheRz + GRID;
        if (gx < 0 || gz < 0 || gx > 2 * GRID || gz > 2 * GRID) return null;
        return cache[gz * (2 * GRID + 1) + gx];
    }

    /** Altura (y + 64) do bloco; a de regiões sem alturas é uma média plana. */
    private static int heightAt(int wx, int wz) {
        MapData.Region r = regionAt(wx, wz);
        if (r == null || r.hgt == null) return UNKNOWN_H;
        int h = r.hgt[Math.floorMod(wz, MapData.REGION_PX) * MapData.REGION_PX + Math.floorMod(wx, MapData.REGION_PX)];
        return h == 0 ? UNKNOWN_H : h;
    }

    // projeção (coordenadas da tela do jogo)
    static double projX, projY;
    static boolean projOk;

    static void project(double bx, double bz) {
        double by = heightAt((int) Math.floor(bx), (int) Math.floor(bz)) - 64.0;
        double vx = bx - camX, vy = by - camY, vz = bz - camZ;
        double zc = vx * fx + vy * fy + vz * fz;
        if (zc < 1) {
            projOk = false;
            projX = -9999;
            projY = -9999;
            return;
        }
        projOk = true;
        projX = guiW / 2 + focalGui * (vx * rx + vz * rz) / zc;
        projY = guiH / 2 - focalGui * (vx * ux + vy * uy + vz * uz) / zc;
    }

    /** O ponto do chão (na altura do alvo) sob um pixel da tela; null se o pixel olha acima do horizonte. */
    static double[] pick(double sx, double sy) {
        double a = (sx - guiW / 2) / focalGui, b = (guiH / 2 - sy) / focalGui;
        double vx = fx + a * rx + b * ux, vy = fy + b * uy, vz = fz + a * rz + b * uz;
        if (vy >= -1e-4) return null;
        double t = (targetY - camY) / vy;
        // refina com a altura real do terreno naquele ponto
        double px = camX + vx * t, pz = camZ + vz * t;
        for (int i = 0; i < 3; i++) {
            double ty = heightAt((int) Math.floor(px), (int) Math.floor(pz)) - 64.0;
            t = (ty - camY) / vy;
            px = camX + vx * t;
            pz = camZ + vz * t;
        }
        return new double[]{px, pz};
    }

    /** Desenha o terreno em 3D na tela inteira. */
    static void render(GuiGraphics g, int guiWidth, int guiHeight) {
        int w = Math.max(64, (int) (guiWidth * QUALITY)), h = Math.max(64, (int) (guiHeight * QUALITY));
        if (img == null || w != rw || h != rh) {
            if (tex != null) Minecraft.getInstance().getTextureManager().release(id);
            rw = w;
            rh = h;
            img = new NativeImage(NativeImage.Format.RGBA, rw, rh, true);
            tex = new DynamicTexture(() -> "hoi4map 3d", img);
            id = Identifier.fromNamespaceAndPath(Hoi4MapClient.MOD_ID, "view3d");
            Minecraft.getInstance().getTextureManager().register(id, tex);
            frame = new int[rw * rh];
        }
        java.util.Arrays.fill(frame, BG);
        double focal = 0.9 * rw;
        double halfX = rw / 2.0, halfY = rh / 2.0;
        double cy = camY;
        for (int col = 0; col < rw; col++) {
            double a = Math.atan((col - halfX) / focal);
            double dirx = Math.sin(yaw + a), dirz = -Math.cos(yaw + a);
            int top = rh;
            double r0 = Math.max(1, camDistH - 1500), rEnd = camDistH + 1500;
            for (double r = r0; r < rEnd && top > 0; r += Math.max(1.5, 2.0 * r / focal)) {
                double wx = camX + dirx * r, wz = camZ + dirz * r;
                int ix = (int) Math.floor(wx), iz = (int) Math.floor(wz);
                MapData.Region reg = regionAt(ix, iz);
                if (reg == null) continue;
                int lx = Math.floorMod(ix, MapData.REGION_PX), lz = Math.floorMod(iz, MapData.REGION_PX);
                int idx = lz * MapData.REGION_PX + lx;
                int color = reg.styledCpu == null ? 0 : reg.styledCpu[idx];
                if (color == 0) continue;
                int hv = reg.hgt == null ? 0 : reg.hgt[idx];
                double by = (hv == 0 ? UNKNOWN_H : hv) - 64.0;
                double vx = wx - camX, vy = by - cy, vz = wz - camZ;
                double zc = vx * fx + vy * fy + vz * fz;
                if (zc < 1) continue;
                double yc = vx * ux + vy * uy + vz * uz;
                int ys = (int) Math.floor(halfY - focal * yc / zc);
                if (ys >= top) continue;
                if (ys < 0) ys = 0;
                // neblina leve com a distância
                float fog = (float) Math.min(0.55, r / 9000.0);
                int c = fog <= 0.01f ? color : mixFog(color, fog);
                for (int y = ys; y < top; y++) frame[y * rw + col] = c;
                top = ys;
            }
        }
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) img.setPixel(x, y, frame[y * rw + x]);
        }
        tex.upload();
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, guiWidth, guiHeight, rw, rh, rw, rh);
    }

    private static int mixFog(int c, float t) {
        int r = (int) (((c >> 16) & 0xFF) * (1 - t) + 0x0B * t);
        int gg = (int) (((c >> 8) & 0xFF) * (1 - t) + 0x16 * t);
        int b = (int) ((c & 0xFF) * (1 - t) + 0x28 * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }
}
