package com.tattoo.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.User;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Database {
    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private static final String DAILY_SUBSCRIBER_TOKENS_KEY = "daily_subscriber_tokens";
    private static final String KIE_INTERNAL_BALANCE_KEY = "kie_internal_balance";

    private static final int DEFAULT_DAILY_SUBSCRIBER_TOKENS = 40;
    private static final int DEFAULT_KIE_INTERNAL_BALANCE = 0;
    private static final int TOKEN_COST_PER_GENERATION = 4;

    private final String jdbcUrl;

    public Database(String dbPath, int ignoredLegacyDefaultDailyLimit) {
        Path path = Path.of(dbPath);
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать папку для БД: " + dbPath, e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath();
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                      user_id INTEGER PRIMARY KEY,
                      username TEXT,
                      first_name TEXT,
                      last_name TEXT,
                      is_admin INTEGER NOT NULL DEFAULT 0,
                      bonus_tokens INTEGER NOT NULL DEFAULT 0,
                      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS settings (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL,
                      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS generation_usage (
                      user_id INTEGER NOT NULL,
                      day_msk TEXT NOT NULL,
                      count INTEGER NOT NULL,
                      PRIMARY KEY (user_id, day_msk)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                      user_id INTEGER PRIMARY KEY,
                      state TEXT NOT NULL,
                      pending_photo_file_id TEXT,
                      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            // Migration for existing DBs created before bonus_tokens was added.
            try {
                st.execute("ALTER TABLE users ADD COLUMN bonus_tokens INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // Column already exists.
            }

            upsertSetting(conn, DAILY_SUBSCRIBER_TOKENS_KEY, String.valueOf(DEFAULT_DAILY_SUBSCRIBER_TOKENS), false);
            upsertSetting(conn, KIE_INTERNAL_BALANCE_KEY, String.valueOf(DEFAULT_KIE_INTERNAL_BALANCE), false);
        } catch (SQLException e) {
            throw new IllegalStateException("Ошибка инициализации БД", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void ensureInitialAdmin(Long userId) {
        if (userId == null) {
            return;
        }
        addAdmin(userId);
    }

    public void upsertUser(User user) {
        if (user == null) {
            return;
        }
        String sql = """
                INSERT INTO users(user_id, username, first_name, last_name, updated_at)
                VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                  username = excluded.username,
                  first_name = excluded.first_name,
                  last_name = excluded.last_name,
                  updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, user.getId());
            ps.setString(2, user.getUserName());
            ps.setString(3, user.getFirstName());
            ps.setString(4, user.getLastName());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Не удалось сохранить пользователя {}", user.getId(), e);
        }
    }

    public boolean isAdmin(long userId) {
        String sql = "SELECT is_admin FROM users WHERE user_id = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getInt("is_admin") == 1;
            }
        } catch (SQLException e) {
            log.error("Не удалось проверить админа {}", userId, e);
            return false;
        }
    }

    public void addAdmin(long userId) {
        String sql = """
                INSERT INTO users(user_id, is_admin, updated_at)
                VALUES(?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                  is_admin = 1,
                  updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось выдать админку пользователю: " + userId, e);
        }
    }

    public Optional<Long> findUserIdByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }
        String clean = username.trim();
        if (clean.startsWith("@")) {
            clean = clean.substring(1);
        }
        String sql = "SELECT user_id FROM users WHERE lower(username) = lower(?) LIMIT 1";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, clean);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong("user_id"));
                }
            }
        } catch (SQLException e) {
            log.error("Не удалось найти пользователя по username {}", username, e);
        }
        return Optional.empty();
    }

    public int getTokenCostPerGeneration() {
        return TOKEN_COST_PER_GENERATION;
    }

    public int getDailySubscriberTokenGrant() {
        try (Connection conn = openConnection()) {
            return getSettingInt(conn, DAILY_SUBSCRIBER_TOKENS_KEY, DEFAULT_DAILY_SUBSCRIBER_TOKENS);
        } catch (SQLException e) {
            log.error("Не удалось получить дневной баланс подписчика", e);
            return DEFAULT_DAILY_SUBSCRIBER_TOKENS;
        }
    }

    public int getKieInternalBalance() {
        try (Connection conn = openConnection()) {
            return getSettingInt(conn, KIE_INTERNAL_BALANCE_KEY, DEFAULT_KIE_INTERNAL_BALANCE);
        } catch (SQLException e) {
            log.error("Не удалось получить внутренний баланс KIE", e);
            return DEFAULT_KIE_INTERNAL_BALANCE;
        }
    }

    public void addKieInternalBalance(int tokens) {
        if (tokens <= 0) {
            return;
        }
        try (Connection conn = openConnection()) {
            int current = getSettingInt(conn, KIE_INTERNAL_BALANCE_KEY, DEFAULT_KIE_INTERNAL_BALANCE);
            int updated = Math.max(0, current + tokens);
            upsertSetting(conn, KIE_INTERNAL_BALANCE_KEY, String.valueOf(updated), true);
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось изменить внутренний баланс KIE", e);
        }
    }

    public void addUserBonusTokens(long userId, int tokens) {
        if (tokens <= 0) {
            return;
        }

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                ensureUserExists(conn, userId);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET bonus_tokens = bonus_tokens + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?")) {
                    ps.setInt(1, tokens);
                    ps.setLong(2, userId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось начислить баланс пользователю " + userId, e);
        }
    }

    public int getTodayUsage(long userId) {
        try (Connection conn = openConnection()) {
            return getUsageByDate(conn, userId, LocalDate.now(MOSCOW_ZONE).toString());
        } catch (SQLException e) {
            log.error("Не удалось получить usage пользователя {}", userId, e);
            return 0;
        }
    }

    public UserBalanceInfo getUserBalance(long userId) {
        try (Connection conn = openConnection()) {
            ensureUserExists(conn, userId);
            int dailyGrant = getSettingInt(conn, DAILY_SUBSCRIBER_TOKENS_KEY, DEFAULT_DAILY_SUBSCRIBER_TOKENS);
            int used = getUsageByDate(conn, userId, LocalDate.now(MOSCOW_ZONE).toString());
            int bonus = getUserBonusTokens(conn, userId);
            int dailyRemaining = Math.max(0, dailyGrant - used * TOKEN_COST_PER_GENERATION);
            int total = dailyRemaining + bonus;
            return new UserBalanceInfo(dailyGrant, used, dailyRemaining, bonus, total, TOKEN_COST_PER_GENERATION);
        } catch (Exception e) {
            log.error("Не удалось получить баланс пользователя {}", userId, e);
            return new UserBalanceInfo(
                    DEFAULT_DAILY_SUBSCRIBER_TOKENS,
                    0,
                    DEFAULT_DAILY_SUBSCRIBER_TOKENS,
                    0,
                    DEFAULT_DAILY_SUBSCRIBER_TOKENS,
                    TOKEN_COST_PER_GENERATION
            );
        }
    }

    public ConsumeResult tryConsumeGeneration(long userId) {
        LocalDate today = LocalDate.now(MOSCOW_ZONE);
        String day = today.toString();

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                ensureUserExists(conn, userId);

                int dailyGrant = getSettingInt(conn, DAILY_SUBSCRIBER_TOKENS_KEY, DEFAULT_DAILY_SUBSCRIBER_TOKENS);
                int usedToday = getUsageByDate(conn, userId, day);
                int bonus = getUserBonusTokens(conn, userId);
                int kieBalance = getSettingInt(conn, KIE_INTERNAL_BALANCE_KEY, DEFAULT_KIE_INTERNAL_BALANCE);

                int dailyRemaining = Math.max(0, dailyGrant - usedToday * TOKEN_COST_PER_GENERATION);
                int totalUserTokens = dailyRemaining + bonus;

                UserBalanceInfo before = new UserBalanceInfo(
                        dailyGrant,
                        usedToday,
                        dailyRemaining,
                        bonus,
                        totalUserTokens,
                        TOKEN_COST_PER_GENERATION
                );

                if (totalUserTokens < TOKEN_COST_PER_GENERATION) {
                    conn.rollback();
                    return new ConsumeResult(ConsumeStatus.USER_BALANCE_LOW, before, kieBalance);
                }

                if (kieBalance < TOKEN_COST_PER_GENERATION) {
                    conn.rollback();
                    return new ConsumeResult(ConsumeStatus.KIE_BALANCE_LOW, before, kieBalance);
                }

                int spendFromDaily = Math.min(dailyRemaining, TOKEN_COST_PER_GENERATION);
                int spendFromBonus = TOKEN_COST_PER_GENERATION - spendFromDaily;

                if (spendFromBonus > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE users SET bonus_tokens = bonus_tokens - ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?")) {
                        ps.setInt(1, spendFromBonus);
                        ps.setLong(2, userId);
                        ps.executeUpdate();
                    }
                }

                upsertUsage(conn, userId, day, usedToday + 1);
                upsertSetting(conn, KIE_INTERNAL_BALANCE_KEY, String.valueOf(kieBalance - TOKEN_COST_PER_GENERATION), true);

                int usedAfter = usedToday + 1;
                int bonusAfter = Math.max(0, bonus - spendFromBonus);
                int dailyRemainingAfter = Math.max(0, dailyGrant - usedAfter * TOKEN_COST_PER_GENERATION);
                int totalAfter = dailyRemainingAfter + bonusAfter;
                int kieAfter = kieBalance - TOKEN_COST_PER_GENERATION;

                UserBalanceInfo after = new UserBalanceInfo(
                        dailyGrant,
                        usedAfter,
                        dailyRemainingAfter,
                        bonusAfter,
                        totalAfter,
                        TOKEN_COST_PER_GENERATION
                );

                conn.commit();
                return new ConsumeResult(ConsumeStatus.SUCCESS, after, kieAfter);
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Не удалось списать токены генерации пользователя {}", userId, e);
            return new ConsumeResult(ConsumeStatus.ERROR, getUserBalance(userId), getKieInternalBalance());
        }
    }

    private void ensureUserExists(Connection conn, long userId) throws SQLException {
        String sql = """
                INSERT INTO users(user_id, updated_at)
                VALUES(?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    private int getUserBonusTokens(Connection conn, long userId) throws SQLException {
        String sql = "SELECT bonus_tokens FROM users WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0, rs.getInt("bonus_tokens"));
                }
            }
        }
        return 0;
    }

    private int getUsageByDate(Connection conn, long userId, String day) throws SQLException {
        String sql = "SELECT count FROM generation_usage WHERE user_id = ? AND day_msk = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
        }
        return 0;
    }

    private void upsertUsage(Connection conn, long userId, String day, int count) throws SQLException {
        String sql = """
                INSERT INTO generation_usage(user_id, day_msk, count)
                VALUES(?, ?, ?)
                ON CONFLICT(user_id, day_msk) DO UPDATE SET count = excluded.count
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, day);
            ps.setInt(3, Math.max(0, count));
            ps.executeUpdate();
        }
    }

    private int getSettingInt(Connection conn, String key, int fallback) throws SQLException {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return Integer.parseInt(rs.getString("value"));
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                }
            }
        }
        return fallback;
    }

    private void upsertSetting(Connection conn, String key, String value, boolean forceUpdate) throws SQLException {
        String sql;
        if (forceUpdate) {
            sql = """
                    INSERT INTO settings(key, value, updated_at)
                    VALUES(?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(key) DO UPDATE SET
                      value = excluded.value,
                      updated_at = CURRENT_TIMESTAMP
                    """;
        } else {
            sql = "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO NOTHING";
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public List<UserSummary> listUsersWithTodayUsage() {
        List<UserSummary> result = new ArrayList<>();
        LocalDate today = LocalDate.now(MOSCOW_ZONE);
        int dailyGrant = getDailySubscriberTokenGrant();

        String sql = """
                SELECT u.user_id,
                       u.username,
                       u.first_name,
                       u.is_admin,
                       u.bonus_tokens,
                       COALESCE(g.count, 0) as used_today
                FROM users u
                LEFT JOIN generation_usage g ON g.user_id = u.user_id AND g.day_msk = ?
                ORDER BY u.created_at DESC
                """;

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, today.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int used = rs.getInt("used_today");
                    int bonus = Math.max(0, rs.getInt("bonus_tokens"));
                    int dailyRemaining = Math.max(0, dailyGrant - used * TOKEN_COST_PER_GENERATION);
                    int total = dailyRemaining + bonus;

                    result.add(new UserSummary(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("first_name"),
                            rs.getInt("is_admin") == 1,
                            bonus,
                            used,
                            dailyRemaining,
                            total
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Не удалось получить список пользователей", e);
        }

        return result;
    }

    public SessionData getSession(long userId) {
        String sql = "SELECT state, pending_photo_file_id FROM sessions WHERE user_id = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new SessionData(userId, ConversationState.IDLE, null);
                }
                String state = rs.getString("state");
                String pending = rs.getString("pending_photo_file_id");
                ConversationState parsed;
                try {
                    parsed = ConversationState.valueOf(state);
                } catch (Exception ignored) {
                    parsed = ConversationState.IDLE;
                }
                return new SessionData(userId, parsed, pending);
            }
        } catch (SQLException e) {
            log.error("Не удалось получить сессию пользователя {}", userId, e);
            return new SessionData(userId, ConversationState.IDLE, null);
        }
    }

    public void setSession(long userId, ConversationState state, String pendingValue) {
        String sql = """
                INSERT INTO sessions(user_id, state, pending_photo_file_id, updated_at)
                VALUES(?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                  state = excluded.state,
                  pending_photo_file_id = excluded.pending_photo_file_id,
                  updated_at = CURRENT_TIMESTAMP
                """;

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, state.name());
            ps.setString(3, pendingValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Не удалось сохранить сессию пользователя {}", userId, e);
        }
    }

    public void clearSession(long userId) {
        setSession(userId, ConversationState.IDLE, null);
    }
}
