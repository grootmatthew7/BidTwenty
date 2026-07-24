package com.bidtwenty.data;

import com.bidtwenty.model.SportPlayer;
import com.bidtwenty.sports.LiveValueProvider;
import com.bidtwenty.sports.SportRegistry;

import java.util.List;

/**
 * Enriches curated players with a live "value" via a sport-specific adapter.
 * The game always works offline because the provider is best-effort and
 * silently falls back to curated values when it cannot resolve live metadata.
 */
public class StatsProvider {
    private final LiveValueProvider liveValueProvider;

    public StatsProvider() {
        this(SportRegistry.nba().liveValueProvider());
    }

    public StatsProvider(LiveValueProvider liveValueProvider) {
        this.liveValueProvider = liveValueProvider;
    }

    public boolean isLiveEnabled() {
        return liveValueProvider.isEnabled();
    }

    /**
     * Attempt to resolve a live value for every player. Mutates each player's
     * resolvedValue / valueSource in place. Never throws.
     */
    public void resolveValues(List<SportPlayer> players) {
        if (!isLiveEnabled()) {
            return;
        }
        for (SportPlayer p : players) {
            Double live = liveValueProvider.resolveValue(p);
            if (live != null) {
                p.setResolvedValue(live);
                p.setValueSource("live");
            }
        }
    }
}
