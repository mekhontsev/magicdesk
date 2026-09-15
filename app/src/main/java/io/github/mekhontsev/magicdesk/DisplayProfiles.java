package io.github.mekhontsev.magicdesk;

import java.util.Locale;

/** Shared display preferences and creation snapshots, independent of Desktop residency. */
final class DisplayProfiles {
    private DisplayProfiles() { }

    static String key(DesktopDisplayInfo display) {
        final DesktopDisplayOutput.Kind kind = switch (display.source) {
            case "phone", "internal" -> null;
            case "wired" -> DesktopDisplayOutput.Kind.WIRED;
            case "wireless" -> DesktopDisplayOutput.Kind.WIRELESS;
            case "virtual", "overlay" -> DesktopDisplayOutput.Kind.SIMULATED;
            default -> null;
        };
        return key(kind, display.uniqueId, display.name, display.width, display.height);
    }

    static String key(DesktopDisplayOutput.Kind kind, String uniqueId, String name, int width, int height) {
        final String scope = kind == null ? "local" : kind.name().toLowerCase(Locale.ROOT);
        if (uniqueId != null && !uniqueId.trim().isEmpty()) {
            return "display:" + scope + ":" + uniqueId.trim();
        }
        final String size = width > 0 && height > 0 ? width + "x" + height : "unknown";
        final String label = name == null || name.trim().isEmpty() ? "unknown" : name.trim();
        return "display:" + scope + ":" + label + "|" + size;
    }

    static String origin(DisplayProfileStore.Profile profile) {
        return profile.originProfileKey.isEmpty() ? profile.key : profile.originProfileKey;
    }

    static CreationDefaults creationDefaults(DesktopDisplayInfo reference, VirtualDisplaySpec fallback) {
        final DisplayProfileStore.Profile profile = reference == null ? null
                : DisplayProfileStore.load(key(reference), reference.densityDpi);
        return snapshot(reference, profile, fallback);
    }

    static CreationDefaults snapshot(DesktopDisplayInfo reference, DisplayProfileStore.Profile profile,
            VirtualDisplaySpec fallback) {
        if (reference == null) {
            return new CreationDefaults(fallback.width, fallback.height, fallback.densityDpi, "");
        }
        return new CreationDefaults(reference.width, reference.height,
                profile.dpiExplicit ? profile.dpi : reference.densityDpi, origin(profile));
    }

    static DisplayProfileStore.Profile createdProfile(DesktopDisplayInfo display, VirtualDisplaySpec spec) {
        final DisplayProfileStore.Profile profile = new DisplayProfileStore.Profile(key(display));
        profile.originProfileKey = spec.originProfileKey.isEmpty() ? profile.key : spec.originProfileKey;
        profile.width = spec.width;
        profile.height = spec.height;
        profile.dpi = spec.densityDpi;
        profile.dpiExplicit = true;
        return profile;
    }

    // The reference may exceed creation limits; validate only the submitted specification.
    static final class CreationDefaults {
        final int width, height, densityDpi;
        final String originProfileKey;

        CreationDefaults(int width, int height, int densityDpi, String originProfileKey) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.originProfileKey = originProfileKey;
        }

        VirtualDisplaySpec spec(int width, int height, int densityDpi, boolean protectedContent) {
            return new VirtualDisplaySpec(width, height, densityDpi, protectedContent).withOrigin(originProfileKey);
        }
    }
}
