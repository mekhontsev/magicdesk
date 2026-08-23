package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Immutable explanation of the composed platform selected at process start. */
public final class PlatformSelection {
    public static final class Provider {
        public final String id;
        public final String evidence;

        Provider(final String id, final String evidence) {
            this.id = id;
            this.evidence = evidence;
        }
    }

    private final String mBaselineId;
    private final String mExtensionId;
    private final String mExtensionEvidence;
    private final Map<PlatformComponent, Provider> mProviders;

    private PlatformSelection(
            final String baselineId,
            final String extensionId,
            final String extensionEvidence,
            final Map<PlatformComponent, Provider> providers) {
        mBaselineId = baselineId;
        mExtensionId = extensionId;
        mExtensionEvidence = extensionEvidence;
        mProviders = Collections.unmodifiableMap(
                new EnumMap<>(providers));
    }

    public static Builder baseline(final String baselineId) {
        return new Builder(baselineId);
    }

    public String baselineId() {
        return mBaselineId;
    }

    public String extensionId() {
        return mExtensionId;
    }

    public String extensionEvidence() {
        return mExtensionEvidence;
    }

    public Provider provider(final PlatformComponent component) {
        return mProviders.get(component);
    }

    public Map<PlatformComponent, Provider> providers() {
        return mProviders;
    }

    public String summary() {
        if (mExtensionId.isEmpty()) {
            return "baseline=" + mBaselineId + "; no firmware extension";
        }
        return "baseline=" + mBaselineId + "; extension=" + mExtensionId
                + "; evidence=" + mExtensionEvidence;
    }

    public static final class Builder {
        private final String mBaselineId;
        private String mExtensionId = "";
        private String mExtensionEvidence = "";
        private final EnumMap<PlatformComponent, Provider> mProviders =
                new EnumMap<>(PlatformComponent.class);

        Builder(final String baselineId) {
            if (baselineId == null || baselineId.isEmpty()) {
                throw new IllegalArgumentException("baseline id is required");
            }
            mBaselineId = baselineId;
            for (final PlatformComponent component
                    : PlatformComponent.values()) {
                mProviders.put(
                        component,
                        new Provider(baselineId, "standard Android baseline"));
            }
        }

        public Builder extension(
                final String extensionId,
                final String evidence) {
            if (extensionId == null || extensionId.isEmpty()) {
                throw new IllegalArgumentException("extension id is required");
            }
            mExtensionId = extensionId;
            mExtensionEvidence = evidence == null ? "" : evidence;
            return this;
        }

        public Builder provider(
                final PlatformComponent component,
                final String providerId,
                final String evidence) {
            if (component == null || providerId == null
                    || providerId.isEmpty()) {
                throw new IllegalArgumentException(
                        "component provider is required");
            }
            mProviders.put(
                    component,
                    new Provider(
                            providerId,
                            evidence == null ? "" : evidence));
            return this;
        }

        public PlatformSelection build() {
            return new PlatformSelection(
                    mBaselineId,
                    mExtensionId,
                    mExtensionEvidence,
                    mProviders);
        }
    }
}
