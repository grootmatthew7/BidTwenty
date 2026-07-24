package com.bidtwenty.model;

/**
 * The live state of an open ascending auction for a single revealed NBA player.
 * Players alternate turns raising the bid or passing; when a player passes and a
 * high bidder exists, that bidder wins at the current bid.
 */
public class Auction {
    private final SportPlayer player;
    private int currentBid;          // 0 means no bid placed yet
    private String highBidderId;     // null until someone bids
    private String turnParticipantId; // whose turn it is to act
    private int consecutiveOpeningPasses; // passes while no bid on the table

    public Auction(SportPlayer player, String openerId) {
        this.player = player;
        this.currentBid = 0;
        this.highBidderId = null;
        this.turnParticipantId = openerId;
        this.consecutiveOpeningPasses = 0;
    }

    public SportPlayer getPlayer() {
        return player;
    }

    public int getCurrentBid() {
        return currentBid;
    }

    public void setCurrentBid(int currentBid) {
        this.currentBid = currentBid;
    }

    public String getHighBidderId() {
        return highBidderId;
    }

    public void setHighBidderId(String highBidderId) {
        this.highBidderId = highBidderId;
    }

    public String getTurnParticipantId() {
        return turnParticipantId;
    }

    public void setTurnParticipantId(String turnParticipantId) {
        this.turnParticipantId = turnParticipantId;
    }

    public int getConsecutiveOpeningPasses() {
        return consecutiveOpeningPasses;
    }

    public void setConsecutiveOpeningPasses(int consecutiveOpeningPasses) {
        this.consecutiveOpeningPasses = consecutiveOpeningPasses;
    }
}
