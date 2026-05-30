package com.tattoo.bot;

public record UserBalanceInfo(
        int dailyGrantTokens,
        int usedTodayGenerations,
        int dailyRemainingTokens,
        int bonusTokens,
        int totalTokens,
        int tokenCostPerGeneration
) {
    public int availableGenerations() {
        if (tokenCostPerGeneration <= 0) {
            return 0;
        }
        return Math.max(0, totalTokens / tokenCostPerGeneration);
    }
}
