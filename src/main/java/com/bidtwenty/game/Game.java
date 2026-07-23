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
    public static final int ROSTER_SIZE = 5;
    // Exactly ROSTER_SIZE players per team must be drafted, and every reveal is
    // drafted by someone, so 2 * ROSTER_SIZE reveals fill both squads exactly.
    public static final int POOL_SIZE = 2 * ROSTER_SIZE;

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

    // --- Roster helpers ------------------------------------------------------

    /** Open roster spots remaining for a participant. */
    private int openSpots(Participant p) {
        return ROSTER_SIZE - p.getRoster().size();
    }

    private boolean isFull(Participant p) {
        return openSpots(p) <= 0;
    }

    private boolean bothFull() {
        for (Participant p : participants) {
            if (!isFull(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Highest amount a participant may bid right now. The budget-reserve rule
     * keeps at least $1 available for every other open roster spot, guaranteeing
     * that a team can always afford to fill all {@link #ROSTER_SIZE} spots.
     */
    private int maxBid(Participant p) {
        return p.getBudget() - (openSpots(p) - 1);
    }

    // --- Reveal / open next auction -----------------------------------------

    private void openAuctionForCurrent() {
        // Skip past anyone who is already full; end once both squads are set.
        while (true) {
            if (bothFull() || currentIndex >= pool.size()) {
                finish();
                return;
            }
            Participant a = participants.get(0);
            Participant b = participants.get(1);
            boolean aRoom = !isFull(a);
            boolean bRoom = !isFull(b);
            NbaPlayer np = pool.get(currentIndex);

            if (aRoom && bRoom) {
                // Contested: both squads still need players -> open auction.
                String openerId = participants.get(currentIndex % 2).getId();
                auction = new Auction(np, openerId);
                resolveForcedPasses();
                return;
            }

            // Uncontested: exactly one squad still has room -> it claims the
            // player at the minimum price and we move on.
            Participant only = aRoom ? a : b;
            auction = null;
            draft(only, np, 1, only.getName() + " claimed " + np.getName()
                    + " for $1 (uncontested)");
            currentIndex++;
            // loop to reveal the next player
        }
    }

    // --- Auction actions -----------------------------------------------------

    public synchronized void placeBid(String participantId, int amount) {
        requireAuctionPhase();
        if (!participantId.equals(auction.getTurnParticipantId())) {
            throw new IllegalStateException("Not your turn");
        }
        Participant bidder = participant(participantId);
        if (isFull(bidder)) {
            throw new IllegalStateException("Your squad is already full");
        }
        int minBid = auction.getCurrentBid() + 1;
        if (amount < minBid) {
            throw new IllegalStateException("Bid must be at least $" + minBid);
        }
        int max = maxBid(bidder);
        if (amount > max) {
            throw new IllegalStateException("Max bid is $" + max
                    + " - you must keep $1 for each of your remaining roster spots");
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
                // Both declined to bid. Every player must still be drafted so both
                // squads reach exactly ROSTER_SIZE, so award it (at $1) to whichever
                // squad has fewer players; ties go to the opener.
                forcedAward();
            } else {
                auction.setTurnParticipantId(other(participantId).getId());
                resolveForcedPasses();
            }
        }
    }

    private void award() {
        Participant winner = participant(auction.getHighBidderId());
        int price = auction.getCurrentBid();
        NbaPlayer np = auction.getPlayer();
        auction = null;
        draft(winner, np, price, winner.getName() + " won " + np.getName() + " for $" + price);
        currentIndex++;
        openAuctionForCurrent();
    }

    private void forcedAward() {
        Participant a = participants.get(0);
        Participant b = participants.get(1);
        Participant target;
        if (a.getRoster().size() != b.getRoster().size()) {
            target = a.getRoster().size() < b.getRoster().size() ? a : b;
        } else {
            target = participants.get(currentIndex % 2); // opener breaks the tie
        }
        NbaPlayer np = auction.getPlayer();
        auction = null;
        draft(target, np, 1, "No bids - " + np.getName() + " awarded to "
                + target.getName() + " for $1");
        currentIndex++;
        openAuctionForCurrent();
    }

    /** Assign a player to a squad, charge the price, and record the action. */
    private void draft(Participant winner, NbaPlayer np, int price, String action) {
        winner.spend(price);
        winner.addPlayer(np);
        lastAction = action;
    }

    /**
     * If the player whose turn it is cannot make any legal bid (their max bid is
     * below the minimum), they are auto-passed so the auction never stalls on a
     * priced-out bidder. Terminates because each auto-pass concedes to the
     * standing high bidder.
     */
    private void resolveForcedPasses() {
        while (phase == Phase.AUCTION && auction != null) {
            String turnId = auction.getTurnParticipantId();
            Participant turn = participant(turnId);
            int minBid = auction.getCurrentBid() + 1;
            if (maxBid(turn) >= minBid) {
                return; // this player has a real choice; wait for input
            }
            applyPass(turnId); // priced out -> concede; loop re-checks
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
