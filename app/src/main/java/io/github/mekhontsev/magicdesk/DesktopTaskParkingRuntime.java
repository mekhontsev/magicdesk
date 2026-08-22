package io.github.mekhontsev.magicdesk;

/** Runtime-owned parking operations that survive a closed desktop session. */
interface DesktopTaskParkingRuntime {
    interface ResultCallback {
        void onComplete(boolean success);
    }

    void park(DesktopDisplayTarget source, ResultCallback callback);

    void preserve(int displayId);

    void restoreWhenReady(DesktopDisplayTarget target);

    void onDesktopHostReady(int displayId);

    void clear();
}
