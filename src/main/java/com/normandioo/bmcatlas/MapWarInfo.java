package com.normandioo.bmcatlas;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** War and diplomacy lookups used by the map screen: names, sides, who is at war, purge text. No Minecraft classes here. */
final class MapWarInfo {
    private MapWarInfo() {}

    static boolean warInvolves(SiteTerritories.Data d, SiteTerritories.WarInfo w, int me) {
        if (w.sides == null) return false;
        for (SiteTerritories.Side side : w.sides) if (Relations.countriesOf(d, side).contains(me)) return true;
        return false;
    }

    static boolean nameIsMe(SiteTerritories.Data d, String name, int me) {
        if (name == null) return false;
        Integer ci = Relations.nameIndex(d).get(Relations.norm(name));
        return ci != null && ci == me;
    }

    static boolean warRunning(SiteTerritories.WarInfo w) {
        return w != null && WarStatus.running(w);
    }

    static String warName(SiteTerritories.WarInfo w) {
        return w.name == null || w.name.isBlank() ? String.valueOf(w.id) : w.name;
    }

    static String sideName(SiteTerritories.WarInfo w, int i) {
        String n = i >= 0 && w.sides != null && i < w.sides.size() ? w.sides.get(i).name : null;
        if ("Attackers".equalsIgnoreCase(n)) return "Attackers";
        if ("Defenders".equalsIgnoreCase(n)) return "Defenders";
        return n == null || n.isBlank() ? "Side " + (i + 1) : n;
    }

    static int countryIndex(SiteTerritories.Data d, String name) {
        Integer i = Relations.nameIndex(d).get(Relations.norm(name));
        return i == null ? -1 : i;
    }

    static String ago(String iso) {
        try {
            OffsetDateTime t = OffsetDateTime.parse(iso);
            long days = Duration.between(t.toInstant(), Instant.now()).toDays();
            return (days <= 0 ? "today" : days == 1 ? "yesterday" : days + " days ago") + "  (" + iso.replace('T', ' ') + ")";
        } catch (RuntimeException e) {
            return iso;
        }
    }

    static String purgeText(SiteTerritories.Player p) {
        if (p.lastJoined == null || p.purge == null || p.purge.days <= 0) return null;
        try {
            OffsetDateTime t = OffsetDateTime.parse(p.lastJoined).plusDays(p.purge.days);
            long left = Duration.between(Instant.now(), t.toInstant()).toDays();
            String date = t.toLocalDate().toString();
            return left < 0 ? "since " + date : "in " + (left + 1) + " day(s), " + date;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
