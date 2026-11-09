package com.normandioo.hoi4map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Preferências de quem joga (config/hoi4map/prefs.json): aparência do mapa, jogadores e minimapa. Nada aqui depende do
 * servidor; o menu de configurações do mapa grava neste arquivo. Cada mudança de aparência do terreno sobe a versão de
 * estilo, e o mapa repinta.
 */
final class Prefs {
    private Prefs() {}

    /** Os valores salvos em disco. */
    static final class Data {
        /** Intensidade da cor dos países sobre o terreno (0-100%). */
        int fillPct = 100;
        /** Opacidade das bordas entre países/províncias (0-100%, de 10 em 10). */
        int borderPct = 100;
        /** Estilo do terreno: 0 = HOI4 (contraste e ruído), 1 = limpo, como o do Xaero's. */
        int mapStyle = 0;
        /** Realce do relevo (100-300%). */
        int reliefPct = 150;
        /** Suavização do terreno: menos ruído entre blocos (0-100%). */
        int smoothPct = 40;
        /** Saturação da água (0-100%). */
        int waterSatPct = 60;
        boolean cityNames = true;
        boolean countryNames = true;
        /** Marcadores simples (quadradinhos) em vez dos ícones do site. */
        boolean simpleMarkers = false;
        /** Tamanho dos ícones dos jogadores online (100-400%). */
        int playerIconPct = 100;
        /** Mostrar a província/país sob o cursor na linha de coordenadas. */
        boolean provinceInTooltip = true;
        /** Quando os ícones e nomes das cidades aparecem ao aproximar: 100 = padrão, mais = mais cedo (50-200%). */
        int zoomBiasPct = 100;
        // minimapa
        boolean minimap = true;
        int minimapSize = 112;       // lado em pixels da tela
        int minimapZoomPct = 100;    // 50-400%: blocos por pixel inverso
        int minimapCorner = 1;       // 0 sup. esq., 1 sup. dir., 2 inf. esq., 3 inf. dir.
        boolean minimapCities = true;
        boolean minimapPlayers = true;
        boolean minimapLimits = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();
    private static Path file;

    static Data get() {
        return data;
    }

    static void load() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("hoi4map");
            Files.createDirectories(dir);
            file = dir.resolve("prefs.json");
            if (Files.exists(file)) {
                Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (read != null) data = read;
            }
        } catch (Exception e) {
            Hoi4MapClient.LOG.warn("Não foi possível ler prefs.json: {}", e.toString());
        }
        clamp();
    }

    /** Salva e avisa o mapa de que o estilo mudou. */
    static void changed() {
        clamp();
        MapStyle.overrideVersion++;
        save();
    }

    static void save() {
        if (file == null) return;
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Hoi4MapClient.LOG.warn("Não foi possível gravar prefs.json: {}", e.toString());
        }
    }

    private static void clamp() {
        data.fillPct = Math.max(0, Math.min(100, data.fillPct));
        data.borderPct = Math.max(0, Math.min(100, data.borderPct));
        data.reliefPct = Math.max(100, Math.min(300, data.reliefPct));
        data.smoothPct = Math.max(0, Math.min(100, data.smoothPct));
        data.waterSatPct = Math.max(0, Math.min(100, data.waterSatPct));
        data.playerIconPct = Math.max(100, Math.min(400, data.playerIconPct));
        data.zoomBiasPct = Math.max(50, Math.min(200, data.zoomBiasPct));
        data.minimapSize = Math.max(64, Math.min(256, data.minimapSize));
        data.minimapZoomPct = Math.max(50, Math.min(400, data.minimapZoomPct));
        data.minimapCorner = Math.max(0, Math.min(3, data.minimapCorner));
    }

    // atalhos
    static float fill() {
        return data.fillPct / 100f;
    }

    static float border() {
        return data.borderPct / 100f;
    }
}
