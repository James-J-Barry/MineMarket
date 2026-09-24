package com.realisticmarkets.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Bank Vault interest and CD terms, loaded from {@code bank_params.properties}. */
public record BankParams(double interestRate, List<CdTerm> cdTerms, long cdMinimumCents) {
    public static final String DEFAULT_RESOURCE = "/realisticmarkets/bank_params.properties";

    public record CdTerm(int days, double dailyRate) {
        public CdTerm {
            if (days <= 0) throw new IllegalArgumentException("CD term must be positive");
            if (dailyRate < 0) throw new IllegalArgumentException("CD rate must be >= 0");
        }
    }

    public BankParams {
        if (interestRate < 0) throw new IllegalArgumentException("interest_rate must be >= 0");
        if (cdMinimumCents < 0) throw new IllegalArgumentException("cd_minimum_cents must be >= 0");
        cdTerms = List.copyOf(cdTerms);
    }

    /**
     * How far bank rates have moved: the configured rates are the ones at the starting central rate
     * ({@link #interestRate()}); when the central rate moves, the vault, new CDs and loans move with it.
     */
    public double shift(double centralRate) {
        return centralRate - interestRate;
    }

    /** A CD term priced at today's central rate (its premium over the vault kept). */
    public CdTerm term(int days, double centralRate) {
        CdTerm t = term(days);
        return new CdTerm(t.days(), Math.max(0.0001, t.dailyRate() + shift(centralRate)));
    }

    public CdTerm term(int days) {
        for (CdTerm t : cdTerms) if (t.days() == days) return t;
        throw new IllegalArgumentException("no " + days + "-day CD");
    }

    public static BankParams loadDefault() {
        try (InputStream in = BankParams.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + DEFAULT_RESOURCE);
            Properties p = new Properties();
            p.load(in);
            return fromProperties(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BankParams fromProperties(Properties p) {
        List<CdTerm> terms = new ArrayList<>();
        for (String t : p.getProperty("cd_terms", "7:0.0045,21:0.006").split(",")) {
            String[] c = t.strip().split(":");
            terms.add(new CdTerm(Integer.parseInt(c[0].strip()), Double.parseDouble(c[1].strip())));
        }
        return new BankParams(Double.parseDouble(p.getProperty("interest_rate", "0.003").strip()), terms,
                Long.parseLong(p.getProperty("cd_minimum_cents", "10000").strip()));
    }
}
