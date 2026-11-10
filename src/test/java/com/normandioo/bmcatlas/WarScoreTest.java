package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WarScoreTest {

    @Test
    void realmValueIsSplitByTier() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        var values = WarScore.values(d);
        // each country has one city: alpha's Large city is worth all 150 points, beta's Small city too
        assertEquals(150.0, values.get("a1(alpha)"), 1e-9);
        assertEquals(150.0, values.get("b1(beta)"), 1e-9);
    }

    @Test
    void noCapturesMeansEachSideKeepsItsOwnCities() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        WarScore.Result r = WarScore.compute(d, Fixtures.war(d), List.of());
        assertEquals(150.0, r.points()[0], 1e-9);
        assertEquals(150.0, r.points()[1], 1e-9);
        assertTrue(r.even());
        assertEquals(0.5, r.share(0), 1e-9);
    }

    @Test
    void capturedCityMovesItsPointsToTheCaptor() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        SiteTerritories.Captured c = new SiteTerritories.Captured();
        c.name = "b1";
        c.country = "beta";
        c.state = "held";
        c.holder = "Alphas";
        c.holderKey = "alpha";
        WarScore.Result r = WarScore.compute(d, Fixtures.war(d), List.of(c));
        assertEquals(300.0, r.points()[0], 1e-9);
        assertEquals(0.0, r.points()[1], 1e-9);
        assertEquals(1, r.holdings().stream().filter(WarScore.Holding::taken).count());
        assertEquals(0, r.leader());
    }
}
