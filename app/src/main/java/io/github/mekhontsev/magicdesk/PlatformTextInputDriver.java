package io.github.mekhontsev.magicdesk;

/** Optional projected-window text-input API supplied by a firmware platform. */
public interface PlatformTextInputDriver {
    int COMMIT_TEXT = 1;
    int SEND_KEY = 2;
    int SET_COMPOSING_TEXT = 3;
    int SET_COMPOSING_REGION = 4;
    int FINISH_COMPOSING = 5;
    int DELETE_SURROUNDING = 6;

    interface Session {
        boolean dispatch(
                int action,
                String text,
                int arg1,
                int arg2,
                int arg3) throws ReflectiveOperationException;
    }

    final class RuntimeState {
        public final String state;
        public final String detail;

        public RuntimeState(final String state, final String detail) {
            this.state = state == null ? "unknown" : state;
            this.detail = detail == null ? "" : detail;
        }
    }

    boolean isAvailable();

    Session capture() throws ReflectiveOperationException;

    void verifyApi() throws ReflectiveOperationException;

    RuntimeState runtimeState();
}
