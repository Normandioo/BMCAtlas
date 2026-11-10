package com.normandioo.bmcatlas;

/** Small territories.json-shaped data for the tests: two countries, one city each, one war, one bloc. */
final class Fixtures {
    private Fixtures() {}

    /** %s is the network block: an edge between the two cities, or null. */
    static final String TEMPLATE = """
            {
              "format": 1,
              "generated": "test",
              "countries": {
                "alpha": {"name": "Alpha", "color": "#4C9A52", "info": {}, "population": []},
                "beta": {"name": "Beta", "color": "#B04A4A", "info": {}, "population": []}
              },
              "provinces": [
                {"id": "AlphaP1", "country": "alpha", "rings": [[0, 0, 100, 0, 100, 100, 0, 100]]},
                {"id": "BetaP1", "country": "beta", "rings": [[200, 0, 300, 0, 300, 100, 200, 100]]}
              ],
              "localities": [
                {"name": "a1", "country": "alpha", "kind": "Large", "x": 50, "z": 50},
                {"name": "b1", "country": "beta", "kind": "Small", "x": 250, "z": 50}
              ],
              "war": {
                "wars": [
                  {
                    "id": "w1",
                    "name": "Test war",
                    "sides": [
                      {"name": "Alphas", "members": [{"name": "alpha"}]},
                      {"name": "Betas", "members": [{"name": "beta"}]}
                    ],
                    "captured": []
                  }
                ],
                "alliances": [
                  {"id": "bloc:one", "name": "Bloc One", "members": [{"name": "alpha"}]}
                ]
              },
              "network": %s
            }
            """;

    static final String EDGE = "{\"edges\": [[\"a1(alpha)\", \"b1(beta)\"]]}";

    static SiteTerritories.Data withNetwork() {
        return SiteTerritories.parse(TEMPLATE.formatted(EDGE));
    }

    static SiteTerritories.Data withoutNetwork() {
        return SiteTerritories.parse(TEMPLATE.formatted("null"));
    }

    static SiteTerritories.WarInfo war(SiteTerritories.Data d) {
        return d.war.wars.get(0);
    }
}
