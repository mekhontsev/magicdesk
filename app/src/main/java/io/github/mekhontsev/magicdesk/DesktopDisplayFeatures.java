package io.github.mekhontsev.magicdesk;

/** Stable behavior exposed by one desktop display environment. */
final class DesktopDisplayFeatures {
    final boolean phoneScreenControl;
    final boolean phoneTouchpad;

    DesktopDisplayFeatures(
            final boolean phoneScreenControl,
            final boolean phoneTouchpad) {
        this.phoneScreenControl = phoneScreenControl;
        this.phoneTouchpad = phoneTouchpad;
    }
}
