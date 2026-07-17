package com.bidtwenty;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.data.StatsProvider;
import com.bidtwenty.game.Game;
import com.bidtwenty.game.GameManager;
import com.bidtwenty.model.Participant;
import com.bidtwenty.web.StateView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BidTwenty server: serves the static frontend and exposes a WebSocket endpoint
 * at {@code /ws} that drives real-time two-player auction games.
 */
public class Main {

    /** Which room + participant a given socket belongs to. */
    private record ClientConn(String room, String participantId) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final PlayerRepository repo = new PlayerRepository();
    private final StatsProvider stats = new StatsProvider();
    private final GameManager manager = new GameManager(repo, stats);
    private final StateView stateView = new StateView(repo, stats.isLiveEnabled());

    // socket -> (room, participant); room -> set of sockets
    private final Map<WsContext, ClientConn> conns = new ConcurrentHashMap<>();
    private final Map<String, Set<WsContext>> roomSockets = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));
        new Main().start(port);
    }

    private void start(int port) {
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
            config.showJavalinBanner = false;
        });

        // Lightweight health check. Used by the GitHub Pages loading page to wake
        // a spun-down free-tier dyno and know when it is ready. CORS-open so the
        // static page (different origin) can read it.
        app.get("/health", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"ok\"}");
        });

        app.ws("/ws", ws -> {
            ws.onMessage(this::onMessage);
            ws.onClose(this::onClose);
            ws.onError(ctx -> { /* ignore transport errors; close handler cleans up */ });
        });

        app.start(port);
        System.out.println("BidTwenty running at http://localhost:" + port
                + "  (live NBA stats: " + (stats.isLiveEnabled() ? "ON" : "OFF - using curated values") + ")");
    }

    private void onMessage(io.javalin.websocket.WsMessageContext ctx) {
        try {
            JsonNode msg = mapper.readTree(ctx.message());
            String type = msg.path("type").asText("");
            switch (type) {
                case "create" -> handleCreate(ctx, msg);
                case "join" -> handleJoin(ctx, msg);
                case "start" -> handleStart(ctx);
                case "bid" -> handleBid(ctx, msg);
                case "pass" -> handlePass(ctx);
                default -> sendError(ctx, "Unknown message type: " + type);
            }
        } catch (IllegalStateException e) {
            sendError(ctx, e.getMessage());
        } catch (Exception e) {
            sendError(ctx, "Bad request: " + e.getMessage());
        }
    }

    private void handleCreate(WsContext ctx, JsonNode msg) {
        Game game = manager.createGame();
        Participant p = game.join(msg.path("name").asText(""));
        register(ctx, game.getRoomCode(), p.getId());
        sendJoined(ctx, game, p);
        broadcast(game);
    }

    private void handleJoin(WsContext ctx, JsonNode msg) {
        String room = msg.path("room").asText("");
        Game game = manager.get(room);
        if (game == null) {
            sendError(ctx, "No room with code " + room.toUpperCase());
            return;
        }
        Participant p = game.join(msg.path("name").asText(""));
        register(ctx, game.getRoomCode(), p.getId());
        sendJoined(ctx, game, p);
        broadcast(game);
    }

    private void handleStart(WsContext ctx) {
        ClientConn c = conns.get(ctx);
        if (c == null) {
            sendError(ctx, "You are not in a room");
            return;
        }
        Game game = manager.get(c.room());
        game.start(c.participantId());
        broadcast(game);
    }

    private void handleBid(WsContext ctx, JsonNode msg) {
        ClientConn c = requireConn(ctx);
        if (c == null) return;
        Game game = manager.get(c.room());
        game.placeBid(c.participantId(), msg.path("amount").asInt(-1));
        broadcast(game);
    }

    private void handlePass(WsContext ctx) {
        ClientConn c = requireConn(ctx);
        if (c == null) return;
        Game game = manager.get(c.room());
        game.pass(c.participantId());
        broadcast(game);
    }

    private ClientConn requireConn(WsContext ctx) {
        ClientConn c = conns.get(ctx);
        if (c == null) {
            sendError(ctx, "You are not in a room");
        }
        return c;
    }

    private void onClose(WsContext ctx) {
        ClientConn c = conns.remove(ctx);
        if (c == null) {
            return;
        }
        Set<WsContext> set = roomSockets.get(c.room());
        if (set != null) {
            set.remove(ctx);
        }
        Game game = manager.get(c.room());
        if (game != null) {
            Participant p = game.participant(c.participantId());
            if (p != null) {
                p.setConnected(false);
            }
            broadcast(game);
        }
    }

    // --- socket helpers ------------------------------------------------------

    private void register(WsContext ctx, String room, String participantId) {
        conns.put(ctx, new ClientConn(room, participantId));
        roomSockets.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(ctx);
    }

    private void sendJoined(WsContext ctx, Game game, Participant p) {
        Map<String, Object> m = Map.of(
                "type", "joined",
                "room", game.getRoomCode(),
                "participantId", p.getId(),
                "isHost", p.getId().equals(game.getHostId())
        );
        sendJson(ctx, m);
    }

    private void broadcast(Game game) {
        Set<WsContext> set = roomSockets.get(game.getRoomCode());
        if (set == null) {
            return;
        }
        String payload = toJson(stateView.build(game));
        for (WsContext ctx : set) {
            try {
                ctx.send(payload);
            } catch (Exception ignored) {
                // socket may be closing; onClose will clean it up
            }
        }
    }

    private void sendError(WsContext ctx, String message) {
        sendJson(ctx, Map.of("type", "error", "message", message == null ? "Unknown error" : message));
    }

    private void sendJson(WsContext ctx, Map<String, Object> m) {
        try {
            ctx.send(toJson(m));
        } catch (Exception ignored) {
        }
    }

    private String toJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"serialization failed\"}";
        }
    }
}
