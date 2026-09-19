package io.github.mekhontsev.magicdesk.wayland;

import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ClientLaunchTestMain {
    public static void main(String[] arguments) {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected helper and native fixture paths");
        Looper.prepareMainLooper();
        new Thread(() -> {
            try {
                transfer(arguments[0], arguments[1], Process.myUid(), true);
                transfer(arguments[0], arguments[1], Process.myUid() + 1, false);
                expire();
                for (Thread worker : Thread.getAllStackTraces().keySet()) {
                    if (!worker.getName().equals("WaylandClientTransfer")) continue;
                    worker.join(2_000);
                    require(!worker.isAlive(), "FD handoff worker survived cleanup");
                }
                System.err.println("PASS: Android FD transfer, UID rejection, deadline, worker cleanup");
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(1);
            }
        }, "WaylandClientFixture").start();
        Looper.loop();
    }

    private static void transfer(String helper, String fixture, int expectedUid, boolean success) throws Exception {
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        java.lang.Process child = null;
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(pair[0]);
                var launch = new WaylandClientLaunch(pair[1], expectedUid)) {
            child = new ProcessBuilder(launch.arguments(helper, fixture, "--verify")).inheritIO().start();
            try {
                launch.transferred().handle((result, error) -> {
                    if (pair[1].getFileDescriptor().valid())
                        throw new AssertionError("Transfer callback ran before descriptor cleanup");
                    if (error != null) throw new java.util.concurrent.CompletionException(error);
                    return result;
                }).toCompletableFuture().get(15, TimeUnit.SECONDS);
                require(success, "Unauthorized client received descriptor");
            } catch (ExecutionException error) {
                require(!success && cause(error) instanceof SecurityException, "Unexpected handoff failure: " + error);
            }
            require(child.waitFor(15, TimeUnit.SECONDS), "Client did not exit");
            require(child.exitValue() == (success ? 0 : 1), "Unexpected client exit");
            require(input.read() == (success ? 'W' : -1), "Incorrect inherited FD bytes");
            require(input.read() == -1, "Client socket retained after exit");
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            pair[1].close();
        }
    }

    private static void expire() throws Exception {
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(pair[0]);
                var launch = new WaylandClientLaunch(pair[1], Process.myUid())) {
            try {
                launch.transferred().handle((result, error) -> {
                    if (pair[1].getFileDescriptor().valid())
                        throw new AssertionError("Deadline callback ran before descriptor cleanup");
                    if (error != null) throw new java.util.concurrent.CompletionException(error);
                    return result;
                }).toCompletableFuture().get(15, TimeUnit.SECONDS);
                throw new AssertionError("Unused channel did not expire");
            } catch (ExecutionException error) {
                require(cause(error) instanceof TimeoutException, "Wrong deadline failure");
            }
            launch.close();
            require(input.read() == -1, "Expired channel retained its descriptor");
        } finally { pair[1].close(); }
    }

    private static Throwable cause(ExecutionException error) {
        Throwable cause = error.getCause();
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null)
            cause = cause.getCause();
        return cause;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}