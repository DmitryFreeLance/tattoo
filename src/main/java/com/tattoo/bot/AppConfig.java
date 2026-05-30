package com.tattoo.bot;

import java.util.Map;

public class AppConfig {
    private final String botToken;
    private final String botUsername;
    private final String kieApiKey;
    private final String requiredChannelId;
    private final String requiredChannelUrl;
    private final String dbPath;
    private final int defaultDailyLimit;
    private final Long initialAdminId;
    private final long pollDelayMillis;
    private final String kieCreateTaskEndpoint;
    private final String kieRecordInfoEndpoint;
    private final String kieFileUploadEndpoint;

    private AppConfig(
            String botToken,
            String botUsername,
            String kieApiKey,
            String requiredChannelId,
            String requiredChannelUrl,
            String dbPath,
            int defaultDailyLimit,
            Long initialAdminId,
            long pollDelayMillis,
            String kieCreateTaskEndpoint,
            String kieRecordInfoEndpoint,
            String kieFileUploadEndpoint
    ) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.kieApiKey = kieApiKey;
        this.requiredChannelId = requiredChannelId;
        this.requiredChannelUrl = requiredChannelUrl;
        this.dbPath = dbPath;
        this.defaultDailyLimit = defaultDailyLimit;
        this.initialAdminId = initialAdminId;
        this.pollDelayMillis = pollDelayMillis;
        this.kieCreateTaskEndpoint = kieCreateTaskEndpoint;
        this.kieRecordInfoEndpoint = kieRecordInfoEndpoint;
        this.kieFileUploadEndpoint = kieFileUploadEndpoint;
    }

    public static AppConfig fromEnv() {
        Map<String, String> env = System.getenv();

        String botToken = firstNonBlank(env,
                "TELEGRAM_BOT_TOKEN",
                "BOT_TOKEN");
        String kieApiKey = firstNonBlank(env,
                "KIE_API_KEY",
                "KIE_TOKEN");
        String requiredChannelId = firstNonBlank(env,
                "REQUIRED_CHANNEL_ID",
                "CHANNEL_ID");

        String requiredChannelUrl = envOrDefault(env, "REQUIRED_CHANNEL_URL", "");
        String botUsername = envOrDefault(env, "TELEGRAM_BOT_USERNAME", "tattoo_helper_bot");
        String dbPath = envOrDefault(env, "DB_PATH", "./data/bot.db");
        int defaultDailyLimit = intEnv(env, "DEFAULT_DAILY_LIMIT", 20);
        Long initialAdminId = longEnvNullable(env, "INITIAL_ADMIN_ID");
        long pollDelayMillis = longEnv(env, "KIE_POLL_DELAY_MS", 1_800L);

        String kieCreateTaskEndpoint = envOrDefault(env,
                "KIE_CREATE_TASK_ENDPOINT",
                "https://api.kie.ai/api/v1/jobs/createTask");
        String kieRecordInfoEndpoint = envOrDefault(env,
                "KIE_RECORD_INFO_ENDPOINT",
                "https://api.kie.ai/api/v1/jobs/recordInfo");
        String kieFileUploadEndpoint = envOrDefault(env,
                "KIE_FILE_UPLOAD_ENDPOINT",
                "https://kieai.redpandaai.co/api/file-base64-upload");

        if (isBlank(botToken)) {
            throw new IllegalArgumentException("Не задан TELEGRAM_BOT_TOKEN");
        }
        if (isBlank(kieApiKey)) {
            throw new IllegalArgumentException("Не задан KIE_API_KEY");
        }
        if (isBlank(requiredChannelId)) {
            throw new IllegalArgumentException("Не задан REQUIRED_CHANNEL_ID (например @my_channel или -1001234567890)");
        }

        return new AppConfig(
                botToken,
                botUsername,
                kieApiKey,
                requiredChannelId,
                requiredChannelUrl,
                dbPath,
                Math.max(defaultDailyLimit, 1),
                initialAdminId,
                Math.max(pollDelayMillis, 500L),
                kieCreateTaskEndpoint,
                kieRecordInfoEndpoint,
                kieFileUploadEndpoint
        );
    }

    private static String firstNonBlank(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String val = env.get(key);
            if (!isBlank(val)) {
                return val.trim();
            }
        }
        return null;
    }

    private static String envOrDefault(Map<String, String> env, String key, String fallback) {
        String val = env.get(key);
        return isBlank(val) ? fallback : val.trim();
    }

    private static int intEnv(Map<String, String> env, String key, int fallback) {
        String val = env.get(key);
        if (isBlank(val)) {
            return fallback;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longEnv(Map<String, String> env, String key, long fallback) {
        String val = env.get(key);
        if (isBlank(val)) {
            return fallback;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Long longEnvNullable(Map<String, String> env, String key) {
        String val = env.get(key);
        if (isBlank(val)) {
            return null;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String getBotToken() {
        return botToken;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getKieApiKey() {
        return kieApiKey;
    }

    public String getRequiredChannelId() {
        return requiredChannelId;
    }

    public String getRequiredChannelUrl() {
        return requiredChannelUrl;
    }

    public String getDbPath() {
        return dbPath;
    }

    public int getDefaultDailyLimit() {
        return defaultDailyLimit;
    }

    public Long getInitialAdminId() {
        return initialAdminId;
    }

    public long getPollDelayMillis() {
        return pollDelayMillis;
    }

    public String getKieCreateTaskEndpoint() {
        return kieCreateTaskEndpoint;
    }

    public String getKieRecordInfoEndpoint() {
        return kieRecordInfoEndpoint;
    }

    public String getKieFileUploadEndpoint() {
        return kieFileUploadEndpoint;
    }
}
