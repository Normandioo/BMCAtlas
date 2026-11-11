package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapDataRegionTest {

    @TempDir
    Path tmp;

    @Test
    void aRegionComesBackAsItWasSaved() throws Exception {
        int[] px = new int[MapData.REGION_PX * MapData.REGION_PX];
        for (int i = 0; i < px.length; i += 37) px[i] = 0xFF000000 | i;   // a few coloured pixels among empty ones
        byte[] comp = MapData.deflate(px);
        Path file = tmp.resolve("r.0.0.bmr");
        MapData.writeRegion(file, new long[4], comp, comp.length);
        assertArrayEquals(px, MapData.readRegion(file));
    }

    @Test
    void aFileThatIsNotARegionIsRefused() throws IOException {
        Path file = tmp.resolve("bad.bmr");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertThrows(Exception.class, () -> MapData.readRegion(file));
    }
}
