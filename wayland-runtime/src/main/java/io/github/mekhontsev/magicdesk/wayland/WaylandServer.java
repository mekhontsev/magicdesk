package io.github.mekhontsev.magicdesk.wayland;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import android.util.LongSparseArray;
import io.github.mekhontsev.magicdesk.hosted.HostedProcessContext;
import io.github.mekhontsev.magicdesk.hosted.HostedServerLifecycle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.LongConsumer;

public final class WaylandServer extends IWaylandServer.Stub {
    public static final String ACTION = "io.github.mekhontsev.magicdesk.wayland.SERVER_READY";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final HostedServerLifecycle lifecycle;
    private final String hostPackage, session, token;
    private final LongSparseArray<Output> outputs = new LongSparseArray<>();
    private final LongSparseArray<Output> nativeOutputs = new LongSparseArray<>();
    private final IBinder.DeathRecipient ownerDied = this::requestStop;
    private final Runnable deadline = this::expireAdmission;
    private IWaylandEvents owner;
    private long handle, lastOutput, lastClient;
    private ParcelFileDescriptor eventDescriptor;

    private static final class Output {
        final long id, window, handle;
        final FrameCredit credit = new FrameCredit();
        int width, height;
        Output(long id, long window, long handle, int width, int height) {
            this.id = id; this.window = window; this.handle = handle; this.width = width; this.height = height;
        }
    }

    private WaylandServer() throws Exception {
        hostPackage = required("MAGICDESK_WAYLAND_PACKAGE");
        session = required("MAGICDESK_WAYLAND_SESSION");
        token = required("MAGICDESK_WAYLAND_TOKEN");
        context = HostedProcessContext.create(required("MAGICDESK_WAYLAND_EXECUTOR"));
        lifecycle = new HostedServerLifecycle(context.getPackageManager().getPackageUid(hostPackage, 0));
        var directory = Os.lstat(required("XDG_RUNTIME_DIR"));
        if (!OsConstants.S_ISDIR(directory.st_mode) || directory.st_uid != Process.myUid()
                || (directory.st_mode & 0777) != 0700)
            throw new SecurityException("Wayland runtime directory must be private to the executor");
        System.load(required("MAGICDESK_WAYLAND_LIBRARY"));
    }

    public static void main(String[] arguments) throws Exception {
        Looper.prepareMainLooper();
        WaylandServer server = new WaylandServer();
        server.handler.postDelayed(server.deadline, 60_000);
        server.announce("attach", null);
        Looper.loop();
    }

    private void expireAdmission() { if (!lifecycle.retained()) System.exit(1); }

    @Override public synchronized void retain(IWaylandEvents next) throws RemoteException {
        lifecycle.checkCaller(Binder.getCallingUid());
        Objects.requireNonNull(next);
        if (owner != null) {
            if (!owner.asBinder().equals(next.asBinder())) throw new SecurityException("Wayland server already owned");
            return;
        }
        next.asBinder().linkToDeath(ownerDied, 0);
        owner = next;
        lifecycle.retain();
        handler.removeCallbacks(deadline);
        handler.post(this::start);
    }

    private void start() {
        if (!lifecycle.beginStart()) return;
        try {
            handle = nativeStart();
            if (handle == 0) throw new IllegalStateException("Cannot start Wayland compositor");
            eventDescriptor = ParcelFileDescriptor.fromFd(nativeEventFd(handle));
            Looper.myQueue().addOnFileDescriptorEventListener(eventDescriptor.getFileDescriptor(),
                    MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT, (descriptor, events) -> {
                        if ((events & MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR) != 0
                                || nativeDispatch(handle) < 0) {
                            onError("Wayland event loop failed");
                            return 0;
                        }
                        return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
                    });
            if (lifecycle.ready()) announce("ready", nativeSocket(handle));
            else shutdown();
        } catch (IOException | RuntimeException error) {
            onError(error.getMessage());
            shutdown();
        }
    }

    private void command(Runnable action) {
        lifecycle.checkReady(Binder.getCallingUid());
        handler.post(() -> {
            if (handle == 0) return;
            action.run();
            if (nativeDispatch(handle) < 0) onError("Wayland command dispatch failed");
        });
    }

    private void output(long id, LongConsumer action) {
        command(() -> {
            Output output = outputs.get(id);
            if (output != null) action.accept(output.handle);
        });
    }

    private boolean dimensions(long id, int width, int height) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096) return false;
        long pixels = (long)width * height;
        for (int index = 0; index < outputs.size(); ++index) {
            Output output = outputs.valueAt(index);
            if (output.id != id) pixels += (long)output.width * output.height;
        }
        return pixels <= 16_777_216;
    }

    @Override public void openOutput(long id, long window, int width, int height) {
        command(() -> {
            if (id <= lastOutput || outputs.size() >= 16 || !dimensions(id, width, height)) {
                failed(id, "Invalid or exhausted Wayland output lease");
                return;
            }
            lastOutput = id;
            long pointer = nativeOpenOutput(handle, window, width, height);
            if (pointer == 0) { failed(id, "Cannot open Wayland window output"); return; }
            Output output = new Output(id, window, pointer, width, height);
            outputs.put(id, output);
            nativeOutputs.put(pointer, output);
            if (!nativeSetVisible(pointer, false)) failed(id, "Cannot suspend Wayland output");
        });
    }

    @Override public void resize(long id, int width, int height) {
        command(() -> {
            Output output = outputs.get(id);
            if (output == null) return;
            if (!dimensions(id, width, height) || !nativeResize(output.handle, width, height)) {
                failed(id, "Cannot resize Wayland output");
                return;
            }
            output.width = width; output.height = height;
        });
    }

    @Override public void releaseOutput(long id) {
        command(() -> {
            Output output = outputs.get(id);
            if (output == null) return;
            outputs.remove(id);
            nativeOutputs.remove(output.handle);
            nativeReleaseOutput(output.handle);
        });
    }
    @Override public void focus(long id, boolean focused) { output(id, pointer -> nativeFocus(pointer, focused)); }
    @Override public void setVisible(long id, boolean visible) {
        output(id, pointer -> {
            if (!nativeSetVisible(pointer, visible)) failed(id, "Cannot change Wayland output visibility");
        });
    }
    @Override public void pointer(long id, double x, double y) { output(id, pointer -> nativePointer(pointer, x, y)); }
    @Override public void button(long id, int button, boolean down) { output(id, pointer -> nativeButton(pointer, button, down)); }
    @Override public void scroll(long id, double horizontal, double vertical) {
        output(id, pointer -> nativeScroll(pointer, horizontal, vertical));
    }
    @Override public void key(long id, int androidKey, int scanCode, boolean down) {
        output(id, pointer -> nativeKey(pointer, androidKey, scanCode, down));
    }
    @Override public void closeWindow(long window, boolean force) { command(() -> nativeCloseWindow(handle, window, force)); }

    @Override public void openClient(long request) {
        command(() -> {
            int descriptor = -1;
            if (request > lastClient) {
                lastClient = request;
                descriptor = nativeConnect(handle);
            }
            try (ParcelFileDescriptor connection = descriptor < 0 ? null : ParcelFileDescriptor.adoptFd(descriptor)) {
                owner.client(request, connection, connection == null ? "Cannot create Wayland client connection" : "");
            } catch (RemoteException | IOException error) { requestStop(); }
        });
    }

    @Override public void frameConsumed(long id, long serial) {
        command(() -> {
            Output output = outputs.get(id);
            if (output != null && output.credit.acknowledge(serial)) nativeRefresh(output.handle);
        });
    }

    private boolean frameWanted(long pointer) {
        Output output = nativeOutputs.get(pointer);
        return output != null && output.credit.offer() != 0;
    }

    private boolean canRender(long pointer) {
        Output output = nativeOutputs.get(pointer);
        return output != null && output.credit.canRender();
    }

    private void onFrame(long pointer, int descriptor, int width, int height) {
        try (ParcelFileDescriptor pixels = descriptor < 0 ? null : ParcelFileDescriptor.adoptFd(descriptor)) {
            Output output = nativeOutputs.get(pointer);
            if (output != null) owner.frame(output.id, pixels == null ? 0 : output.credit.pending(), pixels, width, height);
        } catch (RemoteException | IOException error) { requestStop(); }
    }

    private void onWindow(long id, long parent, byte[] title, byte[] appId, boolean mapped,
            int width, int height, boolean removed) {
        if (removed) {
            for (int index = outputs.size() - 1; index >= 0; --index) {
                Output output = outputs.valueAt(index);
                if (output.window != id) continue;
                outputs.removeAt(index);
                nativeOutputs.remove(output.handle);
                nativeReleaseOutput(output.handle);
            }
        }
        try {
            owner.window(id, parent, new String(title, StandardCharsets.UTF_8),
                    new String(appId, StandardCharsets.UTF_8), mapped, width, height, removed);
        } catch (RemoteException error) { requestStop(); }
    }

    private void onError(String message) { failed(0, message); requestStop(); }
    private void failed(long id, String message) {
        try { if (owner != null) owner.failed(id, message); }
        catch (RemoteException error) { requestStop(); }
    }

    @Override public void stop() { lifecycle.checkCaller(Binder.getCallingUid()); requestStop(); }

    private void requestStop() {
        handler.post(() -> {
            HostedServerLifecycle.Stop action = lifecycle.stop();
            if (action == HostedServerLifecycle.Stop.EXIT) System.exit(0);
            if (action == HostedServerLifecycle.Stop.NATIVE) shutdown();
        });
    }

    private void shutdown() {
        if (eventDescriptor != null) {
            Looper.myQueue().removeOnFileDescriptorEventListener(eventDescriptor.getFileDescriptor());
            try { eventDescriptor.close(); } catch (IOException ignored) { }
            eventDescriptor = null;
        }
        if (handle != 0) { nativeStop(handle); handle = 0; }
        System.exit(0);
    }

    private void announce(String phase, String display) {
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBinder("server", this);
        extras.putString("session", session);
        extras.putString("token", token);
        extras.putString("phase", phase);
        extras.putString("display", display);
        context.sendBroadcast(new Intent(ACTION).setPackage(hostPackage).putExtras(extras), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private native long nativeStart();
    private static native int nativeEventFd(long server);
    private static native int nativeDispatch(long server);
    private static native int nativeConnect(long server);
    private static native String nativeSocket(long server);
    private static native void nativeStop(long server);
    private static native long nativeOpenOutput(long server, long window, int width, int height);
    private static native boolean nativeResize(long output, int width, int height);
    private static native boolean nativeSetVisible(long output, boolean visible);
    private static native void nativeReleaseOutput(long output);
    private static native void nativeRefresh(long output);
    private static native void nativeFocus(long output, boolean focused);
    private static native void nativePointer(long output, double x, double y);
    private static native void nativeButton(long output, int button, boolean down);
    private static native void nativeScroll(long output, double horizontal, double vertical);
    private static native void nativeKey(long output, int androidKey, int scanCode, boolean down);
    private static native void nativeCloseWindow(long server, long window, boolean force);
}
