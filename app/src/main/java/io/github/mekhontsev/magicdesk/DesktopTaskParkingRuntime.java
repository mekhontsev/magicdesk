package io.github.mekhontsev.magicdesk;

/** Runtime-owned parking operations that survive a closed desktop session. */
interface DesktopTaskParkingRuntime {
    interface ResultCallback {
        void onComplete(boolean success);
    }

    interface ReleasePreparation {
        void prepare() throws java.io.IOException;
    }

    void park(DesktopDisplayTarget source, boolean remember,
            ReleasePreparation preparation, ResultCallback callback);

    void preserve(int displayId);

    void onDesktopHostReady(int displayId);

    void clear();
}
