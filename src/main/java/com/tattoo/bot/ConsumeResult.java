package com.tattoo.bot;

public record ConsumeResult(
        ConsumeStatus status,
        UserBalanceInfo userBalance
) {
}
