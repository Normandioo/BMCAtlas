package com.normandioo.bmcatlas;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Text, number and date helpers used by the map screen. No Minecraft classes here. */
final class MapFormat {
    private MapFormat() {}

    /** Marks "no date". */
    static final long NONE = Long.MIN_VALUE;
    static final long DAY = 86_400_000L;

    static String dateText(long ms) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    static String pts(double v) {
        return Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** "in 2d 5h" / "3h 10m ago". */
    static String countdown(long ms) {
        long a = Math.abs(ms) / 60000;
        long days = a / 1440, hours = (a % 1440) / 60, mins = a % 60;
        String t = days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + mins + "m" : mins + "m";
        return ms < 0 ? t + " ago" : "in " + t;
    }

    static String stripAt(String n) {
        return n != null && n.startsWith("@") ? n.substring(1) : n;
    }

    static String travelTime(double blocks, double blocksPerSecond) {
        long sec = Math.round(blocks / blocksPerSecond);
        if (sec < 60) return sec + " s";
        long min = Math.round(sec / 60.0);
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    /** The largest number in the text (site dates keep the year as a number). */
    static long yearOf(String text) {
        long best = 0;
        for (String part : text.split("[^0-9]+")) {
            if (!part.isEmpty() && part.length() <= 9) best = Math.max(best, Long.parseLong(part));
        }
        return best;
    }

    static long parseMs(String iso) {
        if (iso == null || iso.isBlank()) return NONE;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /** Lower case without accents, so "sao" finds "São". */
    static String fold(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
