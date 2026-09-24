package com.realisticmarkets.dealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldEventsTest {
    final DealerCatalog catalog = DealerCatalog.loadDefault();

    @Test
    void catalogResolvesGroupsAndItems() {
        WorldEvents ev = WorldEvents.loadDefault(catalog, 1L);
        assertEquals(10, ev.types().size());
        WorldEvents.Type harvest = ev.types().getFirst();
        assertEquals("bumper_harvest", harvest.id());
        assertTrue(ev.affects(harvest).contains("minecraft:wheat") && ev.affects(harvest).contains("minecraft:beef"));
        assertFalse(ev.affects(harvest).contains("minecraft:iron_ingot"));
        for (WorldEvents.Type t : ev.types()) {
            assertFalse(ev.affects(t).isEmpty(), t.id() + " moves something");
            assertTrue(t.headline().length() <= 36, t.id() + " headline fits one line of the Floor screen");
        }
    }

    @Test
    void scheduleIsDeterministicAndAboutOneEventEveryFewDays() {
        WorldEvents a = WorldEvents.loadDefault(catalog, 42L), b = WorldEvents.loadDefault(catalog, 42L);
        WorldEvents c = WorldEvents.loadDefault(catalog, 43L);
        int n = 0;
        boolean differs = false;
        for (long d = 0; d < 1000; d++) {
            assertEquals(a.startingOn(d), b.startingOn(d));
            differs |= !a.startingOn(d).equals(c.startingOn(d));
            n += a.startingOn(d).size();
        }
        assertTrue(differs, "another world, another history");
        double expected = a.types().stream().mapToDouble(WorldEvents.Type::chancePerDay).sum() * 1000;
        assertEquals(expected, n, expected * 0.25, "events per 1,000 days");
        assertTrue(n / 1000.0 > 1 / 6.0 && n / 1000.0 < 1 / 3.0, "one every 3-6 days: " + n);
    }

    @Test
    void theMarketHearsAtMiddayThenTheShockBuildsAndFades() {
        WorldEvents.Type drought = WorldEvents.loadTypes().get(1);
        double hear = WorldEvents.DELAY_DAYS;
        assertEquals(0.5, hear, 0.0, "half a day's head start for Newsstand readers");
        assertEquals(0, WorldEvents.transientEffect(drought, 0), 1e-12);
        assertEquals(0, WorldEvents.transientEffect(drought, hear - 0.01), 1e-12, "nothing moves before midday");
        double early = WorldEvents.transientEffect(drought, hear + 0.02), peak = WorldEvents.transientEffect(drought, hear + 0.4);
        assertTrue(early < peak * 0.25, "then it moves fast");
        assertTrue(peak > 0.27, "peak near the full +30%: " + peak);
        assertEquals(peak / 2, WorldEvents.transientEffect(drought, hear + 0.4 + drought.halfLifeDays()), 0.01);
    }

    @Test
    void eventSizesVaryAndTheEditionSaysWhichWay() {
        WorldEvents ev = WorldEvents.loadDefault(catalog, 11L);
        double lo = 9, hi = 0;
        for (long d = 0; d < 2000; d++) {
            for (WorldEvents.Event e : ev.startingOn(d)) {
                lo = Math.min(lo, e.scale());
                hi = Math.max(hi, e.scale());
            }
            List<WorldEvents.Story> paper = ev.edition(d);
            assertEquals(ev.startingOn(d).size(), paper.size());
            for (int i = 0; i < paper.size(); i++) {
                WorldEvents.Event e = ev.startingOn(d).get(i);
                assertEquals(e.type().headline(), paper.get(i).headline());
                assertEquals(e.type().shock() > 0, paper.get(i).up());
                assertTrue(paper.get(i).items().containsAll(ev.affects(e.type())));
            }
        }
        assertTrue(lo < 0.55 && hi > 1.45, "a headline says which way, not how far: " + lo + "-" + hi);
    }

    @Test
    void anEventMovesTheDealerForItsItemsOnly() {
        WorldEvents ev = WorldEvents.loadDefault(catalog, 7L);
        long day = 0;
        WorldEvents.Event found = null;
        for (; found == null && day < 500; day++) {
            for (WorldEvents.Event e : ev.startingOn(day)) if (e.type().id().equals("drought")) found = e;
        }
        Dealer d = new Dealer(catalog, DealerParams.noDrift(), 1L);
        d.setShocks(ev);
        String wheat = "minecraft:wheat";
        double before = d.fairValue(wheat, found.day());
        double morning = found.day() + WorldEvents.DELAY_DAYS - 0.01; // (the Dealer only moves forward in time)
        assertEquals(Math.exp(ev.fading(wheat, morning) - ev.fading(wheat, found.day())), d.fairValue(wheat, morning) / before, 1e-3);
        double after = d.fairValue(wheat, found.day() + 1.0);
        assertEquals(0, WorldEvents.transientEffect(found.type(), morning - found.day()), 0.0,
                "the drought isn't in the morning's price yet: a Newsstand reader can still buy at it");
        double expected = Math.exp(ev.fading(wheat, found.day() + 1.0) - ev.fading(wheat, found.day())
                + ev.permanent(wheat, found.day() + 1));
        assertEquals(expected, after / before, 0.01); // 0.01: the anchor also pulls a hair toward catalog value
        assertTrue(after / before > Math.exp(0.3 * found.scale() * 0.8), "the drought lifted wheat: " + after / before);
        double diamondMove = d.fairValue("minecraft:diamond", found.day() + 1.0) / d.fairValue("minecraft:diamond", found.day());
        if (ev.fading("minecraft:diamond", found.day() + 1.0) == 0 && ev.fading("minecraft:diamond", found.day()) == 0) {
            assertEquals(1.0, diamondMove, 0.01, "diamonds untouched by a drought");
        }
    }

}
