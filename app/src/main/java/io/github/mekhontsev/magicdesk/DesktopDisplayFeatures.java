package io.github.mekhontsev.magicdesk;

/** Stable behavior exposed by one desktop display environment. */
final class DesktopDisplayFeatures {
    final boolean temporaryLaunchArea;
    final boolean phoneScreenControl;
    final boolean phoneTouchpad;

    DesktopDisplayFeatures(
            final boolean temporaryLaunchArea,
            final boolean phoneScreenControl,
            final boolean phoneTouchpad) {
        this.temporaryLaunchArea = temporaryLaunchArea;
        this.phoneScreenControl = phoneScreenControl;
        this.phoneTouchpad = phoneTouchpad;
    }
}
