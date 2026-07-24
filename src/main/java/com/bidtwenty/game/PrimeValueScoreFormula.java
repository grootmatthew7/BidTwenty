package com.bidtwenty.game;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Code-owned definition for the Prime Value Score (PVS) formula.
 *
 * <p>This replaces the standalone formula.json artifact as the canonical home for
 * the scoring constants and documentation. The runtime can still compute the
 * value directly from the configured component inputs.</p>
 */
public final class PrimeValueScoreFormula {

    public static final String NAME = "Prime Value Score (PVS)";
    public static final String DESCRIPTION = "Scores a player on their best sustained multi-year prime, independent of which specific award they are tagged with in the dataset.";
    public static final String EQUATION = "PVS = 0.40*peakImpact + 0.25*accoladeDensity + 0.20*teamSuccess + 0.15*primeLongevity";

    public static final double PEAK_IMPACT_WEIGHT = 0.40;
    public static final double ACCOLADE_DENSITY_WEIGHT = 0.25;
    public static final double TEAM_SUCCESS_WEIGHT = 0.20;
    public static final double PRIME_LONGEVITY_WEIGHT = 0.15;

    private PrimeValueScoreFormula() {
    }

    public static double calculate(double peakImpact, double accoladeDensity, double teamSuccess, double primeLongevity) {
        return (PEAK_IMPACT_WEIGHT * peakImpact)
                + (ACCOLADE_DENSITY_WEIGHT * accoladeDensity)
                + (TEAM_SUCCESS_WEIGHT * teamSuccess)
                + (PRIME_LONGEVITY_WEIGHT * primeLongevity);
    }

    public static Map<String, Double> weights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("peakImpact", PEAK_IMPACT_WEIGHT);
        weights.put("accoladeDensity", ACCOLADE_DENSITY_WEIGHT);
        weights.put("teamSuccess", TEAM_SUCCESS_WEIGHT);
        weights.put("primeLongevity", PRIME_LONGEVITY_WEIGHT);
        return weights;
    }
}
