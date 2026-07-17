package com.bidtwenty.game;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.model.NbaPlayer;
import com.bidtwenty.model.Participant;

import java.util.ArrayList;
import java.util.List;

/**
 * Category-weighted scoring. Each rostered player contributes
 * {@code resolvedValue * categoryWeight} to their owner's total. The higher
 * total wins; equal totals are a tie.
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
        public int weight;     // category weight
        public double points;  // value * weight
    }

    public static class ParticipantScore {
        public String participantId;
        public String name;
        public double total;
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
        double total = 0;
        for (NbaPlayer np : p.getRoster()) {
            int weight = repo.categoryWeight(np.getCategory());
            LineItem li = new LineItem();
            li.player = np.getName();
            li.team = np.getTeam();
            li.category = np.getCategory();
            li.value = np.getResolvedValue();
            li.weight = weight;
            li.points = Math.round(np.getResolvedValue() * weight * 10.0) / 10.0;
            ps.breakdown.add(li);
            total += li.points;
        }
        ps.total = Math.round(total * 10.0) / 10.0;
        return ps;
    }

    public Result evaluate(List<Participant> participants) {
        Result result = new Result();
        for (Participant p : participants) {
            result.scores.add(scoreParticipant(p));
        }
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
}
