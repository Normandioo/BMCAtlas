package com.normandioo.hoi4map;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * O que o mapa marca por cima de uma guerra, recalculado uma vez por segundo:
 * ESPADA numa cidade com jogadores online de dois lados dentro do território dela (limite de captura, senão a província);
 * BANDEIRA numa cidade recém-tomada, na cor de quem tomou (FLAG_MS depois da mudança ser vista).
 * A primeira leitura de uma guerra só marca o ponto de partida: nada já tomado ganha bandeira ao entrar.
 */
final class WarMarkers {
    private WarMarkers() {}

    enum Kind { SWORD, FLAG }

    record Marker(Kind kind, double x, double z, int rgb) {}

    static final long FLAG_MS = 10 * 60_000L;

    private static volatile List<Marker> current = List.of();
    private static long lastUpdate;

    static List<Marker> current() {
        return current;
    }

    static void clear() {
        current = List.of();
        lastUpdate = 0;
    }

    private record Flag(double x, double z, int rgb, long at) {}

    private static final Map<String, Map<String, Integer>> LAST_HOLDER = new HashMap<>();
    private static final Map<String, Map<String, Flag>> FLAGS = new HashMap<>();

    /** A guerra ainda está de pé (sem fim, ou com fim no futuro). */
    static boolean running(SiteTerritories.WarInfo war) {
        if (war.ended == null) return true;
        try {
            return java.time.OffsetDateTime.parse(war.ended).toInstant().isAfter(Instant.now());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A guerra que os efeitos seguem: a que está na tela, senão a primeira em andamento (uma ao vivo primeiro). */
    static SiteTerritories.WarInfo followed(SiteTerritories.Data d, String viewedId) {
        List<SiteTerritories.WarInfo> wars = Relations.wars(d);
        if (viewedId != null) {
            for (SiteTerritories.WarInfo w : wars) if (w != null && viewedId.equals(w.id) && w.sides != null) return w;
        }
        SiteTerritories.WarInfo first = null;
        for (SiteTerritories.WarInfo w : wars) {
            if (w == null || w.id == null || w.sides == null || !running(w)) continue;
            if (w.live) return w;
            if (first == null) first = w;
        }
        return first;
    }

    /** Roda no máximo uma vez por segundo. */
    static void update(SiteTerritories.Data d, SiteTerritories.WarInfo war, List<OnlinePlayers.Entry> players, long now) {
        if (now - lastUpdate < 1000) return;
        lastUpdate = now;
        WarScore.Result score = WarScore.of(d, war);
        WarFront front = WarFront.of(d, war);
        String id = war.id == null ? "?" : war.id;
        List<Marker> out = new ArrayList<>();

        // bandeiras: cidade cujo dono mudou desde a última leitura
        Map<String, Integer> last = LAST_HOLDER.get(id);
        Map<String, Integer> cur = new HashMap<>();
        Map<String, Flag> mine = FLAGS.computeIfAbsent(id, k -> new HashMap<>());
        for (WarScore.Holding h : score.holdings()) {
            String k = WarScore.key(h.locality().name, h.locality().country);
            int code = "contested".equals(h.state()) ? -2 : "uncontrolled".equals(h.state()) ? -3 : h.holder();
            cur.put(k, code);
            if (last == null) continue;
            Integer before = last.get(k);
            if (before == null || before == code) continue;
            if (code >= 0 && code < war.sides.size()) {
                mine.put(k, new Flag(h.locality().x, h.locality().z, Relations.sideColor(war.sides.get(code), code), now));
            } else {
                mine.remove(k);
            }
        }
        LAST_HOLDER.put(id, cur);
        mine.values().removeIf(f -> now - f.at() > FLAG_MS);
        for (Flag f : mine.values()) out.add(new Marker(Kind.FLAG, f.x(), f.z(), f.rgb()));

        // espadas: jogadores de dois lados diferentes dentro do território da mesma cidade
        Map<String, Set<Integer>> sidesIn = new HashMap<>();
        Map<String, SiteTerritories.Locality> city = new HashMap<>();
        Map<Integer, Integer> sideOf = new HashMap<>();
        for (int s = 0; s < war.sides.size(); s++) {
            for (int c : Relations.countriesOf(d, war.sides.get(s))) sideOf.putIfAbsent(c, s);
        }
        for (OnlinePlayers.Entry p : players) {
            String site = d.ignToSite.getOrDefault(p.account.toLowerCase(Locale.ROOT), p.account);
            Integer nation = d.countryOfPlayer.get(site.toLowerCase(Locale.ROOT));
            if (nation == null || nation < 0) continue;
            int side = sideOf.getOrDefault(nation, -1);
            if (side < 0) continue;
            int province = SiteTerritories.provinceAt(d, (int) Math.floor(p.toX), (int) Math.floor(p.toZ));
            for (SiteTerritories.Locality l : d.localities) {
                WarFront.Unit u = front.unitOf(l);
                if (u == null || u.neutral()) continue;
                if (!inside(d, l, province, p.toX, p.toZ)) continue;
                String k = WarScore.key(l.name, l.country);
                sidesIn.computeIfAbsent(k, x -> new HashSet<>()).add(side);
                city.put(k, l);
            }
        }
        for (var e : sidesIn.entrySet()) {
            if (e.getValue().size() >= 2) out.add(new Marker(Kind.SWORD, city.get(e.getKey()).x, city.get(e.getKey()).z, 0));
        }
        current = List.copyOf(out);
    }

    /** Dentro do limite de captura da cidade quando o site desenha um; senão, na província dela. */
    private static boolean inside(SiteTerritories.Data d, SiteTerritories.Locality l, int province, double x, double z) {
        SiteTerritories.Zone zone = d.zoneByKey.get(SiteTerritories.zoneKey(l.name, l.country));
        if (zone != null) return contains(zone, x, z);
        return province >= 0 && province == SiteTerritories.provinceAt(d, l.x, l.z);
    }

    /** Par-ímpar sobre todos os anéis da zona, como o SVG do site a preenche. */
    static boolean contains(SiteTerritories.Zone zone, double x, double z) {
        if (zone.rings == null) return false;
        boolean in = false;
        for (double[] ring : zone.rings) {
            if (ring == null || ring.length < 6) continue;
            int n = ring.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = ring[2 * i], zi = ring[2 * i + 1], xj = ring[2 * j], zj = ring[2 * j + 1];
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
            }
        }
        return in;
    }
}
