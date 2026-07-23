package com.bidtwenty.data;

import com.bidtwenty.model.Category;
import com.bidtwenty.model.NbaPlayer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the curated categories + players from {@code players.json} on the
 * classpath. This is the source of truth for category membership (which the
 * free NBA API does not expose) and for fallback player values.
 */
public class PlayerRepository {

    /** Jackson-friendly shape mirroring players.json. */
    public static class Dataset {
        public List<Category> categories = new ArrayList<>();
        public List<NbaPlayer> players = new ArrayList<>();
    }

    private final List<NbaPlayer> players;
    private final Map<String, Category> categoriesById = new LinkedHashMap<>();

    public PlayerRepository() {
        Dataset dataset = load();
        this.players = dataset.players;
        for (Category c : dataset.categories) {
            categoriesById.put(c.id(), c);
        }
    }

    private Dataset load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("players.json")) {
            if (in == null) {
                throw new IllegalStateException("players.json not found on classpath");
            }
            return mapper.readValue(in, Dataset.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load players.json", e);
        }
    }

    /** Fresh, independent copies of every curated player. */
    public List<NbaPlayer> allPlayers() {
        List<NbaPlayer> copies = new ArrayList<>(players.size());
        for (NbaPlayer p : players) {
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
