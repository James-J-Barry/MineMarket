package com.realisticmarkets.dealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void transientEffectBuildsAfterDawnThenFades() {
        WorldEvents.Type drought = WorldEvents.loadTypes().get(1);
        assertEquals(0, WorldEvents.transientEffect(drought, 0), 1e-12);
        double early = WorldEvents.transientEffect(drought, 0.02), peak = WorldEvents.transientEffect(drought, 0.4);
        assertTrue(early < peak * 0.25, "an early reader of the news acts before most of the move");
        assertTrue(peak > 0.27, "peak near the full +30%: " + peak);
        assertEquals(peak / 2, WorldEvents.transientEffect(drought, 0.4 + drought.halfLifeDays()), 0.01);
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
        double before = d.fairValue(wheat, found.day()), after = d.fairValue(wheat, found.day() + 1.0);
        double expected = Math.exp(ev.fading(wheat, found.day() + 1.0) - ev.fading(wheat, found.day())
                + ev.permanent(wheat, found.day() + 1));
        assertEquals(expected, after / before, 0.01); // 0.01: the anchor also pulls a hair toward catalog value
        assertTrue(after / before > 1.2, "the drought lifted wheat: " + after / before);
        double diamondMove = d.fairValue("minecraft:diamond", found.day() + 1.0) / d.fairValue("minecraft:diamond", found.day());
        if (ev.fading("minecraft:diamond", found.day() + 1.0) == 0 && ev.fading("minecraft:diamond", found.day()) == 0) {
            assertEquals(1.0, diamondMove, 0.01, "diamonds untouched by a drought");
        }
    }

}
