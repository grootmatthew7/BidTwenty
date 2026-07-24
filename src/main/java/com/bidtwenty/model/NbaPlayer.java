package com.bidtwenty.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An auctionable NBA player. Loaded from the curated dataset (name, team,
 * category, fallback value) and optionally enriched with a live value resolved
 * from the NBA stats API. {@code resolvedValue} defaults to {@code baseValue}
 * until stats are fetched.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NbaPlayer implements SportPlayer {
    private String name;
    private String team;
    private String category;   // Category id, e.g. "ALL_STAR"
    private String searchName; // surname used to query the stats API
    private String primeWindow; // multi-year peak window for the player
    private Map<String, Double> components = new LinkedHashMap<>();
    private double baseValue;  // curated fallback score (0-100, may include decimals)

    private double resolvedValue; // value actually used for scoring
    private String valueSource = "curated"; // "live" or "curated"

    public NbaPlayer() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSearchName() {
        return searchName;
    }

    public void setSearchName(String searchName) {
        this.searchName = searchName;
    }

    public String getPrimeWindow() {
        return primeWindow;
    }

    public void setPrimeWindow(String primeWindow) {
        this.primeWindow = primeWindow;
    }

    public Map<String, Double> getComponents() {
        return components;
    }

    public void setComponents(Map<String, Double> components) {
        this.components = components == null ? new LinkedHashMap<>() : new LinkedHashMap<>(components);
    }

    public double getBaseValue() {
        return baseValue;
    }

    public void setBaseValue(double baseValue) {
        this.baseValue = baseValue;
    }

    public double getResolvedValue() {
        return resolvedValue;
    }

    public void setResolvedValue(double resolvedValue) {
        this.resolvedValue = resolvedValue;
    }

    public String getValueSource() {
        return valueSource;
    }

    public void setValueSource(String valueSource) {
        this.valueSource = valueSource;
    }

    /** A cheap, independent copy so each game owns its own mutable player state. */
    public NbaPlayer copy() {
        NbaPlayer c = new NbaPlayer();
        c.name = name;
        c.team = team;
        c.category = category;
        c.searchName = searchName;
        c.primeWindow = primeWindow;
        c.components = new LinkedHashMap<>(components);
        c.baseValue = baseValue;
        c.resolvedValue = baseValue; // start from fallback
        c.valueSource = "curated";
        return c;
    }
}
