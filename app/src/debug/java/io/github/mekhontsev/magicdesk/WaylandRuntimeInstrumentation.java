package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.mekhontsev.magicdesk.wayland.IWaylandServer;
import io.github.mekhontsev.magicdesk.wayland.WaylandClientLaunch;
import io.github.mekhontsev.magicdesk.wayland.WaylandServer;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-UID production runtime test, independent of Desktop and privileged service startup. */
public final class WaylandRuntimeInstrumentation extends Instrumentation {
    private String clientPath;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        clientPath = arguments == null ? null : arguments.getString("client");
        start();
    }

    @Override public void onStart() {
        new Thread(() -> {
            Bundle result = new Bundle();
            try {
                runRuntime();
                result.putString("wayland_runtime", "passed: cross-UID compositor, client FD, Android pixels, input, close");
                finish(Activity.RESULT_OK, result);
            } catch (Exception error) {
                result.putString("wayland_runtime", "failed: " + error);
                finish(Activity.RESULT_CANCELED, result);
            }
        }, "WaylandRuntimeFixture").start();
    }

    private void runRuntime() throws Exception {
        if (clientPath == null || !clientPath.startsWith("/")) throw new IOException("An absolute native fixture path is required");
        Context context = getTargetContext();
        CommandExecution execution = new CommandExecution(context, DesktopExecBackend.TERMUX);
        Handler main = new Handler(Looper.getMainLooper());
        String id = "wayland-test-" + UUID.randomUUID();
        String token = UUID.randomUUID() + ":" + UUID.randomUUID();
        String directory = execution.home + "/.cache/w-" + UUID.randomUUID();
        String prefix = new java.io.File(execution.home).getParent() + "/usr";
        String library = context.getApplicationInfo().nativeLibraryDir;
        AtomicReference<WaylandSession> session = new AtomicReference<>();
        CompletableFuture<Void> ready = new CompletableFuture<>(), pixels = new CompletableFuture<>();
        CompletableFuture<Void> destroyed = new CompletableFuture<>(), serverExit = new CompletableFuture<>(), clientExit = new CompletableFuture<>();
        CompletableFuture<Long> mapped = new CompletableFuture<>();
        AtomicReference<String> failure = new AtomicReference<>();
        WaylandSession.Listener listener = new WaylandSession.Listener() {
            @Override public void changed() {
                WaylandSession current = session.get();
                if (current == null) return;
                for (var window : current.windows()) if (window.mapped()) mapped.complete(window.id());
                if (mapped.isDone() && current.windows().isEmpty()) destroyed.complete(null);
            }
            @Override public void failed(long output, String message) {
                failure.compareAndSet(null, message);
                IOException error = new IOException(message);
                ready.completeExceptionally(error);
                mapped.completeExceptionally(error);
                pixels.completeExceptionally(error);
                destroyed.completeExceptionally(error);
            }
        };
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context source, Intent intent) {
                if (getSentFromUid() != execution.uid || !id.equals(intent.getStringExtra("session"))
                        || !token.equals(intent.getStringExtra("token"))) return;
                try {
                    var extras = intent.getExtras();
                    var server = IWaylandServer.Stub.asInterface(extras.getBinder("server"));
                    if ("attach".equals(intent.getStringExtra("phase")) && session.get() == null)
                        session.set(new WaylandSession(server, execution.uid, listener));
                    else if ("ready".equals(intent.getStringExtra("phase")) && session.get() != null) ready.complete(null);
                } catch (Exception error) { ready.completeExceptionally(error); }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(WaylandServer.ACTION), null, main, Context.RECEIVER_EXPORTED);
        String command = "set -eu\numask 077\nmkdir -p " + q(directory) + "\n"
                + "trap " + q("rm -f -- " + q(directory + "/wayland-0") + " " + q(directory + "/wayland-0.lock")
                        + "; rmdir -- " + q(directory)) + " EXIT\n"
                + "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + q(context.getApplicationInfo().sourceDir)
                + " MAGICDESK_WAYLAND_PACKAGE=" + q(context.getPackageName())
                + " MAGICDESK_WAYLAND_EXECUTOR=" + q(execution.termux.packageName)
                + " MAGICDESK_WAYLAND_SESSION=" + q(id) + " MAGICDESK_WAYLAND_TOKEN=" + q(token)
                + " MAGICDESK_WAYLAND_LIBRARY=" + q(library + "/libmagicdesk_wayland_executor.so")
                + " XDG_RUNTIME_DIR=" + q(directory) + " XKB_CONFIG_ROOT=" + q(prefix + "/share/X11/xkb")
                + " /system/bin/app_process -Xnoimage-dex2oat / --nice-name=" + id + " " + WaylandServer.class.getName();
        try (var process = execution.start(command, "", id, null, (code, output, error) -> {
            complete(serverExit, code, output, error);
            if (!ready.isDone()) ready.completeExceptionally(new IOException("Compositor exited: " + output));
        }); var images = ImageReader.newInstance(80, 60, PixelFormat.RGBA_8888, 2)) {
            ready.get(15, TimeUnit.SECONDS);
            images.setOnImageAvailableListener(reader -> {
                try (var image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    var plane = image.getPlanes()[0];
                    var buffer = plane.getBuffer();
                    int offset = 20 * plane.getRowStride() + 20 * plane.getPixelStride();
                    if ((buffer.get(offset) & 255) == 0x12 && (buffer.get(offset + 1) & 255) == 0x67
                            && (buffer.get(offset + 2) & 255) == 0xab) pixels.complete(null);
                } catch (RuntimeException error) { pixels.completeExceptionally(error); }
            }, main);
            try (var launch = new WaylandClientLaunch(context, session.get().connect().get(10, TimeUnit.SECONDS), execution.uid)) {
                String invocation = "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + q(context.getApplicationInfo().sourceDir)
                        + " " + String.join(" ", launch.arguments(execution.termux.packageName,
                                library + "/libmagicdesk_wayland_client.so", clientPath, "--client")
                                .stream().map(WaylandRuntimeInstrumentation::q).toList());
                try (var client = execution.start(invocation, "", id + "-client", null,
                        (code, output, error) -> complete(clientExit, code, output, error))) {
                    launch.transferred().toCompletableFuture().get(15, TimeUnit.SECONDS);
                    long window = mapped.get(15, TimeUnit.SECONDS);
                    try (var output = session.get().openOutput(window, 80, 60)) {
                        output.setSurface(images.getSurface(), 80, 60);
                        pixels.get(15, TimeUnit.SECONDS);
                        output.focus(true);
                        output.pointer(.25, .25);
                        output.button(0, true);
                        output.key(30, true);
                        output.focus(false);
                        session.get().closeWindow(window);
                        clientExit.get(15, TimeUnit.SECONDS);
                        destroyed.get(15, TimeUnit.SECONDS);
                    }
                }
            }
            if (failure.get() != null) throw new IOException(failure.get());
            session.get().close();
            serverExit.get(15, TimeUnit.SECONDS);
        } finally {
            context.unregisterReceiver(receiver);
            if (session.get() != null) session.get().close();
        }
    }

    private static void complete(CompletableFuture<Void> future, int code, String output, Throwable error) {
        if (error != null) future.completeExceptionally(error);
        else if (code != 0) future.completeExceptionally(new IOException("Exit " + code + ": " + output));
        else future.complete(null);
    }

    private static String q(String value) { return ShellCommandLine.quote(value); }
}
