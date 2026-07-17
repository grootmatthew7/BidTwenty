package com.bidtwenty.data;

import com.bidtwenty.model.NbaPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Enriches curated NBA players with a live "value" derived from real stats via
 * the balldontlie API (https://www.balldontlie.io). The current API requires an
 * API key supplied through the BALLDONTLIE_API_KEY environment variable.
 *
 * This is strictly best-effort: if there is no key, the network is unreachable,
 * a player is not found, or anything else goes wrong, the player keeps its
 * curated {@code baseValue}. The game therefore always works offline.
 */
public class StatsProvider {

    private static final String BASE = "https://api.balldontlie.io/v1";
    private static final int SEASON = 2023; // most recent completed season with averages

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey = System.getenv("BALLDONTLIE_API_KEY");

    public boolean isLiveEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Attempt to resolve a live value for every player. Mutates each player's
     * resolvedValue / valueSource in place. Never throws.
     */
    public void resolveValues(List<NbaPlayer> players) {
        if (!isLiveEnabled()) {
            return; // keep curated values; nothing to do
        }
        for (NbaPlayer p : players) {
            try {
                Double live = fetchValue(p.getSearchName());
                if (live != null) {
                    p.setResolvedValue(live);
                    p.setValueSource("live");
                }
            } catch (Exception ignored) {
                // keep curated fallback for this player
            }
        }
    }

    private Double fetchValue(String searchName) throws Exception {
        Integer playerId = findPlayerId(searchName);
        if (playerId == null) {
            return null;
        }
        JsonNode avg = fetchSeasonAverages(playerId);
        if (avg == null) {
            return null;
        }
        double pts = avg.path("pts").asDouble(0);
        double reb = avg.path("reb").asDouble(0);
        double ast = avg.path("ast").asDouble(0);
        double stl = avg.path("stl").asDouble(0);
        double blk = avg.path("blk").asDouble(0);
        // Simple, transparent value formula on a roughly 0-100 scale.
        double raw = pts + 1.2 * reb + 1.5 * ast + 3 * stl + 3 * blk;
        return Math.round(raw * 10.0) / 10.0;
    }

    private Integer findPlayerId(String searchName) throws Exception {
        String url = BASE + "/players?search=" +
                URLEncoder.encode(searchName, StandardCharsets.UTF_8) + "&per_page=1";
        JsonNode body = get(url);
        if (body == null) {
            return null;
        }
        JsonNode data = body.path("data");
        if (data.isArray() && data.size() > 0) {
            return data.get(0).path("id").asInt();
        }
        return null;
    }

    private JsonNode fetchSeasonAverages(int playerId) throws Exception {
        String url = BASE + "/season_averages?season=" + SEASON + "&player_ids[]=" + playerId;
        JsonNode body = get(url);
        if (body == null) {
            return null;
        }
        JsonNode data = body.path("data");
        if (data.isArray() && data.size() > 0) {
            return data.get(0);
        }
        return null;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", apiKey)
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        return mapper.readTree(resp.body());
    }
}
