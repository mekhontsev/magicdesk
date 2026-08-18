package io.github.mekhontsev.magicdesk;

/** Stable behavior exposed by one desktop display environment. */
final class DesktopDisplayFeatures {
    final DesktopTaskAreaPolicy taskAreaPolicy;
    final boolean rootTaskTransfer;
    final boolean phoneScreenControl;
    final boolean phoneTouchpad;

    DesktopDisplayFeatures(
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final boolean rootTaskTransfer,
            final boolean phoneScreenControl,
            final boolean phoneTouchpad) {
        if (taskAreaPolicy == null) {
            throw new IllegalArgumentException("missing task area policy");
        }
        this.taskAreaPolicy = taskAreaPolicy;
        this.rootTaskTransfer = rootTaskTransfer;
        this.phoneScreenControl = phoneScreenControl;
        this.phoneTouchpad = phoneTouchpad;
    }
}
