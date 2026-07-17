package com.bidtwenty.web;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.game.Game;
import com.bidtwenty.game.ScoringEngine;
import com.bidtwenty.model.Auction;
import com.bidtwenty.model.Category;
import com.bidtwenty.model.NbaPlayer;
import com.bidtwenty.model.Participant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a plain, JSON-serializable snapshot of a game's public state. Because
 * bidding is an open ascending auction, all information is public, so both
 * clients receive the same snapshot.
 */
public class StateView {

    private final PlayerRepository repo;
    private final boolean liveStats;

    public StateView(PlayerRepository repo, boolean liveStats) {
        this.repo = repo;
        this.liveStats = liveStats;
    }

    public Map<String, Object> build(Game game) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "state");
        m.put("room", game.getRoomCode());
        m.put("phase", game.getPhase().name());
        m.put("hostId", game.getHostId());
        m.put("lastAction", game.getLastAction());
        m.put("liveStats", liveStats);
        m.put("startBudget", Game.START_BUDGET);
        m.put("poolIndex", game.currentIndex());
        m.put("poolSize", game.poolSize());

        // Category reference (id -> label / weight)
        Map<String, Object> cats = new LinkedHashMap<>();
        for (Category c : repo.categories().values()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("label", c.label());
            cm.put("weight", c.weight());
            cats.put(c.id(), cm);
        }
        m.put("categories", cats);

        // Participants
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Participant p : game.getParticipants()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            pm.put("budget", p.getBudget());
            pm.put("connected", p.isConnected());
            pm.put("isHost", p.getId().equals(game.getHostId()));
            pm.put("roster", rosterView(p.getRoster()));
            parts.add(pm);
        }
        m.put("participants", parts);

        // Current auction
        Auction a = game.getAuction();
        if (a != null) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("player", playerView(a.getPlayer()));
            am.put("currentBid", a.getCurrentBid());
            am.put("minBid", a.getCurrentBid() + 1);
            am.put("highBidderId", a.getHighBidderId());
            am.put("turnId", a.getTurnParticipantId());
            m.put("auction", am);
        } else {
            m.put("auction", null);
        }

        // Result
        if (game.getResult() != null) {
            m.put("result", resultView(game.getResult()));
        } else {
            m.put("result", null);
        }

        return m;
    }

    private List<Map<String, Object>> rosterView(List<NbaPlayer> roster) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (NbaPlayer np : roster) {
            list.add(playerView(np));
        }
        return list;
    }

    private Map<String, Object> playerView(NbaPlayer np) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("name", np.getName());
        pm.put("team", np.getTeam());
        pm.put("category", np.getCategory());
        Category c = repo.category(np.getCategory());
        pm.put("categoryLabel", c == null ? np.getCategory() : c.label());
        pm.put("categoryWeight", c == null ? 1 : c.weight());
        pm.put("value", np.getResolvedValue());
        pm.put("valueSource", np.getValueSource());
        return pm;
    }

    private Map<String, Object> resultView(ScoringEngine.Result r) {
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("tie", r.tie);
        rm.put("winnerId", r.winnerId);
        List<Map<String, Object>> scores = new ArrayList<>();
        for (ScoringEngine.ParticipantScore ps : r.scores) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("participantId", ps.participantId);
            sm.put("name", ps.name);
            sm.put("total", ps.total);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ScoringEngine.LineItem li : ps.breakdown) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("player", li.player);
                im.put("team", li.team);
                im.put("category", li.category);
                im.put("value", li.value);
                im.put("weight", li.weight);
                im.put("points", li.points);
                items.add(im);
            }
            sm.put("breakdown", items);
            scores.add(sm);
        }
        rm.put("scores", scores);
        return rm;
    }
}
