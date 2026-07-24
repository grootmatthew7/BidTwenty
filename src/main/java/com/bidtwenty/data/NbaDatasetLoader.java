package com.bidtwenty.data;

import com.bidtwenty.model.Category;
import com.bidtwenty.model.NbaPlayer;
import com.bidtwenty.model.SportPlayer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default loader for the existing curated NBA dataset. It preserves the current
 * file format and data contract while making the repository decoupled from the
 * specific source of truth.
 */
public class NbaDatasetLoader implements DatasetLoader {
    private final List<Category> categories = new ArrayList<>();
    private final List<SportPlayer> players = new ArrayList<>();
    private final Map<String, Category> categoriesById = new LinkedHashMap<>();

    public NbaDatasetLoader() {
        Dataset dataset = load();
        this.categories.addAll(dataset.categories);
        this.players.addAll(dataset.players);
        for (Category c : dataset.categories) {
            categoriesById.put(c.id(), c);
        }
    }

    @Override
    public List<Category> categories() {
        return categories;
    }

    @Override
    public List<SportPlayer> players() {
        return players;
    }

    @Override
    public Map<String, Category> categoriesById() {
        return categoriesById;
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

    /** Jackson-friendly shape mirroring players.json. */
    public static class Dataset {
        public List<Category> categories = new ArrayList<>();
        public List<NbaPlayer> players = new ArrayList<>();
    }
}
