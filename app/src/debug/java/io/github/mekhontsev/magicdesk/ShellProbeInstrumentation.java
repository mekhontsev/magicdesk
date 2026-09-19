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
    private boolean mProbeBinderFd;

    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        mProbePhoneScreen = arguments != null
                && "true".equals(arguments.getString("phone_screen"));
        mProbeWaylandFd = arguments != null
            && "true".equals(arguments.getString("wayland_fd"));
        mProbeBinderFd = arguments != null
            && "true".equals(arguments.getString("binder_fd"));
        start();
    }

    @Override
    public void onStart() {
        final Thread thread = new Thread(() -> {
            final Bundle result = new Bundle();
            try {
                ShellAccess.initialize();
                awaitShellService();
                result.putString("shell_probe", ShellAccess.probeCapabilities());
                if (mProbeWaylandFd) result.putString("wayland_fd_probe", probeWaylandFd());
                if (mProbeBinderFd) result.putString("binder_fd_probe", probeBinderFd());
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

    private String probeBinderFd() throws IOException, InterruptedException {
        final var context = getTargetContext();
        final var execution = new CommandExecution(context, DesktopExecBackend.SHELL);
        if (execution.uid != ShellAccess.SHELL_UID || execution.uid == android.os.Process.myUid())
            throw new IOException("Binder FD test requires app and shell UID 2000");
        final var pair = android.os.ParcelFileDescriptor.createSocketPair();
        final var delivered = new java.util.concurrent.CompletableFuture<Void>();
        final var completed = new java.util.concurrent.CompletableFuture<Void>();
        final String action = context.getPackageName() + ".debug.FD_" + java.util.UUID.randomUUID();
        final byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        final String token = java.util.HexFormat.of().formatHex(secret);
        final var receiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context source, android.content.Intent intent) {
                if (getSentFromUid() != execution.uid || !token.equals(intent.getStringExtra("token")) || delivered.isDone()) return;
                try {
                    final var probe = IDescriptorProbe.Stub.asInterface(intent.getExtras().getBinder("probe"));
                    if (probe == null) throw new IOException("Missing probe Binder");
                    probe.deliver(pair[1]);
                    pair[1].close();
                    delivered.complete(null);
                } catch (IOException | android.os.RemoteException | RuntimeException error) {
                    delivered.completeExceptionally(error);
                }
            }
        };
        context.registerReceiver(receiver, new android.content.IntentFilter(action), android.content.Context.RECEIVER_EXPORTED);
        final String command = "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH="
                + ShellCommandLine.quote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process -Xnoimage-dex2oat / --nice-name=MagicDeskFdProbe "
                + ShellCommandLine.quote(FdClient.class.getName()) + " " + ShellCommandLine.quote(action)
                + " " + ShellCommandLine.quote(token);
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pair[0]);
             var client = execution.start(command, "", "Binder FD cross-UID probe", null,
                    (exitCode, output, error) -> {
                        if (error != null) completed.completeExceptionally(error);
                        else if (exitCode != 0) completed.completeExceptionally(new IOException("Client exit " + exitCode + ": " + output));
                        else completed.complete(null);
                    })) {
            completed.get(15, TimeUnit.SECONDS);
            delivered.get(15, TimeUnit.SECONDS);
            if (input.read() != 'W' || input.read() != -1) throw new IOException("Incorrect Binder FD bytes or retained descriptor");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IOException("Binder FD transfer failed: " + error, error);
        } finally {
            context.unregisterReceiver(receiver);
            pair[1].close();
        }
        return "passed | appUid=" + android.os.Process.myUid() + " | clientUid=" + execution.uid;
    }

    public static final class FdClient {
        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 2) throw new IllegalArgumentException("Expected action and token");
            android.os.Looper.prepareMainLooper();
            final var handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final var context = io.github.mekhontsev.magicdesk.hosted.HostedProcessContext.create("com.android.shell");
            final int hostUid = context.getPackageManager().getPackageUid(BuildConfig.APPLICATION_ID, 0);
            final var probe = new IDescriptorProbe.Stub() {
                @Override public void deliver(android.os.ParcelFileDescriptor descriptor) {
                    int status = 1;
                    try (var output = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                        if (android.os.Binder.getCallingUid() != hostUid) throw new SecurityException("Wrong FD sender UID");
                        output.write('W');
                        status = 0;
                    } catch (IOException | RuntimeException error) { error.printStackTrace(); }
                    final int exitCode = status;
                    handler.post(() -> System.exit(exitCode));
                }
            };
            final var extras = new Bundle();
            extras.putBinder("probe", probe);
            extras.putString("token", arguments[1]);
            context.sendBroadcast(new android.content.Intent(arguments[0]).setPackage(BuildConfig.APPLICATION_ID).putExtras(extras),
                    null, android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            handler.postDelayed(() -> System.exit(124), 10_000);
            android.os.Looper.loop();
        }
    }

    private String probeWaylandFd() throws IOException, InterruptedException {
        final CommandExecution execution = new CommandExecution(getTargetContext(), DesktopExecBackend.SHELL);
        if (execution.uid != ShellAccess.SHELL_UID || execution.uid == android.os.Process.myUid())
            throw new IOException("Cross-UID test requires app compositor and shell UID 2000");
        final var pair = android.os.ParcelFileDescriptor.createSocketPair();
        final var completed = new java.util.concurrent.CompletableFuture<Void>();
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pair[0]);
             var launch = new io.github.mekhontsev.magicdesk.wayland.WaylandClientLaunch(pair[1], execution.uid)) {
            final String helper = getTargetContext().getApplicationInfo().nativeLibraryDir
                    + "/libmagicdesk_wayland_client.so";
            final String script = "test \"$(id -u)\" -eq " + execution.uid + " || exit 7\n"
                    + "case \"${WAYLAND_SOCKET-}\" in ''|*[!0-9]*) exit 8;; esac\n"
                    + "eval \"printf W >&$WAYLAND_SOCKET\"";
            final String command = String.join(" ", launch.arguments(helper, "/system/bin/sh", "-c", script)
                    .stream().map(ShellCommandLine::quote).toList());
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
