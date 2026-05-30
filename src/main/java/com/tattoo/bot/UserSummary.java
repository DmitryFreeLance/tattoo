package com.tattoo.bot;

public record UserSummary(
        long userId,
        String username,
        String firstName,
        boolean admin,
        int bonusTokens,
        int usedTodayGenerations,
        int dailyRemainingTokens,
        int totalTokens,
        boolean hasActiveSubscription,
        String subscriptionLabel
) {
}
