package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class RelationsTest {

    @Test
    void allianceMembersGetAColourAndOthersStayGrey() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        Relations.Result r = Relations.blocs(d);
        int alpha = d.index.get("alpha");
        int beta = d.index.get("beta");
        assertNotEquals(Relations.GREY, r.country()[alpha]);
        assertEquals(Relations.GREY, r.country()[beta]);
    }

    @Test
    void warSidesAreColouredByTheirSide() {
        SiteTerritories.Data d = Fixtures.withNetwork();
        Relations.Result r = Relations.war(d, Fixtures.war(d));
        int alpha = d.index.get("alpha");
        int beta = d.index.get("beta");
        assertNotEquals(Relations.GREY, r.country()[alpha]);
        assertNotEquals(Relations.GREY, r.country()[beta]);
        assertNotEquals(r.country()[alpha], r.country()[beta]);
    }
}
