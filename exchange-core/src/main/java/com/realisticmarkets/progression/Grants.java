package com.realisticmarkets.progression;

/** Grant strings shared by nodes and quests: {@code blueprint:<item id>}, {@code perk:<id>}, {@code guide:<id>}. */
final class Grants {
    private Grants() {}

    static void validate(String grant) {
        int i = grant.indexOf(':');
        String kind = i < 0 ? "" : grant.substring(0, i);
        if (!(kind.equals("blueprint") || kind.equals("perk") || kind.equals("guide")) || i == grant.length() - 1) {
            throw new IllegalArgumentException("bad grant '" + grant + "' (want blueprint:|perk:|guide:<id>)");
        }
    }
}
