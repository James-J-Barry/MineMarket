package com.realisticmarkets.equities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CompanyNewsTest {
    @Test
    void everyCompanyHasGoodAndBadNewsAboutEveryFewDays() {
        CompanyNews news = CompanyNews.loadDefault(1);
        for (Company c : CompanyCatalog.loadDefault().all()) {
            Set<Boolean> signs = news.types().stream().filter(t -> t.ticker().equals(c.ticker()))
                    .map(t -> t.effect() > 0).collect(Collectors.toSet());
            assertEquals(Set.of(true, false), signs, c.ticker() + " has good and bad news");
        }
        for (CompanyNews.Type t : news.types()) assertTrue(t.headline().length() <= 40, t.id() + " fits the Newsfeed");
        int n = 0;
        for (long d = 0; d < 1000; d++) n += news.startingOn(d).size();
        assertTrue(n > 200 && n < 400, "about one story every three days: " + n);
        CompanyNews again = CompanyNews.loadDefault(1);
        for (long d = 0; d < 100; d++) assertEquals(news.startingOn(d), again.startingOn(d));
    }

    @Test
    void theMarketRepricesAtTheNextDawnInTheHeadlinesDirection() {
        CompanyNews news = CompanyNews.loadDefault(4);
        long day = 10;
        while (news.startingOn(day).isEmpty() || (day + 1) % Company.QUARTER_DAYS == 0) day++;
        CompanyNews.Story story = news.startingOn(day).getFirst();
        String t = story.type().ticker();
        Equities with = new Equities(EquitiesTest.CATALOG, EquitiesTest::base, 2), without = new Equities(EquitiesTest.CATALOG, EquitiesTest::base, 2);
        with.setNews(news);
        without.setNews(new CompanyNews(List.of(), 4));
        for (long d = 0; d <= day; d++) {
            with.observe(d, EquitiesTest::base, List.of());
            without.observe(d, EquitiesTest::base, List.of());
        }
        // Earlier stories may already be priced in; compare how each moves overnight.
        long withBefore = with.fairValueCents(t), withoutBefore = without.fairValueCents(t);
        with.observe(day + 1, EquitiesTest::base, List.of());
        without.observe(day + 1, EquitiesTest::base, List.of());
        double moved = (with.fairValueCents(t) / (double) withBefore) / (without.fairValueCents(t) / (double) withoutBefore);
        assertTrue(Math.signum(moved - 1) == Math.signum(story.type().effect()) && Math.abs(moved - 1) > 0.02,
                story.type().id() + " moved " + t + " by " + moved + " at the next dawn");
    }
}
