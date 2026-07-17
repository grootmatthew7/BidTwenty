package com.bidtwenty.model;

/**
 * An NBA award category that players are grouped by. The weight is used by the
 * category-weighted scoring engine: a player's contribution to a team's score is
 * their resolved value multiplied by their category weight.
 */
public record Category(String id, String label, int weight) {
}
