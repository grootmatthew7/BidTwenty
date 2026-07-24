package com.bidtwenty.game;

import com.bidtwenty.model.Participant;
import com.bidtwenty.model.SportPlayer;

import java.util.List;

/**
 * Default business rule used by the current game: sum resolved values, then
 * optionally apply a frugality bonus for the participant that saved the most
 * money.
 */
public class StraightSumScoringRule implements ScoringRule {
    @Override
    public ScoringEngine.Result evaluate(List<Participant> participants) {
        ScoringEngine.Result result = new ScoringEngine.Result();
        for (Participant p : participants) {
            result.scores.add(scoreParticipant(p));
        }

        applyCashBonus(result.scores);

        ScoringEngine.ParticipantScore best = null;
        boolean tie = false;
        for (ScoringEngine.ParticipantScore ps : result.scores) {
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

    private ScoringEngine.ParticipantScore scoreParticipant(Participant p) {
        ScoringEngine.ParticipantScore ps = new ScoringEngine.ParticipantScore();
        ps.participantId = p.getId();
        ps.name = p.getName();
        ps.cashLeft = p.getBudget();
        ps.bonusMultiplier = 1.0;
        double total = 0;
        for (SportPlayer sp : p.getRoster()) {
            ScoringEngine.LineItem li = new ScoringEngine.LineItem();
            li.player = sp.getName();
            li.team = sp.getTeam();
            li.category = sp.getCategory();
            li.value = sp.getResolvedValue();
            li.points = Math.round(sp.getResolvedValue() * 10.0) / 10.0;
            ps.breakdown.add(li);
            total += li.points;
        }
        ps.rawTotal = Math.round(total * 10.0) / 10.0;
        ps.total = ps.rawTotal;
        return ps;
    }

    private void applyCashBonus(List<ScoringEngine.ParticipantScore> scores) {
        if (scores.size() != 2) {
            return;
        }
        ScoringEngine.ParticipantScore a = scores.get(0);
        ScoringEngine.ParticipantScore b = scores.get(1);
        ScoringEngine.ParticipantScore leader = a.cashLeft >= b.cashLeft ? a : b;
        ScoringEngine.ParticipantScore trailer = leader == a ? b : a;
        int diff = leader.cashLeft - trailer.cashLeft;
        if (diff <= 0) {
            return;
        }
        leader.bonusMultiplier = 1.0 + diff / 100.0;
        leader.total = Math.round(leader.rawTotal * leader.bonusMultiplier * 10.0) / 10.0;
    }
}
