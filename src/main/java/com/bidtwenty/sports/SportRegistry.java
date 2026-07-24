package com.bidtwenty.sports;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple registry for sport adapters. New sports can be added by registering a
 * new definition here without plumbing the rest of the game engine.
 */
public final class SportRegistry {
    private static final Map<String, SportDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        register(new NbaSportDefinition());
    }

    private SportRegistry() {
    }

    public static void register(SportDefinition definition) {
        DEFINITIONS.put(definition.id(), definition);
    }

    public static SportDefinition get(String id) {
        if (id == null || id.isBlank()) {
            return nba();
        }
        String normalized = id.trim().toLowerCase();
        return DEFINITIONS.get(normalized);
    }

    public static SportDefinition from(SportId sportId) {
        if (sportId == null) {
            return nba();
        }
        return get(sportId.name());
    }

    public static SportDefinition nba() {
        return get("nba");
    }
}
