package com.normandioo.hoi4map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Quem segura o quê numa guerra e quanto vale. Cada cidade tem um valor, calculado como o site faz para
 * assentamentos (Lei 7.9): 150 pontos são divididos entre os portes Small/Medium/Large que o reino tem e,
 * dentro de cada porte, igualmente entre as cidades; reino sem assentamentos vale 150 pela capital. O mod só
 * tem as cidades e o país delas, então o país faz o papel do reino. A barra da guerra é a parte dos pontos
 * que cada lado segura agora: as próprias cidades que não foram tomadas mais o que tomou dos outros.
 */
final class WarScore {
    private WarScore() {}

    static final double REALM_VALUE = 150;

    /**
     * Uma cidade numa guerra. owner = lado do país dela (-1 se de ninguém da guerra); holder = lado que a segura
     * agora (-1 se contestada, sem controle ou de alguém de fora). state: own / held / contested / uncontrolled.
     */
    record Holding(SiteTerritories.Locality locality, int owner, int holder, String state, double value) {
        boolean disputed() {
            return "contested".equals(state) || "uncontrolled".equals(state);
        }

        boolean taken() {
            return holder >= 0 && holder != owner;
        }
    }

    record Result(int sides, double[] points, double[] startPoints, List<Holding> holdings) {
        double total() {
            double t = 0;
            for (double p : points) t += p;
            return t;
        }

        /** Fração (0-1) dos pontos de todos os lados que este lado segura; divisão igual se nada vale. */
        double share(int side) {
            double t = total();
            return t <= 0 ? 1.0 / Math.max(1, sides) : points[side] / t;
        }

        int leader() {
            int best = 0;
            for (int i = 1; i < sides; i++) if (points[i] > points[best]) best = i;
            return best;
        }

        /** Nenhum lado à frente por pelo menos um ponto percentual. */
        boolean even() {
            if (sides < 2) return true;
            double top = 0, second = 0;
            for (int i = 0; i < sides; i++) {
                double s = share(i);
                if (s > top) {
                    second = top;
                    top = s;
                } else if (s > second) {
                    second = s;
                }
            }
            return top - second < 0.01;
        }
    }

    // ---- valores ----

    private static SiteTerritories.Data valuesFor;
    private static Map<String, Double> values = Map.of();

    static String key(String name, String country) {
        return (name + "(" + country + ")").toLowerCase(Locale.ROOT);
    }

    /** Small, Medium ou Large de um assentamento (uma "...Capital" conta pelo porte); null para o resto. */
    static String tier(String kind) {
        if (kind == null) return null;
        String base = kind.endsWith("Capital") ? kind.substring(0, kind.length() - "Capital".length()) : kind;
        return base.equals("Small") || base.equals("Medium") || base.equals("Large") ? base : null;
    }

    static boolean isCapital(String kind) {
        return kind != null && kind.endsWith("Capital");
    }

    /** O valor de cada cidade, por "nome(país)" minúsculo. Cidades sem valor ficam de fora. */
    static synchronized Map<String, Double> values(SiteTerritories.Data d) {
        if (valuesFor == d) return values;
        Map<String, List<SiteTerritories.Locality>> byCountry = new LinkedHashMap<>();
        for (SiteTerritories.Locality l : d.localities) {
            byCountry.computeIfAbsent(l.country == null ? "" : l.country.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(l);
        }
        Map<String, Double> out = new HashMap<>();
        for (List<SiteTerritories.Locality> list : byCountry.values()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (SiteTerritories.Locality l : list) {
                String t = tier(l.kind);
                if (t != null) counts.merge(t, 1, Integer::sum);
            }
            if (counts.isEmpty()) {
                for (SiteTerritories.Locality l : list) {
                    if (isCapital(l.kind)) {
                        out.put(key(l.name, l.country), REALM_VALUE);
                        break;
                    }
                }
                continue;
            }
            double share = REALM_VALUE / counts.size();
            for (SiteTerritories.Locality l : list) {
                String t = tier(l.kind);
                if (t != null) out.put(key(l.name, l.country), share / counts.get(t));
            }
        }
        valuesFor = d;
        values = out;
        return out;
    }

    // ---- lados ----

    static int sideOfCountry(SiteTerritories.WarInfo war, String country) {
        if (country == null || war.sides == null) return -1;
        for (int i = 0; i < war.sides.size(); i++) {
            List<SiteTerritories.Member> members = war.sides.get(i).members;
            if (members == null) continue;
            for (SiteTerritories.Member m : members) {
                if (m.name != null && m.name.equalsIgnoreCase(country)) return i;
                if (m.countries != null) for (String c : m.countries) if (c.equalsIgnoreCase(country)) return i;
            }
        }
        return -1;
    }

    /** O lado que tomou a cidade: o que o controle ao vivo viu, ou o do país (ou nome) que a capturou. */
    static int captor(SiteTerritories.WarInfo war, SiteTerritories.Captured c) {
        int n = war.sides == null ? 0 : war.sides.size();
        if (c.liveSide != null) return c.liveSide < n ? c.liveSide : -1;
        int side = sideOfCountry(war, c.holderKey);
        if (side >= 0 || c.holder == null) return side;
        for (int i = 0; i < n; i++) {
            SiteTerritories.Side s = war.sides.get(i);
            if (s.name != null && s.name.equalsIgnoreCase(c.holder)) return i;
            if (s.members != null) for (SiteTerritories.Member m : s.members) if (m.name != null && m.name.equalsIgnoreCase(c.holder)) return i;
        }
        return -1;
    }

    // ---- quem segura o quê ----

    private static SiteTerritories.Data cachedData;
    private static SiteTerritories.WarInfo cachedWar;
    private static List<SiteTerritories.Captured> cachedCaptured;
    private static Result cached;

    /** A situação da guerra agora (com o que o controle ao vivo disser). Guardada até mudarem os dados. */
    static synchronized Result of(SiteTerritories.Data d, SiteTerritories.WarInfo war) {
        List<SiteTerritories.Captured> captured = LiveControl.captured(d, war);
        if (cachedData == d && cachedWar == war && cachedCaptured == captured && cached != null) return cached;
        cached = compute(d, war, captured);
        cachedData = d;
        cachedWar = war;
        cachedCaptured = captured;
        return cached;
    }

    static Result compute(SiteTerritories.Data d, SiteTerritories.WarInfo war, List<SiteTerritories.Captured> captured) {
        int n = war.sides == null ? 0 : war.sides.size();
        Map<String, SiteTerritories.Captured> byKey = new HashMap<>();
        if (captured != null) for (SiteTerritories.Captured c : captured) byKey.put(key(c.name, c.country), c);
        Map<String, Double> vals = values(d);
        double[] points = new double[n], start = new double[n];
        List<Holding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SiteTerritories.Locality l : d.localities) {
            String k = key(l.name, l.country);
            int owner = sideOfCountry(war, l.country);
            SiteTerritories.Captured c = byKey.get(k);
            if (owner < 0 && c == null) continue;
            seen.add(k);
            out.add(holding(war, l, owner, c, vals.getOrDefault(k, 0.0), points, start));
        }
        // cidades tomadas que a lista cita mas as localidades não têm: aparecem, valendo nada
        for (Map.Entry<String, SiteTerritories.Captured> e : byKey.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            SiteTerritories.Captured c = e.getValue();
            SiteTerritories.Locality l = new SiteTerritories.Locality();
            l.name = c.name;
            l.country = c.country;
            l.x = c.x;
            l.z = c.z;
            l.countryIdx = -1;
            out.add(holding(war, l, sideOfCountry(war, c.country), c, 0, points, start));
        }
        return new Result(n, points, start, List.copyOf(out));
    }

    private static Holding holding(SiteTerritories.WarInfo war, SiteTerritories.Locality l, int owner, SiteTerritories.Captured c,
                                   double value, double[] points, double[] start) {
        int holder;
        String state;
        if (c == null) {
            holder = owner;
            state = "own";
        } else if ("contested".equals(c.state)) {
            holder = -1;
            state = "contested";
        } else if ("uncontrolled".equals(c.state)) {
            holder = -1;
            state = "uncontrolled";
        } else {
            holder = captor(war, c);
            state = "held";
        }
        if (owner >= 0) start[owner] += value;
        if (holder >= 0) points[holder] += value;
        return new Holding(l, owner, holder, state, value);
    }
}
