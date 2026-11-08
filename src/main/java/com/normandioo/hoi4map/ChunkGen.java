package com.normandioo.hoi4map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gera (e mapeia) todos os chunks dentro do limite do mapa do servidor.
 *
 * Mundo local: pede os chunks direto ao servidor interno, sem mover o jogador.
 * Servidor remoto: teleporta o jogador (em modo espectador) por uma grade de pontos com /tp, espera os chunks
 * chegarem, mapeia e segue; no fim volta ao ponto de partida e ao modo de jogo anterior. Exige permissão de operador.
 */
final class ChunkGen {
    enum State { IDLE, RUNNING, PAUSED }

    private record Patch(int cx, int cz, int[] px) {}

    private enum Phase { PREP, NEXT, ARRIVE, SETTLE, SCAN, RESTORE, DONE }

    private static final int MAX_INFLIGHT = 6;
    private static final int SUBMIT_PER_TICK = 3;

    static volatile State state = State.IDLE;
    /** {minX, minZ, maxX, maxZ} em blocos. */
    static int[] limit;
    static String limitSource = "";
    static String message = "";
    static boolean remote;
    static long total, known, knownAtStart;
    static long startedAt;

    private static int minCx, minCz, maxCx, maxCz;
    private static long[] order = new long[0];
    private static int cursor;
    private static final AtomicInteger inflight = new AtomicInteger();
    private static final AtomicInteger failures = new AtomicInteger();
    private static final Queue<Patch> PATCHES = new ConcurrentLinkedQueue<>();
    private static int ticks;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

    // modo remoto
    private static Phase phase = Phase.DONE;
    private static int waited, stable, lastLoaded;
    private static double ox, oy, oz;
    private static GameType previousMode;
    private static boolean switchedMode, firstStop;
    private static List<int[]> stops = new ArrayList<>();
    private static int stopCursor;
    private static int half;
    private static int[] target;
    private static List<int[]> scanList = new ArrayList<>();
    private static int scanCursor;
    private static boolean fixup;
    private static final java.util.Set<Long> FAILED = new java.util.HashSet<>();

    private ChunkGen() {}

    // ------------------------------------------------------------------- limite

    /** Limite do mapa: borda do mundo se ela for pequena, senão a área dos territórios do site. */
    static int[] computeLimit(Minecraft mc) {
        if (mc.level != null) {
            WorldBorder wb = mc.level.getWorldBorder();
            if (wb.getSize() < 1.0E6) {
                limitSource = "borda do mundo";
                return new int[]{(int) Math.floor(wb.getMinX()), (int) Math.floor(wb.getMinZ()),
                        (int) Math.ceil(wb.getMaxX()) - 1, (int) Math.ceil(wb.getMaxZ()) - 1};
            }
        }
        int[] s = SiteTerritories.worldLimit();
        if (s != null) {
            limitSource = "mapa do site";
            return s;
        }
        limitSource = "desconhecido";
        return null;
    }

    static boolean isRemote(Minecraft mc) {
        return mc.getSingleplayerServer() == null;
    }

    static double progress() {
        return total <= 0 ? 0 : Math.min(1.0, known / (double) total);
    }

    static String eta() {
        long elapsed = System.currentTimeMillis() - startedAt;
        long gained = known - knownAtStart;
        if (state != State.RUNNING || gained <= 20 || elapsed < 5000) return "calculando...";
        double perMs = gained / (double) elapsed;
        long left = (long) ((total - known) / perMs / 1000.0);
        if (left > 3600) return (left / 3600) + " h " + ((left % 3600) / 60) + " min";
        if (left > 60) return (left / 60) + " min";
        return left + " s";
    }

    // --------------------------------------------------------------------- início

    static void start(Minecraft mc) {
        if (state != State.IDLE || mc.level == null || mc.player == null) return;
        int[] lim = computeLimit(mc);
        if (lim == null) {
            message = "Não sei o tamanho do mapa: defina worldHalfSize em config/hoi4map/site.json.";
            return;
        }
        limit = lim;
        minCx = lim[0] >> 4;
        minCz = lim[1] >> 4;
        maxCx = lim[2] >> 4;
        maxCz = lim[3] >> 4;
        total = (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
        remote = isRemote(mc);
        dimension = mc.level.dimension();
        FAILED.clear();
        PATCHES.clear();
        inflight.set(0);
        failures.set(0);
        cursor = 0;
        ticks = 0;
        startedAt = System.currentTimeMillis();
        known = countKnown();
        knownAtStart = known;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;

        if (!remote) {
            List<long[]> todo = new ArrayList<>();
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    if (MapData.chunkKnown(cx, cz)) continue;
                    long dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
                    todo.add(new long[]{dist, MapData.key(cx, cz)});
                }
            }
            todo.sort((a, b) -> Long.compare(a[0], b[0]));
            order = new long[todo.size()];
            for (int i = 0; i < order.length; i++) order[i] = todo.get(i)[1];
            message = order.length == 0 ? "Todos os chunks do limite já estão no mapa." : "Gerando pelo servidor interno...";
            if (order.length == 0) return;
        } else {
            phase = Phase.PREP;
            waited = 0;
            ox = mc.player.getX();
            oy = mc.player.getY();
            oz = mc.player.getZ();
            previousMode = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
            switchedMode = false;
            firstStop = true;
            stops = new ArrayList<>();
            stopCursor = 0;
            fixup = false;
            half = 0;
            message = "Explorando pelo servidor (teleportes /tp)...";
        }
        state = State.RUNNING;
    }

    static void togglePause() {
        if (state == State.RUNNING) state = State.PAUSED;
        else if (state == State.PAUSED) state = State.RUNNING;
    }

    /** Para tudo; no modo remoto, devolve o jogador ao lugar e ao modo de jogo de antes. */
    static void stop(Minecraft mc, String why) {
        if (state == State.IDLE) return;
        if (remote && mc.player != null && mc.getConnection() != null && phase != Phase.DONE && phase != Phase.PREP) {
            restore(mc);
        }
        finish(why);
    }

    /** Desconectou ou trocou de mundo: só zera o estado. */
    static void reset() {
        state = State.IDLE;
        phase = Phase.DONE;
        PATCHES.clear();
        order = new long[0];
        message = "";
    }

    private static void finish(String why) {
        state = State.IDLE;
        phase = Phase.DONE;
        order = new long[0];
        message = why;
        MapData.save();
    }

    private static void restore(Minecraft mc) {
        var conn = mc.getConnection();
        if (conn == null) return;
        conn.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f", ox, oy, oz));
        if (switchedMode && previousMode != null) conn.sendCommand("gamemode " + previousMode.getName());
        switchedMode = false;
    }

    // ----------------------------------------------------------------------- tick

    static long countKnown() {
        long n = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) if (MapData.chunkKnown(cx, cz)) n++;
        }
        return n;
    }

    static void tick(Minecraft mc) {
        for (int i = 0; i < 48; i++) {
            Patch p = PATCHES.poll();
            if (p == null) break;
            MapData.applyChunk(p.cx, p.cz, p.px);
        }
        if (state == State.IDLE) return;
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        if (!mc.level.dimension().equals(dimension)) {
            stop(mc, "Parado: você mudou de dimensão.");
            return;
        }
        ticks++;
        if (ticks % 40 == 0) known = countKnown();
        if (ticks % 20 == 0 && !(mc.screen instanceof MapScreen)) {
            mc.gui.setOverlayMessage(Component.literal("Gerando mapa: " + (int) (progress() * 100) + "%  (" + known + "/" + total + ")"), false);
        }
        if (state == State.PAUSED) return;
        if (remote) tickRemote(mc);
        else tickLocal(mc);
    }

    // ------------------------------------------------------------------ mundo local

    private static void tickLocal(Minecraft mc) {
        IntegratedServer srv = mc.getSingleplayerServer();
        ServerLevel sl = srv == null ? null : srv.getLevel(mc.level.dimension());
        if (sl == null) {
            stop(mc, "Parado: o servidor interno não está disponível.");
            return;
        }
        if (failures.get() > 200) {
            stop(mc, "Parado: muitos chunks falharam (veja o log do jogo).");
            return;
        }
        int submitted = 0;
        while (cursor < order.length && inflight.get() < MAX_INFLIGHT && submitted < SUBMIT_PER_TICK) {
            long k = order[cursor++];
            final int cx = MapData.keyA(k), cz = MapData.keyB(k);
            inflight.incrementAndGet();
            srv.execute(() -> {
                try {
                    job(sl, cx, cz);
                } catch (Throwable t) {
                    if (failures.incrementAndGet() <= 5) Hoi4MapClient.LOG.warn("Falha ao gerar o chunk {},{}", cx, cz, t);
                } finally {
                    inflight.decrementAndGet();
                }
            });
            submitted++;
        }
        if (cursor >= order.length && inflight.get() == 0 && PATCHES.isEmpty()) {
            known = countKnown();
            finish("Concluído: " + known + " de " + total + " chunks no mapa.");
        }
    }

    /** No thread do servidor: gera o chunk (e os vizinhos de sombreamento) e calcula as cores. */
    private static void job(ServerLevel sl, int cx, int cz) {
        ChunkAccess c = sl.getChunk(cx, cz, ChunkStatus.FULL, true);
        if (cx - 1 >= minCx) sl.getChunk(cx - 1, cz, ChunkStatus.FULL, true);
        if (cz - 1 >= minCz) sl.getChunk(cx, cz - 1, ChunkStatus.FULL, true);
        if (cx - 1 >= minCx && cz - 1 >= minCz) sl.getChunk(cx - 1, cz - 1, ChunkStatus.FULL, true);
        PATCHES.add(new Patch(cx, cz, MapData.computeChunk(sl, c, cx, cz)));
    }

    // ---------------------------------------------------------------- servidor remoto

    private static boolean loaded(Minecraft mc, int cx, int cz) {
        return mc.level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    private static boolean areaKnown(int cx, int cz, int h) {
        for (int x = Math.max(minCx, cx - h); x <= Math.min(maxCx, cx + h); x++) {
            for (int z = Math.max(minCz, cz - h); z <= Math.min(maxCz, cz + h); z++) {
                if (!MapData.chunkKnown(x, z) && !FAILED.contains(MapData.key(x, z))) return false;
            }
        }
        return true;
    }

    private static void buildGrid(Minecraft mc, int pcx, int pcz) {
        // alcance observado: até onde o servidor mandou chunks em volta do jogador
        int eff = 0;
        int cap = Math.max(2, Math.min(mc.options.renderDistance().get(), 32));
        for (int d = 1; d <= cap; d++) {
            if (loaded(mc, pcx + d, pcz) && loaded(mc, pcx - d, pcz) && loaded(mc, pcx, pcz + d) && loaded(mc, pcx, pcz - d)) eff = d;
            else break;
        }
        half = Math.max(1, eff - 1);
        int spacing = 2 * half;
        List<Integer> xs = new ArrayList<>(), zs = new ArrayList<>();
        for (int c = minCx + half; ; c += spacing) {
            xs.add(Math.min(c, maxCx));
            if (c >= maxCx) break;
        }
        for (int c = minCz + half; ; c += spacing) {
            zs.add(Math.min(c, maxCz));
            if (c >= maxCz) break;
        }
        stops = new ArrayList<>();
        for (int x : xs) for (int z : zs) stops.add(new int[]{x, z});
        stops.sort((a, b) -> Long.compare(dist2(a, pcx, pcz), dist2(b, pcx, pcz)));
        stopCursor = 0;
        message = "Explorando: pontos a cada " + (spacing * 16) + " blocos (alcance " + eff + " chunks).";
    }

    private static long dist2(int[] s, int cx, int cz) {
        long dx = s[0] - cx, dz = s[1] - cz;
        return dx * dx + dz * dz;
    }

    /** Próximo ponto que ainda tem chunk desconhecido; null quando terminou. */
    private static int[] nextStop() {
        while (stopCursor < stops.size()) {
            int[] s = stops.get(stopCursor++);
            if (!areaKnown(s[0], s[1], half)) return s;
        }
        if (!fixup) {
            // revisão: qualquer chunk que ficou de fora vira um ponto
            fixup = true;
            stops = new ArrayList<>();
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    if (!MapData.chunkKnown(cx, cz) && !FAILED.contains(MapData.key(cx, cz))) stops.add(new int[]{cx, cz});
                }
            }
            stopCursor = 0;
            return nextStop();
        }
        return null;
    }

    private static void tickRemote(Minecraft mc) {
        var conn = mc.getConnection();
        if (conn == null) {
            reset();
            return;
        }
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        switch (phase) {
            case PREP -> {
                if (waited == 0) {
                    if (previousMode != GameType.SPECTATOR) {
                        conn.sendCommand("gamemode spectator");
                        switchedMode = true;
                    }
                }
                if (++waited >= 10) {
                    phase = Phase.NEXT;
                    waited = 0;
                }
            }
            case NEXT -> {
                if (firstStop) {
                    // o primeiro ponto é onde o jogador está: mede o alcance e monta a grade
                    firstStop = false;
                    stops = new ArrayList<>();
                    target = new int[]{Math.max(minCx, Math.min(maxCx, pcx)), Math.max(minCz, Math.min(maxCz, pcz))};
                    phase = Phase.SETTLE;
                    waited = 0;
                    stable = 0;
                    lastLoaded = -1;
                    return;
                }
                target = nextStop();
                if (target == null) {
                    phase = Phase.RESTORE;
                    waited = 0;
                    return;
                }
                conn.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f", target[0] * 16 + 8.0, oy, target[1] * 16 + 8.0));
                phase = Phase.ARRIVE;
                waited = 0;
            }
            case ARRIVE -> {
                waited++;
                double dx = mc.player.getX() - (target[0] * 16 + 8.0), dz = mc.player.getZ() - (target[1] * 16 + 8.0);
                if (dx * dx + dz * dz < 24 * 24 && waited >= 5) {
                    phase = Phase.SETTLE;
                    waited = 0;
                    stable = 0;
                    lastLoaded = -1;
                } else if (waited > 100) {
                    if (stopCursor <= 1 && !fixup) {
                        stop(mc, "Parado: o /tp não funcionou (sem permissão de operador?).");
                        return;
                    }
                    FAILED.add(MapData.key(target[0], target[1]));
                    phase = Phase.NEXT;
                }
            }
            case SETTLE -> {
                waited++;
                if (waited % 10 == 0) {
                    int rd = Math.max(2, Math.min(mc.options.renderDistance().get(), 32));
                    int count = 0;
                    for (int dx = -rd; dx <= rd; dx++) for (int dz = -rd; dz <= rd; dz++) if (loaded(mc, pcx + dx, pcz + dz)) count++;
                    if (count == lastLoaded) stable++;
                    else stable = 0;
                    lastLoaded = count;
                }
                if ((stable >= 2 && waited >= 30) || waited >= 260) {
                    int rd = Math.max(2, Math.min(mc.options.renderDistance().get(), 32));
                    scanList = new ArrayList<>();
                    for (int dx = -rd; dx <= rd; dx++) for (int dz = -rd; dz <= rd; dz++) scanList.add(new int[]{pcx + dx, pcz + dz});
                    scanCursor = 0;
                    phase = Phase.SCAN;
                }
            }
            case SCAN -> {
                int n = 0;
                var level = mc.level;
                while (scanCursor < scanList.size() && n < 24) {
                    int[] c = scanList.get(scanCursor++);
                    var chunk = level.getChunkSource().getChunkNow(c[0], c[1]);
                    if (chunk == null) continue;
                    MapData.applyChunk(c[0], c[1], MapData.computeChunk(level, chunk, c[0], c[1]));
                    n++;
                }
                if (scanCursor >= scanList.size()) {
                    if (target != null && !MapData.chunkKnown(target[0], target[1])) FAILED.add(MapData.key(target[0], target[1]));
                    if (half == 0) buildGrid(mc, pcx, pcz);
                    phase = Phase.NEXT;
                }
            }
            case RESTORE -> {
                restore(mc);
                known = countKnown();
                finish("Concluído: " + known + " de " + total + " chunks no mapa.");
            }
            default -> { }
        }
    }
}
