package com.multiplayer.server.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Starts a host bot that creates a lobby and N-1 bots that join it.
 */
public final class DemoBotRunner {

    private static final Logger logger = LoggerFactory.getLogger(DemoBotRunner.class);

    private DemoBotRunner() {
    }

    public static void main(String[] args) throws Exception {
        String wsUrl = args.length > 0 ? args[0] : "ws://localhost:8080/game";
        int botCount = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int maxPlayers = args.length > 2 ? Integer.parseInt(args[2]) : Math.max(botCount, 8);

        if (botCount < 2) {
            throw new IllegalArgumentException("botCount must be at least 2");
        }

        URI serverUri = URI.create(wsUrl);
        List<DemoBotClient> bots = new ArrayList<>();

        try {
            DemoBotClient host = new DemoBotClient("bot-1", serverUri);
            bots.add(host);
            host.connect().get(10, TimeUnit.SECONDS);

            String lobbyName = "demo-" + System.currentTimeMillis();
            String lobbyId = host.createLobby(lobbyName, maxPlayers).get(10, TimeUnit.SECONDS);
            logger.info("Host created lobby '{}' with id {}", lobbyName, lobbyId);

            List<CompletableFuture<Void>> joins = new ArrayList<>();
            for (int i = 2; i <= botCount; i++) {
                int botIndex = i;
                DemoBotClient bot = new DemoBotClient("bot-" + i, serverUri);
                bots.add(bot);

                CompletableFuture<Void> joinFlow = bot.connect()
                        .thenCompose(v -> bot.joinLobby(lobbyId))
                        .thenAccept(success -> {
                            if (!success) {
                                throw new IllegalStateException("Join failed for bot-" + botIndex);
                            }
                        });

                joins.add(joinFlow);
            }

            CompletableFuture.allOf(joins.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);
            logger.info("All bots joined lobby {}", lobbyId);

            bots.forEach(DemoBotClient::startSendingInput);
            logger.info("Bots are sending movement input to server. Press ENTER to stop.");
            new Scanner(System.in).nextLine();

        } finally {
            for (DemoBotClient bot : bots) {
                try {
                    bot.close();
                } catch (Exception e) {
                    logger.warn("Error while closing bot: {}", e.getMessage());
                }
            }
        }
    }
}
