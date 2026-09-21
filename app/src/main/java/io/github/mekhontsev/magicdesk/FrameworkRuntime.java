package io.github.mekhontsev.magicdesk;

/** One resolved Android framework profile shared by runtime and diagnostics. */
final class FrameworkRuntime {
    private FrameworkDisplayWindowingApi mDisplayWindowingApi;
    private FrameworkDisplayBrightnessApi mDisplayBrightnessApi;
    private FrameworkInputRoutingApi mInputRoutingApi;
    private FrameworkInputInjectionApi mInputInjectionApi;
    private FrameworkInputMethodApi mInputMethodApi;
    private FrameworkVirtualDisplayApi mVirtualDisplayApi;
    private FrameworkDisplayMirrorApi mDisplayMirrorApi;
    private final FrameworkDisplayCaptureApi mDisplayCaptureApi = new FrameworkDisplayCaptureApi();
    private final FrameworkTaskCaptureApi mTaskCaptureApi = new FrameworkTaskCaptureApi();

    private FrameworkRuntime() {
    }

    static FrameworkRuntime current() {
        return CurrentHolder.INSTANCE;
    }

    FrameworkWindowingCompat windowingCompat() {
        return FrameworkWindowingCompat.current();
    }

    FrameworkWindowingApi windowing() {
        return FrameworkWindowingApi.current();
    }

    FrameworkDisplayCaptureApi displayCapture() {
        return mDisplayCaptureApi;
    }

    FrameworkTaskCaptureApi taskCapture() { return mTaskCaptureApi; }

    synchronized FrameworkDisplayWindowingApi displayWindowing()
            throws ReflectiveOperationException {
        if (mDisplayWindowingApi == null) {
            mDisplayWindowingApi = new FrameworkDisplayWindowingApi();
        }
        return mDisplayWindowingApi;
    }

    FrameworkWindowingCompat.Capabilities capabilities() {
        return windowingCompat().capabilities();
    }

    synchronized FrameworkDisplayBrightnessApi displayBrightness() throws ReflectiveOperationException {
        if (mDisplayBrightnessApi == null) mDisplayBrightnessApi = new FrameworkDisplayBrightnessApi();
        return mDisplayBrightnessApi;
    }

    synchronized FrameworkVirtualDisplayApi virtualDisplays()
            throws ReflectiveOperationException {
        if (mVirtualDisplayApi == null) {
            mVirtualDisplayApi = new FrameworkVirtualDisplayApi();
        }
        return mVirtualDisplayApi;
    }

    synchronized FrameworkInputRoutingApi inputRouting() throws ReflectiveOperationException {
        if (mInputRoutingApi == null) {
            mInputRoutingApi = new FrameworkInputRoutingApi();
        }
        return mInputRoutingApi;
    }

    synchronized FrameworkInputInjectionApi inputInjection() throws ReflectiveOperationException {
        if (mInputInjectionApi == null) { mInputInjectionApi = new FrameworkInputInjectionApi(); }
        return mInputInjectionApi;
    }

    synchronized FrameworkInputMethodApi inputMethod() throws ReflectiveOperationException {
        if (mInputMethodApi == null) { mInputMethodApi = new FrameworkInputMethodApi(); }
        return mInputMethodApi;
    }

    synchronized FrameworkDisplayMirrorApi displayMirror() throws ReflectiveOperationException {
        if (mDisplayMirrorApi == null) mDisplayMirrorApi = new FrameworkDisplayMirrorApi();
        return mDisplayMirrorApi;
    }

    String diagnosticDetail() {
        final FrameworkWindowingCompat.Capabilities capabilities =
                windowingCompat().capabilities();
        final FrameworkWindowingCompat.TaskObservationCapabilities tasks =
                capabilities.taskObservation;
        return "profile=" + capabilities.profile
                + ", wct=" + (windowing().available()
                        ? "available" : "unavailable")
                + ", taskDensity="
                + (windowing().supportsDensityOverride()
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
