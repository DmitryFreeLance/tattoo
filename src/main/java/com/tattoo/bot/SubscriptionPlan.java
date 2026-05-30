package com.tattoo.bot;

import java.util.Arrays;
import java.util.Optional;

public enum SubscriptionPlan {
    WEEKLY("weekly", "Недельная подписка", 7),
    MONTHLY("monthly", "Месячная подписка", 30);

    private final String code;
    private final String title;
    private final int durationDays;

    SubscriptionPlan(String code, String title, int durationDays) {
        this.code = code;
        this.title = title;
        this.durationDays = durationDays;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public int durationDays() {
        return durationDays;
    }

    public static Optional<SubscriptionPlan> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.code.equalsIgnoreCase(code))
                .findFirst();
    }
}
