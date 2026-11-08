package com.normandioo.hoi4map;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pontes e estreitos: se cada um está aberto. Um fechado não liga nada, então não há linha de frente entre as
 * cidades das pontas. O estado: o "bridges" da própria guerra primeiro (palavra da staff, como no site), depois o que o
 * marcador do dynmap diz quando a guerra é lida ao vivo, depois o desenho do mapa.
 */
final class Bridges {
    private Bridges() {}

    private static final Pattern MAP_ID = Pattern.compile("^marker__(.+?)-lp-(.+?)-rp-_*([A-Za-z]+)$");
    private static final Pattern STATE = Pattern.compile("(?i)^(?:bridge|strait)(open|closed)");

    static boolean open(SiteTerritories.WarInfo war, String lowerKey, boolean drawnOpen) {
        if (war != null && war.bridges != null) {
            for (var e : war.bridges.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lowerKey)) {
                    return !WarFront.simple(e.getValue()).equals("closed");
                }
            }
        }
        if (LiveControl.isLive(war)) {
            Boolean live = liveState(lowerKey);
            if (live != null) return live;
        }
        return drawnOpen;
    }

    private static Boolean liveState(String lowerKey) {
        for (DynmapMarkers.Marker m : DynmapMarkers.markers()) {
            boolean same = m.id().toLowerCase(Locale.ROOT).equals(lowerKey);
            if (!same) {
                Matcher mid = MAP_ID.matcher(m.id());
                same = mid.matches() && (mid.group(1) + "(" + mid.group(2) + ")").toLowerCase(Locale.ROOT).equals(lowerKey);
            }
            if (!same) continue;
            Matcher icon = STATE.matcher(m.icon() == null ? "" : m.icon());
            if (icon.find()) return icon.group(1).equalsIgnoreCase("open");
        }
        return null;
    }
}
