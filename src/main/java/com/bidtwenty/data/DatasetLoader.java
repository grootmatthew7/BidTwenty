package com.bidtwenty.data;

import com.bidtwenty.model.Category;
import com.bidtwenty.model.SportPlayer;

import java.util.List;
import java.util.Map;

/**
 * Converts a sport dataset into the common in-memory repository shape used by
 * the room engine. The default loader continues to read the current NBA JSON
 * while future sports can provide new implementations.
 */
public interface DatasetLoader {
    List<Category> categories();
    List<SportPlayer> players();
    Map<String, Category> categoriesById();
}
