package com.normandioo.hoi4map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Jogadores online e onde estão, lidos do dynmap do servidor (um GET igual ao que o navegador faz ao abrir o
 * dynmap). Só pergunta enquanto a tela do mapa está aberta, a cada poucos segundos; não fala com o servidor do
 * jogo e não altera nada. Se o dynmap não responder, usa os jogadores que o próprio jogo tem por perto.
 */
final class OnlinePlayers {
    /**
     * Um jogador na tela. A posição mostrada persegue, a cada quadro, uma previsão (última posição lida mais a
     * velocidade estimada entre as duas últimas leituras): o movimento fica contínuo em vez de andar-e-parar
     * a cada leitura do dynmap.
     */
    static final class Entry {
        final String account;
        String name;
        double shownX, shownZ;      // o que se desenha
        double toX, toZ;            // última posição lida
        double vx, vz;              // blocos por milissegundo
        long toTime;                // quando a posição mudou pela última vez
        long lastFrame;

        Entry(String account) {
            this.account = account;
        }

        /** Avança a posição mostrada até o instante now (uma vez por quadro). */
        void step(long now, long every) {
            double dtFrame = Math.max(0, Math.min(250, now - lastFrame));
            lastFrame = now;
            double ahead = Math.min(now - toTime, every + 400);
            double px = toX + vx * ahead, pz = toZ + vz * ahead;
            if (Math.abs(px - shownX) + Math.abs(pz - shownZ) > 96) {   // teleporte: não desliza pelo mapa
                shownX = px;
                shownZ = pz;
                return;
            }
            double a = 1 - Math.exp(-dtFrame / 220.0);
            shownX += (px - shownX) * a;
            shownZ += (pz - shownZ) * a;
        }

        double x(long now) {
            return shownX;
        }

        double z(long now) {
            return shownZ;
        }
    }

    private record Raw(String account, String name, double x, double z) {}

    private record Result(List<Raw> players, String error) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final AtomicReference<Result> INBOX = new AtomicReference<>();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static boolean inFlight;
    private static long lastPoll, lastNear;
    private static String error;
    private static boolean fromDynmap;

    private OnlinePlayers() {}

    /** Chamado a cada quadro da tela do mapa (thread principal). */
    static void tick() {
        if (!SiteTerritories.showPlayers()) {
            ENTRIES.clear();
            return;
        }
        long now = System.currentTimeMillis();
        Result res = INBOX.getAndSet(null);
        if (res != null) {
            inFlight = false;
            error = res.error;
            fromDynmap = res.error == null;
            apply(res.players, now);
        }
        if (error != null) {
            // sem dynmap: mostra quem o jogo conhece por perto (a cada 1 s)
            if (now - lastNear >= 1000) {
                lastNear = now;
                apply(nearby(), now);
            }
        }
        int every = SiteTerritories.playersRefreshMs();
        for (Entry en : ENTRIES.values()) en.step(now, every);
        // quem está carregado perto de você tem posição exata no próprio jogo: vale por cima do dynmap
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p == mc.player) continue;
                Entry en = ENTRIES.get(p.getName().getString().toLowerCase(Locale.ROOT));
                if (en == null) continue;
                en.shownX = en.toX = p.getX();
                en.shownZ = en.toZ = p.getZ();
                en.vx = en.vz = 0;
                en.toTime = now;
            }
        }
        String base = SiteTerritories.dynmapUrl();
        if (base != null && !inFlight && now - lastPoll >= every) {
            lastPoll = now;
            inFlight = true;
            poll(base, SiteTerritories.dynmapWorld());
        }
    }

    /** Jogadores atuais (já suavizados), na ordem de leitura. */
    static List<Entry> list() {
        return new ArrayList<>(ENTRIES.values());
    }

    static boolean live() {
        return fromDynmap;
    }

    static String error() {
        return error;
    }

    /** Esquece tudo (ao fechar o mapa). */
    static void reset() {
        ENTRIES.clear();
        INBOX.set(null);
        inFlight = false;
        lastPoll = 0;
    }

    private static void apply(List<Raw> raw, long now) {
        Map<String, Raw> seen = new HashMap<>();
        for (Raw r : raw) seen.put(r.account.toLowerCase(Locale.ROOT), r);
        ENTRIES.keySet().removeIf(k -> !seen.containsKey(k));
        long every = SiteTerritories.playersRefreshMs();
        for (Map.Entry<String, Raw> e : seen.entrySet()) {
            Raw r = e.getValue();
            Entry en = ENTRIES.get(e.getKey());
            if (en == null) {
                en = new Entry(r.account);
                en.shownX = en.toX = r.x;
                en.shownZ = en.toZ = r.z;
                en.toTime = now;
                en.lastFrame = now;
                ENTRIES.put(e.getKey(), en);
            } else if (Math.abs(en.toX - r.x) > 0.01 || Math.abs(en.toZ - r.z) > 0.01) {
                long dt = now - en.toTime;
                double nvx = 0, nvz = 0;
                if (dt > 50 && dt < 6000) {
                    nvx = (r.x - en.toX) / dt;
                    nvz = (r.z - en.toZ) / dt;
                }
                // mistura com a velocidade anterior para não balançar
                en.vx = en.vx * 0.4 + nvx * 0.6;
                en.vz = en.vz * 0.4 + nvz * 0.6;
                en.toX = r.x;
                en.toZ = r.z;
                en.toTime = now;
            } else if (now - en.toTime > every * 1.6 + 200) {
                en.vx = 0;     // parou: some a velocidade e a posição mostrada desliza até o ponto
                en.vz = 0;
            }
            en.name = r.name;
        }
    }

    private static List<Raw> nearby() {
        List<Raw> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return out;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            String n = p.getName().getString();
            out.add(new Raw(n, n, p.getX(), p.getZ()));
        }
        return out;
    }

    private static void poll(String base, String world) {
        String url = base + "up/world/" + world + "/" + System.currentTimeMillis();
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "hoi4map-fabric-mod").GET().build(),
                HttpResponse.BodyHandlers.ofString()).whenComplete((response, err) -> {
            try {
                if (err != null) throw new RuntimeException(err);
                if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode());
                INBOX.set(new Result(parse(response.body(), world), null));
            } catch (Exception e) {
                Hoi4MapClient.LOG.debug("Dynmap sem resposta: {}", e.toString());
                INBOX.set(new Result(List.of(), e.getClass().getSimpleName()));
            }
        });
    }

    private static List<Raw> parse(String body, String world) {
        List<Raw> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray arr = root.has("players") && root.get("players").isJsonArray() ? root.getAsJsonArray("players") : new JsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String w = str(o, "world");
            if (w != null && !w.equals(world)) continue; // fora deste mundo ou escondido
            String account = str(o, "account");
            String name = str(o, "name");
            if (account == null) account = name;
            if (account == null || !o.has("x") || !o.has("z")) continue;
            double x = o.get("x").getAsDouble(), z = o.get("z").getAsDouble();
            if (name == null) name = account;
            out.add(new Raw(account, stripTags(name), x, z));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** Tira marcas HTML e códigos de cor do nome que o dynmap pode mandar. */
    private static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", "").replaceAll("§.", "").trim();
    }
}
