package com.tattoo.bot;

public record UserSubscription(
        long userId,
        SubscriptionPlan plan,
        long startedAtEpochSec,
        long endsAtEpochSec
) {
    public boolean isActiveAt(long epochSec) {
        return epochSec < endsAtEpochSec;
    }

    public long remainingSeconds(long epochSec) {
        return Math.max(0L, endsAtEpochSec - epochSec);
    }
}
