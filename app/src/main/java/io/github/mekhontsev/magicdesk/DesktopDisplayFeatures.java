package io.github.mekhontsev.magicdesk;

/** Stable behavior exposed by one desktop display environment. */
final class DesktopDisplayFeatures {
    final boolean temporaryLaunchArea;
    final boolean rootTaskTransfer;
    final boolean phoneScreenControl;
    final boolean phoneTouchpad;

    DesktopDisplayFeatures(
            final boolean temporaryLaunchArea,
            final boolean rootTaskTransfer,
            final boolean phoneScreenControl,
            final boolean phoneTouchpad) {
        this.temporaryLaunchArea = temporaryLaunchArea;
        this.rootTaskTransfer = rootTaskTransfer;
        this.phoneScreenControl = phoneScreenControl;
        this.phoneTouchpad = phoneTouchpad;
    }
}
