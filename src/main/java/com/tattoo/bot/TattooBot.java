package com.tattoo.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.groupadministration.CreateChatInviteLink;
import org.telegram.telegrambots.meta.api.methods.groupadministration.ExportChatInviteLink;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.ChatInviteLink;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TattooBot extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(TattooBot.class);

    private static final String CB_CHECK_SUB = "sub_check";
    private static final String CB_MENU_TRANSFER = "menu_transfer";
    // Legacy callback from old messages (button removed from current menu).
    private static final String CB_MENU_SKETCH = "menu_sketch";
    private static final String CB_MENU_FREE = "menu_free";
    private static final String CB_MENU_ADMIN = "menu_admin";

    private static final String CB_ADMIN_USERS = "admin_users";
    private static final String CB_ADMIN_ADD = "admin_add";
    private static final String CB_ADMIN_GIVE_BALANCE = "admin_give_balance";
    private static final String CB_ADMIN_KIE = "admin_kie";
    private static final String CB_ADMIN_KIE_REFRESH = "admin_kie_refresh";

    private static final String CB_BACK_MENU = "back_menu";
    private static final String CB_CANCEL = "cancel";

    private static final String PROMPT_TRANSFER = "Обработка изображения так, чтобы сделать из этого рисунка контурный линейный рисунок с обозначениями теней пунктиром и линиями разной толщины.";
    private static final String CONTRAST_BLACK_INSTRUCTION =
            "Сделай результат более контрастным: более насыщенный черный, глубокие темные зоны, четкие черные контуры. " +
                    "Сохраняй читаемость деталей, без серой \"мутности\".";

    private static final String WELCOME_TEXT = "👋 <b>Добро пожаловать в Tattoo Assistant</b>\n\n"
            + "Я помогу быстро подготовить:\n"
            + "• 🖼️ трансферный рисунок\n"
            + "• 🧠 свободную генерацию по вашему промпту\n\n"
            + "Выберите режим ниже 👇";

    private final AppConfig config;
    private final Database database;
    private final KieAiClient kieAiClient;
    private final HttpClient httpClient;
    private final ExecutorService generationPool;

    public TattooBot(AppConfig config, Database database, KieAiClient kieAiClient) {
        super(config.getBotToken());
        this.config = config;
        this.database = database;
        this.kieAiClient = kieAiClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.generationPool = Executors.newFixedThreadPool(4);
    }

    @Override
    public String getBotUsername() {
        return config.getBotUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update == null) {
                return;
            }
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Ошибка обработки update", e);
        }
    }

    private void handleCallback(CallbackQuery callback) {
        answerCallback(callback.getId());

        if (callback == null || callback.getFrom() == null || callback.getMessage() == null) {
            return;
        }

        User user = callback.getFrom();
        long userId = user.getId();
        long chatId = callback.getMessage().getChatId();
        String data = callback.getData();

        database.upsertUser(user);

        if (CB_CHECK_SUB.equals(data)) {
            if (isSubscribed(userId)) {
                database.clearSession(userId);
                sendMainMenu(chatId, userId, buildWelcomeWithBalance(userId));
            } else {
                sendSubscriptionGate(chatId, "Пока не вижу подписку. Подпишитесь на канал и нажмите кнопку еще раз 👇");
            }
            return;
        }

        if (!ensureSubscribed(userId, chatId)) {
            return;
        }

        switch (data) {
            case CB_MENU_TRANSFER -> {
                database.setSession(userId, ConversationState.WAIT_TRANSFER_PHOTO, null);
                sendMessage(chatId,
                        "🖼️ <b>Трансферный рисунок</b>\n\n"
                                + "Пришлите <b>одно фото</b>, и я подготовлю контурный трансфер для переноса.\n"
                                + "Только фото, текст сейчас не обрабатываю.",
                        keyboardWithCancel());
            }
            case CB_MENU_SKETCH -> {
                database.clearSession(userId);
                sendMainMenu(chatId, userId,
                        withBalance("ℹ️ Режим <b>«Эскиз»</b> отключен. Используйте доступные режимы ниже 👇", userId));
            }
            case CB_MENU_FREE -> {
                database.setSession(userId, ConversationState.WAIT_FREE_INPUT, null);
                sendMessage(chatId,
                        "🧠 <b>Свободная генерация</b>\n\n"
                                + "Варианты:\n"
                                + "1) Фото + подпись (промпт) одним сообщением\n"
                                + "2) Сначала фото, потом отдельным сообщением промпт\n"
                                + "3) Только текстовый промпт\n\n"
                                + "Важно: без промпта генерация не запускается.",
                        keyboardWithCancel());
            }
            case CB_MENU_ADMIN -> {
                if (!database.isAdmin(userId)) {
                    sendMessage(chatId, "⛔️ Админ-панель доступна только администраторам.", backToMenuKeyboard());
                    return;
                }
                sendAdminPanel(chatId);
            }
            case CB_ADMIN_USERS -> {
                if (!database.isAdmin(userId)) {
                    sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
                    return;
                }
                sendUsersList(chatId);
            }
            case CB_ADMIN_ADD -> {
                if (!database.isAdmin(userId)) {
                    sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
                    return;
                }
                database.setSession(userId, ConversationState.WAIT_ADMIN_ID, null);
                sendMessage(chatId,
                        "➕ <b>Добавление администратора</b>\n\n"
                                + "Пришлите:\n"
                                + "• ID пользователя (например <code>123456789</code>)\n"
                                + "или\n"
                                + "• @username (если пользователь уже писал боту).",
                        keyboardWithCancel());
            }
            case CB_ADMIN_GIVE_BALANCE -> {
                if (!database.isAdmin(userId)) {
                    sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
                    return;
                }
                database.setSession(userId, ConversationState.WAIT_BONUS_TARGET, null);
                sendMessage(chatId,
                        "🎟️ <b>Выдача дополнительных генераций</b>\n\n"
                                + "Пришлите ID пользователя или @username.",
                        keyboardWithCancel());
            }
            case CB_ADMIN_KIE -> {
                if (!database.isAdmin(userId)) {
                    sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
                    return;
                }
                sendKieAccounting(chatId);
            }
            case CB_ADMIN_KIE_REFRESH -> {
                if (!database.isAdmin(userId)) {
                    sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
                    return;
                }
                sendKieAccounting(chatId);
            }
            case CB_BACK_MENU, CB_CANCEL -> {
                database.clearSession(userId);
                sendMainMenu(chatId, userId, withBalance("Главное меню снова перед вами 👇", userId));
            }
            default -> sendMainMenu(chatId, userId, withBalance("Меню обновлено 👇", userId));
        }
    }

    private void handleMessage(Message message) {
        if (message == null || message.getFrom() == null || message.getChat() == null) {
            return;
        }

        if (!"private".equalsIgnoreCase(message.getChat().getType())) {
            return;
        }

        User user = message.getFrom();
        long userId = user.getId();
        long chatId = message.getChatId();
        String text = message.hasText() ? message.getText().trim() : "";

        database.upsertUser(user);

        if (text.startsWith("/start") || text.startsWith("/menu") || text.startsWith("/help")) {
            if (!ensureSubscribed(userId, chatId)) {
                return;
            }
            database.clearSession(userId);
            sendMainMenu(chatId, userId, buildWelcomeWithBalance(userId));
            return;
        }

        if (!ensureSubscribed(userId, chatId)) {
            return;
        }

        SessionData session = database.getSession(userId);

        if (!database.isAdmin(userId)
                && (session.state() == ConversationState.WAIT_ADMIN_ID
                || session.state() == ConversationState.WAIT_BONUS_TARGET
                || session.state() == ConversationState.WAIT_BONUS_AMOUNT)) {
            database.clearSession(userId);
            sendMainMenu(chatId, userId, withBalance("Сессия администратора закрыта. Возвращаю вас в меню.", userId));
            return;
        }

        switch (session.state()) {
            case WAIT_TRANSFER_PHOTO -> handleTransferInput(message, userId, chatId);
            case WAIT_SKETCH_PHOTO -> {
                database.clearSession(userId);
                sendMainMenu(chatId, userId,
                        withBalance("ℹ️ Режим <b>«Эскиз»</b> отключен. Выберите другой режим 👇", userId));
            }
            case WAIT_FREE_INPUT, WAIT_FREE_PROMPT -> handleFreeInput(message, session, userId, chatId);
            case WAIT_ADMIN_ID -> handleAdminAddInput(message, userId, chatId);
            case WAIT_BONUS_TARGET -> handleBonusTargetInput(message, userId, chatId);
            case WAIT_BONUS_AMOUNT -> handleBonusAmountInput(message, session, userId, chatId);
            case IDLE -> {
                if (message.hasPhoto() || (message.hasText() && !message.getText().startsWith("/"))) {
                    sendMainMenu(chatId, userId,
                            withBalance("Чтобы не потеряться в сценариях, выберите действие в меню ниже 👇", userId));
                }
            }
        }
    }

    private void handleTransferInput(Message message, long userId, long chatId) {
        if (!message.hasPhoto()) {
            sendMessage(chatId,
                    "🖼️ Для трансфера нужно именно <b>фото</b>. Отправьте одно изображение, и я продолжу.",
                    keyboardWithCancel());
            return;
        }

        if (!consumeTokensOrNotify(chatId, userId)) {
            return;
        }

        database.clearSession(userId);
        processGenerationAsync(chatId, userId, PROMPT_TRANSFER, getLargestPhoto(message.getPhoto()).getFileId());
    }

    private void handleFreeInput(Message message, SessionData session, long userId, long chatId) {
        String pendingPhotoFileId = session.pendingPhotoFileId();

        if (message.hasPhoto()) {
            PhotoSize largest = getLargestPhoto(message.getPhoto());
            String prompt = message.getCaption();

            if (prompt != null && !prompt.trim().isEmpty()) {
                if (!consumeTokensOrNotify(chatId, userId)) {
                    return;
                }
                database.clearSession(userId);
                processGenerationAsync(chatId, userId, prompt.trim(), largest.getFileId());
                return;
            }

            database.setSession(userId, ConversationState.WAIT_FREE_PROMPT, largest.getFileId());
            sendMessage(chatId,
                    "📝 Фото получил. Теперь пришлите текстовый промпт, и я запущу генерацию.",
                    keyboardWithCancel());
            return;
        }

        if (message.hasText()) {
            String prompt = message.getText().trim();
            if (prompt.isBlank()) {
                sendMessage(chatId, "Промпт пустой. Напишите, что нужно сгенерировать ✍️", keyboardWithCancel());
                return;
            }
            if (prompt.startsWith("/")) {
                sendMessage(chatId, "Команда сейчас не ожидается. Пришлите промпт текстом 👇", keyboardWithCancel());
                return;
            }

            if (!consumeTokensOrNotify(chatId, userId)) {
                return;
            }

            database.clearSession(userId);
            processGenerationAsync(chatId, userId, prompt, pendingPhotoFileId);
            return;
        }

        sendMessage(chatId,
                "🧠 Для свободной генерации отправьте фото и/или текстовый промпт."
                        + " Без промпта запуск невозможен.",
                keyboardWithCancel());
    }

    private boolean consumeTokensOrNotify(long chatId, long userId) {
        ConsumeResult result = database.tryConsumeGeneration(userId);

        if (result.status() == ConsumeStatus.SUCCESS) {
            return true;
        }

        if (result.status() == ConsumeStatus.USER_BALANCE_LOW) {
            UserBalanceInfo balance = result.userBalance();
            sendMessage(chatId,
                    "💰 <b>Недостаточно баланса для генерации</b>\n\n"
                            + "Ваш баланс: <b>" + balance.totalTokens() + " токенов</b> ("
                            + balance.availableGenerations() + " ген.)\n"
                            + "Ежедневно активному подписчику начисляется <b>"
                            + balance.dailyGrantTokens() + " токенов</b>.\n"
                            + "Стоимость 1 генерации: <b>" + balance.tokenCostPerGeneration() + " токена</b>.",
                    backToMenuKeyboard());
            return false;
        }

        sendMessage(chatId,
                "⚠️ Не удалось проверить баланс. Попробуйте снова через пару секунд.",
                backToMenuKeyboard());
        return false;
    }

    private void handleAdminAddInput(Message message, long adminUserId, long chatId) {
        if (!database.isAdmin(adminUserId)) {
            database.clearSession(adminUserId);
            sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
            return;
        }

        if (!message.hasText()) {
            sendMessage(chatId,
                    "Не удалось распознать ID. Пришлите число вида <code>123456789</code> или @username.",
                    keyboardWithCancel());
            return;
        }

        Long targetId = resolveUserIdFromText(message.getText().trim());
        if (targetId == null) {
            sendMessage(chatId,
                    "Не удалось распознать ID. Пришлите число вида <code>123456789</code> или @username.",
                    keyboardWithCancel());
            return;
        }

        database.addAdmin(targetId);
        database.clearSession(adminUserId);
        sendMessage(chatId,
                "✅ Пользователь <code>" + targetId + "</code> назначен администратором.",
                adminBackKeyboard());
    }

    private void handleBonusTargetInput(Message message, long adminUserId, long chatId) {
        if (!database.isAdmin(adminUserId)) {
            database.clearSession(adminUserId);
            sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
            return;
        }

        if (!message.hasText()) {
            sendMessage(chatId,
                    "Пришлите ID пользователя или @username.",
                    keyboardWithCancel());
            return;
        }

        String raw = message.getText().trim();
        Long targetId = resolveUserIdFromText(raw);
        if (targetId == null) {
            sendMessage(chatId,
                    "Пользователь не найден. Используйте ID или @username (если он уже писал боту).",
                    keyboardWithCancel());
            return;
        }

        database.setSession(adminUserId, ConversationState.WAIT_BONUS_AMOUNT, String.valueOf(targetId));
        sendMessage(chatId,
                "🎟️ Пользователь: <code>" + targetId + "</code>\n"
                        + "Теперь пришлите количество <b>дополнительных генераций</b> (например <code>10</code>).",
                keyboardWithCancel());
    }

    private void handleBonusAmountInput(Message message, SessionData session, long adminUserId, long chatId) {
        if (!database.isAdmin(adminUserId)) {
            database.clearSession(adminUserId);
            sendMessage(chatId, "⛔️ Недостаточно прав.", backToMenuKeyboard());
            return;
        }

        String pendingTarget = session.pendingPhotoFileId();
        if (pendingTarget == null || !pendingTarget.matches("^-?\\d+$")) {
            database.clearSession(adminUserId);
            sendMessage(chatId,
                    "Сессия выдачи баланса повреждена. Запустите действие заново.",
                    adminBackKeyboard());
            return;
        }

        if (!message.hasText() || !message.getText().trim().matches("^\\d+$")) {
            sendMessage(chatId,
                    "Нужно положительное число генераций. Например: <code>10</code>",
                    keyboardWithCancel());
            return;
        }

        int generations = Integer.parseInt(message.getText().trim());
        if (generations < 1 || generations > 10000) {
            sendMessage(chatId,
                    "Допустимый диапазон: от <b>1</b> до <b>10000</b> генераций.",
                    keyboardWithCancel());
            return;
        }

        long targetId = Long.parseLong(pendingTarget);
        int tokenCost = database.getTokenCostPerGeneration();
        int tokens = generations * tokenCost;
        database.addUserBonusTokens(targetId, tokens);
        database.clearSession(adminUserId);

        UserBalanceInfo updatedBalance = database.getUserBalance(targetId);
        sendMessage(chatId,
                "✅ Начислено <b>" + generations + " доп. генераций</b> пользователю <code>" + targetId + "</code>.\n"
                        + "Это <b>" + tokens + " токенов</b>.\n\n"
                        + "Новый баланс пользователя: <b>" + updatedBalance.totalTokens() + " токенов</b> ("
                        + updatedBalance.availableGenerations() + " ген.).",
                adminBackKeyboard());
    }

    private void processGenerationAsync(long chatId, long userId, String prompt, String photoFileId) {
        sendMessage(chatId,
                "⏳ Запускаю генерацию. Максимальное время ожидания ответа от ИИ: <b>120 секунд</b>.",
                null);

        generationPool.submit(() -> {
            try {
                sendTyping(chatId, ActionType.UPLOADPHOTO);

                byte[] photoBytes = null;
                String mimeType = "image/jpeg";
                if (photoFileId != null && !photoFileId.isBlank()) {
                    photoBytes = downloadTelegramPhoto(photoFileId);
                }

                String effectivePrompt = buildModelPrompt(prompt);
                byte[] result = kieAiClient.generateImage(effectivePrompt, photoBytes, mimeType);
                sendImage(chatId, result,
                        "✅ Готово!\n\n"
                                + "Если хотите, можно сразу сделать еще вариант с новым промптом 👇");
                sendMainMenu(chatId, userId, withBalance("Выберите следующий режим:", userId));
            } catch (AiTimeoutException timeout) {
                sendMessage(chatId,
                        "⚠️ Не удалось получить ответ от ИИ за 120 секунд. Попробуйте снова.",
                        backToMenuKeyboard());
            } catch (Exception e) {
                log.error("Ошибка генерации для user {}", userId, e);
                sendMessage(chatId,
                        "⚠️ Не удалось завершить генерацию: <code>" + safe(e.getMessage()) + "</code>\n"
                                + "Попробуйте еще раз через меню.",
                        backToMenuKeyboard());
            }
        });
    }

    private byte[] downloadTelegramPhoto(String fileId) throws TelegramApiException {
        GetFile getFile = new GetFile();
        getFile.setFileId(fileId);

        org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);
        String path = tgFile.getFilePath();
        String url = "https://api.telegram.org/file/bot" + config.getBotToken() + "/" + path;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(40))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Не удалось скачать фото из Telegram. HTTP " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка загрузки фото из Telegram: " + e.getMessage(), e);
        }
    }

    private boolean ensureSubscribed(long userId, long chatId) {
        if (isSubscribed(userId)) {
            return true;
        }
        sendSubscriptionGate(chatId,
                "📢 Для использования бота нужна активная подписка на канал."
                        + " После подписки нажмите кнопку <b>Я подписался</b>.");
        return false;
    }

    private boolean isSubscribed(long userId) {
        try {
            GetChatMember getChatMember = new GetChatMember();
            getChatMember.setChatId(config.getRequiredChannelId());
            getChatMember.setUserId(userId);

            ChatMember member = execute(getChatMember);
            if (member == null || member.getStatus() == null) {
                return false;
            }
            String status = member.getStatus();
            return "member".equals(status)
                    || "administrator".equals(status)
                    || "creator".equals(status);
        } catch (TelegramApiException e) {
            log.warn("Проверка подписки не удалась для user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    private void sendSubscriptionGate(long chatId, String text) {
        String inviteLink = resolveInviteLink();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (inviteLink != null && !inviteLink.isBlank()) {
            rows.add(singleButtonRow(urlButton("📢 Подписаться на канал", inviteLink)));
        }
        rows.add(singleButtonRow(callbackButton("✅ Я подписался", CB_CHECK_SUB)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        String effectiveText = text;
        if (inviteLink == null || inviteLink.isBlank()) {
            effectiveText += "\n\n⚠️ Не удалось автоматически получить ссылку на канал. "
                    + "Проверьте, что у бота есть право приглашать пользователей.";
        }
        sendMessage(chatId, effectiveText, markup);
    }

    private void sendUsersList(long chatId) {
        List<UserSummary> users = database.listUsersWithTodayUsage();
        if (users.isEmpty()) {
            sendMessage(chatId, "Пользователей пока нет в базе.", adminBackKeyboard());
            return;
        }

        int dailyGrant = database.getDailySubscriberTokenGrant();
        int tokenCost = database.getTokenCostPerGeneration();

        String header = "👥 <b>Список пользователей</b>\n"
                + "Ежедневный баланс подписчика: <b>" + dailyGrant + " токенов</b>\n"
                + "Списание за генерацию: <b>" + tokenCost + " токена</b>\n\n";

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);

        for (UserSummary u : users) {
            StringBuilder entry = new StringBuilder();
            entry.append("• <code>").append(u.userId()).append("</code>");
            if (u.username() != null && !u.username().isBlank()) {
                entry.append(" @").append(u.username());
            }
            if (u.firstName() != null && !u.firstName().isBlank()) {
                entry.append(" (").append(safe(u.firstName())).append(")");
            }
            entry.append("\n");
            entry.append("  🔐 ").append(u.admin() ? "админ" : "пользователь").append("\n");
            entry.append("  🎁 бонус: ").append(u.bonusTokens()).append(" токенов (")
                    .append(u.bonusTokens() / Math.max(1, tokenCost)).append(" доп. ген.)\n");
            entry.append("  📈 сегодня: ").append(u.usedTodayGenerations()).append(" генераций\n");
            entry.append("  💰 доступно: ").append(u.totalTokens()).append(" токенов\n\n");

            if (current.length() + entry.length() > 3500) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(entry);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        for (String chunk : chunks) {
            sendMessage(chatId, chunk, null);
        }
        sendMessage(chatId, "Панель администратора:", adminBackKeyboard());
    }

    private void sendMainMenu(long chatId, long userId, String text) {
        boolean admin = database.isAdmin(userId);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(singleButtonRow(callbackButton("🖼️ Трансферный рисунок", CB_MENU_TRANSFER)));
        rows.add(singleButtonRow(callbackButton("🧠 Свободная генерация", CB_MENU_FREE)));
        if (admin) {
            rows.add(singleButtonRow(callbackButton("🛠️ Админ-панель", CB_MENU_ADMIN)));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        sendMessage(chatId, text, markup);
    }

    private void sendAdminPanel(long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(singleButtonRow(callbackButton("👥 Список пользователей", CB_ADMIN_USERS)));
        rows.add(singleButtonRow(callbackButton("➕ Добавить админа", CB_ADMIN_ADD)));
        rows.add(singleButtonRow(callbackButton("🎟️ Выдать доп. генерации", CB_ADMIN_GIVE_BALANCE)));
        rows.add(singleButtonRow(callbackButton("🏦 Учет баланса KIE", CB_ADMIN_KIE)));
        rows.add(singleButtonRow(callbackButton("⬅️ Назад в меню", CB_BACK_MENU)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        sendMessage(chatId,
                "🛠️ <b>Админ-панель</b>\n\n"
                        + "Выберите действие:",
                markup);
    }

    private void sendKieAccounting(long chatId) {
        Integer remoteCredits = kieAiClient.getRemainingCredits();
        int cost = database.getTokenCostPerGeneration();

        StringBuilder sb = new StringBuilder();
        sb.append("🏦 <b>Учет баланса KIE</b>\n\n");
        sb.append("• Баланс по API KIE: <b>").append(remoteCredits == null ? "недоступно" : remoteCredits).append("</b>\n");
        sb.append("• Списание за 1 генерацию: <b>").append(cost).append(" токена</b>\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(singleButtonRow(callbackButton("🔄 Обновить", CB_ADMIN_KIE_REFRESH)));
        rows.add(singleButtonRow(callbackButton("⬅️ В админ-панель", CB_MENU_ADMIN)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        sendMessage(chatId, sb.toString(), markup);
    }

    private InlineKeyboardMarkup keyboardWithCancel() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(singleButtonRow(callbackButton("❌ Отмена", CB_CANCEL)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup backToMenuKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(singleButtonRow(callbackButton("⬅️ В главное меню", CB_BACK_MENU)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup adminBackKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(singleButtonRow(callbackButton("⬅️ В админ-панель", CB_MENU_ADMIN)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardButton callbackButton(String text, String data) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(data);
        return button;
    }

    private InlineKeyboardButton urlButton(String text, String url) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setUrl(url);
        return button;
    }

    private List<InlineKeyboardButton> singleButtonRow(InlineKeyboardButton button) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(button);
        return row;
    }

    private void sendMessage(long chatId, String htmlText, InlineKeyboardMarkup markup) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(htmlText);
        message.setParseMode("HTML");
        message.setDisableWebPagePreview(true);
        if (markup != null) {
            message.setReplyMarkup(markup);
        }

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение", e);
        }
    }

    private void sendImage(long chatId, byte[] bytes, String caption) {
        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(String.valueOf(chatId));
        sendPhoto.setCaption(caption);
        sendPhoto.setParseMode("HTML");
        sendPhoto.setPhoto(new InputFile(new java.io.ByteArrayInputStream(bytes), "result.png"));

        try {
            execute(sendPhoto);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить фото", e);
        }
    }

    private void sendTyping(long chatId, ActionType action) {
        SendChatAction chatAction = new SendChatAction();
        chatAction.setChatId(String.valueOf(chatId));
        chatAction.setAction(action);
        try {
            execute(chatAction);
        } catch (TelegramApiException e) {
            log.debug("Не удалось отправить chat action: {}", e.getMessage());
        }
    }

    private void answerCallback(String callbackId) {
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackId);
            execute(answer);
        } catch (TelegramApiException e) {
            log.debug("Не удалось ответить на callback: {}", e.getMessage());
        }
    }

    private static PhotoSize getLargestPhoto(List<PhotoSize> photos) {
        return photos.stream()
                .max(Comparator.comparing(PhotoSize::getFileSize, Comparator.nullsFirst(Integer::compareTo)))
                .orElse(photos.get(photos.size() - 1));
    }

    private String resolveInviteLink() {
        String chatId = config.getRequiredChannelId();

        try {
            CreateChatInviteLink createLink = new CreateChatInviteLink();
            createLink.setChatId(chatId);
            createLink.setName("tattoo-bot-" + System.currentTimeMillis());
            createLink.setExpireDate((int) Instant.now().plus(Duration.ofDays(7)).getEpochSecond());

            ChatInviteLink invite = execute(createLink);
            if (invite != null && invite.getInviteLink() != null && !invite.getInviteLink().isBlank()) {
                return invite.getInviteLink();
            }
        } catch (TelegramApiException e) {
            log.warn("Не удалось создать новую invite-ссылку: {}", e.getMessage());
        }

        try {
            ExportChatInviteLink export = new ExportChatInviteLink();
            export.setChatId(chatId);
            String invite = execute(export);
            if (invite != null && !invite.isBlank()) {
                return invite;
            }
        } catch (TelegramApiException e) {
            log.warn("Не удалось получить primary invite-ссылку: {}", e.getMessage());
        }

        if (config.getRequiredChannelUrl() != null && !config.getRequiredChannelUrl().isBlank()) {
            return config.getRequiredChannelUrl();
        }
        return null;
    }

    private Long resolveUserIdFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String value = text.trim();
        if (value.matches("^-?\\d+$")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (value.startsWith("@")) {
            Optional<Long> byUsername = database.findUserIdByUsername(value);
            return byUsername.orElse(null);
        }

        return null;
    }

    private String buildWelcomeWithBalance(long userId) {
        return withBalance(WELCOME_TEXT, userId);
    }

    private String buildModelPrompt(String basePrompt) {
        String prompt = basePrompt == null ? "" : basePrompt.trim();
        if (prompt.isEmpty()) {
            prompt = "Подготовь изображение для тату-работы.";
        }
        return prompt + "\n\n" + CONTRAST_BLACK_INSTRUCTION;
    }

    private String withBalance(String text, long userId) {
        UserBalanceInfo balance = database.getUserBalance(userId);
        return text + "\n\n"
                + "💰 <b>Ваш баланс:</b> " + balance.totalTokens() + " токенов ("
                + balance.availableGenerations() + " ген.)";
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", " ")
                .trim();
    }
}
