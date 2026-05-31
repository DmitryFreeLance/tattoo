package com.tattoo.bot;

import java.util.Arrays;
import java.util.Optional;

public enum PaymentProduct {
    SUB_WEEK("sub_w", "Недельная подписка (10 генераций в день)", 199, Kind.SUBSCRIPTION, SubscriptionPlan.WEEKLY, 0, 0),
    SUB_MONTH("sub_m", "Месячная подписка (10 генераций в день)", 699, Kind.SUBSCRIPTION, SubscriptionPlan.MONTHLY, 0, 0),
    ADD_WEEK_5("add_w5", "Увеличение лимита +5 генераций в день до конца недельной подписки", 149, Kind.DAILY_BOOST, SubscriptionPlan.WEEKLY, 5, 0),
    ADD_WEEK_10("add_w10", "Увеличение лимита +10 генераций в день до конца недельной подписки", 300, Kind.DAILY_BOOST, SubscriptionPlan.WEEKLY, 10, 0),
    ADD_MONTH_5("add_m5", "Увеличение лимита +5 генераций в день до конца месячной подписки", 640, Kind.DAILY_BOOST, SubscriptionPlan.MONTHLY, 5, 0),
    ADD_MONTH_10("add_m10", "Увеличение лимита +10 генераций в день до конца месячной подписки", 1290, Kind.DAILY_BOOST, SubscriptionPlan.MONTHLY, 10, 0),
    PACK_10("pack_10", "Разовый пакет: 10 дополнительных генераций (не сгорают)", 69, Kind.ONE_TIME_PACK, null, 0, 10),
    PACK_20("pack_20", "Разовый пакет: 20 дополнительных генераций (не сгорают)", 149, Kind.ONE_TIME_PACK, null, 0, 20);

    public enum Kind {
        SUBSCRIPTION,
        DAILY_BOOST,
        ONE_TIME_PACK
    }

    private final String code;
    private final String title;
    private final int amountRub;
    private final Kind kind;
    private final SubscriptionPlan requiredPlan;
    private final int extraGenerationsPerDay;
    private final int oneTimeGenerations;

    PaymentProduct(
            String code,
            String title,
            int amountRub,
            Kind kind,
            SubscriptionPlan requiredPlan,
            int extraGenerationsPerDay,
            int oneTimeGenerations
    ) {
        this.code = code;
        this.title = title;
        this.amountRub = amountRub;
        this.kind = kind;
        this.requiredPlan = requiredPlan;
        this.extraGenerationsPerDay = extraGenerationsPerDay;
        this.oneTimeGenerations = oneTimeGenerations;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public int amountRub() {
        return amountRub;
    }

    public Kind kind() {
        return kind;
    }

    public SubscriptionPlan requiredPlan() {
        return requiredPlan;
    }

    public int extraGenerationsPerDay() {
        return extraGenerationsPerDay;
    }

    public int oneTimeGenerations() {
        return oneTimeGenerations;
    }

    public static Optional<PaymentProduct> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.code.equalsIgnoreCase(code))
                .findFirst();
    }
}
