package com.bidtwenty.sports;

import com.bidtwenty.data.DatasetLoader;
import com.bidtwenty.data.NbaDatasetLoader;
import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.model.Category;
import com.bidtwenty.model.SportPlayer;

import java.util.List;

/**
 * Defines the sport-specific behavior for a game room so different sports can
 * be wired in via configuration rather than code rewrites.
 */
public interface SportDefinition {
    String id();
    String label();

    default int startBudget() {
        return 20;
    }

    default int rosterSize() {
        return 5;
    }

    default DatasetLoader datasetLoader() {
        return new NbaDatasetLoader();
    }

    default LiveValueProvider liveValueProvider() {
        return new NbaLiveValueProvider();
    }

    Category chooseCategory(PlayerRepository repo);

    List<SportPlayer> buildPool(PlayerRepository repo, Category category);
}
