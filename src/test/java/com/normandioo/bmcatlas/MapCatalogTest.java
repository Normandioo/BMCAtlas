package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MapCatalogTest {

    @Test
    void parsesAValidMapList() {
        var maps = MapCatalog.parse("{\"version\": 1, \"maps\": [{\"key\": \"main\", \"name\": \"Main\", \"territories\": \"territories.json\"}]}");
        assertEquals(1, maps.size());
        assertEquals("main", maps.get(0).key);
        assertEquals("Main", maps.get(0).name);
        assertEquals("overworld", maps.get(0).dimension);
    }

    @Test
    void refusesTheWholeFileWhenAMapHasNoKey() {
        assertThrows(IllegalArgumentException.class,
                () -> MapCatalog.parse("{\"maps\": [{\"name\": \"no key\", \"territories\": \"t.json\"}]}"));
    }

    @Test
    void refusesAListWithNoMaps() {
        assertThrows(IllegalArgumentException.class, () -> MapCatalog.parse("{\"maps\": []}"));
    }

    @Test
    void refusesInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> MapCatalog.parse("not json"));
    }
}
