package com.normandioo.hoi4map;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "E se a cidade caísse?": tira uma foto da guerra, passa algumas cidades para o lado inimigo só na memória e roda
 * de novo o mesmo cálculo da Lei 7 (pontos, In Play, cerco, capitulação). Nada daqui vai para o jogo nem para o site.
 */
final class WarSim {
    private WarSim() {}

    /** Ligado enquanto o painel do simulador está aberto. */
    static boolean active;
    /** Cidades escolhidas, como "nome(país)" minúsculo. */
    private static final Set<String> picks = new LinkedHashSet<>();
    private static int version;

    static List<String> picks() { return new ArrayList<>(picks); }
    static boolean isPicked(SiteTerritories.Locality l) { return picks.contains(WarScore.key(l.name, l.country)); }

    static void toggle(SiteTerritories.WarInfo war, SiteTerritories.Locality l) {
        if (WarScore.sideOfCountry(war, l.country) < 0) return;   // só cidades de quem está na guerra
        String k = WarScore.key(l.name, l.country);
        if (!picks.remove(k)) picks.add(k);
        version++;
    }

    static void clear() { picks.clear(); version++; }

    /** Resultado de um cálculo: placar e frente. */
    record Run(WarScore.Result score, WarFront front) {
        int count(boolean encircled, int side) {
            int n = 0;
            if (front == null) return 0;
            for (WarFront.Unit u : front.units()) {
                if (u.control != side) continue;
                if (encircled ? u.encircled : u.inPlay) n += u.members.size();
            }
            return n;
        }
    }

    record Pair(Run before, Run after) {}

    private static SiteTerritories.Data cData;
    private static SiteTerritories.WarInfo cWar;
    private static int cVersion = -1;
    private static Pair cached;

    /** Antes (a guerra como está) e depois (com as cidades escolhidas tomadas). */
    static synchronized Pair of(SiteTerritories.Data d, SiteTerritories.WarInfo war) {
        if (cached != null && cData == d && cWar == war && cVersion == version) return cached;
        List<SiteTerritories.Captured> real = LiveControl.captured(d, war);
        Run before = run(d, war, real);
        List<SiteTerritories.Captured> all = new ArrayList<>();
        if (real != null) for (SiteTerritories.Captured c : real) {
            if (!picks.contains(WarScore.key(c.name, c.country))) all.add(c);
        }
        for (SiteTerritories.Locality l : d.localities) {
            if (!picks.contains(WarScore.key(l.name, l.country))) continue;
            int owner = WarScore.sideOfCountry(war, l.country);
            int n = war.sides == null ? 0 : war.sides.size();
            if (owner < 0 || n < 2) continue;
            SiteTerritories.Captured c = new SiteTerritories.Captured();
            c.name = l.name;
            c.country = l.country;
            c.x = l.x;
            c.z = l.z;
            c.state = "held";
            c.liveSide = owner == 0 ? 1 : 0;
            all.add(c);
        }
        Run after = run(d, war, all);
        cData = d;
        cWar = war;
        cVersion = version;
        cached = new Pair(before, after);
        return cached;
    }

    private static Run run(SiteTerritories.Data d, SiteTerritories.WarInfo war, List<SiteTerritories.Captured> captured) {
        WarScore.Result score = WarScore.compute(d, war, captured);
        WarFront f = WarFront.solve(d, war, score);
        return new Run(score, f.hasNetwork() ? f : null);
    }

    /** Pontuação de capitulação de uma nação (0 se não há). */
    static double capitulation(Run r, String nation) {
        if (r.front() == null) return 0;
        for (WarFront.Score s : r.front().scores()) if (s.nation().toLowerCase(Locale.ROOT).equals(nation.toLowerCase(Locale.ROOT))) return s.score();
        return 0;
    }
}
