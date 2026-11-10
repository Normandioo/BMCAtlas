package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WarFrontTest {

    @Test
    void connectedCitiesOfTwoSidesMakeAFrontLine() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        SiteTerritories.WarInfo war = Fixtures.war(d);
        WarFront front = WarFront.solve(d, war, WarScore.compute(d, war, List.of()));
        assertTrue(front.hasNetwork());
        assertEquals(1, front.hotLines().size());
    }

    @Test
    void withoutConnectionsThereIsNoFront() {
        SiteTerritories.Data d = Fixtures.withoutNetwork();
        SiteTerritories.WarInfo war = Fixtures.war(d);
        WarFront front = WarFront.solve(d, war, WarScore.compute(d, war, List.of()));
        assertFalse(front.hasNetwork());
        assertTrue(front.hotLines().isEmpty());
    }

    @Test
    void cityKeepsItsControlAndSideInTheSolvedFront() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        SiteTerritories.WarInfo war = Fixtures.war(d);
        WarFront front = WarFront.solve(d, war, WarScore.compute(d, war, List.of()));
        SiteTerritories.Locality a1 = d.localities.get(0);
        WarFront.Unit u = front.unitOf(a1);
        assertEquals(0, u.control);
        assertTrue(u.inPlay, "a city next to the other side is in play");
    }
}
