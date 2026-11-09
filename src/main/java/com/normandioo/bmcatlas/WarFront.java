package com.normandioo.bmcatlas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A war's front, worked out the way the site's War mapmode does it (js/warmap.js, "Wars without a map") and the BmcWar plugin does
 * (its WarGraph, which this is a port of), naval rules left out:
 *   - its Theater (7.1.2-7.1.4): a Restricted War's "theater", else every city of its Belligerents; the rest is neutral
 *   - who holds each city: what WarScore says (the war's captured list, dynmap's icons for a live war), else its own nation's side
 *   - Warzones (5.5.14-15, 7.8.7): cities joined by a yellow line are one unit, held by whoever holds most of it
 *   - Connections (5.5.8-10), through open Bridges and Straits (7.7.10-14), and across clusters of neutral cities between the
 *     two cities of the war with the fewest neutral ones in between (7.7.3-4); a tie needs Staff's pick ("shortCircuits")
 *   - Encircled: cut off from every Capital (or Supply Capital) its side holds (7.8.9, 7.8.11)
 *   - In Play: Connected to a unit held by a different side (7.8.1); an Encircled pocket only puts In Play a unit whose capture would
 *     reconnect it (7.8.10); a Contested unit, or a Warzone being split, is In Play.
 * Capitulation (7.9) is scored from the same control. No Minecraft classes in here.
 */
final class WarFront {
    /** Control of a city outside the war's Theater. */
    static final int NEUTRAL = -4, CONTESTED = -2, UNCONTROLLED = -3;

    static final class Unit {
        final List<SiteTerritories.Locality> members = new ArrayList<>();
        String key;
        /** A side, CONTESTED, UNCONTROLLED or NEUTRAL. */
        int control;
        /** A Warzone whose cities are held by different sides: still being fought over. */
        boolean fighting;
        boolean encircled, inPlay;
        /** Neighbouring unit -> true when the only Connection to it is across neutral land. */
        final Map<Unit, Boolean> neighbours = new LinkedHashMap<>();
        final Set<Integer> capitalSides = new HashSet<>();

        boolean neutral() { return control == NEUTRAL; }
        boolean capital() { return members.stream().anyMatch(WarFront::isCapital); }
        /** "Name" or "Name warzone (n localities)". */
        String label() { return members.size() == 1 ? members.get(0).name : members.get(0).name + " warzone (" + members.size() + " localities)"; }
    }

    /** A tie for the shortest Connection across neutral land between two pieces of the war: Staff must pick it ("shortCircuits"). */
    record Tie(int distance, List<String[]> pairs) {}

    /** A nation's Capitulation score (7.9): the value of its settlements held by another side; it capitulates over 100 with its Capital taken. */
    record Score(String nation, int side, double score, int taken, int total, boolean hasCapital, boolean capitalTaken) {
        boolean capitulated() { return score > 100 && capitalTaken; }
    }

    private final List<Unit> units = new ArrayList<>();
    private final Map<String, Unit> unitByCity = new HashMap<>();
    private final List<Tie> ties = new ArrayList<>();
    private final List<Integer> noCapital = new ArrayList<>();
    private final List<Score> scores = new ArrayList<>();
    private final List<Hot> hot = new ArrayList<>();
    /** Units u -> neighbours that put u In Play (the site's "by"). */
    private final Map<Unit, Set<Unit>> putsInPlay = new HashMap<>();
    private int sides;
    private SiteTerritories.WarInfo war;
    private static volatile WarFront viewing;
    private boolean hasNetwork;

    List<Unit> units() { return units; }
    Unit unitOf(SiteTerritories.Locality l) { return l == null ? null : unitByCity.get(WarScore.key(l.name, l.country)); }
    /** False when the site sent no Connections: then nothing can be worked out and no status is shown. */
    boolean hasNetwork() { return hasNetwork; }
    List<Tie> ties() { return ties; }
    /** Sides holding no Capital at all (nothing of theirs is reached, so nothing is Encircled). */
    List<Integer> noCapital() { return noCapital; }
    List<Score> scores() { return scores; }

    /** A Connection between two cities of different sides that puts one of them In Play: the site draws it as a "hot" line (the front). */
    record Hot(SiteTerritories.Locality a, SiteTerritories.Locality b, int controlA, int controlB) {}
    List<Hot> hotLines() { return hot; }

    private final List<Hot> inner = new ArrayList<>();
    /** Connections between two cities held by the same side (the network inside it). */
    List<Hot> innerLines() { return inner; }
    int sides() { return sides; }

    /** The front of the war the menu is showing, for the map to mark its In Play cities; null when none. */
    static void view(WarFront g) { viewing = g; }

    /** The colour of the holder of this city when it is In Play in the war being viewed (yellow when contested), else null. */
    static Integer playRgb(SiteTerritories.Locality l) {
        WarFront g = viewing;
        if (g == null || !g.hasNetwork || g.war == null || g.war.sides == null) return null;
        Unit u = g.unitOf(l);
        if (u == null || !u.inPlay) return null;
        if (u.control >= 0 && u.control < g.war.sides.size()) return Relations.sideColor(g.war.sides.get(u.control), u.control);
        return u.control == CONTESTED ? Relations.CONTESTED : null;
    }

    static boolean isCapital(SiteTerritories.Locality l) { return l.kind != null && l.kind.endsWith("Capital"); }

    /** "In Play", "Encircled", "Encircled, In Play" or null (not in play, or no Connections to say). */
    String statusOf(SiteTerritories.Locality l) {
        if (!hasNetwork) return null;
        Unit u = unitOf(l);
        if (u == null || u.neutral()) return null;
        if (u.encircled) return u.inPlay ? "Encircled, In Play" : "Encircled";
        return u.inPlay ? "In Play" : null;
    }

    // ---- cached ----

    private static SiteTerritories.Data cachedIndex;
    private static SiteTerritories.WarInfo cachedWar;
    private static WarScore.Result cachedScore;
    private static WarFront cached;

    /** The war's front right now. Cached until the data or the holdings change. */
    static synchronized WarFront of(SiteTerritories.Data index, SiteTerritories.WarInfo war) {
        WarScore.Result score = WarScore.of(index, war);
        if (cachedIndex == index && cachedWar == war && cachedScore == score && cached != null) return cached;
        cached = solve(index, war, score);
        cachedIndex = index; cachedWar = war; cachedScore = score;
        return cached;
    }

    // ---- names ----

    static String simple(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s.replaceAll("§.", ""), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** simple(any name of a country: its id, name, display name or official name) -> its index. */
    private static Map<String, Integer> countryTags(SiteTerritories.Data index) {
        Map<String, Integer> out = new HashMap<>();
        for (int c = 0; c < index.ids.length; c++) {
            Map<String, String> info = index.info[c];
            out.putIfAbsent(simple(index.ids[c]), c);
            out.putIfAbsent(simple(index.names[c]), c);
            if (info != null) for (String k : new String[]{"displayName", "officialName"}) {
                String v = info.get(k);
                if (v != null && !v.isBlank()) out.putIfAbsent(simple(v), c);
            }
        }
        return out;
    }

    /** The cities a Restricted War is fought over (7.1.2-7.1.4): "Name(Country)", a nation, or "@Nation" with its subjects; null when Unrestricted. */
    private static Set<String> theaterOf(SiteTerritories.Data index, SiteTerritories.WarInfo war, Map<String, SiteTerritories.Locality> byKey, Map<String, Integer> tags) {
        if (war.theater == null || war.theater.isBlank()) return null;
        Map<Integer, Integer> overlord = new HashMap<>();
        for (int c = 0; c < index.ids.length; c++) {
            var dip = index.diplomacy[c];
            if (dip == null || dip.overlord == null || dip.overlord.isEmpty()) continue;
            Integer o = tags.get(simple(dip.overlord.get(0)));
            if (o != null && o != c) overlord.put(c, o);
        }
        Set<String> out = new HashSet<>();
        for (String raw : war.theater.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (byKey.containsKey(t.toLowerCase(Locale.ROOT))) { out.add(t.toLowerCase(Locale.ROOT)); continue; }
            boolean subjects = t.startsWith("@");
            Integer nation = tags.get(simple(subjects ? t.substring(1) : t));
            if (nation == null) continue;   // a Hinterland's name, say: left out
            for (var l : index.localities) {
                Integer k = tags.get(simple(l.country));
                if (k == null) continue;
                if (k.equals(nation)) { out.add(WarScore.key(l.name, l.country)); continue; }
                if (!subjects) continue;
                Set<Integer> seen = new HashSet<>();
                for (Integer o = overlord.get(k); o != null && seen.add(o); o = overlord.get(o)) {
                    if (o.equals(nation)) { out.add(WarScore.key(l.name, l.country)); break; }
                }
            }
        }
        return out;
    }

    // ---- solving ----

    static WarFront solve(SiteTerritories.Data index, SiteTerritories.WarInfo war, WarScore.Result score) {
        WarFront g = new WarFront();
        g.war = war;
        g.sides = war.sides == null ? 0 : war.sides.size();
        SiteTerritories.Network net = index.network;
        g.hasNetwork = net != null && net.edges != null && !net.edges.isEmpty();

        Map<String, SiteTerritories.Locality> byKey = new HashMap<>();
        for (var l : index.localities) byKey.putIfAbsent(WarScore.key(l.name, l.country), l);
        Map<String, WarScore.Holding> held = new HashMap<>();
        for (var h : score.holdings()) held.put(WarScore.key(h.locality().name, h.locality().country), h);
        Set<String> theater = theaterOf(index, war, byKey, countryTags(index));

        // Control of every city
        Map<String, Integer> control = new HashMap<>(), ownerSide = new HashMap<>();
        for (var l : index.localities) {
            String k = WarScore.key(l.name, l.country);
            int owner = WarScore.sideOfCountry(war, l.country);
            ownerSide.put(k, owner);
            boolean inTheater = theater != null ? theater.contains(k) : owner >= 0;
            int ctl;
            if (!inTheater) ctl = NEUTRAL;
            else {
                var h = held.get(k);
                if (h == null) ctl = owner >= 0 ? owner : NEUTRAL;   // held by a nation outside this war
                else if ("contested".equals(h.state())) ctl = CONTESTED;
                else if ("uncontrolled".equals(h.state())) ctl = UNCONTROLLED;
                else ctl = h.holder() >= 0 && h.holder() < g.sides ? h.holder() : owner >= 0 ? owner : NEUTRAL;
            }
            control.put(k, ctl);
        }

        // Warzones no longer exist: every city is a unit of its own (the yellow-line flag in the data is ignored)
        Map<String, String> parent = new HashMap<>();
        for (var l : index.localities) { String k = WarScore.key(l.name, l.country); parent.put(k, k); }
        List<String[]> edges = new ArrayList<>();
        Map<String, Boolean> linkOpen = new HashMap<>();
        if (g.hasNetwork) {
            if (net.links != null) for (var e : net.links.entrySet()) if (e.getValue() != null) linkOpen.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().open);
            for (List<Object> e : net.edges) {
                if (e == null || e.size() < 2 || !(e.get(0) instanceof String) || !(e.get(1) instanceof String)) continue;
                String a = ((String) e.get(0)).toLowerCase(Locale.ROOT), b = ((String) e.get(1)).toLowerCase(Locale.ROOT);
                edges.add(new String[]{a, b});
            }
        }
        Map<String, Unit> byRoot = new LinkedHashMap<>();
        for (var l : index.localities) {
            String k = WarScore.key(l.name, l.country);
            Unit u = byRoot.computeIfAbsent(find(parent, k), r -> { Unit nu = new Unit(); g.units.add(nu); return nu; });
            u.members.add(l);
            g.unitByCity.put(k, u);
        }
        Set<String> supply = new HashSet<>();
        if (war.supplyCapitals != null) for (List<String> list : war.supplyCapitals.values()) if (list != null) for (String s : list) if (s != null) supply.add(s.toLowerCase(Locale.ROOT));
        for (Unit u : g.units) {
            unitControl(u, control);
            for (var l : u.members) {
                String k = WarScore.key(l.name, l.country);
                if (isCapital(l) && ownerSide.get(k) >= 0) u.capitalSides.add(ownerSide.get(k));
                if (supply.contains(k) && u.control >= 0) u.capitalSides.add(u.control);
            }
        }
        if (g.hasNetwork) g.connect(war, edges, linkOpen, ownerSide);
        g.score(index, war);
        Set<String> drawn = new HashSet<>();
        if (g.hasNetwork) for (String[] e : edges) {
            var a = byKey.get(e[0]); var b = byKey.get(e[1]);
            Unit ua = g.unitByCity.get(e[0]), ub = g.unitByCity.get(e[1]);
            if (a == null || b == null || ua == null || ub == null) continue;
            if (ua == ub) {
                int ca = control.getOrDefault(e[0], NEUTRAL), cb = control.getOrDefault(e[1], NEUTRAL);
                if (ca >= 0 && cb >= 0 && ca != cb) g.hot.add(new Hot(a, b, ca, cb));   // a warzone split between two sides
                continue;
            }
            if (ua.control >= 0 && ua.control == ub.control) g.inner.add(new Hot(a, b, ua.control, ub.control));
        }
        if (g.hasNetwork) for (String[] e : edges) {
            var a = byKey.get(e[0]); var b = byKey.get(e[1]);
            Unit ua = g.unitByCity.get(e[0]), ub = g.unitByCity.get(e[1]);
            if (a == null || b == null || ua == null || ub == null || ua == ub) continue;
            if (ua.control == NEUTRAL || ub.control == NEUTRAL || ua.control == ub.control) continue;
            if (!g.putsInPlay.getOrDefault(ua, Set.of()).contains(ub) && !g.putsInPlay.getOrDefault(ub, Set.of()).contains(ua)) continue;
            int ca = control.getOrDefault(e[0], NEUTRAL), cb = control.getOrDefault(e[1], NEUTRAL);   // each end takes the colour of whoever holds that city
            if (ca >= 0 && cb >= 0 && ca != cb) g.hot.add(new Hot(a, b, ca, cb));
            else if (ca < 0 || cb < 0) g.hot.add(new Hot(a, b, ua.control, ub.control));
            drawn.add(pair(ua, ub));
        }
        // the cities that are In Play across neutral land (Short Circuits), a Bridge or a Strait have no Connection of their own to draw:
        // the line goes between the closest two cities of the two units, however far apart they are
        if (g.hasNetwork) for (var e : g.putsInPlay.entrySet()) {
            Unit u = e.getKey();
            for (Unit v : e.getValue()) {
                if (u.control == NEUTRAL || v.control == NEUTRAL || u.control == v.control) continue;
                String uv = pair(u, v);
                if (!drawn.add(uv)) continue;
                SiteTerritories.Locality bestA = null, bestB = null;
                double best = Double.MAX_VALUE;
                int cA = u.control, cB = v.control;
                for (var a : u.members) for (var b : v.members) {
                    int xa = control.getOrDefault(WarScore.key(a.name, a.country), NEUTRAL), xb = control.getOrDefault(WarScore.key(b.name, b.country), NEUTRAL);
                    if (xa >= 0 && xb >= 0 && xa == xb) continue;   // same side on both ends: no front there
                    double d = Math.hypot(a.x - b.x, a.z - b.z);
                    if (d < best) { best = d; bestA = a; bestB = b; cA = xa >= 0 ? xa : u.control; cB = xb >= 0 ? xb : v.control; }
                }
                if (bestA != null) g.hot.add(new Hot(bestA, bestB, cA, cB));
            }
        }
        return g;
    }

    private static String pair(Unit a, Unit b) {
        int x = System.identityHashCode(a), y = System.identityHashCode(b);
        return Math.min(x, y) + "|" + Math.max(x, y);
    }

    private void connect(SiteTerritories.WarInfo war, List<String[]> edges, Map<String, Boolean> linkOpen, Map<String, Integer> ownerSide) {
        // Connections, through open Bridges and Straits too (5.5.10, 7.7.11)
        Map<String, List<String>> linkEnds = new HashMap<>();
        for (String[] e : edges) {
            Unit a = unitByCity.get(e[0]), b = unitByCity.get(e[1]);
            if (a != null && b != null) { link(a, b, false); }
            else if (a != null && linkOpen.containsKey(e[1])) linkEnds.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
            else if (b != null && linkOpen.containsKey(e[0])) linkEnds.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
        }
        for (var e : linkEnds.entrySet()) {
            boolean open = Bridges.open(war, e.getKey(), linkOpen.getOrDefault(e.getKey(), true));   // a closed one joins nothing: no front line between the cities at its ends
            if (!open) continue;
            List<String> ends = e.getValue();
            for (int i = 0; i < ends.size(); i++) for (int j = i + 1; j < ends.size(); j++) {
                Unit a = unitByCity.get(ends.get(i)), b = unitByCity.get(ends.get(j));
                link(a, b, false);
            }
        }

        if (war.shortCircuit == null || war.shortCircuit) shortCircuits(war);

        // Encirclement (7.8.9)
        Set<Integer> present = new HashSet<>();
        for (Unit u : units) if (u.control >= 0) present.add(u.control);
        for (int side : present) {
            List<Unit> own = units.stream().filter(u -> u.control == side).toList();
            List<Unit> start = own.stream().filter(u -> u.capitalSides.contains(side)).toList();
            if (start.isEmpty()) { noCapital.add(side); continue; }
            Set<Unit> reached = new HashSet<>(start);
            ArrayDeque<Unit> queue = new ArrayDeque<>(start);
            while (!queue.isEmpty()) {
                Unit u = queue.poll();
                for (Unit v : u.neighbours.keySet()) if (v.control == side && reached.add(v)) queue.add(v);
            }
            for (Unit u : own) u.encircled = !reached.contains(u) && !u.neighbours.isEmpty();
        }

        // In Play (7.8.1, 7.8.10)
        for (Unit u : units) {
            if (u.neutral()) continue;
            if (u.control == CONTESTED || u.fighting) u.inPlay = true;
            for (Unit v : u.neighbours.keySet()) {
                int side = v.control;
                if (side < 0 || side == u.control) continue;
                if (!v.encircled) { u.inPlay = true; putsInPlay.computeIfAbsent(u, k -> new HashSet<>()).add(v); continue; }
                boolean reconnects = u.neighbours.keySet().stream().anyMatch(w -> w != v && w.control == side && !w.encircled);
                if (reconnects) { u.inPlay = true; putsInPlay.computeIfAbsent(u, k -> new HashSet<>()).add(v); }   // capturing it would break the pocket's encirclement
            }
        }
    }

    private static String find(Map<String, String> parent, String k) {
        while (!parent.get(k).equals(k)) { parent.put(k, parent.get(parent.get(k))); k = parent.get(k); }
        return k;
    }

    /** A Warzone held by different sides stays with whoever holds most of it, and is still being fought over. */
    private static void unitControl(Unit u, Map<String, Integer> control) {
        List<Integer> controls = new ArrayList<>();
        for (var l : u.members) controls.add(control.get(WarScore.key(l.name, l.country)));
        Set<Integer> distinct = new HashSet<>(controls);
        if (distinct.size() == 1) { u.control = controls.get(0); return; }
        Map<Integer, Integer> counts = new HashMap<>();
        for (int c : controls) if (c >= 0) counts.merge(c, 1, Integer::sum);
        if (!counts.isEmpty()) {
            u.control = counts.entrySet().stream().max(Comparator.comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue())
                    .thenComparingInt(e -> -controls.indexOf(e.getKey()))).get().getKey();
        } else if (distinct.contains(CONTESTED)) u.control = CONTESTED;
        else u.control = controls.stream().filter(c -> c != NEUTRAL).findFirst().orElse(NEUTRAL);
        u.fighting = u.control != NEUTRAL;
    }

    private static void link(Unit a, Unit b, boolean shortCircuit) {
        if (a == null || b == null || a == b) return;
        a.neighbours.merge(b, shortCircuit, (x, y) -> x && y);
        b.neighbours.merge(a, shortCircuit, (x, y) -> x && y);
    }

    /** Connections across clusters of neutral cities (7.7.3-4), as js/warmap.js applyShortCircuits. */
    private void shortCircuits(SiteTerritories.WarInfo war) {
        Map<Unit, Unit> parent = new HashMap<>();
        for (Unit u : units) if (!u.neutral()) parent.put(u, u);
        for (Unit u : units) {
            if (u.neutral()) continue;
            for (Unit v : u.neighbours.keySet()) if (!v.neutral()) union(parent, u, v);
        }

        // The neutral clusters
        Map<Unit, List<Unit>> clusterOf = new HashMap<>();
        List<List<Unit>> clusters = new ArrayList<>();
        for (Unit u : units) {
            if (!u.neutral() || clusterOf.containsKey(u)) continue;
            List<Unit> c = new ArrayList<>();
            clusters.add(c);
            clusterOf.put(u, c);
            ArrayDeque<Unit> queue = new ArrayDeque<>(List.of(u));
            while (!queue.isEmpty()) {
                Unit x = queue.poll();
                c.add(x);
                for (Unit v : x.neighbours.keySet()) if (v.neutral() && !clusterOf.containsKey(v)) { clusterOf.put(v, c); queue.add(v); }
            }
        }

        // Staff's picks first
        if (war.shortCircuits != null) for (List<String> pair : war.shortCircuits) {
            if (pair == null || pair.size() < 2 || pair.get(0) == null || pair.get(1) == null) continue;
            Unit a = unitByCity.get(pair.get(0).toLowerCase(Locale.ROOT)), b = unitByCity.get(pair.get(1).toLowerCase(Locale.ROOT));
            if (a == null || b == null || a == b) continue;
            if (!a.neighbours.containsKey(b)) link(a, b, true);
            if (parent.containsKey(a) && parent.containsKey(b)) union(parent, a, b);
        }

        record Option(Unit a, Unit b, int d) {}
        List<Option> options = new ArrayList<>();
        for (List<Unit> c : clusters) {
            Set<Unit> inCluster = new HashSet<>(c);
            Map<Unit, List<Unit>> border = new LinkedHashMap<>();
            for (Unit m : c) for (Unit v : m.neighbours.keySet()) if (!v.neutral()) border.computeIfAbsent(v, k -> new ArrayList<>()).add(m);
            List<Unit> list = new ArrayList<>(border.keySet());
            if (list.size() < 2) continue;
            for (int i = 0; i < list.size(); i++) {
                Unit a = list.get(i);
                Map<Unit, Integer> dist = clusterDistances(border.get(a), inCluster);
                for (int j = i + 1; j < list.size(); j++) {
                    Unit b = list.get(j);
                    if (find(parent, a) == find(parent, b)) continue;
                    int d = Integer.MAX_VALUE;
                    for (Unit m : border.get(b)) if (dist.containsKey(m)) d = Math.min(d, dist.get(m));
                    if (d != Integer.MAX_VALUE) options.add(new Option(a, b, d));
                }
            }
        }
        options.sort(Comparator.comparingInt(Option::d));
        for (int i = 0; i < options.size();) {
            int d = options.get(i).d();
            int j = i;
            while (j < options.size() && options.get(j).d() == d) j++;
            Map<String, List<Option>> byPair = new LinkedHashMap<>();
            for (Option o : options.subList(i, j)) {
                Unit ra = find(parent, o.a()), rb = find(parent, o.b());
                if (ra == rb) continue;
                int x = units.indexOf(ra), y = units.indexOf(rb);
                byPair.computeIfAbsent(Math.min(x, y) + "|" + Math.max(x, y), k -> new ArrayList<>()).add(o);
            }
            i = j;
            for (List<Option> list : byPair.values()) {
                Option first = list.get(0);
                if (find(parent, first.a()) == find(parent, first.b())) continue;
                if (list.size() == 1) link(first.a(), first.b(), true);
                else {
                    List<String[]> pairs = new ArrayList<>();
                    for (Option o : list) pairs.add(new String[]{o.a().label(), o.b().label()});
                    ties.add(new Tie(d, pairs));
                }
                union(parent, first.a(), first.b());
            }
        }
    }

    private static Unit find(Map<Unit, Unit> parent, Unit u) {
        while (parent.get(u) != u) { parent.put(u, parent.get(parent.get(u))); u = parent.get(u); }
        return u;
    }

    private static void union(Map<Unit, Unit> parent, Unit a, Unit b) {
        Unit ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }

    /** How many of the cluster's cities lie between a border city and each cluster city (counting the one reached). */
    private static Map<Unit, Integer> clusterDistances(List<Unit> starts, Set<Unit> inCluster) {
        Map<Unit, Integer> dist = new HashMap<>();
        ArrayDeque<Unit> queue = new ArrayDeque<>();
        for (Unit m : starts) if (!dist.containsKey(m)) { dist.put(m, 1); queue.add(m); }
        while (!queue.isEmpty()) {
            Unit x = queue.poll();
            for (Unit y : x.neighbours.keySet()) if (inCluster.contains(y) && !dist.containsKey(y)) { dist.put(y, dist.get(x) + 1); queue.add(y); }
        }
        return dist;
    }

    // ---- Capitulation (7.9) ----

    /** Each nation of the war scored as one Mainland, with the city values WarScore already works out (the same 150 points a realm). */
    private void score(SiteTerritories.Data index, SiteTerritories.WarInfo war) {
        Map<String, Double> values = WarScore.values(index);
        Map<String, List<SiteTerritories.Locality>> byNation = new LinkedHashMap<>();
        for (var l : index.localities) {
            Unit u = unitOf(l);
            if (u == null || u.neutral() || WarScore.sideOfCountry(war, l.country) < 0) continue;
            byNation.computeIfAbsent(l.country, k -> new ArrayList<>()).add(l);
        }
        for (var e : byNation.entrySet()) {
            int side = WarScore.sideOfCountry(war, e.getKey());
            double score = 0;
            int taken = 0, total = 0;
            SiteTerritories.Locality capital = null;
            for (var l : e.getValue()) {
                if (capital == null && isCapital(l)) capital = l;
                double v = values.getOrDefault(WarScore.key(l.name, l.country), 0.0);
                if (v <= 0) continue;
                total++;
                if (lost(l, side)) { score += v; taken++; }
            }
            scores.add(new Score(e.getKey(), side, score, taken, total, capital != null, capital != null && lost(capital, side)));
        }
    }

    private boolean lost(SiteTerritories.Locality l, int side) {
        Unit u = unitOf(l);
        return u != null && u.control >= 0 && u.control != side;
    }
}
