package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformInputRoutingDriver;

/** Standard Android needs no firmware-specific input-routing hooks. */
final class GenericAndroidInputRoutingDriver
        implements PlatformInputRoutingDriver {
    private static final Session NO_OP_SESSION = new Session() {
        @Override
        public void refresh() {
        }

        @Override
        public void close() {
        }
    };

    @Override
    public Session open(final boolean nativeConsoleTarget) {
        return NO_OP_SESSION;
    }

    @Override
    public void verifyApi() {
    }
}
