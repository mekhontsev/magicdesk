package io.github.mekhontsev.magicdesk;

/** Immutable binding of a task workspace to its selected output. */
final class DesktopDisplayTarget {
    final int workspaceDisplayId;
    final DesktopDisplayOutput output;

    DesktopDisplayTarget(final int workspaceDisplayId, final DesktopDisplayOutput output) {
        if (workspaceDisplayId < 0 || output == null) {
            throw new IllegalArgumentException("workspace and output are required");
        }
        this.workspaceDisplayId = workspaceDisplayId;
        this.output = output;
    }

    static DesktopDisplayTarget phone() {
        return direct(DesktopDisplayOutput.Kind.PHONE, 0,
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);
    }

    static DesktopDisplayTarget wired(final int displayId) {
        return direct(DesktopDisplayOutput.Kind.WIRED, displayId,
                DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
    }

    static DesktopDisplayTarget wireless(final int displayId) {
        return direct(DesktopDisplayOutput.Kind.WIRELESS, displayId,
                DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
    }

    static DesktopDisplayTarget simulated(final int displayId) {
        return direct(DesktopDisplayOutput.Kind.SIMULATED, displayId,
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);
    }

    private static DesktopDisplayTarget direct(final DesktopDisplayOutput.Kind kind,
            final int displayId, final DesktopDisplayOutput.ActivationSource source) {
        return new DesktopDisplayTarget(displayId,
                new DesktopDisplayOutput(kind, displayId, "", source));
    }

    static DesktopDisplayTarget restore(final DesktopDisplayOutput.Kind kind,
            final int workspaceDisplayId, final int outputDisplayId,
            final String profileKey, final DesktopDisplayOutput.ActivationSource source) {
        return new DesktopDisplayTarget(workspaceDisplayId,
                new DesktopDisplayOutput(kind, outputDisplayId, profileKey, source));
    }

    DesktopDisplayTarget withProfile(final String profileKey) {
        return new DesktopDisplayTarget(workspaceDisplayId, output.withProfile(profileKey));
    }

    DesktopDisplayTarget withActivationSource(final DesktopDisplayOutput.ActivationSource source) {
        return new DesktopDisplayTarget(workspaceDisplayId, output.withActivationSource(source));
    }

    boolean isPhoneWorkspace() {
        return workspaceDisplayId == 0;
    }

    boolean ownsWorkspace(final int displayId) {
        return workspaceDisplayId == displayId;
    }

    boolean usesOutput(final int displayId) {
        return output.displayId == displayId;
    }

    boolean sameBinding(final DesktopDisplayTarget other) {
        return other != null && workspaceDisplayId == other.workspaceDisplayId
                && output.sameEndpoint(other.output);
    }

    android.os.Bundle toBundle() {
        final android.os.Bundle bundle = new android.os.Bundle();
        bundle.putInt("workspace", workspaceDisplayId);
        bundle.putInt("output", output.displayId);
        bundle.putString("kind", output.kind.name());
        bundle.putString("profile", output.profileKey);
        bundle.putString("activation", output.activationSource.name());
        return bundle;
    }

    static DesktopDisplayTarget fromBundle(final android.os.Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        try {
            return restore(DesktopDisplayOutput.Kind.valueOf(bundle.getString("kind", "")),
                    bundle.getInt("workspace", -1), bundle.getInt("output", -1),
                    bundle.getString("profile", ""),
                    DesktopDisplayOutput.ActivationSource.valueOf(bundle.getString("activation", "")));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    void requireDirectBinding() {
        // The production presenter still uses Android's normal display binding.
        // Representing another binding must not silently enable an unverified backend.
        if (workspaceDisplayId != output.displayId) {
            throw new IllegalArgumentException("only direct desktop output is implemented");
        }
    }
}
