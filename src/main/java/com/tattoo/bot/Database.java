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
    private static final String DEFAULT_LIMIT_KEY = "default_daily_limit";

    private final String jdbcUrl;

    public Database(String dbPath, int defaultDailyLimit) {
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
        initSchema(defaultDailyLimit);
    }

    private void initSchema(int defaultDailyLimit) {
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
                      daily_limit INTEGER,
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

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO NOTHING")) {
                ps.setString(1, DEFAULT_LIMIT_KEY);
                ps.setString(2, String.valueOf(defaultDailyLimit));
                ps.executeUpdate();
            }
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

    public int getDefaultDailyLimit() {
        String sql = "SELECT value FROM settings WHERE key = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DEFAULT_LIMIT_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(1, Integer.parseInt(rs.getString("value")));
                }
            }
        } catch (Exception e) {
            log.error("Не удалось получить дневной лимит", e);
        }
        return 20;
    }

    public void setDefaultDailyLimit(int limit) {
        String sql = """
                INSERT INTO settings(key, value, updated_at)
                VALUES(?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(key) DO UPDATE SET
                  value = excluded.value,
                  updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DEFAULT_LIMIT_KEY);
            ps.setString(2, String.valueOf(Math.max(limit, 1)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось обновить лимит", e);
        }
    }

    public int getEffectiveDailyLimit(long userId) {
        String sql = "SELECT daily_limit FROM users WHERE user_id = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int val = rs.getInt("daily_limit");
                    if (!rs.wasNull()) {
                        return Math.max(1, val);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Не удалось получить лимит пользователя {}", userId, e);
        }
        return getDefaultDailyLimit();
    }

    public int getTodayUsage(long userId) {
        LocalDate today = LocalDate.now(MOSCOW_ZONE);
        return getUsageByDate(userId, today);
    }

    private int getUsageByDate(long userId, LocalDate day) {
        String sql = "SELECT count FROM generation_usage WHERE user_id = ? AND day_msk = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
        } catch (SQLException e) {
            log.error("Не удалось получить usage пользователя {}", userId, e);
        }
        return 0;
    }

    public boolean tryConsumeGeneration(long userId) {
        LocalDate today = LocalDate.now(MOSCOW_ZONE);
        String day = today.toString();

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                int limit = getEffectiveDailyLimit(conn, userId);
                int used = getUsageByDate(conn, userId, day);
                if (used >= limit) {
                    conn.rollback();
                    return false;
                }

                if (used == 0) {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO generation_usage(user_id, day_msk, count) VALUES(?, ?, 1)")) {
                        insert.setLong(1, userId);
                        insert.setString(2, day);
                        insert.executeUpdate();
                    }
                } else {
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE generation_usage SET count = count + 1 WHERE user_id = ? AND day_msk = ?")) {
                        update.setLong(1, userId);
                        update.setString(2, day);
                        update.executeUpdate();
                    }
                }
                conn.commit();
                return true;
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Не удалось обновить usage пользователя {}", userId, e);
            return false;
        }
    }

    private int getEffectiveDailyLimit(Connection conn, long userId) throws SQLException {
        String userSql = "SELECT daily_limit FROM users WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(userSql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int val = rs.getInt("daily_limit");
                    if (!rs.wasNull()) {
                        return Math.max(1, val);
                    }
                }
            }
        }

        String defaultSql = "SELECT value FROM settings WHERE key = ?";
        try (PreparedStatement ps = conn.prepareStatement(defaultSql)) {
            ps.setString(1, DEFAULT_LIMIT_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(1, Integer.parseInt(rs.getString("value")));
                }
            }
        }
        return 20;
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

    public List<UserSummary> listUsersWithTodayUsage() {
        List<UserSummary> result = new ArrayList<>();
        LocalDate today = LocalDate.now(MOSCOW_ZONE);

        String sql = """
                SELECT u.user_id,
                       u.username,
                       u.first_name,
                       u.is_admin,
                       u.daily_limit,
                       COALESCE(g.count, 0) as used_today
                FROM users u
                LEFT JOIN generation_usage g ON g.user_id = u.user_id AND g.day_msk = ?
                ORDER BY u.created_at DESC
                """;

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, today.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer personal = null;
                    int raw = rs.getInt("daily_limit");
                    if (!rs.wasNull()) {
                        personal = raw;
                    }

                    result.add(new UserSummary(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getString("first_name"),
                            rs.getInt("is_admin") == 1,
                            personal,
                            rs.getInt("used_today")
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
                String photo = rs.getString("pending_photo_file_id");
                ConversationState parsed;
                try {
                    parsed = ConversationState.valueOf(state);
                } catch (Exception ignored) {
                    parsed = ConversationState.IDLE;
                }
                return new SessionData(userId, parsed, photo);
            }
        } catch (SQLException e) {
            log.error("Не удалось получить сессию пользователя {}", userId, e);
            return new SessionData(userId, ConversationState.IDLE, null);
        }
    }

    public void setSession(long userId, ConversationState state, String pendingPhotoFileId) {
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
            ps.setString(3, pendingPhotoFileId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Не удалось сохранить сессию пользователя {}", userId, e);
        }
    }

    public void clearSession(long userId) {
        setSession(userId, ConversationState.IDLE, null);
    }
}
