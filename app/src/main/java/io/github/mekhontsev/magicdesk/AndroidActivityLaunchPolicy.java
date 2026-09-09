package io.github.mekhontsev.magicdesk;

import android.content.Intent;

/** Selects caller identity independently from desktop task placement. */
final class AndroidActivityLaunchPolicy {
    enum Delivery {
        SHELL_INTENT,
        APP_PENDING_INTENT,
        ACTIVITY_RESULT_RELAY
    }

    final Delivery delivery;
    final boolean selectionSurface;

    private AndroidActivityLaunchPolicy(
            final Delivery delivery,
            final boolean selectionSurface) {
        this.delivery = delivery;
        this.selectionSurface = selectionSurface;
    }

    static AndroidActivityLaunchPolicy select(
            final boolean chooser,
            final boolean expectResult,
            final boolean requiresResolver,
            final boolean requiresAppIdentity,
            final int intentFlags) {
        final boolean selectionSurface = chooser || requiresResolver;
        if (expectResult) {
            return new AndroidActivityLaunchPolicy(
                    Delivery.ACTIVITY_RESULT_RELAY,
                    selectionSurface);
        }
        // URI grants are checked against the launch caller, not just the receiving Activity.
        final boolean grantsContent = (intentFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) != 0;
        if (selectionSurface || requiresAppIdentity || grantsContent) {
            return new AndroidActivityLaunchPolicy(
                    Delivery.APP_PENDING_INTENT,
                    selectionSurface);
        }
        return new AndroidActivityLaunchPolicy(
                Delivery.SHELL_INTENT,
                false);
    }

    boolean usesApplicationIdentity() {
        return delivery != Delivery.SHELL_INTENT;
    }

    boolean usesResultRelay() {
        return delivery == Delivery.ACTIVITY_RESULT_RELAY;
    }

    String identityName() {
        return usesApplicationIdentity() ? "application" : "shell";
    }

    String deliveryName() {
        if (delivery == Delivery.APP_PENDING_INTENT) {
            return "pending-intent";
        }
        if (delivery == Delivery.ACTIVITY_RESULT_RELAY) {
            return "activity-result-relay";
        }
        return "direct-intent";
    }
}
