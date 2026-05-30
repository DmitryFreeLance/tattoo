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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Database {
    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private static final String DAILY_SUBSCRIBER_TOKENS_KEY = "daily_subscriber_tokens";

    private static final int DEFAULT_DAILY_SUBSCRIBER_TOKENS = 40;
    private static final int TOKEN_COST_PER_GENERATION = 4;
    private static final long DAY_SECONDS = 86_400L;

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

            st.execute("""
                    CREATE TABLE IF NOT EXISTS subscriptions (
                      user_id INTEGER PRIMARY KEY,
                      plan_code TEXT NOT NULL,
                      started_at_epoch INTEGER NOT NULL,
                      ends_at_epoch INTEGER NOT NULL,
                      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS payment_requests (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      user_id INTEGER NOT NULL,
                      product_code TEXT NOT NULL,
                      product_title TEXT NOT NULL,
                      amount_rub INTEGER NOT NULL,
                      payer_name TEXT,
                      status TEXT NOT NULL DEFAULT 'PENDING',
                      created_at_epoch INTEGER NOT NULL,
                      reviewed_by_admin INTEGER,
                      reviewed_at_epoch INTEGER,
                      admin_comment TEXT
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS subscription_boosts (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      user_id INTEGER NOT NULL,
                      plan_code TEXT NOT NULL,
                      extra_generations_per_day INTEGER NOT NULL,
                      starts_at_epoch INTEGER NOT NULL,
                      ends_at_epoch INTEGER NOT NULL,
                      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            st.execute("CREATE INDEX IF NOT EXISTS idx_payment_requests_user_status ON payment_requests(user_id, status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_payment_requests_created ON payment_requests(created_at_epoch DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_subscription_boosts_user_end ON subscription_boosts(user_id, ends_at_epoch)");

            // Migration for existing DBs created before bonus_tokens was added.
            try {
                st.execute("ALTER TABLE users ADD COLUMN bonus_tokens INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // Column already exists.
            }
            try {
                st.execute("ALTER TABLE payment_requests ADD COLUMN payer_name TEXT");
            } catch (SQLException ignored) {
                // Column already exists.
            }

            upsertSetting(conn, DAILY_SUBSCRIBER_TOKENS_KEY, String.valueOf(DEFAULT_DAILY_SUBSCRIBER_TOKENS), false);
        } catch (SQLException e) {
            throw new IllegalStateException("Ошибка инициализации БД", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private long nowEpochSec() {
        return Instant.now().getEpochSecond();
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

    public List<Long> listAdminIds() {
        List<Long> result = new ArrayList<>();
        String sql = "SELECT user_id FROM users WHERE is_admin = 1";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getLong("user_id"));
            }
        } catch (SQLException e) {
            log.error("Не удалось получить список админов", e);
        }
        return result;
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

    public Optional<UserSubscription> getActiveSubscription(long userId) {
        long now = nowEpochSec();
        try (Connection conn = openConnection()) {
            return getActiveSubscription(conn, userId, now);
        } catch (SQLException e) {
            log.error("Не удалось получить активную подписку пользователя {}", userId, e);
            return Optional.empty();
        }
    }

    public boolean hasActiveSubscription(long userId) {
        return getActiveSubscription(userId).isPresent();
    }

    public Optional<UserSubscription> getAnySubscription(long userId) {
        String sql = "SELECT user_id, plan_code, started_at_epoch, ends_at_epoch FROM subscriptions WHERE user_id = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return mapSubscription(rs);
            }
        } catch (SQLException e) {
            log.error("Не удалось получить подписку пользователя {}", userId, e);
            return Optional.empty();
        }
    }

    public void grantSubscription(long userId, SubscriptionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                ensureUserExists(conn, userId);
                grantSubscription(conn, userId, plan, nowEpochSec());
                conn.commit();
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось выдать подписку пользователю " + userId, e);
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

    public Optional<PaymentRequest> findLatestPendingPayment(long userId) {
        String sql = """
                SELECT id, user_id, product_code, product_title, amount_rub, payer_name, status,
                       created_at_epoch, reviewed_by_admin, reviewed_at_epoch, admin_comment
                FROM payment_requests
                WHERE user_id = ? AND status = 'PENDING'
                ORDER BY created_at_epoch DESC
                LIMIT 1
                """;

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return mapPaymentRequest(rs);
            }
        } catch (SQLException e) {
            log.error("Не удалось получить pending оплату пользователя {}", userId, e);
            return Optional.empty();
        }
    }

    public Optional<PaymentRequest> getPaymentRequest(long requestId) {
        String sql = """
                SELECT id, user_id, product_code, product_title, amount_rub, payer_name, status,
                       created_at_epoch, reviewed_by_admin, reviewed_at_epoch, admin_comment
                FROM payment_requests
                WHERE id = ?
                LIMIT 1
                """;

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return mapPaymentRequest(rs);
            }
        } catch (SQLException e) {
            log.error("Не удалось получить оплату {}", requestId, e);
            return Optional.empty();
        }
    }

    public long createPaymentRequest(long userId, PaymentProduct product, String payerName) {
        if (product == null) {
            throw new IllegalArgumentException("product is required");
        }

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                ensureUserExists(conn, userId);

                String insert = """
                        INSERT INTO payment_requests(user_id, product_code, product_title, amount_rub, payer_name, status, created_at_epoch)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                        """;

                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setLong(1, userId);
                    ps.setString(2, product.code());
                    ps.setString(3, product.title());
                    ps.setInt(4, product.amountRub());
                    ps.setString(5, (payerName == null || payerName.isBlank()) ? null : payerName.trim());
                    ps.setLong(6, nowEpochSec());
                    ps.executeUpdate();
                }

                long id = readLastInsertRowId(conn);
                conn.commit();
                return id;
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать заявку на оплату", e);
        }
    }

    public PaymentApprovalResult approvePaymentRequest(long requestId, long adminId) {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                PaymentRequest request = getPaymentRequestForUpdate(conn, requestId).orElse(null);
                if (request == null) {
                    conn.rollback();
                    return PaymentApprovalResult.failed("Заявка не найдена.");
                }

                if (request.status() != PaymentStatus.PENDING) {
                    conn.rollback();
                    return PaymentApprovalResult.failed("Заявка уже обработана ранее.");
                }

                long now = nowEpochSec();
                ensureUserExists(conn, request.userId());

                PaymentProduct product = request.product();
                int grantedTokens = 0;
                String approvedMessage;

                switch (product.kind()) {
                    case SUBSCRIPTION -> {
                        UserSubscription subscription = grantSubscription(conn, request.userId(), product.requiredPlan(), now);
                        approvedMessage = "Подписка активирована: " + subscription.plan().title();
                    }
                    case DAILY_BOOST -> {
                        Optional<UserSubscription> activeOpt = getActiveSubscription(conn, request.userId(), now);
                        if (activeOpt.isEmpty()) {
                            conn.rollback();
                            return PaymentApprovalResult.failed("У пользователя нет активной подписки. Увеличение лимита доступно только в период активной подписки.");
                        }

                        UserSubscription active = activeOpt.get();
                        if (active.plan() != product.requiredPlan()) {
                            conn.rollback();
                            return PaymentApprovalResult.failed("Тариф не подходит к текущей подписке пользователя (" + active.plan().title() + ").");
                        }

                        long remaining = active.remainingSeconds(now);
                        int remainingDays = (int) ((remaining + DAY_SECONDS - 1) / DAY_SECONDS);
                        remainingDays = Math.max(1, remainingDays);

                        addSubscriptionBoost(conn, request.userId(), active.plan(), product.extraGenerationsPerDay(), now, active.endsAtEpochSec());

                        approvedMessage = "Активировано увеличение лимита: +" + product.extraGenerationsPerDay()
                                + " генераций/день до конца подписки (" + remainingDays + " дн.).";
                    }
                    case ONE_TIME_PACK -> {
                        grantedTokens = product.oneTimeGenerations() * TOKEN_COST_PER_GENERATION;
                        addBonusTokens(conn, request.userId(), grantedTokens);
                        approvedMessage = "Начислен разовый пакет: " + product.oneTimeGenerations() + " генераций.";
                    }
                    default -> {
                        conn.rollback();
                        return PaymentApprovalResult.failed("Неизвестный тип продукта.");
                    }
                }

                markPaymentStatus(conn, requestId, PaymentStatus.APPROVED, adminId, now, approvedMessage);
                conn.commit();

                PaymentRequest updated = getPaymentRequestForUpdate(conn, requestId).orElse(request);
                return PaymentApprovalResult.approved(updated, grantedTokens, approvedMessage);
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Не удалось подтвердить оплату {}", requestId, e);
            return PaymentApprovalResult.failed("Ошибка при подтверждении оплаты: " + e.getMessage());
        }
    }

    public boolean rejectPaymentRequest(long requestId, long adminId, String comment) {
        String safeComment = (comment == null || comment.isBlank()) ? "Оплата отклонена администратором." : comment.trim();
        String sql = """
                UPDATE payment_requests
                SET status = 'REJECTED', reviewed_by_admin = ?, reviewed_at_epoch = ?, admin_comment = ?
                WHERE id = ? AND status = 'PENDING'
                """;

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, adminId);
            ps.setLong(2, nowEpochSec());
            ps.setString(3, safeComment);
            ps.setLong(4, requestId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Не удалось отклонить оплату {}", requestId, e);
            return false;
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
            int dailyGrant = getEffectiveDailyGrant(conn, userId, nowEpochSec());
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

                int dailyGrant = getEffectiveDailyGrant(conn, userId, nowEpochSec());
                int usedToday = getUsageByDate(conn, userId, day);
                int bonus = getUserBonusTokens(conn, userId);

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
                    return new ConsumeResult(ConsumeStatus.USER_BALANCE_LOW, before);
                }

                int spendFromDaily = Math.min(dailyRemaining, TOKEN_COST_PER_GENERATION);
                int spendFromBonus = TOKEN_COST_PER_GENERATION - spendFromDaily;

                if (spendFromBonus > 0) {
                    addBonusTokens(conn, userId, -spendFromBonus);
                }

                upsertUsage(conn, userId, day, usedToday + 1);

                int usedAfter = usedToday + 1;
                int bonusAfter = Math.max(0, bonus - spendFromBonus);
                int dailyRemainingAfter = Math.max(0, dailyGrant - usedAfter * TOKEN_COST_PER_GENERATION);
                int totalAfter = dailyRemainingAfter + bonusAfter;

                UserBalanceInfo after = new UserBalanceInfo(
                        dailyGrant,
                        usedAfter,
                        dailyRemainingAfter,
                        bonusAfter,
                        totalAfter,
                        TOKEN_COST_PER_GENERATION
                );

                conn.commit();
                return new ConsumeResult(ConsumeStatus.SUCCESS, after);
            } catch (Exception inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Не удалось списать токены генерации пользователя {}", userId, e);
            return new ConsumeResult(ConsumeStatus.ERROR, getUserBalance(userId));
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

    private Optional<UserSubscription> getActiveSubscription(Connection conn, long userId, long nowEpochSec) throws SQLException {
        String sql = """
                SELECT user_id, plan_code, started_at_epoch, ends_at_epoch
                FROM subscriptions
                WHERE user_id = ? AND ends_at_epoch > ?
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, nowEpochSec);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return mapSubscription(rs);
            }
        }
    }

    private Optional<UserSubscription> mapSubscription(ResultSet rs) throws SQLException {
        String planCode = rs.getString("plan_code");
        Optional<SubscriptionPlan> planOpt = SubscriptionPlan.fromCode(planCode);
        if (planOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new UserSubscription(
                rs.getLong("user_id"),
                planOpt.get(),
                rs.getLong("started_at_epoch"),
                rs.getLong("ends_at_epoch")
        ));
    }

    private UserSubscription grantSubscription(Connection conn, long userId, SubscriptionPlan plan, long nowEpochSec) throws SQLException {
        Optional<UserSubscription> existingOpt = getActiveSubscription(conn, userId, nowEpochSec);

        long startedAt;
        long endsAt;

        if (existingOpt.isPresent()) {
            UserSubscription existing = existingOpt.get();
            startedAt = existing.startedAtEpochSec();
            endsAt = existing.endsAtEpochSec() + (long) plan.durationDays() * DAY_SECONDS;
        } else {
            startedAt = nowEpochSec;
            endsAt = nowEpochSec + (long) plan.durationDays() * DAY_SECONDS;
        }

        String upsert = """
                INSERT INTO subscriptions(user_id, plan_code, started_at_epoch, ends_at_epoch, updated_at)
                VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                  plan_code = excluded.plan_code,
                  started_at_epoch = excluded.started_at_epoch,
                  ends_at_epoch = excluded.ends_at_epoch,
                  updated_at = CURRENT_TIMESTAMP
                """;

        try (PreparedStatement ps = conn.prepareStatement(upsert)) {
            ps.setLong(1, userId);
            ps.setString(2, plan.code());
            ps.setLong(3, startedAt);
            ps.setLong(4, endsAt);
            ps.executeUpdate();
        }

        return new UserSubscription(userId, plan, startedAt, endsAt);
    }

    private void markPaymentStatus(
            Connection conn,
            long requestId,
            PaymentStatus status,
            long adminId,
            long reviewedAtEpoch,
            String comment
    ) throws SQLException {
        String sql = """
                UPDATE payment_requests
                SET status = ?, reviewed_by_admin = ?, reviewed_at_epoch = ?, admin_comment = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, adminId);
            ps.setLong(3, reviewedAtEpoch);
            ps.setString(4, comment);
            ps.setLong(5, requestId);
            ps.executeUpdate();
        }
    }

    private Optional<PaymentRequest> getPaymentRequestForUpdate(Connection conn, long requestId) throws SQLException {
        String sql = """
                SELECT id, user_id, product_code, product_title, amount_rub, payer_name, status,
                       created_at_epoch, reviewed_by_admin, reviewed_at_epoch, admin_comment
                FROM payment_requests
                WHERE id = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return mapPaymentRequest(rs);
            }
        }
    }

    private Optional<PaymentRequest> mapPaymentRequest(ResultSet rs) throws SQLException {
        String productCode = rs.getString("product_code");
        Optional<PaymentProduct> productOpt = PaymentProduct.fromCode(productCode);
        if (productOpt.isEmpty()) {
            return Optional.empty();
        }

        String statusRaw = rs.getString("status");
        PaymentStatus status;
        try {
            status = PaymentStatus.valueOf(statusRaw);
        } catch (Exception ignored) {
            status = PaymentStatus.PENDING;
        }

        long reviewedBy = rs.getLong("reviewed_by_admin");
        Long reviewedByNullable = rs.wasNull() ? null : reviewedBy;

        long reviewedAt = rs.getLong("reviewed_at_epoch");
        Long reviewedAtNullable = rs.wasNull() ? null : reviewedAt;

        return Optional.of(new PaymentRequest(
                rs.getLong("id"),
                rs.getLong("user_id"),
                productOpt.get(),
                rs.getInt("amount_rub"),
                rs.getString("payer_name"),
                status,
                rs.getLong("created_at_epoch"),
                reviewedByNullable,
                reviewedAtNullable,
                rs.getString("admin_comment")
        ));
    }

    private void addBonusTokens(Connection conn, long userId, int delta) throws SQLException {
        String sql = """
                UPDATE users
                SET bonus_tokens = MAX(0, bonus_tokens + ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    private void addSubscriptionBoost(
            Connection conn,
            long userId,
            SubscriptionPlan plan,
            int extraGenerationsPerDay,
            long startsAtEpochSec,
            long endsAtEpochSec
    ) throws SQLException {
        String sql = """
                INSERT INTO subscription_boosts(
                  user_id, plan_code, extra_generations_per_day, starts_at_epoch, ends_at_epoch
                )
                VALUES(?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, plan.code());
            ps.setInt(3, extraGenerationsPerDay);
            ps.setLong(4, startsAtEpochSec);
            ps.setLong(5, endsAtEpochSec);
            ps.executeUpdate();
        }
    }

    private long readLastInsertRowId(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_insert_rowid() AS id"); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("id");
            }
        }
        throw new IllegalStateException("Не удалось получить ID созданной записи");
    }

    private int getEffectiveDailyGrant(Connection conn, long userId, long nowEpochSec) throws SQLException {
        return getSettingInt(conn, DAILY_SUBSCRIBER_TOKENS_KEY, DEFAULT_DAILY_SUBSCRIBER_TOKENS);
    }

    private int getActiveBoostGenerationsPerDay(Connection conn, long userId, long nowEpochSec) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(extra_generations_per_day), 0) AS extra
                FROM subscription_boosts
                WHERE user_id = ? AND ends_at_epoch > ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, nowEpochSec);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0, rs.getInt("extra"));
                }
            }
        }
        return 0;
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

        long now = nowEpochSec();

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, today.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int used = rs.getInt("used_today");
                    int bonus = Math.max(0, rs.getInt("bonus_tokens"));

                    int dailyGrant = getEffectiveDailyGrant(conn, rs.getLong("user_id"), now);

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
                            total,
                            false,
                            "доступ через подписку на канал"
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
