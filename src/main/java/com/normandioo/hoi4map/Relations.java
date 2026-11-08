package com.normandioo.hoi4map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cores dos modos Diplomacia e Guerra: quais países ficam de que cor (o resto fica cinza).
 * Só leitura dos dados do site.
 */
final class Relations {
    static final int GREY = -1;
    static final int ALLY = 0x3FA34D, BLOC = 0x7FCB6F, VASSAL = 0x9A63D6, NAP = 0x3A8FD8, ENEMY = 0xD83A3A;
    static final int CONTESTED = 0xE0C040, UNCONTROLLED = 0x9A9A9A, NEUTRAL = 0x6E6E6E;
    private static final int[] SIDE_COLORS = {0xD83A3A, 0x3A6FD8, 0x3AAE5A, 0xE59A2E, 0x8E5AC8, 0x2AAFAF};
    private static final int[] BLOC_COLORS = {0x3A6FD8, 0xE59A2E, 0x3AAE5A, 0x8E5AC8, 0x2AAFAF, 0xC8527A, 0xB8B03A, 0x6B8E3A};

    /** País (RGB ou GREY), províncias que fogem da cor do país, e a cor da zona de captura de cada cidade (por chave). */
    record Result(int[] country, Map<Integer, Integer> provinces, Map<String, Integer> zones, List<Rel> legend) {}

    /** Uma linha da legenda: o que a cor significa e quais países. */
    record Rel(String label, int rgb, List<Integer> countries) {}

    private Relations() {}

    static String norm(String s) {
        if (s == null) return "";
        String t = s.startsWith("@") ? s.substring(1) : s;
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static Map<String, Integer> nameIndex(SiteTerritories.Data d) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < d.ids.length; i++) {
            m.putIfAbsent(norm(d.ids[i]), i);
            m.putIfAbsent(norm(d.names[i]), i);
            m.putIfAbsent(norm(d.labels[i]), i);
        }
        return m;
    }

    private static List<SiteTerritories.AllianceInfo> alliances(SiteTerritories.Data d) {
        return d.war == null || d.war.alliances == null ? List.of() : d.war.alliances;
    }

    static List<SiteTerritories.WarInfo> wars(SiteTerritories.Data d) {
        return d.war == null || d.war.wars == null ? List.of() : d.war.wars;
    }

    static boolean isBilateral(SiteTerritories.AllianceInfo a) {
        return a.id != null && a.id.startsWith("bilateral:");
    }

    /** Países que um membro (nação, "@nação e súditos" ou confederação) cobre. */
    private static void memberCountries(SiteTerritories.Data d, Map<String, Integer> idx, SiteTerritories.Member m, Set<Integer> out) {
        if (m.countries != null) {
            for (String c : m.countries) {
                Integer i = idx.get(norm(c));
                if (i != null) out.add(i);
            }
        }
        if ((m.countries == null || m.countries.isEmpty()) && m.name != null) {
            Integer i = idx.get(norm(m.name));
            if (i != null) out.add(i);
        }
    }

    static Set<Integer> countriesOf(SiteTerritories.Data d, SiteTerritories.AllianceInfo a) {
        Map<String, Integer> idx = nameIndex(d);
        Set<Integer> out = new LinkedHashSet<>();
        if (a.members != null) for (SiteTerritories.Member m : a.members) memberCountries(d, idx, m, out);
        return out;
    }

    static Set<Integer> countriesOf(SiteTerritories.Data d, SiteTerritories.Side side) {
        Map<String, Integer> idx = nameIndex(d);
        Set<Integer> out = new LinkedHashSet<>();
        if (side.members != null) for (SiteTerritories.Member m : side.members) memberCountries(d, idx, m, out);
        return out;
    }

    static Integer parseHex(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        try {
            return h.length() == 6 ? Integer.parseInt(h, 16) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int sideColor(SiteTerritories.Side side, int index) {
        Integer own = parseHex(side.color);
        return own != null ? own : SIDE_COLORS[index % SIDE_COLORS.length];
    }

    static int blocColor(SiteTerritories.AllianceInfo a, int index) {
        Integer own = parseHex(a.color);
        return own != null ? own : BLOC_COLORS[index % BLOC_COLORS.length];
    }

    private static int[] blank(SiteTerritories.Data d) {
        int[] ov = new int[d.ids.length];
        Arrays.fill(ov, GREY);
        return ov;
    }

    private static boolean listed(List<String> list, int country, Map<String, Integer> idx) {
        if (list == null) return false;
        for (String s : list) {
            Integer i = idx.get(norm(s));
            if (i != null && i == country) return true;
        }
        return false;
    }

    private static void addAll(Set<Integer> out, List<String> list, Map<String, Integer> idx) {
        if (list == null) return;
        for (String s : list) {
            Integer i = idx.get(norm(s));
            if (i != null) out.add(i);
        }
    }

    // ------------------------------------------------------------------ diplomacia

    /** Sem país escolhido: cada bloco/aliança formal numa cor; quem não está em nenhuma, cinza. */
    static Result blocs(SiteTerritories.Data d) {
        int[] ov = blank(d);
        List<Rel> legend = new ArrayList<>();
        int k = 0;
        for (SiteTerritories.AllianceInfo a : alliances(d)) {
            if (isBilateral(a)) continue;
            int color = blocColor(a, k++);
            List<Integer> mine = new ArrayList<>();
            for (int c : countriesOf(d, a)) {
                if (ov[c] == GREY) {
                    ov[c] = color;
                    mine.add(c);
                }
            }
            if (!mine.isEmpty()) legend.add(new Rel(a.name == null ? a.id : a.name, color, mine));
        }
        return new Result(ov, Map.of(), Map.of(), legend);
    }

    /** Uma aliança escolhida: seus membros na cor dela, o resto cinza. */
    static Result alliance(SiteTerritories.Data d, SiteTerritories.AllianceInfo a) {
        int[] ov = blank(d);
        int idx = Math.max(0, alliances(d).indexOf(a));
        int color = blocColor(a, idx);
        List<Integer> mine = new ArrayList<>();
        for (int c : countriesOf(d, a)) {
            ov[c] = color;
            mine.add(c);
        }
        List<Rel> legend = new ArrayList<>();
        if (!mine.isEmpty()) legend.add(new Rel("Membros", color, mine));
        return new Result(ov, Map.of(), Map.of(), legend);
    }

    /** Relações de um país: ele mesmo na cor própria, aliados, suserania, NAP e guerra. */
    static Result focus(SiteTerritories.Data d, int f) {
        int nc = d.ids.length;
        int[] ov = blank(d);
        Map<String, Integer> idx = nameIndex(d);
        Set<Integer> vassal = new LinkedHashSet<>(), ally = new LinkedHashSet<>(), bloc = new LinkedHashSet<>();
        Set<Integer> nap = new LinkedHashSet<>(), enemy = new LinkedHashSet<>();

        SiteTerritories.Diplomacy mine = d.diplomacy[f];
        if (mine != null) {
            addAll(vassal, mine.overlord, idx);
            addAll(vassal, mine.subject, idx);
            addAll(ally, mine.allies, idx);
            addAll(nap, mine.nap, idx);
        }
        // o outro lado da relação (o site nem sempre lista nos dois países)
        for (int o = 0; o < nc; o++) {
            if (o == f || d.diplomacy[o] == null) continue;
            SiteTerritories.Diplomacy od = d.diplomacy[o];
            if (listed(od.overlord, f, idx) || listed(od.subject, f, idx)) vassal.add(o);
            if (listed(od.allies, f, idx)) ally.add(o);
            if (listed(od.nap, f, idx)) nap.add(o);
        }
        // blocos de que faz parte
        for (SiteTerritories.AllianceInfo a : alliances(d)) {
            if (isBilateral(a)) continue;
            Set<Integer> members = countriesOf(d, a);
            if (members.contains(f)) bloc.addAll(members);
        }
        // guerras em que está
        for (SiteTerritories.WarInfo w : wars(d)) {
            if (w.sides == null) continue;
            int mySide = -1;
            for (int s = 0; s < w.sides.size(); s++) if (countriesOf(d, w.sides.get(s)).contains(f)) mySide = s;
            if (mySide < 0) continue;
            for (int s = 0; s < w.sides.size(); s++) if (s != mySide) enemy.addAll(countriesOf(d, w.sides.get(s)));
        }
        bloc.remove(f);
        ally.remove(f);
        vassal.remove(f);
        nap.remove(f);
        enemy.remove(f);

        for (int c : nap) ov[c] = NAP;
        for (int c : bloc) ov[c] = BLOC;
        for (int c : ally) ov[c] = ALLY;
        for (int c : vassal) ov[c] = VASSAL;
        for (int c : enemy) ov[c] = ENEMY;
        ov[f] = d.rgb[f] & 0xFFFFFF;

        List<Rel> legend = new ArrayList<>();
        legend.add(new Rel("Selecionado", d.rgb[f] & 0xFFFFFF, List.of(f)));
        if (!ally.isEmpty()) legend.add(new Rel("Aliados", ALLY, new ArrayList<>(ally)));
        if (!bloc.isEmpty()) legend.add(new Rel("Mesmo bloco", BLOC, new ArrayList<>(bloc)));
        if (!vassal.isEmpty()) legend.add(new Rel("Suserano / súditos", VASSAL, new ArrayList<>(vassal)));
        if (!nap.isEmpty()) legend.add(new Rel("Pacto de não agressão", NAP, new ArrayList<>(nap)));
        if (!enemy.isEmpty()) legend.add(new Rel("Em guerra", ENEMY, new ArrayList<>(enemy)));
        return new Result(ov, Map.of(), Map.of(), legend);
    }

    // ---------------------------------------------------------------------- guerra

    /** Uma guerra: os países de cada lado na cor do lado; as cidades tomadas pintam a província e o limite. */
    static Result war(SiteTerritories.Data d, SiteTerritories.WarInfo w) {
        int[] ov = blank(d);
        Map<Integer, Integer> provinces = new HashMap<>();
        Map<String, Integer> zones = new HashMap<>();
        List<Rel> legend = new ArrayList<>();
        if (w == null) return new Result(ov, provinces, zones, legend);

        Map<String, Integer> sideOf = new HashMap<>();
        List<SiteTerritories.Side> sides = w.sides == null ? List.of() : w.sides;
        for (int s = 0; s < sides.size(); s++) {
            int color = sideColor(sides.get(s), s);
            Set<Integer> members = countriesOf(d, sides.get(s));
            for (int c : members) {
                ov[c] = color;
                sideOf.put(norm(d.ids[c]), color);
            }
            String name = sides.get(s).name == null ? "Lado " + (s + 1) : sides.get(s).name;
            legend.add(new Rel(name, color, new ArrayList<>(members)));
        }
        if (w.captured != null) {
            for (SiteTerritories.Captured c : w.captured) {
                int color;
                if ("contested".equals(c.state)) color = CONTESTED;
                else if ("uncontrolled".equals(c.state)) color = UNCONTROLLED;
                else {
                    Integer s = sideOf.get(norm(c.holderKey));
                    if (s == null) s = sideOf.get(norm(c.holder));
                    color = s == null ? NEUTRAL : s;
                }
                int p = SiteTerritories.provinceAt(d, c.x, c.z);
                if (p >= 0) provinces.put(p, color);
                zones.put(SiteTerritories.zoneKey(c.name, c.country), color);
            }
            legend.add(new Rel("Em disputa", CONTESTED, List.of()));
        }
        return new Result(ov, provinces, zones, legend);
    }
}
