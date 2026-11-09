package com.normandioo.bmcatlas;

import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnóstico de desempenho do mapa (F7 liga e desliga): milissegundos por parte do desenho, suavizados. */
final class Perf {
    private Perf() {}

    static boolean on;
    private static final Map<String, double[]> MS = new LinkedHashMap<>();   // nome -> {média suavizada, início}

    static void begin(String name) {
        if (!on) return;
        MS.computeIfAbsent(name, k -> new double[2])[1] = System.nanoTime();
    }

    static void end(String name) {
        if (!on) return;
        double[] v = MS.get(name);
        if (v == null) return;
        double ms = (System.nanoTime() - v[1]) / 1e6;
        v[0] = v[0] == 0 ? ms : v[0] * 0.95 + ms * 0.05;
    }

    static Map<String, double[]> values() { return MS; }
}
