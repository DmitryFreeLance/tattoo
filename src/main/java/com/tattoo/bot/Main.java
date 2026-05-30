package com.tattoo.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.concurrent.CountDownLatch;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        AppConfig config = AppConfig.fromEnv();
        Database database = new Database(config.getDbPath(), config.getDefaultDailyLimit());
        database.ensureInitialAdmin(config.getInitialAdminId());

        KieAiClient kieAiClient = new KieAiClient(config);
        TattooBot bot = new TattooBot(config, database, kieAiClient);

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            log.info("Tattoo bot started successfully");
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Не удалось запустить Telegram-бота", e);
        }

        new CountDownLatch(1).await();
    }
}
