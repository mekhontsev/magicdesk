package io.github.mekhontsev.magicdesk;

/** Optional read-only cursor observation supplied by a firmware platform. */
public interface PlatformPointerDriver {
    boolean isAvailable();

    /** Unknown display identity must remain -1; unavailable coordinates remain null. */
    default PointerPosition observePosition() {
        return null;
    }
}
