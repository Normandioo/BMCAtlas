package com.normandioo.bmcatlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Preferências de quem joga (config/bmcatlas/prefs.json): aparência do mapa, jogadores e minimapa. Nada aqui depende do
 * servidor; o menu de configurações do mapa grava neste arquivo. Cada mudança de aparência do terreno sobe a versão de
 * estilo, e o mapa repinta.
 */
final class Prefs {
    private Prefs() {}

    /** Um ponto marcado no mapa. */
    static final class Mark {
        String map, name;
        int x, z;
        /** Index in MARK_COLORS. */
        int color;
    }

    /** Os valores salvos em disco. */
    static final class Data {
        /** Intensidade da cor dos países sobre o terreno (0-100%). */
        int fillPct = 100;
        /** Opacidade das bordas entre países/províncias (0-100%, de 10 em 10). */
        int borderPct = 100;
        /** Estilo do terreno: 0 = Atlas (contraste e ruído), 1 = limpo, como o do Xaero's. */
        int mapStyle = 0;
        /** Realce do relevo (100-300%). */
        int reliefPct = 100;
        /** Suavização do terreno: menos ruído entre blocos (0-100%). */
        int smoothPct = 0;
        /** Saturação da água (0-100%). */
        int waterSatPct = 10;
        boolean cityNames = true;
        boolean countryNames = false;
        /** Marcadores simples (quadradinhos) em vez dos ícones do site. */
        boolean simpleMarkers = false;
        /** Tamanho dos ícones das cidades no mapa e no minimapa (50-300%). */
        int cityIconPct = 100;
        /** Tamanho dos ícones dos jogadores online (100-400%). */
        int playerIconPct = 100;
        /** Mostrar a província/país sob o cursor na linha de coordenadas. */
        boolean provinceInTooltip = true;
        /** Quando os ícones e nomes das cidades aparecem ao aproximar: 100 = padrão, mais = mais cedo (50-200%). */
        int zoomBiasPct = 50;
        // minimapa
        boolean minimap = true;
        int minimapSize = 160;       // lado em pixels da tela
        int minimapZoomPct = 50;    // 50-400%: blocos por pixel inverso
        int minimapCorner = 0;       // 0 sup. esq., 1 sup. dir., 2 inf. esq., 3 inf. dir.
        boolean minimapCities = true;
        boolean minimapPlayers = true;
        /** Size of the map menus: 0 = automatic from the resolution, else 60-150%. */
        int uiScalePct = 0;
        /** Each menu of the map has its own size on top of the one above (50-200%); set by dragging its corner. */
        int panelScalePct = 100, outlinerScalePct = 100, barScalePct = 100;
        /** Minimap opacity (40-100%). */
        int minimapOpacityPct = 100;
        /** true: the minimap is enlarged while the key is held; false: the key switches it. */
        boolean minimapHold = true;
        /** Record the map of the followed war over time (see WarRecorder). */
        boolean recordWar = false;
        /** Tell the player when a newer version of the mod is out (see UpdateCheck). */
        boolean updateCheck = true;
        // what the big map looked like when it was last closed
        int mapMode = 1;
        double mapScale = 1.0;
        boolean showCities = true;
        boolean showPlayers = true;
        /** Limites das cidades desenhados no terreno (mapa e minimapa). */
        boolean limits = true;
        /** Pontos marcados pelo jogador no mapa (só neste computador). */
        java.util.List<Mark> marks = new java.util.ArrayList<>();
    }

    static final int[] MARK_COLORS = {0xECEBE4, 0xE5534B, 0xF0B429, 0x57C26B, 0x4FA3E3, 0xB574E0};

    static int markColor(Mark m) {
        return 0xFF000000 | MARK_COLORS[Math.floorMod(m.color, MARK_COLORS.length)];
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();
    private static Path file;

    static Data get() {
        return data;
    }

    static void load() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("bmcatlas");
            Files.createDirectories(dir);
            file = dir.resolve("prefs.json");
            if (Files.exists(file)) {
                Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (read != null) data = read;
            }
        } catch (Exception e) {
            BmcAtlasClient.LOG.warn("Could not read prefs.json: {}", e.toString());
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
            BmcAtlasClient.LOG.warn("Could not write prefs.json: {}", e.toString());
        }
    }

    private static void clamp() {
        data.fillPct = Math.max(0, Math.min(100, data.fillPct));
        data.borderPct = Math.max(0, Math.min(100, data.borderPct));
        data.reliefPct = Math.max(100, Math.min(300, data.reliefPct));
        data.smoothPct = Math.max(0, Math.min(100, data.smoothPct));
        data.waterSatPct = Math.max(0, Math.min(100, data.waterSatPct));
        data.uiScalePct = data.uiScalePct <= 0 ? 0 : Math.max(60, Math.min(150, data.uiScalePct));
        data.panelScalePct = Math.max(50, Math.min(200, data.panelScalePct));
        data.outlinerScalePct = Math.max(50, Math.min(200, data.outlinerScalePct));
        data.barScalePct = Math.max(50, Math.min(200, data.barScalePct));
        data.cityIconPct = Math.max(50, Math.min(300, data.cityIconPct));
        data.minimapOpacityPct = Math.max(40, Math.min(100, data.minimapOpacityPct));
        data.mapMode = Math.max(0, Math.min(4, data.mapMode));
        data.mapScale = Double.isFinite(data.mapScale) ? Math.max(0.03, Math.min(8.0, data.mapScale)) : 1.0;
        if (data.marks == null) data.marks = new java.util.ArrayList<>();
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
