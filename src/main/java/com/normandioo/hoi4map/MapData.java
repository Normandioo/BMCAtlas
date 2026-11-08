package com.normandioo.hoi4map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Dados do mundo atual: terreno explorado (1 pixel por bloco, em regiões de 16x16 chunks)
 * e dono de cada província.
 */
final class MapData {
    /** Lado de uma província, em chunks (2 chunks = 32 blocos). */
    static final int PROV = 2;
    /** Lado de uma região, em chunks / pixels. */
    static final int REGION_CHUNKS = 16;
    static final int REGION_PX = REGION_CHUNKS * 16;

    private static final int SCAN_BUDGET = 8;      // chunks lidos por tick
    private static final int SCAN_CHECKS = 64;     // chunks verificados por tick
    private static final long RESCAN_TICKS = 600;  // 30 s

    private static final Gson GSON = new Gson();

    /** Uma região de terreno. */
    static final class Region {
        final int rx, rz;
        final int[] px = new int[REGION_PX * REGION_PX]; // ARGB; 0 = desconhecido
        boolean dirty = true;
        private NativeImage image;
        private DynamicTexture texture;
        Identifier id;

        Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }

        /** Garante a textura e envia os pixels se houve mudança. Retorna false se não deu para criar. */
        boolean ensureUploaded() {
            if (texture == null) {
                image = new NativeImage(NativeImage.Format.RGBA, REGION_PX, REGION_PX, true);
                texture = new DynamicTexture(() -> "hoi4map region", image);
                id = Identifier.fromNamespaceAndPath(Hoi4MapClient.MOD_ID, "region/r" + (textureCounter++));
                Minecraft.getInstance().getTextureManager().register(id, texture);
                dirty = true;
            }
            if (dirty) {
                for (int y = 0; y < REGION_PX; y++) {
                    for (int x = 0; x < REGION_PX; x++) {
                        image.setPixel(x, y, px[y * REGION_PX + x]);
                    }
                }
                texture.upload();
                dirty = false;
            }
            return true;
        }

        void release() {
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(id);
                texture = null;
                image = null;
            }
        }
    }

    private static int textureCounter;

    static final Map<Long, Region> REGIONS = new HashMap<>();
    static final Map<Long, String> OWNERS = new HashMap<>();

    private static final Long2LongOpenHashMap LAST_SCAN = new Long2LongOpenHashMap();
    private static List<int[]> spiral = new ArrayList<>();
    private static int spiralRadius = -1;
    private static int cursor;
    private static long tickCount;

    private static String worldKey;
    private static int ownersVersion;
    private static boolean dirty;

    private MapData() {}

    // ------------------------------------------------------------------ chaves

    static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    static int keyA(long k) {
        return (int) (k >> 32);
    }

    static int keyB(long k) {
        return (int) k;
    }

    static int ownersVersion() {
        return ownersVersion;
    }

    static void ownersChanged() {
        ownersVersion++;
        dirty = true;
    }

    static void setOwner(int px, int pz, String id) {
        if (id == null) OWNERS.remove(key(px, pz));
        else OWNERS.put(key(px, pz), id);
        ownersChanged();
    }

    static String ownerOf(int px, int pz) {
        return OWNERS.get(key(px, pz));
    }

    static boolean isDirty() {
        return dirty;
    }

    // ------------------------------------------------------------------- regiões

    static Region region(int rx, int rz, boolean create) {
        long k = key(rx, rz);
        Region r = REGIONS.get(k);
        if (r == null && create) {
            r = new Region(rx, rz);
            REGIONS.put(k, r);
        }
        return r;
    }

    /** O chunk já foi lido alguma vez? */
    static boolean chunkKnown(int cx, int cz) {
        Region r = region(Math.floorDiv(cx, REGION_CHUNKS), Math.floorDiv(cz, REGION_CHUNKS), false);
        if (r == null) return false;
        int ox = Math.floorMod(cx, REGION_CHUNKS) * 16 + 8;
        int oz = Math.floorMod(cz, REGION_CHUNKS) * 16 + 8;
        return r.px[oz * REGION_PX + ox] != 0;
    }

    // --------------------------------------------------------------------- mundo

    private static String computeKey(Minecraft mc) {
        String base = "local";
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null) base = sd.ip;
        if (mc.getSingleplayerServer() != null) {
            base = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        String dim = mc.level.dimension().toString();
        return (base + "_" + dim).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("hoi4map");
    }

    static void ensureWorld(Minecraft mc) {
        if (mc.level == null) return;
        String k = computeKey(mc);
        if (k.equals(worldKey)) return;
        save();
        clear();
        worldKey = k;
        load();
    }

    private static void clear() {
        for (Region r : REGIONS.values()) r.release();
        REGIONS.clear();
        OWNERS.clear();
        LAST_SCAN.clear();
        cursor = 0;
        ownersVersion++;
        dirty = false;
    }

    static void unload() {
        save();
        clear();
        worldKey = null;
    }

    // ---------------------------------------------------------------- persistência

    private static void load() {
        if (worldKey == null) return;
        Path regions = dir().resolve(worldKey + ".regions");
        if (Files.exists(regions)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(regions)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int rx = in.readInt();
                    int rz = in.readInt();
                    int len = in.readInt();
                    byte[] comp = new byte[len];
                    in.readFully(comp);
                    Region r = new Region(rx, rz);
                    inflateInto(comp, r.px);
                    REGIONS.put(key(rx, rz), r);
                }
            } catch (IOException | DataFormatException e) {
                Hoi4MapClient.LOG.warn("Cache de terreno ilegível: {}", regions, e);
            }
        }
        Path owners = dir().resolve(worldKey + ".owners.json");
        if (Files.exists(owners)) {
            try (Reader r = Files.newBufferedReader(owners, StandardCharsets.UTF_8)) {
                Map<String, String> m = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) {
                    for (Map.Entry<String, String> e : m.entrySet()) {
                        String[] p = e.getKey().split(",");
                        OWNERS.put(key(Integer.parseInt(p[0]), Integer.parseInt(p[1])), e.getValue());
                    }
                }
            } catch (IOException | RuntimeException e) {
                Hoi4MapClient.LOG.warn("Donos de províncias ilegíveis: {}", owners, e);
            }
        }
        ownersVersion++;
        dirty = false;
    }

    static void save() {
        if (worldKey == null || (REGIONS.isEmpty() && OWNERS.isEmpty())) return;
        try {
            Files.createDirectories(dir());
            Path regions = dir().resolve(worldKey + ".regions");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(regions)))) {
                out.writeInt(REGIONS.size());
                for (Region r : REGIONS.values()) {
                    byte[] comp = deflate(r.px);
                    out.writeInt(r.rx);
                    out.writeInt(r.rz);
                    out.writeInt(comp.length);
                    out.write(comp);
                }
            }
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<Long, String> e : OWNERS.entrySet()) {
                m.put(keyA(e.getKey()) + "," + keyB(e.getKey()), e.getValue());
            }
            Path owners = dir().resolve(worldKey + ".owners.json");
            try (Writer w = Files.newBufferedWriter(owners, StandardCharsets.UTF_8)) {
                GSON.toJson(m, w);
            }
            dirty = false;
        } catch (IOException e) {
            Hoi4MapClient.LOG.warn("Falha ao salvar o mapa", e);
        }
    }

    private static byte[] deflate(int[] px) {
        ByteBuffer bb = ByteBuffer.allocate(px.length * 4);
        bb.asIntBuffer().put(px);
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(bb.array());
        d.finish();
        byte[] buf = new byte[px.length * 4 + 64];
        int n = d.deflate(buf);
        d.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    private static void inflateInto(byte[] comp, int[] px) throws DataFormatException {
        Inflater inf = new Inflater();
        inf.setInput(comp);
        byte[] raw = new byte[px.length * 4];
        int off = 0;
        while (off < raw.length && !inf.finished()) {
            int n = inf.inflate(raw, off, raw.length - off);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            off += n;
        }
        inf.end();
        ByteBuffer.wrap(raw).asIntBuffer().get(px);
    }

    // ----------------------------------------------------------------------- scan

    private static void buildSpiral(int radius) {
        spiral = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) spiral.add(new int[]{dx, dz});
            }
        }
        spiral.sort(Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        spiralRadius = radius;
        cursor = 0;
    }

    /** Chamado todo tick: lê alguns chunks carregados perto do jogador. */
    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        ensureWorld(mc);
        tickCount++;

        int radius = Math.max(4, Math.min(mc.options.renderDistance().get(), 24));
        if (radius != spiralRadius) buildSpiral(radius);

        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int scanned = 0;
        for (int i = 0; i < SCAN_CHECKS && scanned < SCAN_BUDGET; i++) {
            int[] off = spiral.get(cursor);
            cursor = (cursor + 1) % spiral.size();
            int cx = pcx + off[0];
            int cz = pcz + off[1];
            long k = key(cx, cz);
            if (LAST_SCAN.containsKey(k) && tickCount - LAST_SCAN.get(k) < RESCAN_TICKS) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            scanChunk(level, chunk, cx, cz);
            LAST_SCAN.put(k, tickCount);
            scanned++;
        }
    }

    private static void scanChunk(ClientLevel level, LevelChunk chunk, int cx, int cz) {
        Region reg = region(Math.floorDiv(cx, REGION_CHUNKS), Math.floorDiv(cz, REGION_CHUNKS), true);
        int ox = Math.floorMod(cx, REGION_CHUNKS) * 16;
        int oz = Math.floorMod(cz, REGION_CHUNKS) * 16;

        LevelChunk west = level.getChunkSource().getChunkNow(cx - 1, cz);
        LevelChunk north = level.getChunkSource().getChunkNow(cx, cz - 1);
        LevelChunk northWest = level.getChunkSource().getChunkNow(cx - 1, cz - 1);

        int[] heights = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                heights[lz * 16 + lx] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
            }
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int top = heights[lz * 16 + lx];
                int y = top - 1;
                int wx = (cx << 4) + lx;
                int wz = (cz << 4) + lz;
                int outIndex = (oz + lz) * REGION_PX + (ox + lx);
                if (y < -64) {
                    reg.px[outIndex] = 0;
                    continue;
                }
                pos.set(wx, y, wz);
                BlockState state = chunk.getBlockState(pos);

                int color;
                if (!state.getFluidState().isEmpty()) {
                    int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, lx, lz);
                    color = waterColor(Math.max(0, top - floor));
                } else {
                    MapColor mc = state.getMapColor(level, pos);
                    if (mc.col == 0) {
                        reg.px[outIndex] = 0;
                        continue;
                    }
                    int hNW = neighborHeight(chunk, west, north, northWest, heights, lx - 1, lz - 1, top);
                    float shade = 1.0f + Math.max(-4, Math.min(4, top - hNW)) * 0.055f;
                    color = shadeColor(mc.col, shade);
                }
                reg.px[outIndex] = color;
            }
        }
        reg.dirty = true;
        dirty = true;
    }

    private static int neighborHeight(LevelChunk self, LevelChunk west, LevelChunk north, LevelChunk northWest,
                                      int[] heights, int lx, int lz, int fallback) {
        if (lx >= 0 && lz >= 0) return heights[lz * 16 + lx];
        LevelChunk c;
        int x = lx, z = lz;
        if (lx < 0 && lz < 0) {
            c = northWest;
            x = 15;
            z = 15;
        } else if (lx < 0) {
            c = west;
            x = 15;
        } else {
            c = north;
            z = 15;
        }
        if (c == null) return fallback;
        return c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    private static int waterColor(int depth) {
        float f = 1.0f - Math.min(depth, 30) / 46.0f; // 1.0 raso ... ~0.35 fundo
        return shadeColor(0x3A68D8, f);
    }

    private static int shadeColor(int rgb, float shade) {
        shade = Math.max(0.25f, Math.min(1.3f, shade));
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * shade));
        int b = Math.min(255, (int) ((rgb & 0xFF) * shade));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
