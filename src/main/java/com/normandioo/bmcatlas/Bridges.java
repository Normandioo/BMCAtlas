package com.normandioo.bmcatlas;

import java.util.List;
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
        ServerSync.WarState fromServer = war == null ? null : ServerSync.war(war.id);
        if (fromServer != null) {   // the server's war plugin: the bridges its staff opened or closed
            Boolean set = fromServer.bridges().get(lowerKey);
            return set != null ? set : drawnOpen;
        }
        if (LiveControl.isLive(war)) {
            Boolean live = liveState(lowerKey);
            if (live != null) return live;
        }
        return drawnOpen;
    }

    private static List<DynmapMarkers.Marker> stateFrom;
    private static final java.util.Map<String, Boolean> STATES = new java.util.HashMap<>();

    /** Estado das pontes lido dos marcadores, indexado uma vez por leitura (antes cada ponte varria todos os marcadores). */
    private static synchronized Boolean liveState(String lowerKey) {
        List<DynmapMarkers.Marker> markers = DynmapMarkers.markers();
        if (stateFrom != markers) {
            STATES.clear();
            for (DynmapMarkers.Marker m : markers) {
                Matcher icon = STATE.matcher(m.icon() == null ? "" : m.icon());
                if (!icon.find()) continue;
                Boolean open = icon.group(1).equalsIgnoreCase("open");
                STATES.putIfAbsent(m.id().toLowerCase(Locale.ROOT), open);
                Matcher mid = MAP_ID.matcher(m.id());
                if (mid.matches()) STATES.putIfAbsent((mid.group(1) + "(" + mid.group(2) + ")").toLowerCase(Locale.ROOT), open);
            }
            stateFrom = markers;
        }
        return STATES.get(lowerKey);
    }
}
