package com.tattoo.bot;

public record PaymentRequest(
        long id,
        long userId,
        PaymentProduct product,
        int amountRub,
        String payerName,
        PaymentStatus status,
        long createdAtEpochSec,
        Long reviewedByAdminId,
        Long reviewedAtEpochSec,
        String adminComment
) {
}
