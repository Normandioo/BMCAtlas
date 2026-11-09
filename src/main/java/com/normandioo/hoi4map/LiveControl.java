package com.normandioo.hoi4map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quem segura as cidades de uma guerra marcada "ao vivo" no site, lido dos marcadores do dynmap como o site faz:
 * cada cidade casa com um marcador pelo id "Nome(País)" (ou "marker__Nome-lp-País-rp-__Tipo"), pelo nome, ou pelo
 * marcador mais próximo em poucos blocos; o ícone diz quem segura: ...Red o 1º lado, ...Blue o 2º, ...Green o 3º,
 * ...Contested, ...Uncontrolled. O que o dynmap diz vale por cima da lista "captured" da guerra.
 */
final class LiveControl {
    private LiveControl() {}

    private static final Pattern MAP_ID = Pattern.compile("^marker__(.+?)-lp-(.+?)-rp-_*([A-Za-z]+)$");
    private static final Pattern ICON = Pattern.compile("(Red|Blue|Green|Contested|Uncontrolled)$");
    private static final int SNAP_BLOCKS = 8;

    private static final Map<String, List<SiteTerritories.Captured>> CACHE = new HashMap<>();
    private static final Map<String, List<SiteTerritories.Captured>> LAST = new HashMap<>();

    private static boolean sameContent(List<SiteTerritories.Captured> a, List<SiteTerritories.Captured> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SiteTerritories.Captured x = a.get(i), y = b.get(i);
            if (!java.util.Objects.equals(x.name, y.name) || !java.util.Objects.equals(x.country, y.country) || !java.util.Objects.equals(x.state, y.state)
                    || !java.util.Objects.equals(x.holder, y.holder) || !java.util.Objects.equals(x.liveSide, y.liveSide) || x.x != y.x || x.z != y.z) return false;
        }
        return true;
    }

    /** Índices dos marcadores, montados uma vez por leitura: id, id "marker__...", e nome. */
    private static final class Index {
        final Map<String, DynmapMarkers.Marker> byId = new HashMap<>(), byName = new HashMap<>();
    }

    private static List<DynmapMarkers.Marker> indexed;
    private static Index index;

    private static synchronized Index indexOf(List<DynmapMarkers.Marker> markers) {
        if (index != null && indexed == markers) return index;
        Index ix = new Index();
        for (DynmapMarkers.Marker m : markers) {
            String idLow = m.id().toLowerCase();
            ix.byId.putIfAbsent(idLow, m);
            Matcher mid = MAP_ID.matcher(m.id());
            if (mid.matches()) ix.byId.putIfAbsent(WarScore.key(mid.group(1), mid.group(2)), m);
            ix.byName.putIfAbsent(m.label().toLowerCase(), m);
            ix.byName.putIfAbsent(idLow, m);
        }
        indexed = markers;
        index = ix;
        return ix;
    }
    private static long cachedAt = -1;
    private static SiteTerritories.Data cachedData;

    /** Se o controle desta guerra está sendo lido ao vivo agora. */
    static boolean isLive(SiteTerritories.WarInfo war) {
        return war != null && war.live && war.ended == null && SiteTerritories.liveWar() && DynmapMarkers.known();
    }

    /** As cidades tomadas da guerra: a lista dela, com a leitura do dynmap por cima quando está ao vivo. */
    static synchronized List<SiteTerritories.Captured> captured(SiteTerritories.Data d, SiteTerritories.WarInfo war) {
        List<SiteTerritories.Captured> own = war.captured == null ? List.of() : war.captured;
        if (!isLive(war)) return own;
        if (cachedData != d || cachedAt != DynmapMarkers.markersAt()) {
            CACHE.clear();
            cachedData = d;
            cachedAt = DynmapMarkers.markersAt();
        }
        String id = war.id == null ? String.valueOf(System.identityHashCode(war)) : war.id;
        List<SiteTerritories.Captured> cur = CACHE.get(id);
        if (cur != null) return cur;
        List<SiteTerritories.Captured> merged = merge(d, war, own, DynmapMarkers.markers());
        // a mesma lista de antes (mesmo objeto) quando nada mudou: placar e frente não são recalculados a cada leitura do dynmap
        List<SiteTerritories.Captured> prev = LAST.get(id);
        if (prev != null && sameContent(prev, merged)) merged = prev;
        LAST.put(id, merged);
        CACHE.put(id, merged);
        return merged;
    }

    static List<SiteTerritories.Captured> merge(SiteTerritories.Data d, SiteTerritories.WarInfo war,
                                                List<SiteTerritories.Captured> own, List<DynmapMarkers.Marker> markers) {
        Map<String, SiteTerritories.Captured> byKey = new HashMap<>();
        List<SiteTerritories.Captured> out = new ArrayList<>();
        for (SiteTerritories.Captured c : own) {
            byKey.put(WarScore.key(c.name, c.country), c);
            out.add(c);
        }
        for (SiteTerritories.Locality l : d.localities) {
            if (WarScore.sideOfCountry(war, l.country) < 0) continue;   // só cidades de quem está na guerra
            DynmapMarkers.Marker m = markerFor(l, markers);
            if (m == null) continue;
            Matcher icon = ICON.matcher(m.icon() == null ? "" : m.icon());
            if (!icon.find()) continue;
            SiteTerritories.Captured c = new SiteTerritories.Captured();
            c.name = l.name;
            c.country = l.country;
            c.x = l.x;
            c.z = l.z;
            switch (icon.group(1)) {
                case "Contested" -> c.state = "contested";
                case "Uncontrolled" -> c.state = "uncontrolled";
                default -> {
                    c.state = "held";
                    int side = switch (icon.group(1)) {
                        case "Red" -> 0;
                        case "Blue" -> 1;
                        default -> 2;
                    };
                    if (war.sides == null || side >= war.sides.size()) continue;   // ícone de um lado que esta guerra não tem
                    c.liveSide = side;
                    c.holder = war.sides.get(side).name;
                }
            }
            String k = WarScore.key(l.name, l.country);
            SiteTerritories.Captured old = byKey.get(k);
            if (old != null) out.set(out.indexOf(old), c);
            else out.add(c);   // o dynmap vale mais que a lista
            byKey.put(k, c);
        }
        return List.copyOf(out);
    }

    private static DynmapMarkers.Marker markerFor(SiteTerritories.Locality l, List<DynmapMarkers.Marker> markers) {
        Index ix = indexOf(markers);
        DynmapMarkers.Marker hit = ix.byId.get(WarScore.key(l.name, l.country));                 // pelo id
        if (hit != null) return hit;
        hit = ix.byName.get(l.name.toLowerCase());                                                // pelo nome
        if (hit != null) return hit;
        DynmapMarkers.Marker best = null;
        double bestD = SNAP_BLOCKS * SNAP_BLOCKS + 1;
        for (DynmapMarkers.Marker m : markers) {                                   // o mais próximo em poucos blocos
            double dd = (m.x() - l.x) * (m.x() - l.x) + (m.z() - l.z) * (m.z() - l.z);
            if (dd < bestD) {
                bestD = dd;
                best = m;
            }
        }
        return best;
    }
}
