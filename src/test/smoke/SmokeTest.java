import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dependency-light end-to-end smoke test. Spins up two WebSocket clients against
 * a running BidTwenty server, plays a full auction with simple bidding strategies,
 * and asserts the game reaches FINISHED with a scored result.
 *
 * Run against a server already listening on ws://localhost:7070/ws.
 */
public class SmokeTest {

    static final ObjectMapper M = new ObjectMapper();
    static final CountDownLatch finished = new CountDownLatch(1);
    static final AtomicReference<String> roomCode = new AtomicReference<>();
    static final AtomicReference<JsonNode> finalState = new AtomicReference<>();

    static class Client {
        final String name;
        final int cap;            // max this player will pay for any one item
        WebSocket ws;
        String id;
        final StringBuilder buf = new StringBuilder();
        final CountDownLatch joined = new CountDownLatch(1);

        Client(String name, int cap) { this.name = name; this.cap = cap; }

        void send(String json) { ws.sendText(json, true); }
    }

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        Client a = new Client("Alice", 6);
        Client b = new Client("Bob", 4);

        a.ws = open(http, a);
        b.ws = open(http, b);

        // Alice creates a room.
        a.send("{\"type\":\"create\",\"name\":\"Alice\"}");
        if (!a.joined.await(5, TimeUnit.SECONDS)) fail("Alice never joined");
        String code = roomCode.get();
        System.out.println("Room code: " + code);

        // Bob joins it.
        b.send("{\"type\":\"join\",\"name\":\"Bob\",\"room\":\"" + code + "\"}");
        if (!b.joined.await(5, TimeUnit.SECONDS)) fail("Bob never joined");

        // Alice (host) starts the auction.
        a.send("{\"type\":\"start\"}");

        if (!finished.await(20, TimeUnit.SECONDS)) fail("Game did not finish in time");

        JsonNode s = finalState.get();
        JsonNode result = s.get("result");
        if (result == null || result.isNull()) fail("No result on finished game");

        System.out.println("\n=== RESULT ===");
        for (JsonNode ps : result.get("scores")) {
            int players = ps.get("breakdown").size();
            System.out.println(ps.get("name").asText() + ": " + ps.get("total").asDouble() + " pts"
                    + " (" + players + " players)");
            if (players != 5) fail(ps.get("name").asText() + " ended with " + players + " players, expected exactly 5");
        }
        boolean tie = result.get("tie").asBoolean();
        System.out.println(tie ? "Tie" : "Winner id: " + result.get("winnerId").asText());
        System.out.println("\nSMOKE TEST PASSED");
        System.exit(0);
    }

    static WebSocket open(HttpClient http, Client c) throws Exception {
        return http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:7070/ws"), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        c.buf.append(data);
                        if (last) {
                            String full = c.buf.toString();
                            c.buf.setLength(0);
                            try { handle(c, M.readTree(full)); }
                            catch (Exception e) { e.printStackTrace(); }
                        }
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    static synchronized void handle(Client c, JsonNode msg) {
        String type = msg.path("type").asText();
        switch (type) {
            case "joined" -> {
                c.id = msg.get("participantId").asText();
                if (c.name.equals("Alice")) roomCode.set(msg.get("room").asText());
                c.joined.countDown();
            }
            case "state" -> onState(c, msg);
            case "error" -> System.out.println("[" + c.name + "] ERROR: " + msg.path("message").asText());
        }
    }

    static void onState(Client c, JsonNode s) {
        String phase = s.get("phase").asText();
        if (phase.equals("FINISHED")) {
            if (finished.getCount() > 0) {
                finalState.set(s);
                finished.countDown();
            }
            return;
        }
        if (!phase.equals("AUCTION")) return;

        JsonNode auction = s.get("auction");
        if (auction == null || auction.isNull()) return;
        if (!auction.get("turnId").asText().equals(c.id)) return; // not my turn

        int rosterSize = s.path("rosterSize").asInt(5);
        int minBid = auction.get("minBid").asInt();
        int myBudget = 0, myRoster = 0;
        for (JsonNode p : s.get("participants")) {
            if (p.get("id").asText().equals(c.id)) {
                myBudget = p.get("budget").asInt();
                myRoster = p.get("roster").size();
            }
        }
        int openSpots = rosterSize - myRoster;
        int myMaxBid = myBudget; // bids open at $0; only limit is remaining budget
        // Strategy: raise up to my per-item cap and my budget; otherwise pass.
        // minBid can be 0 (opening bid), so a broke GM still claims players.
        if (openSpots > 0 && minBid <= c.cap && minBid <= myMaxBid) {
            c.send("{\"type\":\"bid\",\"amount\":" + minBid + "}");
        } else {
            c.send("{\"type\":\"pass\"}");
        }
    }

    static void fail(String why) {
        System.out.println("SMOKE TEST FAILED: " + why);
        System.exit(1);
    }
}
