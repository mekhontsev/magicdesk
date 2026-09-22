package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShellProbeInstrumentation extends Instrumentation {
    private static final long SERVICE_TIMEOUT_MILLIS = 60_000;
    private static final long OPERATION_TIMEOUT_SECONDS = 10;
    private boolean mProbePhoneScreen;
    private boolean mProbeWaylandFd;
    private DesktopExecBackend mWaylandBackend;

    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        mProbePhoneScreen = arguments != null
                && "true".equals(arguments.getString("phone_screen"));
        mProbeWaylandFd = arguments != null
            && "true".equals(arguments.getString("wayland_fd"));
        mWaylandBackend = DesktopExecBackend.parse(arguments == null ? "shell" : arguments.getString("wayland_executor", "shell"));
        start();
    }

    @Override
    public void onStart() {
        final Thread thread = new Thread(() -> {
            final Bundle result = new Bundle();
            try {
                if (mWaylandBackend != DesktopExecBackend.TERMUX || !mProbeWaylandFd) {
                    ShellAccess.initialize();
                    awaitShellService();
                }
                if (mProbeWaylandFd) result.putString("wayland_fd_probe", probeWaylandFd());
                else {
                    result.putString("shell_probe", ShellAccess.probeCapabilities());
                }
                if (mProbePhoneScreen) {
                    result.putString(
                            "phone_screen_probe",
                            probePhoneScreen());
                }
                finish(Activity.RESULT_OK, result);
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                final String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                result.putString("shell_probe", "probe=failed | " + message);
                finish(Activity.RESULT_CANCELED, result);
            }
        }, "MagicDeskShellProbe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void awaitShellService() throws InterruptedException, IOException {
        final Object lock = new Object();
        final ShellAccess.StateListener listener = snapshot -> {
            synchronized (lock) { lock.notifyAll(); }
        };
        ShellAccess.addStateListener(listener);
        try {
            synchronized (lock) {
                final long deadline = android.os.SystemClock.uptimeMillis() + SERVICE_TIMEOUT_MILLIS;
                while (!ShellAccess.isReady()) {
                    final long remaining = deadline - android.os.SystemClock.uptimeMillis();
                    if (remaining <= 0) throw new IOException(ShellAccess.currentSnapshot().error);
                    EventDrivenWaits.await(lock, EventDrivenWaits.Reason.SERVICE_BINDING, remaining);
                }
            }
        } finally {
            ShellAccess.removeStateListener(listener);
        }
    }

    private String probeWaylandFd() throws IOException, InterruptedException {
        final CommandExecution execution = new CommandExecution(getTargetContext(), mWaylandBackend);
        if (execution.uid == android.os.Process.myUid()) throw new IOException("Cross-UID test requires distinct UIDs");
        final var pair = android.os.ParcelFileDescriptor.createSocketPair();
        final var completed = new java.util.concurrent.CompletableFuture<Void>();
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pair[0]);
             var launch = new io.github.mekhontsev.magicdesk.wayland.WaylandClientLaunch(getTargetContext(), pair[1], execution.uid)) {
            final String helper = getTargetContext().getApplicationInfo().nativeLibraryDir
                    + "/libmagicdesk_wayland_client.so";
            final String command = "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH="
                    + ShellCommandLine.quote(getTargetContext().getApplicationInfo().sourceDir) + " "
                    + String.join(" ", launch.arguments(execution.termux == null ? "com.android.shell" : execution.termux.packageName,
                            helper, "/system/bin/app_process", "-Xnoimage-dex2oat", "/", FdSink.class.getName(),
                            Integer.toString(execution.uid)).stream().map(ShellCommandLine::quote).toList());
            try (var client = execution.start(command, "", "Wayland FD cross-UID probe", null,
                    (exitCode, output, error) -> {
                        if (error != null) completed.completeExceptionally(error);
                        else if (exitCode != 0) completed.completeExceptionally(
                                new IOException("Client exit " + exitCode + ": " + output));
                        else completed.complete(null);
                    })) {
                completed.get(15, TimeUnit.SECONDS);
                launch.transferred().toCompletableFuture().get(15, TimeUnit.SECONDS);
                if (pair[1].getFileDescriptor().valid()) throw new IOException("Completion preceded FD cleanup");
                if (input.read() != 'W' || input.read() != -1) throw new IOException("Incorrect client bytes or retained FD");
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                throw new IOException("Cross-UID FD transfer failed: " + error, error);
            }
        } finally { pair[1].close(); }
        return "passed | appUid=" + android.os.Process.myUid() + " | clientUid=" + execution.uid;
    }

    public static final class FdSink {
        public static void main(String[] arguments) throws IOException {
            if (arguments.length != 1 || android.os.Process.myUid() != Integer.parseInt(arguments[0]))
                throw new SecurityException("Unexpected client identity after exec");
            int descriptor = Integer.parseInt(System.getenv("WAYLAND_SOCKET"));
            try (var output = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(
                    android.os.ParcelFileDescriptor.adoptFd(descriptor))) {
                output.write('W');
            }
        }
    }

    private static String probePhoneScreen()
            throws IOException, InterruptedException {
        final int serviceUid = ShellAccess.connectAndGetUid();
        if (serviceUid != ShellAccess.SHELL_UID) {
            throw new IOException(
                    "The service must run as shell UID 2000; found UID "
                            + serviceUid);
        }

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        DesktopOperations.setPhoneScreenOff(false, value -> {
            success.set(value);
            completed.countDown();
        });
        if (!completed.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("phone-screen safe probe timed out");
        }
        if (!success.get()) {
            throw new IOException("phone-screen safe probe failed");
        }
        return "granted | uid=" + serviceUid;
    }
}
