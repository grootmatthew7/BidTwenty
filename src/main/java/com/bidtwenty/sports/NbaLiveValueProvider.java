package com.bidtwenty.sports;

import com.bidtwenty.model.SportPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * NBA-specific live-value provider. This is isolated behind the sport adapter
 * boundary so the core engine does not care which provider is used.
 */
public class NbaLiveValueProvider implements LiveValueProvider {
    private static final String BASE = "https://api.balldontlie.io/v1";
    private static final int SEASON = 2023;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey = System.getenv("BALLDONTLIE_API_KEY");

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Double resolveValue(SportPlayer player) {
        if (!isEnabled()) {
            return null;
        }
        try {
            Integer playerId = findPlayerId(player.getSearchName());
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
            double raw = pts + 1.2 * reb + 1.5 * ast + 3 * stl + 3 * blk;
            return Math.round(raw * 10.0) / 10.0;
        } catch (Exception ignored) {
            return null;
        }
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
