package io.github.mekhontsev.magicdesk;

/** Creation parameters; runtime display IDs are never preferences. */
final class VirtualDisplaySpec {
    static final int DEFAULT_WIDTH = 1920;
    static final int DEFAULT_HEIGHT = 1080;
    static final int DEFAULT_DPI = 160;
    final int width;
    final int height;
    final int densityDpi;
    final boolean protectedContent;
    final String originProfileKey;

    VirtualDisplaySpec(final int width, final int height, final int densityDpi) {
        this(width, height, densityDpi, false);
    }

    VirtualDisplaySpec(final int width, final int height, final int densityDpi,
            final boolean protectedContent) {
        this(width, height, densityDpi, protectedContent, "");
    }

    private VirtualDisplaySpec(final int width, final int height, final int densityDpi,
            final boolean protectedContent, final String originProfileKey) {
        if (width < 320 || height < 320 || width > 8192 || height > 8192
                || (long) width * height > 33_554_432L
                || densityDpi < 80 || densityDpi > 640) {
            throw new IllegalArgumentException("invalid virtual display size or density");
        }
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.protectedContent = protectedContent;
        this.originProfileKey = originProfileKey;
    }

    VirtualDisplaySpec withOrigin(final String profileKey) {
        return new VirtualDisplaySpec(width, height, densityDpi, protectedContent,
                java.util.Objects.requireNonNull(profileKey));
    }

    void requireOverlayCompatible() {
        if (protectedContent) {
            throw new IllegalArgumentException("protected content requires an owned virtual display, not overlay preview");
        }
        // OverlayDisplayAdapter limits preview displays more tightly than VirtualDisplay.
        if (width > 4096 || height > 4096 || densityDpi < 120) {
            throw new IllegalArgumentException("phone preview requires size <= 4096 and density >= 120");
        }
    }
}
