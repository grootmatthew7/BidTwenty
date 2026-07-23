package com.bidtwenty.game;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.model.NbaPlayer;
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

    public ScoringEngine(PlayerRepository repo) {
        this.repo = repo;
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

    public ParticipantScore scoreParticipant(Participant p) {
        ParticipantScore ps = new ParticipantScore();
        ps.participantId = p.getId();
        ps.name = p.getName();
        ps.cashLeft = p.getBudget();
        ps.bonusMultiplier = 1.0;
        double total = 0;
        for (NbaPlayer np : p.getRoster()) {
            LineItem li = new LineItem();
            li.player = np.getName();
            li.team = np.getTeam();
            li.category = np.getCategory();
            li.value = np.getResolvedValue();
            li.points = Math.round(np.getResolvedValue() * 10.0) / 10.0;
            ps.breakdown.add(li);
            total += li.points;
        }
        ps.rawTotal = Math.round(total * 10.0) / 10.0;
        ps.total = ps.rawTotal;
        return ps;
    }

    public Result evaluate(List<Participant> participants) {
        Result result = new Result();
        for (Participant p : participants) {
            result.scores.add(scoreParticipant(p));
        }

        // Frugality bonus: the GM who banked the most cash gets a 1.xx multiplier
        // where xx is their dollar lead over the other GM (e.g. $18 vs $12 -> 1.06).
        // A tie in leftover cash means no advantage, so the multiplier stays 1.00.
        applyCashBonus(result.scores);

        ParticipantScore best = null;
        boolean tie = false;
        for (ParticipantScore ps : result.scores) {
            if (best == null || ps.total > best.total) {
                best = ps;
                tie = false;
            } else if (ps.total == best.total) {
                tie = true;
            }
        }
        result.tie = tie;
        result.winnerId = tie ? null : (best == null ? null : best.participantId);
        return result;
    }

    /**
     * Reward the GM who saved the most money. The player with the higher leftover
     * budget has their raw total multiplied by {@code 1 + (theirCash - otherCash)/100};
     * the other player keeps a 1.00 multiplier. With two players and equal cash the
     * difference is zero, so no bonus is applied.
     */
    private void applyCashBonus(List<ParticipantScore> scores) {
        if (scores.size() != 2) {
            return;
        }
        ParticipantScore a = scores.get(0);
        ParticipantScore b = scores.get(1);
        ParticipantScore leader = a.cashLeft >= b.cashLeft ? a : b;
        ParticipantScore trailer = leader == a ? b : a;
        int diff = leader.cashLeft - trailer.cashLeft;
        if (diff <= 0) {
            return;
        }
        leader.bonusMultiplier = 1.0 + diff / 100.0;
        leader.total = Math.round(leader.rawTotal * leader.bonusMultiplier * 10.0) / 10.0;
    }
}
