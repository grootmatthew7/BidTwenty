package com.bidtwenty.game;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.data.StatsProvider;
import com.bidtwenty.model.Auction;
import com.bidtwenty.model.NbaPlayer;
import com.bidtwenty.model.Participant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A single game room. Holds two participants and drives an open ascending
 * auction over a shuffled pool of NBA players, one player revealed at a time.
 *
 * All mutating actions are synchronized on the instance so concurrent WebSocket
 * messages from the two clients cannot corrupt state.
 */
public class Game {

    public enum Phase { LOBBY, AUCTION, FINISHED }

    public static final int START_BUDGET = 20;
    public static final int POOL_SIZE = 10;

    private final String roomCode;
    private final PlayerRepository repo;
    private final StatsProvider stats;
    private final ScoringEngine scoring;

    private final List<Participant> participants = new ArrayList<>();
    private String hostId;

    private Phase phase = Phase.LOBBY;
    private List<NbaPlayer> pool = new ArrayList<>();
    private int currentIndex = -1;
    private Auction auction;
    private String lastAction = "";
    private ScoringEngine.Result result;

    public Game(String roomCode, PlayerRepository repo, StatsProvider stats) {
        this.roomCode = roomCode;
        this.repo = repo;
        this.stats = stats;
        this.scoring = new ScoringEngine(repo);
    }

    public String getRoomCode() {
        return roomCode;
    }

    public Phase getPhase() {
        return phase;
    }

    public List<Participant> getParticipants() {
        return participants;
    }

    public String getHostId() {
        return hostId;
    }

    public Auction getAuction() {
        return auction;
    }

    public String getLastAction() {
        return lastAction;
    }

    public ScoringEngine.Result getResult() {
        return result;
    }

    public int poolSize() {
        return pool.size();
    }

    public int currentIndex() {
        return currentIndex;
    }

    public Participant participant(String id) {
        for (Participant p : participants) {
            if (p.getId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    public Participant other(String id) {
        for (Participant p : participants) {
            if (!p.getId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // --- Lobby ---------------------------------------------------------------

    public synchronized Participant join(String name) {
        if (participants.size() >= 2) {
            throw new IllegalStateException("Room is full");
        }
        if (phase != Phase.LOBBY) {
            throw new IllegalStateException("Game already started");
        }
        String id = UUID.randomUUID().toString();
        Participant p = new Participant(id, sanitizeName(name), START_BUDGET);
        participants.add(p);
        if (hostId == null) {
            hostId = id;
        }
        lastAction = p.getName() + " joined";
        return p;
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "Player " + (participants.size() + 1);
        }
        return name.trim().substring(0, Math.min(24, name.trim().length()));
    }

    // --- Start ---------------------------------------------------------------

    public synchronized void start(String requesterId) {
        if (phase != Phase.LOBBY) {
            throw new IllegalStateException("Game already started");
        }
        if (!requesterId.equals(hostId)) {
            throw new IllegalStateException("Only the host can start the game");
        }
        if (participants.size() < 2) {
            throw new IllegalStateException("Need two players to start");
        }
        buildPool();
        // Best-effort live value enrichment; silently keeps curated values.
        stats.resolveValues(pool);
        phase = Phase.AUCTION;
        currentIndex = 0;
        openAuctionForCurrent();
        lastAction = "Game started - " + pool.get(0).getName() + " is up first!";
    }

    private void buildPool() {
        List<NbaPlayer> all = repo.allPlayers();
        Collections.shuffle(all);
        int n = Math.min(POOL_SIZE, all.size());
        pool = new ArrayList<>(all.subList(0, n));
    }

    private void openAuctionForCurrent() {
        NbaPlayer np = pool.get(currentIndex);
        String openerId = participants.get(currentIndex % 2).getId();
        auction = new Auction(np, openerId);
        resolveForcedPasses();
    }

    // --- Auction actions -----------------------------------------------------

    public synchronized void placeBid(String participantId, int amount) {
        requireAuctionPhase();
        if (!participantId.equals(auction.getTurnParticipantId())) {
            throw new IllegalStateException("Not your turn");
        }
        Participant bidder = participant(participantId);
        int minBid = auction.getCurrentBid() + 1;
        if (amount < minBid) {
            throw new IllegalStateException("Bid must be at least $" + minBid);
        }
        if (amount > bidder.getBudget()) {
            throw new IllegalStateException("You only have $" + bidder.getBudget());
        }
        auction.setCurrentBid(amount);
        auction.setHighBidderId(participantId);
        auction.setConsecutiveOpeningPasses(0);
        auction.setTurnParticipantId(other(participantId).getId());
        lastAction = bidder.getName() + " bid $" + amount + " on " + auction.getPlayer().getName();
        resolveForcedPasses();
    }

    public synchronized void pass(String participantId) {
        requireAuctionPhase();
        if (!participantId.equals(auction.getTurnParticipantId())) {
            throw new IllegalStateException("Not your turn");
        }
        applyPass(participantId);
    }

    private void applyPass(String participantId) {
        Participant passer = participant(participantId);
        if (auction.getHighBidderId() != null) {
            // Opponent holds the high bid -> they win the player.
            award();
        } else {
            // No bid on the table yet.
            int passes = auction.getConsecutiveOpeningPasses() + 1;
            auction.setConsecutiveOpeningPasses(passes);
            lastAction = passer.getName() + " passed on " + auction.getPlayer().getName();
            if (passes >= 2) {
                // Both declined -> nobody buys this player.
                lastAction = auction.getPlayer().getName() + " went unsold";
                advance();
            } else {
                auction.setTurnParticipantId(other(participantId).getId());
                resolveForcedPasses();
            }
        }
    }

    private void award() {
        Participant winner = participant(auction.getHighBidderId());
        int price = auction.getCurrentBid();
        winner.spend(price);
        winner.addPlayer(auction.getPlayer());
        lastAction = winner.getName() + " won " + auction.getPlayer().getName() + " for $" + price;
        advance();
    }

    private void advance() {
        currentIndex++;
        if (currentIndex >= pool.size() || bothBroke()) {
            finish();
        } else {
            openAuctionForCurrent();
        }
    }

    private boolean bothBroke() {
        for (Participant p : participants) {
            if (p.getBudget() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * If the player whose turn it is cannot make any legal bid (min bid exceeds
     * their remaining budget), they are forced to pass automatically. This keeps
     * the auction from dead-locking on a broke player. Terminates because every
     * forced pass either awards the player or moves toward the two-pass unsold
     * limit.
     */
    private void resolveForcedPasses() {
        while (phase == Phase.AUCTION && auction != null) {
            String turnId = auction.getTurnParticipantId();
            Participant turn = participant(turnId);
            int minBid = auction.getCurrentBid() + 1;
            if (turn.getBudget() >= minBid) {
                return; // this player has a real choice; wait for input
            }
            applyPass(turnId); // may advance to a new auction; loop re-checks
        }
    }

    private void finish() {
        phase = Phase.FINISHED;
        auction = null;
        result = scoring.evaluate(participants);
        String summary;
        if (result.tie) {
            summary = "It's a tie!";
        } else {
            Participant w = participant(result.winnerId);
            summary = (w == null ? "Game over" : w.getName() + " wins!");
        }
        lastAction = "Auction complete. " + summary;
    }

    private void requireAuctionPhase() {
        if (phase != Phase.AUCTION || auction == null) {
            throw new IllegalStateException("No auction in progress");
        }
    }
}
