package com.tattoo.bot;

public record PaymentApprovalResult(
        boolean approved,
        String message,
        PaymentRequest request,
        int grantedTokens
) {
    public static PaymentApprovalResult approved(PaymentRequest request, int grantedTokens, String message) {
        return new PaymentApprovalResult(true, message, request, grantedTokens);
    }

    public static PaymentApprovalResult failed(String message) {
        return new PaymentApprovalResult(false, message, null, 0);
    }
}
