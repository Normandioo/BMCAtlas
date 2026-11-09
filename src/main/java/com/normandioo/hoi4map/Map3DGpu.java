package com.normandioo.hoi4map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;

/**
 * Mapa 3D na placa de vídeo (experimental): monta uma malha de cubos com as texturas do atlas de blocos e
 * desenha numa textura da interface (projeção ortogonal, com buffer de profundidade).
 */
final class Map3DGpu {
    private Map3DGpu() {}

    static boolean enabled = false;
    static String status = "";

    record State(int x0, int y0, int x1, int y1, float scale, ScreenRectangle scissorArea, ScreenRectangle bounds,
                 double cx, double cz, double zoom, float yaw, float pitch, int version) implements PictureInPictureRenderState {}

    /** Envia o quadro do mapa 3D (a tela inteira) para a fila da interface. */
    static void submit(GuiGraphics g, double cx, double cz, double zoom, int w, int h) {
        ScreenRectangle scissor = null;
        ScreenRectangle bounds = PictureInPictureRenderState.getBounds(0, 0, w, h, scissor);
        int ver = MapStyle.overrideVersion * 31 + MapData.dataVersion();
        g.guiRenderState.submitPicturesInPictureState(new State(0, 0, w, h, 1f, scissor, bounds, cx, cz, zoom,
                (float) Math.toDegrees(Map3D.yaw), (float) Math.toDegrees(Map3D.pitch), ver));
    }

    static final class Renderer extends PictureInPictureRenderer<State> {
        Renderer(MultiBufferSource.BufferSource src) {
            super(src);
        }

        @Override
        public Class<State> getRenderStateClass() {
            return State.class;
        }

        @Override
        protected String getTextureLabel() {
            return "hoi4map 3d gpu";
        }

        @Override
        protected void renderToTexture(State st, PoseStack pose) {
            try {
                draw(st, pose);
                status = "ok";
            } catch (Throwable t) {
                status = t.toString();
                Hoi4MapClient.LOG.warn("Mapa 3D (GPU) falhou", t);
            }
        }

        private void draw(State st, PoseStack pose) {
            double gs = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
            double px = st.zoom() * gs;                      // pixels por bloco
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotationDegrees(180f));
            pose.mulPose(Axis.XP.rotationDegrees(st.pitch()));
            pose.mulPose(Axis.YP.rotationDegrees(st.yaw()));
            pose.scale((float) px, (float) px, (float) px);
            pose.translate((float) -st.cx(), (float) -Map3D.targetY, (float) -st.cz());
            VertexConsumer vc = bufferSource.getBuffer(RenderTypes.cutoutMovingBlock());
            int radius = (int) Math.min(150, Math.max(40, Math.max(st.x1(), st.y1()) * 0.9 / Math.max(0.2, st.zoom())));
            build(vc, pose.last(), st.cx(), st.cz(), radius);
            pose.popPose();
        }
    }

    // ------------------------------------------------------------------ malha

    private static final int LIGHT = 15728880;

    private static final class Face {
        float u0, u1, v0, v1;
        boolean tinted;
    }

    private static final Map<Integer, Face[]> FACES = new HashMap<>();

    private static Face[] faces(int id) {
        return FACES.computeIfAbsent(id, k -> {
            Face[] out = new Face[2];
            try {
                Block b = BuiltInRegistries.BLOCK.byId(k);
                BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(b.defaultBlockState());
                List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));
                out[0] = find(parts, Direction.UP, model);
                out[1] = find(parts, Direction.NORTH, model);
            } catch (Throwable t) {
                // deixa nulos
            }
            return out;
        });
    }

    private static Face find(List<BlockModelPart> parts, Direction dir, BlockStateModel model) {
        for (BlockModelPart part : parts) {
            for (BakedQuad q : part.getQuads(dir)) {
                return of(q.sprite(), q.isTinted());
            }
        }
        for (BlockModelPart part : parts) {
            for (Direction d : Direction.values()) {
                for (BakedQuad q : part.getQuads(d)) return of(q.sprite(), q.isTinted());
            }
        }
        return of(model.particleIcon(), false);
    }

    private static Face of(TextureAtlasSprite s, boolean tint) {
        Face f = new Face();
        f.u0 = s.getU0();
        f.u1 = s.getU1();
        f.v0 = s.getV0();
        f.v1 = s.getV1();
        f.tinted = tint;
        return f;
    }

    /** Intervalos sólidos de uma coluna (y do mundo): ate 3 camadas. */
    private static int intervals(MapData.Region reg, int idx, double[] lo, double[] hi, int[] blk) {
        int n = 0;
        int hv = reg.hgt == null ? 0 : reg.hgt[idx];
        if (hv == 0) return 0;
        hi[0] = hv - 64.0;
        int b0 = reg.bot0 == null || !MapData.layers ? 0 : reg.bot0[idx];
        lo[0] = b0 == 0 ? -64.0 : b0 - 64.0;
        blk[0] = reg.blk == null ? 0 : reg.blk[idx];
        n = 1;
        if (reg.sp != null && MapData.layers) {
            for (int s = 0; s < 2; s++) {
                int base = idx * MapData.SP_N + s * 3;
                int t = reg.sp[base];
                if (t <= 0) break;
                int b = reg.sp[base + 1];
                hi[n] = t - 64.0;
                lo[n] = b == 0 ? -64.0 : b - 64.0;
                blk[n] = reg.sp[base + 2] & 0xFFFF;
                n++;
            }
        }
        return n;
    }

    private static void build(VertexConsumer vc, PoseStack.Pose pose, double cx, double cz, int radius) {
        int x0 = (int) Math.floor(cx) - radius, x1 = (int) Math.floor(cx) + radius;
        int z0 = (int) Math.floor(cz) - radius, z1 = (int) Math.floor(cz) + radius;
        double[] lo = new double[3], hi = new double[3];
        int[] blk = new int[3];
        double[] nlo = new double[3], nhi = new double[3];
        int[] nblk = new int[3];
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                MapData.Region reg = Map3D.regionAt(x, z);
                if (reg == null) continue;
                int idx = Math.floorMod(z, MapData.REGION_PX) * MapData.REGION_PX + Math.floorMod(x, MapData.REGION_PX);
                int color = reg.styledCpu == null ? 0 : reg.styledCpu[idx];
                if (color == 0) continue;
                int n = intervals(reg, idx, lo, hi, blk);
                if (n == 0) continue;
                int tintRgb = tintOf(color);
                for (int s = 0; s < n; s++) {
                    Face[] fc = blk[s] > 0 ? faces(blk[s] - 1) : null;
                    Face top = fc == null ? null : fc[0];
                    Face side = fc == null ? null : fc[1];
                    // topo
                    quad(vc, pose, x, hi[s], z, x, hi[s], z + 1, x + 1, hi[s], z + 1, x + 1, hi[s], z, top, s == 0 ? tintRgb : 0xFFFFFF, 1.0f, 0, 1, 0, true);
                    // laterais expostas
                    for (int[] d : dirs) {
                        int nx = x + d[0], nz = z + d[1];
                        MapData.Region nr = Map3D.regionAt(nx, nz);
                        int nn = 0;
                        if (nr != null) {
                            int nidx = Math.floorMod(nz, MapData.REGION_PX) * MapData.REGION_PX + Math.floorMod(nx, MapData.REGION_PX);
                            nn = intervals(nr, nidx, nlo, nhi, nblk);
                        }
                        wall(vc, pose, reg, idx, s, x, z, d, lo[s], hi[s], nlo, nhi, nn, side, tintRgb, blk[s]);
                    }
                }
            }
        }
    }

    private static void wall(VertexConsumer vc, PoseStack.Pose pose, MapData.Region reg, int idx, int span, int x, int z, int[] d,
                             double lo, double hi, double[] nlo, double[] nhi, int nn, Face side, int tint, int topBlk) {
        // trechos de [lo, hi] que o vizinho não cobre
        double cur = hi;
        int guard = 0;
        while (cur > lo + 1e-6 && guard++ < 8) {
            // acha o intervalo do vizinho que cobre cur (ou o próximo abaixo)
            double covered = Double.NaN, nextTop = lo;
            boolean inside = false;
            for (int i = 0; i < nn; i++) {
                if (nhi[i] >= cur - 1e-6 && nlo[i] < cur - 1e-6) {
                    inside = true;
                    covered = nlo[i];
                } else if (nhi[i] < cur - 1e-6 && nhi[i] > nextTop) {
                    nextTop = nhi[i];
                }
            }
            if (inside) {
                cur = covered;
                continue;
            }
            double bot = Math.max(lo, nextTop);
            emitWall(vc, pose, reg, idx, span, x, z, d, cur, bot, hi, side, topBlk);
            cur = bot;
        }
    }

    private static void emitWall(VertexConsumer vc, PoseStack.Pose pose, MapData.Region reg, int idx, int span, int x, int z, int[] d,
                                 double top, double bot, double spanTop, Face side, int topBlk) {
        float shade = d[0] != 0 ? 0.6f : 0.8f;
        int units = 0;
        double y = top;
        while (y > bot + 1e-6 && units++ < 48) {
            double yb = Math.max(bot, y - 1.0);
            if (units == 48) yb = bot;
            Face f = side;
            if (span == 0 && reg.wall != null) {
                int depth = (int) Math.floor(spanTop - y + 1e-6);
                if (depth >= 1) {
                    int wid = wallBlock(reg, idx, depth);
                    if (wid > 0) {
                        Face[] fc = faces(wid - 1);
                        if (fc[1] != null) f = fc[1];
                    }
                }
            }
            double xa, za, xb, zb;
            if (d[0] == 1) { xa = x + 1; xb = x + 1; za = z; zb = z + 1; }
            else if (d[0] == -1) { xa = x; xb = x; za = z + 1; zb = z; }
            else if (d[1] == 1) { xa = x + 1; xb = x; za = z + 1; zb = z + 1; }
            else { xa = x; xb = x + 1; za = z; zb = z; }
            quad(vc, pose, xa, y, za, xb, y, zb, xb, yb, zb, xa, yb, za, f, 0xFFFFFF, shade, d[0], 0, d[1], false);
            y = yb;
        }
    }

    private static int wallBlock(MapData.Region reg, int idx, int depth) {
        int wid = 0, acc = 1;
        for (int q = 0; q < MapData.WALL_R; q++) {
            int e = reg.wall[idx * MapData.WALL_R + q] & 0xFFFF;
            if (e == 0) break;
            int ln = e & 31;
            wid = e >> 5;
            if (ln == 0 || depth < acc + ln) break;
            acc += ln;
        }
        return wid;
    }

    private static int tintOf(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        int m = Math.max(1, Math.max(r, Math.max(g, b)));
        return ((r * 255 / m) << 16) | ((g * 255 / m) << 8) | (b * 255 / m);
    }

    /** Um quadrado com a textura da face; a ordem dos vértices acompanha a normal (face de fora). */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz, Face f, int rgb, float shade,
                             float nx, float ny, float nz, boolean isTop) {
        float u0 = f == null ? 0 : f.u0, u1 = f == null ? 0 : f.u1, v0 = f == null ? 0 : f.v0, v1 = f == null ? 0 : f.v1;
        int c = 0xFF000000 | ((int) (((rgb >> 16) & 255) * shade) << 16) | ((int) (((rgb >> 8) & 255) * shade) << 8) | (int) ((rgb & 255) * shade);
        if (f != null && !f.tinted && isTop) c = 0xFF000000 | ((int) (255 * shade) << 16) | ((int) (255 * shade) << 8) | (int) (255 * shade);
        if (f != null && !f.tinted && !isTop) c = 0xFF000000 | ((int) (255 * shade) << 16) | ((int) (255 * shade) << 8) | (int) (255 * shade);
        if (f == null) c = 0xFF000000 | ((int) (120 * shade) << 16) | ((int) (120 * shade) << 8) | (int) (120 * shade);
        // ordem invertida: a GUI tem o Y para baixo, o que inverte o sentido das faces (o culling escondia o topo)
        v(vc, pose, dx, dy, dz, c, u1, v0, nx, ny, nz);
        v(vc, pose, cx, cy, cz, c, u1, v1, nx, ny, nz);
        v(vc, pose, bx, by, bz, c, u0, v1, nx, ny, nz);
        v(vc, pose, ax, ay, az, c, u0, v0, nx, ny, nz);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z, int c, float u, float vv, float nx, float ny, float nz) {
        vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(c).setUv(u, vv).setLight(LIGHT).setNormal(pose, nx, ny, nz);
    }
}
