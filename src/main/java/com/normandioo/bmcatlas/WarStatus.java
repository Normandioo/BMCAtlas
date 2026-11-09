package com.normandioo.bmcatlas;

import java.time.Instant;
import java.util.List;

/** Which war the map follows and whether it is still running. */
final class WarStatus {
    private WarStatus() {}

    /** The war is still on (no end, or an end in the future). */
    static boolean running(SiteTerritories.WarInfo war) {
        if (war.ended == null) return true;
        try {
            return java.time.OffsetDateTime.parse(war.ended).toInstant().isAfter(Instant.now());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The war the effects follow: the one on screen, else the first running one (a live one first). */
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
}
