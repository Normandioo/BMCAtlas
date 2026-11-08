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
        return CACHE.computeIfAbsent(id, k -> merge(d, war, own, DynmapMarkers.markers()));
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
        String id = WarScore.key(l.name, l.country);
        for (DynmapMarkers.Marker m : markers) {                                   // pelo id
            if (m.id().toLowerCase().equals(id)) return m;
            Matcher mid = MAP_ID.matcher(m.id());
            if (mid.matches() && WarScore.key(mid.group(1), mid.group(2)).equals(id)) return m;
        }
        for (DynmapMarkers.Marker m : markers) {                                   // pelo nome
            if (m.label().equalsIgnoreCase(l.name) || m.id().equalsIgnoreCase(l.name)) return m;
        }
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
