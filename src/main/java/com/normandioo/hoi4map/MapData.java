package com.normandioo.hoi4map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Dados do mundo atual: cor de terreno por chunk (explorado) e dono de cada província. */
final class MapData {
    /** Lado de uma província, em chunks (2 chunks = 32 blocos). */
    static final int PROV = 2;

    private static final Gson GSON = new Gson();

    static final Long2IntOpenHashMap CHUNK_COLORS = new Long2IntOpenHashMap();
    static final Map<Long, String> OWNERS = new HashMap<>();

    private static String worldKey;
    private static int ownersVersion;
    private static boolean dirty;

    private MapData() {}

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

    // ---------------------------------------------------------------- mundo

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
        worldKey = k;
        CHUNK_COLORS.clear();
        OWNERS.clear();
        load();
        ownersVersion++;
    }

    static void unload() {
        save();
        worldKey = null;
        CHUNK_COLORS.clear();
        OWNERS.clear();
        ownersVersion++;
    }

    private static void load() {
        if (worldKey == null) return;
        Path chunks = dir().resolve(worldKey + ".chunks");
        if (Files.exists(chunks)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(chunks)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    long k = in.readLong();
                    int c = in.readInt();
                    CHUNK_COLORS.put(k, c);
                }
            } catch (IOException e) {
                Hoi4MapClient.LOG.warn("Cache de terreno ilegível: {}", chunks, e);
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
        dirty = false;
    }

    static void save() {
        if (worldKey == null || CHUNK_COLORS.isEmpty() && OWNERS.isEmpty()) return;
        try {
            Files.createDirectories(dir());
            Path chunks = dir().resolve(worldKey + ".chunks");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(chunks)))) {
                out.writeInt(CHUNK_COLORS.size());
                for (Long2IntOpenHashMap.Entry e : CHUNK_COLORS.long2IntEntrySet()) {
                    out.writeLong(e.getLongKey());
                    out.writeInt(e.getIntValue());
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

    static boolean isDirty() {
        return dirty;
    }

    // -------------------------------------------------------------- terreno

    /** Lê os chunks carregados perto do jogador e guarda uma cor por chunk. */
    static void scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        ensureWorld(mc);
        int radius = Math.min(mc.options.renderDistance().get(), 16);
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                int color = sampleChunk(level, chunk, cx, cz);
                if (color != 0) {
                    CHUNK_COLORS.put(key(cx, cz), color);
                    dirty = true;
                }
            }
        }
    }

    private static int sampleChunk(ClientLevel level, LevelChunk chunk, int cx, int cz) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] offs = {4, 12};
        for (int lx : offs) {
            for (int lz : offs) {
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int y = Math.max(-64, top - 1);
                pos.set((cx << 4) + lx, y, (cz << 4) + lz);
                BlockState state = chunk.getBlockState(pos);
                MapColor mc = state.getMapColor(level, pos);
                int col = mc.col;
                if (col == 0) continue;
                float shade = Math.max(0.72f, Math.min(1.18f, 0.88f + (y - 63) / 220f));
                r += Math.min(255, (int) (((col >> 16) & 0xFF) * shade));
                g += Math.min(255, (int) (((col >> 8) & 0xFF) * shade));
                b += Math.min(255, (int) ((col & 0xFF) * shade));
                n++;
            }
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }
}
