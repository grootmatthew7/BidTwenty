package com.bidtwenty.game;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.data.StatsProvider;
import com.bidtwenty.model.Auction;
import com.bidtwenty.model.Category;
import com.bidtwenty.model.NbaPlayer;
import com.bidtwenty.model.Participant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    // Delay between showing an auto-claimed player and adding it to the trailing
    // squad. Overridable via env var so tests can run without the 3s pause.
    public static final long AUTO_CLAIM_DELAY_MS =
            Long.parseLong(System.getenv().getOrDefault("BIDTWENTY_AUTOCLAIM_MS", "3000"));

    private final String roomCode;
    private final PlayerRepository repo;
    private final StatsProvider stats;
    private final ScoringEngine scoring;

    private final List<Participant> participants = new ArrayList<>();
    private String hostId;

    private Phase phase = Phase.LOBBY;
    private Category category;
    private List<NbaPlayer> pool = new ArrayList<>();
    private int currentIndex = -1;
    private Auction auction;
    private String lastAction = "";
    private ScoringEngine.Result result;

    // Auto-fill: a player revealed and pending a delayed, free add to the
    // trailing squad (set while the reveal is on screen, cleared on commit).
    private NbaPlayer autoClaimPlayer;
    private String autoClaimTargetId;

    // Wired by the server so the game can drive its own timed auto-fill steps
    // and push a fresh snapshot to both clients after each step.
    private ScheduledExecutorService scheduler;
    private Runnable broadcaster;

    public Game(String roomCode, PlayerRepository repo, StatsProvider stats) {
        this.roomCode = roomCode;
        this.repo = repo;
        this.stats = stats;
        this.scoring = new ScoringEngine(repo);
    }

    /** Wire the scheduler + broadcast callback used to drive timed auto-fill. */
    public void setAutoRunner(ScheduledExecutorService scheduler, Runnable broadcaster) {
        this.scheduler = scheduler;
        this.broadcaster = broadcaster;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public Phase getPhase() {
        return phase;
    }

    /** The single award category this game is played over, or null in the lobby. */
    public Category getCategory() {
        return category;
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

    /** Player currently being shown before its delayed auto-add, or null. */
    public NbaPlayer getAutoClaimPlayer() {
        return autoClaimPlayer;
    }

    /** Squad that a pending auto-claim will be added to, or null. */
    public String getAutoClaimTargetId() {
        return autoClaimTargetId;
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
        lastAction = category.label() + " auction! " + pool.get(0).getName() + " is up first.";
    }

    /**
     * Pick one random award category and draw the whole pool from it, so every
     * player revealed this game belongs to the same set.
     */
    private void buildPool() {
        List<Category> cats = new ArrayList<>(repo.categories().values());
        Collections.shuffle(cats);
        category = cats.get(0);

        List<NbaPlayer> inCategory = new ArrayList<>();
        for (NbaPlayer p : repo.allPlayers()) {
            if (category.id().equals(p.getCategory())) {
                inCategory.add(p);
            }
        }
        Collections.shuffle(inCategory);
        int n = Math.min(POOL_SIZE, inCategory.size());
        pool = new ArrayList<>(inCategory.subList(0, n));
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
     * Minimum legal next bid. Bidding opens at $0 (so a GM who has spent all their
     * money can still acquire a player for free), and each raise must be at least
     * $1 above the standing bid.
     */
    private int currentMinBid() {
        return auction.getHighBidderId() == null ? 0 : auction.getCurrentBid() + 1;
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

            // Uncontested: exactly one squad still has room -> reveal the player
            // and, after a short delay, add it to that squad for free. Returns so
            // the reveal is broadcast; a scheduled task commits the add.
            Participant only = aRoom ? a : b;
            beginAutoClaim(only, np);
            return;
        }
    }

    /**
     * Show an uncontested player, then auto-add it to the trailing squad after
     * {@link #AUTO_CLAIM_DELAY_MS}. There is nothing to bid on, so both clients
     * just watch the squad fill out one player at a time.
     */
    private void beginAutoClaim(Participant target, NbaPlayer np) {
        auction = null;
        autoClaimPlayer = np;
        autoClaimTargetId = target.getId();
        lastAction = np.getName() + " up next — filling out " + target.getName() + "'s squad…";
        scheduleAutoClaimCommit();
    }

    private void scheduleAutoClaimCommit() {
        if (scheduler == null) {
            // No scheduler wired (e.g. a bare unit context): resolve instantly.
            commitAutoClaim();
            return;
        }
        scheduler.schedule(() -> {
            synchronized (this) {
                commitAutoClaim();
            }
            if (broadcaster != null) {
                broadcaster.run();
            }
        }, AUTO_CLAIM_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void commitAutoClaim() {
        if (phase != Phase.AUCTION || autoClaimPlayer == null) {
            return; // nothing pending (or the game already ended)
        }
        Participant target = participant(autoClaimTargetId);
        NbaPlayer np = autoClaimPlayer;
        autoClaimPlayer = null;
        autoClaimTargetId = null;
        draft(target, np, 0, target.getName() + " added " + np.getName() + " for $0 (uncontested)");
        currentIndex++;
        openAuctionForCurrent();
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
        int minBid = currentMinBid();
        if (amount < minBid) {
            throw new IllegalStateException("Bid must be at least $" + minBid);
        }
        if (amount > bidder.getBudget()) {
            throw new IllegalStateException("You only have $" + bidder.getBudget()
                    + " to spend");
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
                // Both declined to bid: nobody is forced to take the player. Send
                // it to the back of the queue to resurface later, and reveal the
                // next one.
                requeueCurrent();
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

    /**
     * Both GMs passed with no bid on the table: move the revealed player to the
     * back of the queue (leaving {@code currentIndex} put, since the next player
     * shifts into this slot) and reveal whoever is next.
     */
    private void requeueCurrent() {
        NbaPlayer np = pool.remove(currentIndex);
        pool.add(np);
        auction = null;
        lastAction = "No bids on " + np.getName() + " — back of the queue";
        openAuctionForCurrent();
    }

    /** Assign a player to a squad, charge the price, and record the action. */
    private void draft(Participant winner, NbaPlayer np, int price, String action) {
        winner.spend(price);
        winner.addPlayer(np);
        lastAction = action;
    }

    /**
     * If the player whose turn it is cannot make any legal bid (their budget is
     * below the minimum next bid), they are auto-passed so the auction never
     * stalls on a priced-out bidder. Because the opening bid is $0, this only
     * triggers once the standing bid has climbed above a broke GM's budget.
     * Terminates because each auto-pass concedes to the standing high bidder.
     */
    private void resolveForcedPasses() {
        while (phase == Phase.AUCTION && auction != null) {
            String turnId = auction.getTurnParticipantId();
            Participant turn = participant(turnId);
            if (turn.getBudget() >= currentMinBid()) {
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
