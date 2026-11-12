package com.normandioo.bmcatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RecHighlightsTest {

    private static final long START = 1_000_000;

    private static RecFormat.City city(String name, Integer side, String state) {
        RecFormat.City c = new RecFormat.City();
        c.name = name;
        c.country = "gaia";
        c.side = side;
        c.state = state;
        c.x = 100;
        c.z = 200;
        return c;
    }

    private static RecFormat.Frame frame(long t, RecFormat.City... cities) {
        RecFormat.Frame f = new RecFormat.Frame();
        f.t = t;
        f.cities = new ArrayList<>(List.of(cities));
        return f;
    }

    private static RecFormat.Meta meta(long length, long[]... sessions) {
        RecFormat.Meta m = new RecFormat.Meta();
        m.start = START;
        m.end = START + length;
        m.periodMs = 2000;
        for (long[] s : sessions) m.intervals.add(new long[]{START + s[0], START + s[1]});
        for (String name : new String[]{"Red", "Blue"}) {
            RecFormat.SideInfo si = new RecFormat.SideInfo();
            si.name = name;
            si.countries.add(name.toLowerCase());
            m.sides.add(si);
        }
        return m;
    }

    private static List<RecHighlights.Highlight> of(List<RecHighlights.Highlight> all, RecHighlights.Type type) {
        return all.stream().filter(h -> h.type() == type).toList();
    }

    private static final RecHighlights.Cities ALPHA_CAPITAL = new RecHighlights.Cities() {
        public double value(RecFormat.City c) {
            return 5;
        }

        public boolean capital(RecFormat.City c) {
            return "Alpha".equals(c.name);
        }
    };

    @Test
    void capturesRetakesContestsAndCapitals() {
        RecFormat.Meta m = meta(100_000, new long[]{0, 100_000});
        Replay r = new Replay(null, m, List.of(
                frame(0),
                frame(10_000, city("Beta", 1, "held")),
                frame(20_000, city("Beta", 1, "held"), city("Gamma", null, "contested")),
                frame(30_000, city("Gamma", null, "contested"), city("Alpha", 0, "held")),
                frame(40_000, city("Alpha", 0, "held"))), Map.of(), List.of());
        List<RecHighlights.Highlight> all = RecHighlights.find(r, ALPHA_CAPITAL, null);
        assertEquals(RecHighlights.Type.START, all.get(0).type());
        assertEquals(RecHighlights.Type.END, all.get(all.size() - 1).type());
        List<RecHighlights.Highlight> captures = of(all, RecHighlights.Type.CAPTURE);
        assertEquals(1, captures.size());
        assertEquals(10_000, captures.get(0).t());
        assertEquals("Blue captured Beta", captures.get(0).text());
        assertEquals(5, captures.get(0).weight());
        assertEquals(1, of(all, RecHighlights.Type.CONTESTED).size());
        List<RecHighlights.Highlight> capitals = of(all, RecHighlights.Type.CAPITAL);
        assertEquals(1, capitals.size());
        assertEquals(15, capitals.get(0).weight());   // a capital counts triple
        // Beta goes back to its owner at 30 s, and Gamma stops being contested at 40 s
        assertEquals(List.of(30_000L, 40_000L), of(all, RecHighlights.Type.RETAKEN).stream().map(RecHighlights.Highlight::t).toList());
    }

    @Test
    void aChangeWhileAwayIsNotAHighlight() {
        // away from 30 s to 60 s; the first frame after coming back holds what changed meanwhile
        RecFormat.Meta m = meta(100_000, new long[]{0, 30_000}, new long[]{60_000, 100_000});
        Replay r = new Replay(null, m, List.of(
                frame(0),
                frame(60_500, city("Beta", 1, "held")),
                frame(80_000, city("Beta", 1, "held"), city("Delta", 0, "held"))), Map.of(), List.of());
        List<RecHighlights.Highlight> captures = of(RecHighlights.find(r, RecHighlights.Cities.NONE, null), RecHighlights.Type.CAPTURE);
        assertEquals(1, captures.size());
        assertEquals("Red captured Delta", captures.get(0).text());
        assertTrue(RecHighlights.witnessed(m, 80_000));
        assertTrue(!RecHighlights.witnessed(m, 45_000));
        assertTrue(!RecHighlights.witnessed(m, 60_500));
    }

    @Test
    void capitulationsComeFromTheGivenNations() {
        RecFormat.Meta m = meta(50_000, new long[]{0, 50_000});
        Replay r = new Replay(null, m, List.of(frame(0), frame(10_000, city("Beta", 1, "held")), frame(20_000, city("Beta", 1, "held"), city("Alpha", 1, "held"))),
                Map.of(), List.of());
        List<RecHighlights.Highlight> all = RecHighlights.find(r, RecHighlights.Cities.NONE, i -> i >= 2 ? Set.of("red") : Set.of());
        List<RecHighlights.Highlight> cap = of(all, RecHighlights.Type.CAPITULATION);
        assertEquals(1, cap.size());
        assertEquals(20_000, cap.get(0).t());
        assertEquals("red capitulated", cap.get(0).text());
    }

    @Test
    void aBattleNeedsThreePlayersOfTwoSidesForTenSeconds() {
        RecFormat.Meta m = meta(60_000, new long[]{0, 60_000});
        Map<Integer, RecFormat.PlayerInfo> players = new LinkedHashMap<>();
        String[] countries = {"red", "red", "blue", "blue"};
        for (int i = 0; i < countries.length; i++) {
            RecFormat.PlayerInfo p = new RecFormat.PlayerInfo();
            p.account = "p" + i;
            p.country = countries[i];
            players.put(i, p);
        }
        List<RecTrack.Block> blocks = new ArrayList<>();
        for (long t = 0; t <= 60_000; t += 2000) {
            List<RecTrack.Sample> s = new ArrayList<>();
            boolean together = t >= 20_000 && t <= 36_000;   // 16 s together
            s.add(RecTrack.Sample.at(0, 0, 0));
            s.add(RecTrack.Sample.at(1, 10, 0));
            s.add(RecTrack.Sample.at(2, together ? 20 : 5000, 0));
            s.add(RecTrack.Sample.at(3, together ? 30 : -5000, 0));
            blocks.add(new RecTrack.Block(t, s));
        }
        Replay r = new Replay(null, m, List.of(frame(0)), players, blocks);
        List<RecHighlights.Highlight> battles = of(RecHighlights.find(r, RecHighlights.Cities.NONE, null), RecHighlights.Type.BATTLE);
        assertEquals(1, battles.size());
        RecHighlights.Highlight b = battles.get(0);
        assertTrue(b.t() >= 18_000 && b.t() <= 22_000, "starts with the group: " + b.t());
        assertEquals(2.0, b.weight());   // 4 players
        assertEquals(List.of(0, 1), b.sides());
        assertEquals(15, b.x(), 1e-9);
    }

    @Test
    void captionShowsTheLatestCaptureForAFewSeconds() {
        List<RecHighlights.Highlight> hs = List.of(
                new RecHighlights.Highlight(10_000, RecHighlights.Type.CAPTURE, "Beta", 0, 0, List.of(), 1, "Blue captured Beta"),
                new RecHighlights.Highlight(12_000, RecHighlights.Type.BATTLE, null, 0, 0, List.of(), 1, "Battle"),
                new RecHighlights.Highlight(20_000, RecHighlights.Type.CAPITAL, "Alpha", 0, 0, List.of(), 1, "Red captured Alpha (capital)"));
        assertNull(RecHighlights.captionAt(hs, 9_000, 4_000));                        // nothing yet
        assertEquals("Blue captured Beta", RecHighlights.captionAt(hs, 11_000, 4_000));
        assertNull(RecHighlights.captionAt(hs, 15_000, 4_000));                       // over
        assertEquals("Red captured Alpha (capital)", RecHighlights.captionAt(hs, 21_000, 4_000));   // the newest wins
    }

    @Test
    void twoPlayersOrOneSideIsNoBattle() {
        RecFormat.Meta m = meta(30_000, new long[]{0, 30_000});
        Map<Integer, RecFormat.PlayerInfo> players = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            RecFormat.PlayerInfo p = new RecFormat.PlayerInfo();
            p.country = "red";
            players.put(i, p);
        }
        List<RecTrack.Block> blocks = new ArrayList<>();
        for (long t = 0; t <= 30_000; t += 2000) {
            blocks.add(new RecTrack.Block(t, List.of(RecTrack.Sample.at(0, 0, 0), RecTrack.Sample.at(1, 5, 0), RecTrack.Sample.at(2, 9, 0))));
        }
        Replay r = new Replay(null, m, List.of(frame(0)), players, blocks);
        assertEquals(0, of(RecHighlights.find(r, RecHighlights.Cities.NONE, null), RecHighlights.Type.BATTLE).size());
    }
}
