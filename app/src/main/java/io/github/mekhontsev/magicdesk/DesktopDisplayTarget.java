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
        return builtIn(0);
    }

    static DesktopDisplayTarget builtIn(final int displayId) {
        return direct(DesktopDisplayOutput.Kind.BUILT_IN, displayId,
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

    boolean isDefaultWorkspace() {
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

    org.json.JSONObject toJson() throws org.json.JSONException {
        return new org.json.JSONObject().put("workspaceDisplayId", workspaceDisplayId)
                .put("output", new org.json.JSONObject().put("displayId", output.displayId)
                        .put("kind", output.kind.name()).put("profileKey", output.profileKey)
                        .put("activationSource", output.activationSource.name()));
    }

    static DesktopDisplayTarget fromJson(final org.json.JSONObject value) throws org.json.JSONException {
        final org.json.JSONObject output = value.getJSONObject("output");
        return restore(DesktopDisplayOutput.Kind.valueOf(output.getString("kind")),
                value.getInt("workspaceDisplayId"), output.getInt("displayId"),
                output.getString("profileKey"),
                DesktopDisplayOutput.ActivationSource.valueOf(output.getString("activationSource")));
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

    void requireSupportedBinding() {
        // The production presenter still uses Android's normal display binding.
        // Representing another binding must not silently enable an unverified backend.
        if (workspaceDisplayId != output.displayId) {
            throw new IllegalArgumentException("only direct desktop output is implemented");
        }
        if (output.isBuiltIn() && !isDefaultWorkspace()) {
            throw new IllegalArgumentException("secondary built-in desktop hosting is not implemented");
        }
    }
}
