package com.bidtwenty.data;

import com.bidtwenty.model.Category;
import com.bidtwenty.model.NbaPlayer;
import com.bidtwenty.model.SportPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the curated categories + players from the selected sport dataset.
 * The default implementation remains NBA-compatible so the app continues to
 * function as-is while making the data source replaceable.
 */
public class PlayerRepository {

    private final List<SportPlayer> players;
    private final Map<String, Category> categoriesById;

    public PlayerRepository() {
        this(new NbaDatasetLoader());
    }

    public PlayerRepository(DatasetLoader loader) {
        this.players = new ArrayList<>(loader.players());
        this.categoriesById = loader.categoriesById();
    }

    /** Fresh, independent copies of every curated player. */
    public List<SportPlayer> allPlayers() {
        List<SportPlayer> copies = new ArrayList<>(players.size());
        for (SportPlayer p : players) {
            copies.add(p.copy());
        }
        return copies;
    }

    public Category category(String id) {
        return categoriesById.get(id);
    }

    public Map<String, Category> categories() {
        return categoriesById;
    }
}
