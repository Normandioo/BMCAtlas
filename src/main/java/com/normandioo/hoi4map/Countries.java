package com.normandioo.hoi4map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lista de países, lida de config/hoi4map/countries.json. */
final class Countries {
    static final class Country {
        String id;
        String name;
        String color; // "#RRGGBB"
        transient int rgb = 0xFF808080;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final List<Country> LIST = new ArrayList<>();
    static final Map<String, Country> BY_ID = new LinkedHashMap<>();

    private Countries() {}

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("hoi4map").resolve("countries.json");
    }

    static void load() {
        LIST.clear();
        BY_ID.clear();
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults(), w);
                }
            }
            List<Country> read;
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                read = GSON.fromJson(r, new TypeToken<List<Country>>() {}.getType());
            }
            if (read != null) {
                for (Country c : read) {
                    if (c == null || c.id == null || c.id.isBlank()) continue;
                    if (c.name == null || c.name.isBlank()) c.name = c.id;
                    c.rgb = parseColor(c.color);
                    if (BY_ID.putIfAbsent(c.id, c) == null) LIST.add(c);
                }
            }
        } catch (IOException | RuntimeException e) {
            Hoi4MapClient.LOG.error("Falha ao ler {}", f, e);
        }
        MapData.ownersChanged();
    }

    private static List<Country> defaults() {
        List<Country> l = new ArrayList<>();
        l.add(make("verdania", "Verdania", "#4C9A52"));
        l.add(make("aurelia", "Aurelia", "#C8A23C"));
        l.add(make("ferrovia", "Ferrovia", "#B04A4A"));
        l.add(make("nordlands", "Nordlands", "#4A78B0"));
        return l;
    }

    private static Country make(String id, String name, String color) {
        Country c = new Country();
        c.id = id;
        c.name = name;
        c.color = color;
        return c;
    }

    static int parseColor(String s) {
        try {
            String h = s == null ? "" : s.trim();
            if (h.startsWith("#")) h = h.substring(1);
            return 0xFF000000 | (Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (RuntimeException e) {
            return 0xFF808080;
        }
    }
}
