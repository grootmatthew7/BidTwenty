package com.bidtwenty.model;

/**
 * An NBA award category that players are grouped by. Each game is played over a
 * single category, so every player revealed at auction comes from the same set.
 */
public record Category(String id, String label) {
}
