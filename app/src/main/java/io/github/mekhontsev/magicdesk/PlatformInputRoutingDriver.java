package io.github.mekhontsev.magicdesk;

/** Firmware hooks layered on top of Android input-device display associations. */
public interface PlatformInputRoutingDriver {
    interface Session extends AutoCloseable {
        void refresh();

        @Override
        void close();
    }

    Session open(boolean nativeConsoleTarget) throws Exception;

    void verifyApi() throws ReflectiveOperationException;
}
