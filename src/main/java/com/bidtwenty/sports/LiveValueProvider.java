package com.bidtwenty.sports;

import com.bidtwenty.model.SportPlayer;

/**
 * Strategy for enriching a curated asset with a live numeric value. Each sport
 * can supply its own provider without touching the core auction engine.
 */
public interface LiveValueProvider {
    boolean isEnabled();
    Double resolveValue(SportPlayer player);
}
