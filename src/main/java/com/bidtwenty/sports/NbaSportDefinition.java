package com.bidtwenty.sports;

import com.bidtwenty.data.DatasetLoader;
import com.bidtwenty.data.NbaDatasetLoader;
import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.model.Category;
import com.bidtwenty.model.SportPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NBA sport adapter. It defines the current baseline configuration and pool
 * selection behavior while the room engine stays sport-agnostic.
 */
public class NbaSportDefinition implements SportDefinition {
    @Override
    public String id() {
        return "nba";
    }

    @Override
    public String label() {
        return "NBA";
    }

    @Override
    public DatasetLoader datasetLoader() {
        return new NbaDatasetLoader();
    }

    @Override
    public LiveValueProvider liveValueProvider() {
        return new NbaLiveValueProvider();
    }

    @Override
    public int startBudget() {
        return 20;
    }

    @Override
    public int rosterSize() {
        return 5;
    }

    @Override
    public Category chooseCategory(PlayerRepository repo) {
        List<Category> cats = new ArrayList<>(repo.categories().values());
        Collections.shuffle(cats);
        return cats.get(0);
    }

    @Override
    public List<SportPlayer> buildPool(PlayerRepository repo, Category category) {
        List<SportPlayer> inCategory = new ArrayList<>();
        for (SportPlayer p : repo.allPlayers()) {
            if (category.id().equals(p.getCategory())) {
                inCategory.add(p);
            }
        }
        Collections.shuffle(inCategory);
        return inCategory;
    }
}
