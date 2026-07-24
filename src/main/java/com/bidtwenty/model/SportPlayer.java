package com.bidtwenty.model;

/**
 * Common auctionable asset interface for the game engine. The current app uses
 * NBA players, but the engine should be able to work with any sport-specific
 * entity by depending on this abstraction instead of the concrete NBA class.
 */
public interface SportPlayer {
    String getName();
    void setName(String name);

    String getTeam();
    void setTeam(String team);

    String getCategory();
    void setCategory(String category);

    String getSearchName();
    void setSearchName(String searchName);

    double getBaseValue();
    void setBaseValue(double baseValue);

    double getResolvedValue();
    void setResolvedValue(double resolvedValue);

    String getValueSource();
    void setValueSource(String valueSource);

    SportPlayer copy();
}
