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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
    /** Execuções (bloco, comprimento) por coluna abaixo do topo; comprimento 0 = até o fundo. Empacotado: (id+1)<<5 | len. */
    static final int WALL_R = 8;
    /** Segundas e terceiras camadas de sólido numa coluna (vãos de 3+ blocos de ar): (topo, fundo, bloco) x 2. */
    static final int SP_N = 6;
    /** camadas extras (beirais/vãos) no 3D; H alterna. Desligado = colunas sólidas até o fundo. */
    static boolean layers = true;
    /** o 3D da CPU (cabo de raios por coluna) fica sem camadas: só a malha da GPU mostra vãos corretamente */
    static boolean layersCpu = false;
    private static final int CHUNK_EXTRA = 256 + 256 * SP_N;

    static final class Region {
        final int rx, rz;
        int[] px = EMPTY_PX; // ARGB; 0 = desconhecido. Compartilhado até a região ser lida de verdade.
        /** Altura de cada bloco (y + 64, 1..); 0 = desconhecida. Só existe onde o chunk foi lido com esta versão. */
        short[] hgt;
        /** Bloco do topo de cada coluna (id no registro + 1); 0 = desconhecido. */
        short[] blk;
        /** Blocos abaixo do topo (WALL_K-1 por coluna: profundidade 1..): textura das paredes no 3D; 0 = desconhecido. */
        short[] wall;
        /** Fundo (y + 64) da camada de cima de cada coluna; 0 = vai até o fundo do mundo. */
        short[] bot0;
        /** Camadas extras: topo, fundo e bloco (id + 1) de cada uma; topo 0 = sem camada. */
        short[] sp;
        /** Cópia das cores já pintadas no estilo atual (o modo 3D lê daqui); null até a região ser pintada. */
        int[] styledCpu;
        long lastUsed;
        boolean dirty = true;
        private NativeImage image;
        private DynamicTexture texture;
        Identifier id;

        Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }

        int styledMode = -1;
        int styledVer = -1;

        /** Garante a textura e (re)pinta os pixels no estilo do modo atual, se necessário. */
        boolean ensureUploaded(int mode) {
            if (texture == null) {
                image = new NativeImage(NativeImage.Format.RGBA, REGION_PX, REGION_PX, true);
                texture = new DynamicTexture(() -> "hoi4map region", image);
                id = Identifier.fromNamespaceAndPath(Hoi4MapClient.MOD_ID, "region/r" + (textureCounter++));
                Minecraft.getInstance().getTextureManager().register(id, texture);
                dirty = true;
            }
            int ver = MapStyle.overrideVersion;
            if (dirty || styledMode != mode || styledVer != ver) {
                int[] out = MapStyle.paint(this, mode);
                if (styledCpu == null) styledCpu = new int[REGION_PX * REGION_PX];
                System.arraycopy(out, 0, styledCpu, 0, REGION_PX * REGION_PX);
                for (int y = 0; y < REGION_PX; y++) {
                    for (int x = 0; x < REGION_PX; x++) {
                        image.setPixel(x, y, out[y * REGION_PX + x]);
                    }
                }
                texture.upload();
                dirty = false;
                styledMode = mode;
                styledVer = ver;
            }
            return true;
        }

        /** Dá à região o seu próprio array de pixels (ao ler chunks ou carregar do disco). */
        int[] writable() {
            if (px == EMPTY_PX) px = new int[REGION_PX * REGION_PX];
            return px;
        }

        boolean hasData() {
            return px != EMPTY_PX;
        }

        void release() {
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(id);
                texture = null;
                image = null;
                id = null;
                styledCpu = null;
                styledMode = -1;
                dirty = true;
            }
        }
    }

    private static final int[] EMPTY_PX = new int[REGION_PX * REGION_PX];
    private static int textureCounter;
    private static boolean siteApplies;
    private static int siteSeen = -1;
    private static long frame;

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

    /** Mudança geral (recarregar países, trocar de mundo): repinta todas as regiões. */
    static void ownersChanged() {
        ownersVersion++;
        dirty = true;
        for (Region r : REGIONS.values()) r.styledMode = -1;
    }

    static final int PROVS_PER_REGION = REGION_CHUNKS / PROV;

    static void setOwner(int px, int pz, String id) {
        if (id == null) OWNERS.remove(key(px, pz));
        else OWNERS.put(key(px, pz), id);
        ownersVersion++;
        dirty = true;
        // repinta só a região da província e as vizinhas (o brilho da fronteira cruza regiões)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Region r = region(Math.floorDiv(px + dx, PROVS_PER_REGION), Math.floorDiv(pz + dz, PROVS_PER_REGION), false);
                if (r != null) r.styledMode = -1;
            }
        }
    }

    static String ownerOf(int px, int pz) {
        return OWNERS.get(key(px, pz));
    }

    private static int dataVer;

    /** Muda sempre que o terreno lido muda (o 3D da GPU refaz a malha). */
    static void bumpData() { dataVer++; }

    static int dataVersion() {
        return dataVer;
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

    /** Região só para visualização (sem terreno lido): usada para mostrar países do site em áreas não exploradas. */
    static Region viewRegion(int rx, int rz) {
        return region(rx, rz, true);
    }

    static boolean siteApplies() {
        return siteApplies;
    }

    /** Chamado a cada quadro da tela: libera texturas de regiões que saíram de vista há um tempo. */
    static void markUsed(Region r) {
        r.lastUsed = frame;
    }

    static void nextFrame() {
        frame++;
        if (frame % 120 != 0) return;
        for (Region r : REGIONS.values()) {
            if (r.id != null && frame - r.lastUsed > 1500) r.release();
        }
    }

    /** Repinta as regiões que cruzam o retângulo de blocos (por exemplo, ao selecionar uma província). */
    static void invalidateRect(int minX, int minZ, int maxX, int maxZ) {
        int size = REGION_PX;
        for (int rx = Math.floorDiv(minX - 1, size); rx <= Math.floorDiv(maxX + 1, size); rx++) {
            for (int rz = Math.floorDiv(minZ - 1, size); rz <= Math.floorDiv(maxZ + 1, size); rz++) {
                Region r = region(rx, rz, false);
                if (r != null) r.styledMode = -1;
            }
        }
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
        ServerData srv = mc.getCurrentServer();
        SiteTerritories.serverChanged(srv != null && srv.ip != null ? srv.ip : "");
        siteApplies = SiteTerritories.dimensionApplies(mc.level.dimension().toString());
        if (siteSeen != SiteTerritories.version()) {
            siteSeen = SiteTerritories.version();
            for (Region r : REGIONS.values()) r.styledMode = -1;
        }
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
        Path regions = dir().resolve(worldKey + ".regions5");
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
                    inflateInto(comp, r.writable());
                    REGIONS.put(key(rx, rz), r);
                }
            } catch (IOException | DataFormatException e) {
                Hoi4MapClient.LOG.warn("Cache de terreno ilegível: {}", regions, e);
            }
        }
        Path heights = dir().resolve(worldKey + ".heights1");
        if (Files.exists(heights)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(heights)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int rx = in.readInt();
                    int rz = in.readInt();
                    int len = in.readInt();
                    byte[] comp = new byte[len];
                    in.readFully(comp);
                    Region r = REGIONS.get(key(rx, rz));
                    if (r == null) continue;
                    java.util.zip.Inflater inf = new java.util.zip.Inflater();
                    inf.setInput(comp);
                    byte[] raw = new byte[REGION_PX * REGION_PX * 2];
                    int got = 0;
                    while (got < raw.length && !inf.finished()) got += inf.inflate(raw, got, raw.length - got);
                    inf.end();
                    short[] h = new short[REGION_PX * REGION_PX];
                    for (int k = 0; k < h.length; k++) h[k] = (short) (((raw[2 * k] & 0xFF) << 8) | (raw[2 * k + 1] & 0xFF));
                    r.hgt = h;
                }
            } catch (IOException | DataFormatException e) {
                Hoi4MapClient.LOG.warn("Alturas ilegíveis: {}", heights, e);
            }
        }
        Path blocks = dir().resolve(worldKey + ".blocks1");
        if (Files.exists(blocks)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(blocks)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int rx = in.readInt();
                    int rz = in.readInt();
                    int len = in.readInt();
                    short[] data = readShorts(in, len);
                    Region r = REGIONS.get(key(rx, rz));
                    if (r != null) r.blk = data;
                }
            } catch (IOException | DataFormatException e) {
                Hoi4MapClient.LOG.warn("Blocos ilegíveis: {}", blocks, e);
            }
        }
        Path walls = dir().resolve(worldKey + ".walls2");
        if (Files.exists(walls)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(walls)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int rx = in.readInt();
                    int rz = in.readInt();
                    int len = in.readInt();
                    short[] data = readShorts(in, len, REGION_PX * REGION_PX * WALL_R);
                    Region r = REGIONS.get(key(rx, rz));
                    if (r != null) r.wall = data;
                }
            } catch (IOException | DataFormatException e) {
                Hoi4MapClient.LOG.warn("Paredes ilegíveis: {}", walls, e);
            }
        }
        for (int which = 0; which < 2; which++) {
            Path f = dir().resolve(worldKey + (which == 0 ? ".bot1" : ".spans1"));
            if (!Files.exists(f)) continue;
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f)))) {
                int n = in.readInt();
                for (int i2 = 0; i2 < n; i2++) {
                    int rx = in.readInt();
                    int rz = in.readInt();
                    int len = in.readInt();
                    short[] data = readShorts(in, len, REGION_PX * REGION_PX * (which == 0 ? 1 : SP_N));
                    Region r = REGIONS.get(key(rx, rz));
                    if (r != null) { if (which == 0) r.bot0 = data; else r.sp = data; }
                }
            } catch (IOException | DataFormatException e) {
                Hoi4MapClient.LOG.warn("Camadas ilegíveis: {}", f, e);
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

    private static short[] readShorts(DataInputStream in, int len) throws IOException, DataFormatException {
        return readShorts(in, len, REGION_PX * REGION_PX);
    }

    private static short[] readShorts(DataInputStream in, int len, int count) throws IOException, DataFormatException {
        byte[] comp = new byte[len];
        in.readFully(comp);
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(comp);
        byte[] raw = new byte[count * 2];
        int got = 0;
        while (got < raw.length && !inf.finished()) got += inf.inflate(raw, got, raw.length - got);
        inf.end();
        short[] h = new short[count];
        for (int k = 0; k < h.length; k++) h[k] = (short) (((raw[2 * k] & 0xFF) << 8) | (raw[2 * k + 1] & 0xFF));
        return h;
    }

    private static void writeShorts(DataOutputStream out, Region r, short[] data) throws IOException {
        byte[] raw = new byte[data.length * 2];
        for (int k = 0; k < data.length; k++) {
            raw[2 * k] = (byte) (data[k] >> 8);
            raw[2 * k + 1] = (byte) data[k];
        }
        java.util.zip.Deflater def = new java.util.zip.Deflater(1);
        def.setInput(raw);
        def.finish();
        byte[] buf = new byte[raw.length + 64];
        int len = def.deflate(buf);
        def.end();
        out.writeInt(r.rx);
        out.writeInt(r.rz);
        out.writeInt(len);
        out.write(buf, 0, len);
    }

    static void save() {
        if (worldKey == null || (REGIONS.isEmpty() && OWNERS.isEmpty())) return;
        saveNow(new java.util.ArrayList<>(REGIONS.values()), new HashMap<>(OWNERS), worldKey);
    }

    private static volatile boolean saving;

    /** Salva em segundo plano (ao fechar o mapa não trava o jogo). */
    static void saveAsync() {
        if (worldKey == null || (REGIONS.isEmpty() && OWNERS.isEmpty()) || !dirty || saving) return;
        saving = true;
        java.util.List<Region> snap = new java.util.ArrayList<>(REGIONS.values());
        Map<Long, String> owners = new HashMap<>(OWNERS);
        String key = worldKey;
        Thread th = new Thread(() -> {
            try { saveNow(snap, owners, key); } finally { saving = false; }
        }, "hoi4map-save");
        th.setDaemon(true);
        th.start();
    }

    private static void saveNow(java.util.Collection<Region> regs, Map<Long, String> ownersSnap, String worldKey) {
        try {
            Files.createDirectories(dir());
            Path regions = dir().resolve(worldKey + ".regions5");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(regions)))) {
                int count = 0;
                for (Region r : regs) if (r.hasData()) count++;
                out.writeInt(count);
                for (Region r : regs) {
                    if (!r.hasData()) continue;
                    byte[] comp = deflate(r.px);
                    out.writeInt(r.rx);
                    out.writeInt(r.rz);
                    out.writeInt(comp.length);
                    out.write(comp);
                }
            }
            Path heights = dir().resolve(worldKey + ".heights1");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(heights)))) {
                int count = 0;
                for (Region r : regs) if (r.hgt != null) count++;
                out.writeInt(count);
                for (Region r : regs) {
                    if (r.hgt == null) continue;
                    byte[] raw = new byte[r.hgt.length * 2];
                    for (int k = 0; k < r.hgt.length; k++) {
                        raw[2 * k] = (byte) (r.hgt[k] >> 8);
                        raw[2 * k + 1] = (byte) r.hgt[k];
                    }
                    java.util.zip.Deflater def = new java.util.zip.Deflater(1);
                    def.setInput(raw);
                    def.finish();
                    byte[] buf = new byte[raw.length + 64];
                    int len = def.deflate(buf);
                    def.end();
                    out.writeInt(r.rx);
                    out.writeInt(r.rz);
                    out.writeInt(len);
                    out.write(buf, 0, len);
                }
            }
            Path blocks = dir().resolve(worldKey + ".blocks1");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(blocks)))) {
                int count = 0;
                for (Region r : regs) if (r.blk != null) count++;
                out.writeInt(count);
                for (Region r : regs) if (r.blk != null) writeShorts(out, r, r.blk);
            }
            Path walls = dir().resolve(worldKey + ".walls2");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(walls)))) {
                int count = 0;
                for (Region r : regs) if (r.wall != null) count++;
                out.writeInt(count);
                for (Region r : regs) if (r.wall != null) writeShorts(out, r, r.wall);
            }
            for (int which = 0; which < 2; which++) {
                Path f = dir().resolve(worldKey + (which == 0 ? ".bot1" : ".spans1"));
                try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(f)))) {
                    int count = 0;
                    for (Region r : regs) if ((which == 0 ? r.bot0 : r.sp) != null) count++;
                    out.writeInt(count);
                    for (Region r : regs) {
                        short[] arr = which == 0 ? r.bot0 : r.sp;
                        if (arr != null) writeShorts(out, r, arr);
                    }
                }
            }
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<Long, String> e : ownersSnap.entrySet()) {
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
            applyChunk(cx, cz, computeChunk(level, chunk, cx, cz));
            LAST_SCAN.put(k, tickCount);
            scanned++;
        }
    }

    /** Escreve os pixels de um chunk (256 valores, linha a linha) na região dele. Só no thread do cliente. */
    static void applyChunk(int cx, int cz, int[] px) {
        if (px == null) return;
        Region reg = region(Math.floorDiv(cx, REGION_CHUNKS), Math.floorDiv(cz, REGION_CHUNKS), true);
        reg.writable();
        int ox = Math.floorMod(cx, REGION_CHUNKS) * 16;
        int oz = Math.floorMod(cz, REGION_CHUNKS) * 16;
        for (int lz = 0; lz < 16; lz++) {
            System.arraycopy(px, lz * 16, reg.px, (oz + lz) * REGION_PX + ox, 16);
        }
        if (px.length >= 512) {
            if (reg.hgt == null) reg.hgt = new short[REGION_PX * REGION_PX];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    reg.hgt[(oz + lz) * REGION_PX + ox + lx] = (short) px[256 + lz * 16 + lx];
                }
            }
        }
        if (px.length >= 768) {
            if (reg.blk == null) reg.blk = new short[REGION_PX * REGION_PX];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    reg.blk[(oz + lz) * REGION_PX + ox + lx] = (short) px[512 + lz * 16 + lx];
                }
            }
        }
        if (px.length >= 768 + 256 * WALL_R) {
            if (reg.wall == null) reg.wall = new short[REGION_PX * REGION_PX * WALL_R];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int col = (oz + lz) * REGION_PX + ox + lx;
                    for (int d = 0; d < WALL_R; d++) {
                        reg.wall[col * WALL_R + d] = (short) px[768 + (lz * 16 + lx) * WALL_R + d];
                    }
                }
            }
        }
        int ex = 768 + 256 * WALL_R;
        if (px.length >= ex + CHUNK_EXTRA) {
            if (reg.bot0 == null) reg.bot0 = new short[REGION_PX * REGION_PX];
            if (reg.sp == null) reg.sp = new short[REGION_PX * REGION_PX * SP_N];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int col = (oz + lz) * REGION_PX + ox + lx, k = lz * 16 + lx;
                    reg.bot0[col] = (short) px[ex + k];
                    for (int d = 0; d < SP_N; d++) reg.sp[col * SP_N + d] = (short) px[ex + 256 + k * SP_N + d];
                }
            }
        }
        reg.dirty = true;
        dirty = true;
        dataVer++;
    }

    /** Lê agora todos os chunks carregados no quadrado em volta (cx, cz); devolve quantos leu. */
    static int scanAround(Minecraft mc, int pcx, int pcz, int radius, int limit) {
        ClientLevel level = mc.level;
        if (level == null) return 0;
        int n = 0;
        for (int dx = -radius; dx <= radius && n < limit; dx++) {
            for (int dz = -radius; dz <= radius && n < limit; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long k = key(cx, cz);
                if (LAST_SCAN.containsKey(k) && tickCount - LAST_SCAN.get(k) < RESCAN_TICKS) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                applyChunk(cx, cz, computeChunk(level, chunk, cx, cz));
                LAST_SCAN.put(k, tickCount);
                n++;
            }
        }
        return n;
    }

    private static ChunkAccess neighbor(Level level, int cx, int cz) {
        return level.getChunk(cx, cz, ChunkStatus.FULL, false);
    }

    /**
     * Calcula as cores de um chunk (índice lz*16+lx). Não mexe em regiões: pode rodar fora do thread
     * do cliente (o servidor interno usa para gerar o mapa sem o jogador estar perto).
     */
    static int[] computeChunk(Level level, ChunkAccess chunk, int cx, int cz) {
        int[] result = new int[768 + 256 * WALL_R + CHUNK_EXTRA];       // 0..255 cores; 256..511 alturas (y + 64); 512..767 blocos (id + 1)
        ChunkAccess west = neighbor(level, cx - 1, cz);
        ChunkAccess north = neighbor(level, cx, cz - 1);
        ChunkAccess northWest = neighbor(level, cx - 1, cz - 1);

        // MOTION_BLOCKING ignora plantas/flores (sem colisão) mas inclui folhas, vidro e fluidos
        int[] heights = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                heights[lz * 16 + lx] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
            }
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int top = heights[lz * 16 + lx];
                int wx = (cx << 4) + lx;
                int wz = (cz << 4) + lz;
                int outIndex = lz * 16 + lx;
                if (top < -63) {
                    result[outIndex] = 0;
                    continue;
                }
                result[256 + outIndex] = Math.max(1, top + 64);
                BlockState state = stateAtTop(chunk, pos, wx, top, wz);
                result[512 + outIndex] = BuiltInRegistries.BLOCK.getId(state.getBlock()) + 1;
                {
                    int topY = pos.getY();
                    int need = 48;
                    if (lx > 0 && lx < 15 && lz > 0 && lz < 15) {
                        int mn = Math.min(Math.min(heights[outIndex - 1], heights[outIndex + 1]), Math.min(heights[outIndex - 16], heights[outIndex + 16]));
                        need = Math.max(1, Math.min(48, top - mn + 1));
                    }
                    BlockPos.MutableBlockPos wp = new BlockPos.MutableBlockPos();
                    int slot = 0, runId = 0, runLen = 0, lastId = result[512 + outIndex];
                    int base = 768 + outIndex * WALL_R;
                    boolean full = false;
                    for (int d = 1; d <= need; d++) {
                        wp.set(wx, topY - d, wz);
                        BlockState ws = chunk.getBlockState(wp);
                        if (!ws.isAir() && ws.getFluidState().isEmpty()) lastId = BuiltInRegistries.BLOCK.getId(ws.getBlock()) + 1;
                        if (lastId > 2046) lastId = 0;
                        if (runLen > 0 && (lastId != runId || runLen >= 31)) {
                            if (slot >= WALL_R - 1) { full = true; break; }
                            result[base + slot++] = (runId << 5) | runLen;
                            runLen = 0;
                        }
                        runId = lastId;
                        runLen++;
                    }
                    // a última execução vale até o fundo
                    if (runLen > 0 && slot < WALL_R) result[base + slot] = (runId << 5);
                }
                if (state.getFluidState().isEmpty()) scanSpans(chunk, wx, wz, pos.getY(), result, 768 + 256 * WALL_R, outIndex);
                FluidState fluid = state.getFluidState();

                int color;
                if (!fluid.isEmpty() && !fluid.is(FluidTags.LAVA)) {
                    // água: o estilo final pinta pelo flag/profundidade guardado no byte alto
                    // profundidade: conta os blocos de fluido abaixo da superfície (o heightmap do fundo não existe no cliente)
                    int depth = 0;
                    int y0 = pos.getY();
                    for (int y = y0 - 1; y >= -64 && depth < 63; y--) {
                        pos.setY(y);
                        FluidState below = chunk.getFluidState(pos);
                        if (below.isEmpty() || below.is(FluidTags.LAVA)) break;
                        depth++;
                    }
                    pos.setY(y0);
                    color = (0x80 | depth) << 24;
                } else if (!fluid.isEmpty()) {
                    color = 0xFFD9561A;
                } else {
                    int c = blockColor(level, state, pos);
                    if (c == 0) {
                        result[outIndex] = 0;
                        continue;
                    }
                    int hW = neighborHeight(chunk, west, north, northWest, heights, lx - 1, lz, top);
                    int hN = neighborHeight(chunk, west, north, northWest, heights, lx, lz - 1, top);
                    int slope = (top - hW) + (top - hN);
                    // relevo estilo Xaero's: cada degrau de 1 bloco escurece/clareia bem (luz vinda do noroeste) + leve tom por altitude
                    float shade = 1.0f + Math.max(-4, Math.min(4, slope)) * 0.20f + Math.max(-0.10f, Math.min(0.10f, (top - 70) * 0.0025f));
                    color = shadeColor(c, shade);
                }
                result[outIndex] = color;
            }
        }
        return result;
    }

    /**
     * Camadas de sólido de uma coluna (para o 3D ver por baixo de beirais, pontes e topos largos): o fundo da
     * camada de cima e até duas camadas abaixo de vãos de 3+ blocos de ar. Para quando acha 16 blocos sólidos seguidos.
     */
    private static void scanSpans(ChunkAccess chunk, int wx, int wz, int topY, int[] out, int ex, int k) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int minY = Math.max(-64, topY - 300);
        int spanIdx = 0;                 // 0 = camada de cima
        int solidRun = 1, air = 0, lastSolid = topY;
        boolean in = true;
        out[ex + k] = 0;
        for (int y = topY - 1; y >= minY; y--) {
            q.set(wx, y, wz);
            BlockState bs = chunk.getBlockState(q);
            boolean solid = !bs.isAir() && bs.getFluidState().isEmpty();
            if (in) {
                if (solid) {
                    lastSolid = y;
                    air = 0;
                    if (++solidRun >= 16) break;
                } else {
                    solidRun = 0;
                    air++;
                    if (air >= 3) {
                        in = false;
                        // fecha a camada: fundo na face de baixo do último sólido
                        if (spanIdx == 0) out[ex + k] = lastSolid + 64;
                        else out[ex + 256 + k * SP_N + (spanIdx - 1) * 3 + 1] = lastSolid + 64;
                    }
                }
            } else {
                if (solid) {
                    if (spanIdx >= 2) { break; }
                    spanIdx++;
                    int id = BuiltInRegistries.BLOCK.getId(bs.getBlock()) + 1;
                    int b = ex + 256 + k * SP_N + (spanIdx - 1) * 3;
                    out[b] = y + 1 + 64;
                    out[b + 2] = id > 32000 ? 0 : id;
                    out[b + 1] = 0;
                    in = true;
                    solidRun = 1;
                    air = 0;
                    lastSolid = y;
                }
            }
        }
    }

    /**
     * O heightmap devolve a altura do bloco mais alto (ou 1 a mais, conforme a versão):
     * lê em h e, se for ar, em h-1. Deixa pos no bloco lido.
     */
    private static BlockState stateAtTop(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int wx, int h, int wz) {
        pos.set(wx, h, wz);
        BlockState s = chunk.getBlockState(pos);
        if (s.isAir()) {
            pos.set(wx, h - 1, wz);
            s = chunk.getBlockState(pos);
        }
        return s;
    }

    private static int neighborHeight(ChunkAccess self, ChunkAccess west, ChunkAccess north, ChunkAccess northWest,
                                      int[] heights, int lx, int lz, int fallback) {
        if (lx >= 0 && lz >= 0) return heights[lz * 16 + lx];
        ChunkAccess c;
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
        return c.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    // ------------------------------------------------------------- cores de bloco

    /** Textura de um bloco: cor média + pixels (primeiro quadro), para dar granulado. */
    static final class Tex {
        int avg;
        int[] px;
        int w;
    }

    private static final Tex NO_TEX = new Tex();
    private static final Map<Block, Tex> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Block, Tex> SIDE_TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] STRIP_SUFFIXES = {
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_button", "_pressure_plate"
    };

    /** Cor do bloco: textura (face de cima) com granulado, multiplicada pelo tint do bioma. */
    private static int blockColor(Level level, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        Tex t = TEXTURES.get(block);
        if (t == null) {
            t = loadTexture(block);
            TEXTURES.put(block, t);
        }
        if (t == NO_TEX) {
            MapColor mc = state.getMapColor(level, pos);
            return mc.col == 0 ? 0 : (0xFF000000 | mc.col);
        }
        int u = Math.floorMod(pos.getX(), 16) * t.w / 16;
        int v = Math.floorMod(pos.getZ(), 16) * t.w / 16;
        int s = t.px[v * t.w + u];
        int base = ((s >>> 24) < 32) ? t.avg : mix(t.avg, s, 0.6f);
        int tint;
        try {
            tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        } catch (RuntimeException e) {
            tint = -1; // sem tint do bioma (por exemplo, nível do servidor sem as cores de bioma)
        }
        if (tint != -1) return multiply(base, tint);
        return base;
    }

    /** Textura da face de cima do bloco (id do registro), ou null se não houver. */
    static Tex topTexture(int blockId) {
        Block b = BuiltInRegistries.BLOCK.byId(blockId);
        Tex t = TEXTURES.computeIfAbsent(b, k -> loadTexture(k, false));
        return t == NO_TEX ? null : t;
    }

    /** Textura da face lateral do bloco, ou null se não houver. */
    static Tex sideTexture(int blockId) {
        Block b = BuiltInRegistries.BLOCK.byId(blockId);
        Tex t = SIDE_TEXTURES.computeIfAbsent(b, k -> loadTexture(k, true));
        return t == NO_TEX ? null : t;
    }

    private static Tex loadTexture(Block block) {
        return loadTexture(block, false);
    }

    private static Tex loadTexture(Block block, boolean side) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String ns = id.getNamespace();
        String path = id.getPath();

        List<String> names = new ArrayList<>();
        if (path.equals("lava")) names.add("lava_still");
        addCandidates(names, path, side);
        for (String suffix : STRIP_SUFFIXES) {
            if (path.endsWith(suffix)) {
                String stem = path.substring(0, path.length() - suffix.length());
                addCandidates(names, stem, side);
                names.add(stem + "s");
                names.add(stem + "_planks");
                names.add(stem + "_block");
                break;
            }
        }
        for (String name : names) {
            Tex t = readTexture(ns, name);
            if (t != null) return t;
        }
        return NO_TEX;
    }

    private static void addCandidates(List<String> out, String name, boolean side) {
        if (side) {
            out.add(name + "_side");
            out.add(name);
            out.add(name + "_top");
            out.add(name + "_front");
            return;
        }
        out.add(name + "_top");
        out.add(name);
        out.add(name + "_side");
        out.add(name + "_front");
    }

    private static Tex readTexture(String ns, String name) {
        Identifier tex = Identifier.fromNamespaceAndPath(ns, "textures/block/" + name + ".png");
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(tex);
            if (res.isEmpty()) return null;
            try (java.io.InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                int w = img.getWidth();
                int h = Math.min(img.getHeight(), w); // texturas animadas: só o primeiro quadro
                if (w < 1 || h < 1 || w != h) return null;
                int[] px = new int[w * w];
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = 0; y < w; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = img.getPixel(x, y);
                        px[y * w + x] = argb;
                        if (((argb >>> 24) & 0xFF) < 32) continue;
                        r += (argb >> 16) & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += argb & 0xFF;
                        n++;
                    }
                }
                if (n == 0) return null;
                Tex t = new Tex();
                t.w = w;
                t.px = px;
                t.avg = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
                return t;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int multiply(int a, int b) {
        int r = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
        int g = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
        int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int shadeColor(int rgb, float shade) {
        shade = Math.max(0.25f, Math.min(1.3f, shade));
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * shade));
        int b = Math.min(255, (int) ((rgb & 0xFF) * shade));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
