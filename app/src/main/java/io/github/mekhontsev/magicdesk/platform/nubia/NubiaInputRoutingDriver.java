package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;
import io.github.mekhontsev.magicdesk.PlatformInputRoutingDriver;

import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.Method;

/** Nubia display-service hooks required by its projected input panel. */
final class NubiaInputRoutingDriver implements PlatformInputRoutingDriver {
    private static final long DISPLAY_COMMAND_TIMEOUT_MILLIS = 3_000L;
    private static final int DISPLAY_COMMAND_OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final String DUMPSYS = "/system/bin/dumpsys";

    @Override
    public Session open(final boolean nativeConsoleTarget) throws Exception {
        Object displayManager = null;
        Method notePanelStatus = null;
        Binder panelToken = null;
        try {
            displayManager = getDisplayManager();
            notePanelStatus = resolvePanelStatusMethod();
            panelToken = new Binder();
            notePanelStatus.invoke(displayManager, panelToken);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println(
                    "MAGICDESK_INPUT_ROUTING_PANEL unavailable=" + error);
            displayManager = null;
            notePanelStatus = null;
            panelToken = null;
        }
        if (nativeConsoleTarget) {
            setMouseInputSourceOverride(true);
        }
        return new NubiaSession(
                displayManager,
                notePanelStatus,
                panelToken,
                nativeConsoleTarget);
    }

    @Override
    public void verifyApi() throws ReflectiveOperationException {
        resolvePanelStatusMethod();
    }

    private static Object getDisplayManager()
            throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName(
                "android.os.ServiceManager");
        final Object binder = serviceManager
                .getMethod("getService", String.class)
                .invoke(null, "display");
        final Class<?> stub = Class.forName(
                "android.hardware.display.IDisplayManager$Stub");
        return stub.getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static Method resolvePanelStatusMethod()
            throws ReflectiveOperationException {
        return Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("noteMirrorInputPanelStatus", IBinder.class);
    }

    private static void setMouseInputSourceOverride(final boolean enabled)
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(
                DUMPSYS, "display", "dmctrl", "inputSource",
                enabled ? "mouse" : "none")
                .redirectErrorStream(true)
                .start();
        final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                process,
                DISPLAY_COMMAND_TIMEOUT_MILLIS,
                DISPLAY_COMMAND_OUTPUT_LIMIT_BYTES);
        if (result.exitCode != 0 || result.truncated) {
            throw new IOException(
                    "display mirror input source failed "
                            + result.exitCode + ": " + result.output);
        }
    }

    private static final class NubiaSession implements Session {
        private final Object mDisplayManager;
        private final Method mNotePanelStatus;
        private final Binder mPanelToken;
        private final boolean mMouseInputSourceOverride;
        private boolean mClosed;

        NubiaSession(
                final Object displayManager,
                final Method notePanelStatus,
                final Binder panelToken,
                final boolean mouseInputSourceOverride) {
            mDisplayManager = displayManager;
            mNotePanelStatus = notePanelStatus;
            mPanelToken = panelToken;
            mMouseInputSourceOverride = mouseInputSourceOverride;
        }

        @Override
        public void refresh() {
            if (mClosed || mNotePanelStatus == null
                    || mDisplayManager == null || mPanelToken == null) {
                return;
            }
            try {
                mNotePanelStatus.invoke(mDisplayManager, mPanelToken);
            } catch (ReflectiveOperationException | RuntimeException error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_PANEL registration="
                                + error);
            }
        }

        @Override
        public void close() {
            if (mClosed) {
                return;
            }
            mClosed = true;
            if (mMouseInputSourceOverride) {
                try {
                    setMouseInputSourceOverride(false);
                } catch (IOException | InterruptedException error) {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    System.err.println(
                            "MAGICDESK_INPUT_ROUTING_CLEANUP mouseSource="
                                    + error);
                }
            }
            if (mNotePanelStatus != null && mDisplayManager != null) {
                try {
                    mNotePanelStatus.invoke(
                            mDisplayManager, new Object[] {null});
                } catch (ReflectiveOperationException | RuntimeException error) {
                    System.err.println(
                            "MAGICDESK_INPUT_ROUTING_CLEANUP panel=" + error);
                }
            }
        }
    }
}
