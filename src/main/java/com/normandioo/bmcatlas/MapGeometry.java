package com.normandioo.bmcatlas;

import java.util.Arrays;

/** Outline helpers for the map screen: thinning and smoothing of zone rings. No Minecraft classes here. */
final class MapGeometry {
    private MapGeometry() {}

    /** Keeps only the points at least minDist apart (flat x,z array). */
    static double[] decimate(double[] ring, double minDist) {
        int n = ring.length / 2;
        if (n <= 4) return ring;
        double[] out = new double[ring.length];
        int k = 0;
        double lx = ring[0], lz = ring[1];
        out[k++] = lx;
        out[k++] = lz;
        double min2 = minDist * minDist;
        for (int i = 1; i < n; i++) {
            double x = ring[2 * i], zz = ring[2 * i + 1];
            if ((x - lx) * (x - lx) + (zz - lz) * (zz - lz) >= min2) {
                out[k++] = x;
                out[k++] = zz;
                lx = x;
                lz = zz;
            }
        }
        if (k < 6) return ring;
        return Arrays.copyOf(out, k);
    }

    /** Rounds the corners of a ring (passes = number of corner-cutting passes). */
    static double[] smoothRing(double[] ring, int passes) {
        if (ring.length < 6) return ring;
        double[] cur = ring;
        for (int pass = 0; pass < passes; pass++) {
            int m = cur.length / 2;
            double[] nx = new double[m * 4];
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                nx[4 * i] = 0.75 * cur[2 * i] + 0.25 * cur[2 * j];
                nx[4 * i + 1] = 0.75 * cur[2 * i + 1] + 0.25 * cur[2 * j + 1];
                nx[4 * i + 2] = 0.25 * cur[2 * i] + 0.75 * cur[2 * j];
                nx[4 * i + 3] = 0.25 * cur[2 * i + 1] + 0.75 * cur[2 * j + 1];
            }
            cur = nx;
        }
        return cur;
    }
}
