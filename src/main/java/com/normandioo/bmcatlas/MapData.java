package com.normandioo.bmcatlas;

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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.StandardCopyOption;

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
        int[] px = EMPTY_PX; // ARGB; 0 = desconhecido. Compartilhado até a região ser lida de verdade.
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

        /** One bit per chunk already read (16x16), kept in memory even while the pixels are only on disk. */
        final long[] known = new long[4];
        /** The pixels have a file on disk (they may or may not be in memory right now). */
        boolean onDisk;
        /** A background read of the file is on its way. */
        boolean loading;
        /** Pixels changed since the last write. */
        boolean unsaved;
        /** Tick of the last read or write of the pixels (least recently used go back to disk first). */
        long pxUsed;

        /** Garante a textura e (re)pinta os pixels no estilo do modo atual, se necessário. */
        boolean ensureUploaded(int mode) {
            if (px == EMPTY_PX && onDisk) {      // pixels are on disk: ask for them and paint when they arrive
                requestLoad(this, false);
                return false;
            }
            if (texture == null) {
                image = new NativeImage(NativeImage.Format.RGBA, REGION_PX, REGION_PX, true);
                texture = new DynamicTexture(() -> "bmcatlas region", image);
                id = Identifier.fromNamespaceAndPath(BmcAtlasClient.MOD_ID, "region/r" + (textureCounter++));
                Minecraft.getInstance().getTextureManager().register(id, texture);
                dirty = true;
            }
            int ver = MapStyle.overrideVersion;
            if (dirty || styledMode != mode || styledVer != ver) {
                int[] out = MapStyle.paint(this, mode);
                for (int y = 0; y < REGION_PX; y++) {
                    for (int x = 0; x < REGION_PX; x++) {
                        image.setPixel(x, y, out[y * REGION_PX + x]);
                    }
                }
                texture.upload();
                dirty = false;
                styledMode = mode;
                styledVer = ver;
                pxUsed = tickCount;
                return true;
            }
            return false;
        }

        /** Dá à região o seu próprio array de pixels (ao ler chunks ou carregar do disco). */
        int[] writable() {
            if (px == EMPTY_PX) {
                px = new int[REGION_PX * REGION_PX];
                inMemory++;
            }
            return px;
        }

        boolean hasData() {
            return px != EMPTY_PX || onDisk;
        }

        void release() {
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(id);
                texture = null;
                image = null;
                id = null;
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
    private static boolean ownersDirty;

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
        ownersDirty = true;
        for (Region r : REGIONS.values()) r.styledMode = -1;
    }

    static final int PROVS_PER_REGION = REGION_CHUNKS / PROV;

    static void setOwner(int px, int pz, String id) {
        if (id == null) OWNERS.remove(key(px, pz));
        else OWNERS.put(key(px, pz), id);
        ownersVersion++;
        dirty = true;
        ownersDirty = true;
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
        pumpLoads();
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
        int bit = Math.floorMod(cz, REGION_CHUNKS) * REGION_CHUNKS + Math.floorMod(cx, REGION_CHUNKS);
        return (r.known[bit >> 6] & (1L << (bit & 63))) != 0;
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
        return FabricLoader.getInstance().getGameDir().resolve("bmcatlas");
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
        flush(true);
        clear();
        worldKey = k;
        load();
    }

    private static void clear() {
        for (Region r : REGIONS.values()) r.release();
        REGIONS.clear();
        OWNERS.clear();
        LAST_SCAN.clear();
        LOADED.clear();
        loadingCount = 0;
        inMemory = 0;
        cursor = 0;
        ownersVersion++;
        dirty = false;
        ownersDirty = false;
    }

    static void unload() {
        flush(true);
        clear();
        worldKey = null;
    }

    // ---------------------------------------------------------------- persistence

    // One file per region (<world>.regions/r.<rx>.<rz>.bmr): magic, 4 longs with the chunks already read, then the
    // pixels deflated. Files are read and written on a background thread; only changed regions are written, and the
    // pixels of regions not used for a while go back to disk, so memory does not grow with the explored world.

    private static final int MAGIC = 0x424D5231;       // "BMR1"
    private static final int HEADER = 4 + 4 * 8;
    private static final int MAX_LOADING = 8;
    private static final int MAX_IN_MEMORY = 96;        // regions with pixels in memory (about 1 MB each)

    private record Loaded(Region r, int[] px) {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bmcatlas-io");
        t.setDaemon(true);
        return t;
    });
    private static final Queue<Loaded> LOADED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pendingWrites = new AtomicInteger();
    private static int loadingCount;
    private static int inMemory;

    static {
        // writes still in the queue are finished before the game exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            IO.shutdown();
            try {
                IO.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // leaving anyway
            }
        }, "bmcatlas-io-flush"));
    }

    private static void exec(Runnable task) {
        try {
            IO.execute(task);
        } catch (RejectedExecutionException e) {
            task.run();      // game is closing: do it right here
        }
    }

    private static Path regionsDir() {
        return dir().resolve(worldKey + ".regions");
    }

    private static Path regionFile(Path regionsDir, int rx, int rz) {
        return regionsDir.resolve("r." + rx + "." + rz + ".bmr");
    }

    private static void computeKnown(int[] px, long[] known) {
        java.util.Arrays.fill(known, 0L);
        for (int cz = 0; cz < REGION_CHUNKS; cz++) {
            for (int cx = 0; cx < REGION_CHUNKS; cx++) {
                if (px[(cz * 16 + 8) * REGION_PX + cx * 16 + 8] == 0) continue;
                int bit = cz * REGION_CHUNKS + cx;
                known[bit >> 6] |= 1L << (bit & 63);
            }
        }
    }

    private static void writeAtomic(Path file, java.util.function.Consumer<DataOutputStream> body) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
            try {
                body.accept(out);
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeRegion(Path file, long[] known, byte[] comp, int len) throws IOException {
        writeAtomic(file, out -> {
            try {
                out.writeInt(MAGIC);
                for (long k : known) out.writeLong(k);
                out.write(comp, 0, len);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private static int[] readRegion(Path file) throws IOException, DataFormatException {
        byte[] all = Files.readAllBytes(file);
        if (all.length <= HEADER || ByteBuffer.wrap(all).getInt() != MAGIC) throw new IOException("not a region file");
        int[] px = new int[REGION_PX * REGION_PX];
        inflateInto(all, HEADER, all.length - HEADER, px);
        return px;
    }

    /** Asks for the pixels of a region that is only on disk; they arrive a moment later (see pumpLoads). */
    private static void requestLoad(Region r, boolean force) {
        if (r.loading || !r.onDisk || worldKey == null) return;
        if (!force && (loadingCount >= MAX_LOADING || inMemory >= MAX_IN_MEMORY + 32)) return;
        r.loading = true;
        loadingCount++;
        Path file = regionFile(regionsDir(), r.rx, r.rz);
        exec(() -> {
            int[] px = null;
            try {
                px = readRegion(file);
            } catch (IOException | DataFormatException | RuntimeException e) {
                BmcAtlasClient.LOG.warn("Unreadable region file: {}", file, e);
            }
            LOADED.add(new Loaded(r, px));
        });
    }

    private static void merge(Region r, int[] disk) {
        if (r.px == EMPTY_PX) {
            r.px = disk;
            inMemory++;
        } else {
            int[] px = r.px;      // chunks read while the file was on its way stay on top
            for (int i = 0; i < px.length; i++) if (px[i] == 0) px[i] = disk[i];
        }
        r.dirty = true;
        r.pxUsed = tickCount;
    }

    /** Client thread: takes the regions that finished reading. */
    private static void pumpLoads() {
        Loaded l;
        while ((l = LOADED.poll()) != null) {
            Region r = l.r;
            if (REGIONS.get(key(r.rx, r.rz)) != r || !r.loading) continue;   // another world, or already merged
            r.loading = false;
            loadingCount = Math.max(0, loadingCount - 1);
            if (l.px == null) {
                r.onDisk = false;
                continue;
            }
            merge(r, l.px);
        }
    }

    /** Pixels of regions not used lately go back to disk (their textures stay). */
    private static void evict() {
        if (inMemory <= MAX_IN_MEMORY) return;
        List<Region> candidates = new ArrayList<>();
        int ver = MapStyle.overrideVersion;
        for (Region r : REGIONS.values()) {
            if (r.px == EMPTY_PX || !r.onDisk || r.unsaved || r.loading) continue;
            boolean painted = r.id != null && !r.dirty && r.styledVer == ver;
            if (painted ? tickCount - r.pxUsed > 20 : tickCount - r.pxUsed > 200) candidates.add(r);
        }
        candidates.sort(Comparator.comparingLong(r -> r.pxUsed));
        for (Region r : candidates) {
            if (inMemory <= MAX_IN_MEMORY) break;
            r.px = EMPTY_PX;
            inMemory--;
        }
    }

    private static void load() {
        if (worldKey == null) return;
        try {
            IO.submit(() -> {}).get(20, TimeUnit.SECONDS);     // writes of the previous world first
        } catch (Exception ignored) {
            // carry on with what is on disk
        }
        Path rd = regionsDir();
        Path old = dir().resolve(worldKey + ".regions5");
        if (!Files.isDirectory(rd) && Files.exists(old)) migrate(old, rd);
        if (Files.isDirectory(rd)) {
            try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(rd, "r.*.bmr")) {
                for (Path f : files) {
                    String[] parts = f.getFileName().toString().split("\\.");
                    if (parts.length != 4) continue;
                    try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
                        int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
                        if (in.readInt() != MAGIC) continue;
                        Region r = new Region(rx, rz);
                        for (int i = 0; i < r.known.length; i++) r.known[i] = in.readLong();
                        r.onDisk = true;
                        REGIONS.put(key(rx, rz), r);
                    } catch (IOException | RuntimeException e) {
                        BmcAtlasClient.LOG.warn("Unreadable region file: {}", f, e);
                    }
                }
            } catch (IOException e) {
                BmcAtlasClient.LOG.warn("Could not list {}", rd, e);
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
                BmcAtlasClient.LOG.warn("Unreadable province owners: {}", owners, e);
            }
        }
        ownersVersion++;
        dirty = false;
        ownersDirty = false;
    }

    /** One time: splits the old single file (<world>.regions5) into one file per region. The old file is kept as .old. */
    private static void migrate(Path old, Path rd) {
        Path tmp = rd.resolveSibling(rd.getFileName() + ".migrating");
        try {
            if (Files.isDirectory(tmp)) {
                try (java.util.stream.Stream<Path> left = Files.list(tmp)) {
                    for (Path f : (Iterable<Path>) left::iterator) Files.deleteIfExists(f);
                }
            }
            Files.createDirectories(tmp);
            int[] px = new int[REGION_PX * REGION_PX];
            long[] known = new long[4];
            int n;
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(old)))) {
                n = in.readInt();
                for (int i = 0; i < n; i++) {
                    int rx = in.readInt(), rz = in.readInt(), len = in.readInt();
                    byte[] comp = new byte[len];
                    in.readFully(comp);
                    java.util.Arrays.fill(px, 0);
                    inflateInto(comp, 0, len, px);
                    computeKnown(px, known);
                    writeRegion(regionFile(tmp, rx, rz), known, comp, len);
                }
            }
            Files.move(tmp, rd);
            Files.move(old, old.resolveSibling(old.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            BmcAtlasClient.LOG.info("Terrain cache converted to one file per region ({} regions)", n);
        } catch (IOException | DataFormatException | RuntimeException e) {
            BmcAtlasClient.LOG.warn("Could not convert the old terrain cache {}", old, e);
        }
    }

    /** Writes what changed, in the background. Cheap: safe to call often. */
    static void save() {
        flush(false);
    }

    /**
     * Hands the changed regions to the writer thread. all = leaving this world: nothing may be left behind, so a
     * region still waiting for its file is read right here before it is written.
     */
    private static void flush(boolean all) {
        if (worldKey == null) return;
        Path rd = regionsDir();
        for (Region r : REGIONS.values()) {
            if (!r.unsaved || r.px == EMPTY_PX) continue;
            if (r.loading) {
                if (!all) continue;
                try {
                    merge(r, readRegion(regionFile(rd, r.rx, r.rz)));
                } catch (IOException | DataFormatException | RuntimeException e) {
                    BmcAtlasClient.LOG.warn("Unreadable region file r.{}.{}", r.rx, r.rz, e);
                }
                r.loading = false;
            }
            if (!all && pendingWrites.get() >= 24) break;       // the writer is busy: the rest goes next time
            int[] snapshot = r.px.clone();
            long[] known = r.known.clone();
            Path file = regionFile(rd, r.rx, r.rz);
            r.unsaved = false;
            r.onDisk = true;
            pendingWrites.incrementAndGet();
            exec(() -> {
                try {
                    byte[] comp = deflate(snapshot);
                    writeRegion(file, known, comp, comp.length);
                } catch (IOException | RuntimeException e) {
                    BmcAtlasClient.LOG.warn("Could not write {}", file, e);
                } finally {
                    pendingWrites.decrementAndGet();
                }
            });
        }
        if (ownersDirty) {
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<Long, String> e : OWNERS.entrySet()) {
                m.put(keyA(e.getKey()) + "," + keyB(e.getKey()), e.getValue());
            }
            Path owners = dir().resolve(worldKey + ".owners.json");
            try {
                Files.createDirectories(owners.getParent());
                Path tmp = owners.resolveSibling(owners.getFileName() + ".tmp");
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(m, w);
                }
                Files.move(tmp, owners, StandardCopyOption.REPLACE_EXISTING);
                ownersDirty = false;
            } catch (IOException | RuntimeException e) {
                BmcAtlasClient.LOG.warn("Could not save province owners", e);
            }
        }
        dirty = false;
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

    private static void inflateInto(byte[] comp, int offset, int len, int[] px) throws DataFormatException {
        Inflater inf = new Inflater();
        inf.setInput(comp, offset, len);
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
        pumpLoads();
        if (tickCount % 100 == 0) flush(false);
        if (tickCount % 10 == 5) evict();

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
        if (reg.px == EMPTY_PX && reg.onDisk) requestLoad(reg, true);   // the rest of the region comes from disk and is merged in
        int[] dst = reg.writable();
        int lcx = Math.floorMod(cx, REGION_CHUNKS), lcz = Math.floorMod(cz, REGION_CHUNKS);
        int ox = lcx * 16, oz = lcz * 16;
        // only a chunk that really changed repaints the region and goes back to disk
        boolean changed = false;
        for (int lz = 0; lz < 16; lz++) {
            int off = (oz + lz) * REGION_PX + ox;
            if (!changed) {
                for (int k = 0; k < 16; k++) {
                    if (dst[off + k] != px[lz * 16 + k]) {
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) System.arraycopy(px, lz * 16, dst, off, 16);
        }
        int bit = lcz * REGION_CHUNKS + lcx;
        long mask = 1L << (bit & 63);
        boolean isKnown = px[8 * 16 + 8] != 0;
        if (((reg.known[bit >> 6] & mask) != 0) != isKnown) {
            if (isKnown) reg.known[bit >> 6] |= mask;
            else reg.known[bit >> 6] &= ~mask;
            changed = true;
        }
        reg.pxUsed = tickCount;
        if (!changed) return;
        reg.dirty = true;
        reg.unsaved = true;
        dirty = true;
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
        int[] result = new int[256];
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
                BlockState state = stateAtTop(chunk, pos, wx, top, wz);
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
    private static final class Tex {
        int avg;
        int[] px;
        int w;
    }

    private static final Tex NO_TEX = new Tex();
    private static final Map<Block, Tex> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();

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
            tint = Compat.tint(state, level, pos);
        } catch (RuntimeException e) {
            tint = -1; // sem tint do bioma (por exemplo, nível do servidor sem as cores de bioma)
        }
        if (tint != -1) return multiply(base, tint);
        return base;
    }

    private static Tex loadTexture(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String ns = id.getNamespace();
        String path = id.getPath();

        List<String> names = new ArrayList<>();
        if (path.equals("lava")) names.add("lava_still");
        addCandidates(names, path);
        for (String suffix : STRIP_SUFFIXES) {
            if (path.endsWith(suffix)) {
                String stem = path.substring(0, path.length() - suffix.length());
                addCandidates(names, stem);
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

    private static void addCandidates(List<String> out, String name) {
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
