package io.github.mekhontsev.magicdesk;

/** One resolved Android framework profile shared by runtime and diagnostics. */
final class FrameworkRuntime {
    private final FrameworkWindowingCompat mWindowingCompat;
    private final FrameworkWindowingApi mWindowingApi;

    private FrameworkRuntime() {
        mWindowingCompat = FrameworkWindowingCompat.current();
        mWindowingApi = FrameworkWindowingApi.current();
    }

    static FrameworkRuntime current() {
        return CurrentHolder.INSTANCE;
    }

    FrameworkWindowingCompat windowingCompat() {
        return mWindowingCompat;
    }

    FrameworkWindowingApi windowing() {
        return mWindowingApi;
    }

    FrameworkWindowingCompat.Capabilities capabilities() {
        return mWindowingCompat.capabilities();
    }

    String profileDetail() {
        return FrameworkWindowingCompat.overrideDetail();
    }

    String diagnosticDetail() {
        final FrameworkWindowingCompat.Capabilities capabilities =
                mWindowingCompat.capabilities();
        final FrameworkWindowingCompat.TaskObservationCapabilities tasks =
                capabilities.taskObservation;
        return "profile=" + capabilities.profile
                + ", wct=" + (mWindowingApi.available()
                        ? "available" : "unavailable")
                + ", caption=" + capabilities.captionStrategy()
                + ", taskSource=typed-binder-root-hierarchy+listener"
                + ", taskObservation=" + tasks.strategy
                + "/" + tasks.fallbackIntervalMillis + "ms"
                + "/limit-" + tasks.taskLimit
                + ", immersive=" + tasks.immersiveRequest.label
                + ", bounds=" + tasks.windowGeometry.label;
    }

    private static final class CurrentHolder {
        static final FrameworkRuntime INSTANCE = new FrameworkRuntime();
    }
}
