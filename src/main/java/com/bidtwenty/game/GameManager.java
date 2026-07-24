package com.bidtwenty.game;

import com.bidtwenty.data.PlayerRepository;
import com.bidtwenty.data.StatsProvider;
import com.bidtwenty.sports.SportDefinition;
import com.bidtwenty.sports.SportRegistry;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active game rooms keyed by a short room code.
 */
public class GameManager {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 4;

    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final PlayerRepository repo;
    private final StatsProvider stats;
    private final SportDefinition sportDefinition;
    private final SecureRandom random = new SecureRandom();

    public GameManager(PlayerRepository repo, StatsProvider stats) {
        this(repo, stats, SportRegistry.nba());
    }

    public GameManager(PlayerRepository repo, StatsProvider stats, SportDefinition sportDefinition) {
        this.repo = repo;
        this.stats = stats;
        this.sportDefinition = sportDefinition;
    }

    public Game createGame() {
        String code = uniqueCode();
        Game game = new Game(code, repo, stats, sportDefinition);
        games.put(code, game);
        return game;
    }

    public Game get(String code) {
        if (code == null) {
            return null;
        }
        return games.get(code.toUpperCase());
    }

    public void remove(String code) {
        games.remove(code);
    }

    private String uniqueCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            code = sb.toString();
        } while (games.containsKey(code));
        return code;
    }
}
