package com.bidtwenty.game;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.model.Participant;

import java.util.ArrayList;
import java.util.List;

/**
 * Straight-sum scoring. Because every game is played over a single category,
 * each rostered player simply contributes their resolved value to their owner's
 * total. The higher total wins; equal totals are a tie.
 */
public class ScoringEngine {

    private final PlayerRepository repo;
    private final ScoringRule scoringRule;

    public ScoringEngine(PlayerRepository repo) {
        this(repo, new StraightSumScoringRule());
    }

    public ScoringEngine(PlayerRepository repo, ScoringRule scoringRule) {
        this.repo = repo;
        this.scoringRule = scoringRule;
    }

    public static class LineItem {
        public String player;
        public String team;
        public String category;
        public double value;   // resolved player value
        public double points;  // equals value (no multiplier)
    }

    public static class ParticipantScore {
        public String participantId;
        public String name;
        public double rawTotal;         // straight sum of point values
        public int cashLeft;            // budget remaining at game end
        public double bonusMultiplier;  // 1.0 unless this GM banked the most cash
        public double total;            // rawTotal * bonusMultiplier (rounded)
        public List<LineItem> breakdown = new ArrayList<>();
    }

    public static class Result {
        public List<ParticipantScore> scores = new ArrayList<>();
        public String winnerId; // null on a tie
        public boolean tie;
    }

    public Result evaluate(List<Participant> participants) {
        return scoringRule.evaluate(participants);
    }
}
