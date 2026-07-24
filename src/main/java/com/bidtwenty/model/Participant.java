package com.bidtwenty.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A human participant in a game room. Holds their remaining budget and the roster
 * of auctionable assets they have won at auction.
 */
public class Participant {
    private final String id;
    private String name;
    private int budget;
    private final List<SportPlayer> roster = new ArrayList<>();
    private boolean connected = true;

    public Participant(String id, String name, int budget) {
        this.id = id;
        this.name = name;
        this.budget = budget;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getBudget() {
        return budget;
    }

    public void spend(int amount) {
        this.budget -= amount;
    }

    public List<SportPlayer> getRoster() {
        return roster;
    }

    public void addPlayer(SportPlayer player) {
        roster.add(player);
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }
}
