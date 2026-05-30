package com.tattoo.bot;

public record UserSummary(
        long userId,
        String username,
        String firstName,
        boolean admin,
        Integer personalDailyLimit,
        int usedToday
) {
}
