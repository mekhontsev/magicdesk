package io.github.mekhontsev.magicdesk;

import android.os.Bundle;

/** Same-boot phone policy carried by one installer, never persistent startup preferences. */
record AppUpdatePowerState(String leaseId, long expiresAt, boolean blackScreen) {
    static final long MAX_LEASE_MS = 1_800_000;
    static final long RESUME_TIMEOUT_MS = 30_000;

    AppUpdatePowerState {
        if (leaseId == null || leaseId.length() > 128 || expiresAt < 0
                || leaseId.isEmpty() != (expiresAt == 0)) {
            throw new IllegalArgumentException("invalid update power state");
        }
    }

    long remaining(long now) { return Math.max(0, expiresAt - now); }
    boolean requested() { return blackScreen || !leaseId.isEmpty(); }
    long bridgeDuration(long now, long limit) {
        return blackScreen ? limit : Math.min(remaining(now), limit);
    }

    Bundle toBundle() {
        var bundle = new Bundle();
        bundle.putString("leaseId", leaseId);
        bundle.putLong("expiresAt", expiresAt);
        bundle.putBoolean("blackScreen", blackScreen);
        return bundle;
    }

    static AppUpdatePowerState fromBundle(Bundle bundle, long now) {
        if (bundle == null) throw new IllegalArgumentException("update power state required");
        var state = new AppUpdatePowerState(bundle.getString("leaseId", ""),
                bundle.getLong("expiresAt"), bundle.getBoolean("blackScreen"));
        if (state.remaining(now) > MAX_LEASE_MS) throw new IllegalArgumentException("update awake lease exceeds limit");
        return state;
    }
}
