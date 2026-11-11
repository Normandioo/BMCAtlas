package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerSyncTest {

    /** A map message for the map on screen (the default site address, as no map list is loaded in the tests). */
    private static void sendMap(String cities, String bridges) {
        String json = "{\"t\":\"map\",\"v\":1,\"map\":\"gaia\",\"site\":\"" + SiteTerritories.url() + "\",\"generated\":\"g1\","
                + "\"wars\":[{\"id\":\"w1\",\"cities\":" + cities + ",\"bridges\":" + bridges + "}]}";
        ServerSync.receive(json.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void leaveServer() {
        ServerSync.reset();
    }

    @Test
    void helloAloneCoversNothing() {
        ServerSync.receive("{\"t\":\"hello\",\"v\":1}".getBytes(StandardCharsets.UTF_8));
        assertFalse(ServerSync.active());
        assertNull(ServerSync.war("w1"));
    }

    @Test
    void mapOfTheSiteOnScreenIsCovered() {
        sendMap("{\"b1(beta)\":0,\"a1(alpha)\":0}", "{\"Bridge(Alpha)\":false}");
        assertTrue(ServerSync.active());
        ServerSync.WarState w = ServerSync.war("w1");
        assertNotNull(w);
        assertEquals(0, w.cities().get("b1(beta)"));
        assertEquals(false, w.bridges().get("bridge(alpha)"), "bridge keys are lower case, like the site's network");
        assertNull(ServerSync.war("another war"));
    }

    @Test
    void anotherSiteIsNotCovered() {
        String json = "{\"t\":\"map\",\"map\":\"elsewhere\",\"site\":\"https://example/other.json\",\"wars\":[]}";
        ServerSync.receive(json.getBytes(StandardCharsets.UTF_8));
        assertFalse(ServerSync.active());
    }

    @Test
    void serverCapturesGoIntoTheScore() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        SiteTerritories.WarInfo war = Fixtures.war(d);
        List<SiteTerritories.Captured> captured = LiveControl.mergeServer(d, war, List.of(), Map.of("b1(beta)", 0, "a1(alpha)", 0));
        WarScore.Result r = WarScore.compute(d, war, captured);
        assertEquals(300.0, r.points()[0], 1e-9);
        assertEquals(0.0, r.points()[1], 1e-9);
    }

    @Test
    void contestedAndUnknownSides() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        SiteTerritories.WarInfo war = Fixtures.war(d);
        List<SiteTerritories.Captured> captured = LiveControl.mergeServer(d, war, List.of(),
                Map.of("b1(beta)", ServerSync.CONTESTED, "a1(alpha)", 7));   // side 7 does not exist in a two-side war
        assertEquals(1, captured.size());
        assertEquals("contested", captured.get(0).state);
        assertEquals("b1", captured.get(0).name);
    }

    @Test
    void unreadableMessagesAreIgnored() {
        ServerSync.receive("not json".getBytes(StandardCharsets.UTF_8));
        ServerSync.receive("{\"t\":\"map\"}".getBytes(StandardCharsets.UTF_8));   // no map key
        assertFalse(ServerSync.active());
    }

    @Test
    void helloReplySaysTheModVersion() {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(ServerSync.helloReply("1.2.6", true)).getAsJsonObject();
        assertEquals("hello", o.get("t").getAsString());
        assertEquals("1.2.6", o.get("mod").getAsString());
        assertTrue(o.get("selfCheck").getAsBoolean());
        assertFalse(com.google.gson.JsonParser.parseString(ServerSync.helloReply("1.2.6", false)).getAsJsonObject().get("selfCheck").getAsBoolean());
    }

    @Test
    void leavingTheServerForgetsEverything() {
        sendMap("{\"b1(beta)\":0}", "{}");
        assertTrue(ServerSync.active());
        long before = ServerSync.version();
        ServerSync.reset();
        assertFalse(ServerSync.active());
        assertTrue(ServerSync.version() != before);
    }
}
