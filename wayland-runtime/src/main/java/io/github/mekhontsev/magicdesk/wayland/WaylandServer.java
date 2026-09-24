package io.github.mekhontsev.magicdesk.wayland;
import android.hardware.HardwareBuffer;
import io.github.mekhontsev.magicdesk.hosted.HostedFrame;

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
import io.github.mekhontsev.magicdesk.hosted.HostedFileExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.HashSet;
import java.util.function.LongConsumer;

public final class WaylandServer extends IWaylandServer.Stub {
    public static final String ACTION = "io.github.mekhontsev.magicdesk.wayland.SERVER_READY";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final HostedServerLifecycle lifecycle;
    private final String hostPackage, session, token;
    private final LongSparseArray<Output> outputs = new LongSparseArray<>();
    private final LongSparseArray<Output> nativeOutputs = new LongSparseArray<>();
    private final ShellSurfaceCatalog shell = new ShellSurfaceCatalog();
    private final HashSet<Long> applicationViews = new HashSet<>();
    private final IBinder.DeathRecipient ownerDied = this::requestStop;
    private final Runnable deadline = this::expireAdmission;
    private IWaylandEvents owner;
    private long handle, lastOutput, lastClient, shellRevision;
    private ParcelFileDescriptor eventDescriptor;
    private final HostedFileExchange contentFiles;

    private static final class Output {
        final long id, window, handle;
        final FrameCredit credit = new FrameCredit();
        long generation;
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
        contentFiles = new HostedFileExchange(required("XDG_RUNTIME_DIR") + "/content",
                System.getenv("MAGICDESK_WAYLAND_GUEST_CONTENT"),
                System.getenv("MAGICDESK_GUEST_FILES_SOCKET"), System.getenv("MAGICDESK_GUEST_FILES_TOKEN"));
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
            if ("1".equals(System.getenv("MAGICDESK_WAYLAND_GUEST_SOCKET"))) exportGuestSocket();
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

    private void exportGuestSocket() throws IOException {
        try {
            java.io.File directory = new java.io.File(required("XDG_RUNTIME_DIR"));
            var parent = Os.lstat(directory.getParent());
            String socket = new java.io.File(directory, nativeSocket(handle)).getPath();
            var endpoint = Os.lstat(socket);
            if (!OsConstants.S_ISDIR(parent.st_mode) || parent.st_uid != Process.myUid()
                    || (parent.st_mode & 0777) != 0700 || !OsConstants.S_ISSOCK(endpoint.st_mode)
                    || endpoint.st_uid != Process.myUid())
                throw new SecurityException("Guest socket needs a private executor-owned parent");
            // The authorized root executor may bind this session into a guest namespace.
            // The private parent stays inaccessible to other Android application UIDs.
            Os.chmod(directory.getPath(), 0755);
        } catch (android.system.ErrnoException error) {
            throw new IOException("Cannot expose the selected Wayland socket to its guest", error);
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

    @Override public void openOutput(long id, long window, long shellOwner, long parentOutput, int width, int height) {
        command(() -> {
            boolean admitted = shellOwner == 0 ? !shell.contains(window)
                    : shellOwner == shell.owner() && shell.contains(window);
            if (!admitted || id <= lastOutput || outputs.size() >= 16 || !dimensions(id, width, height)) {
                failed(id, "Invalid or exhausted Wayland output lease");
                return;
            }
            lastOutput = id;
            Output parent = parentOutput == 0 ? null : outputs.get(parentOutput);
            if (parentOutput != 0 && (shellOwner != 0 || parent == null || parent.window != window)) {
                failed(id, "Invalid dependent output owner"); return;
            }
            long pointer = parent == null ? nativeOpenOutput(handle, window, width, height)
                    : nativeBorrowDependents(parent.handle);
            if (pointer == 0) { failed(id, "Cannot open Wayland window output"); return; }
            Output output = new Output(id, window, pointer, width, height);
            outputs.put(id, output);
            nativeOutputs.put(pointer, output);
            if (!nativeSetVisible(pointer, false)) failed(id, "Cannot suspend Wayland output");
        });
    }

    @Override public void viewport(long id, long generation, int x, int y, int width, int height, boolean configureClient) {
        command(() -> {
            Output output = outputs.get(id);
            if (output == null) { failed(id, generation, "Wayland output is unavailable"); return; }
            if (generation <= output.generation) return;
            if (x < -16384 || y < -16384 || x > 16384 || y > 16384
                    || (configureClient && (x != 0 || y != 0)) || !dimensions(id, width, height)
                    || !(configureClient ? nativeResize(output.handle, width, height)
                        : nativeViewport(output.handle, x, y, width, height))) {
                failed(id, generation, "Cannot configure Wayland output viewport");
                return;
            }
            output.generation = generation;
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
    @Override public void scale(long id, double scale) {
        output(id, pointer -> { if (!nativeScale(pointer, scale)) failed(id, "Invalid Wayland output scale"); });
    }
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
    @Override public void text(long id, long editor, byte[] utf8, boolean composing, int cursor) {
        if (utf8 == null || utf8.length > WaylandText.MAX_BYTES || cursor < 0 || cursor > utf8.length)
            throw new IllegalArgumentException("Invalid Wayland text edit");
        output(id, pointer -> nativeText(pointer, editor, utf8, composing, cursor));
    }
    @Override public void deleteText(long id, long editor, long revision, int before, int after, byte[] preedit, int cursor) {
        if (before < 0 || after < 0 || before > WaylandText.MAX_BYTES || after > WaylandText.MAX_BYTES
                || preedit == null || preedit.length > WaylandText.MAX_BYTES || cursor < 0 || cursor > preedit.length)
            throw new IllegalArgumentException("Invalid Wayland text deletion");
        output(id, pointer -> nativeDeleteText(pointer, editor, revision, before, after, preedit, cursor));
    }
    @Override public void confirmFullscreen(long window, long serial, boolean fullscreen) {
        command(() -> nativeConfirmFullscreen(handle, window, serial, fullscreen));
    }

    @Override public void contentActive(boolean active) { command(() -> nativeContentEnable(handle, active)); }
    @Override public void drag(long id, int action, long offer, double x, double y, boolean accepted) {
        if (action < 0 || action > 6 || !Double.isFinite(x) || !Double.isFinite(y))
            throw new IllegalArgumentException("Invalid drag event");
        output(id, pointer -> nativeDrag(handle, pointer, action, offer, x, y, accepted));
    }
    @Override public void publishContent(int channel, long id, String types) {
        if (types == null || types.length() >= 8192) throw new IllegalArgumentException("Invalid content formats");
        command(() -> nativeContentPublish(handle, channel, id, types));
    }
    @Override public void readContent(int channel, long id, long request, String type) {
        if (type == null || type.length() >= 128) throw new IllegalArgumentException("Invalid content type");
        command(() -> nativeContentRead(handle, channel, id, request, type));
    }
    @Override public void replyContent(long request, ParcelFileDescriptor data) {
        try { lifecycle.checkReady(Binder.getCallingUid()); }
        catch (RuntimeException error) { closeDescriptor(data); throw error; }
        if (!handler.post(() -> {
            try { if (handle != 0) nativeContentReply(handle, request, data == null ? -1 : data.getFd()); }
            finally { closeDescriptor(data); }
        })) closeDescriptor(data);
    }
    @Override public ParcelFileDescriptor openContentFile(String uri) {
        lifecycle.checkReady(Binder.getCallingUid());
        try { return contentFiles.open(uri); }
        catch (IOException e) { throw new IllegalStateException("Cannot open guest file", e); }
    }
    @Override public String importContentFile(ParcelFileDescriptor file, String name) {
        try (file) {
            lifecycle.checkReady(Binder.getCallingUid());
            return contentFiles.importFile(file, name);
        } catch (IOException e) { throw new IllegalStateException("Cannot import guest file", e); }
    }
    private static void closeDescriptor(ParcelFileDescriptor fd) {
        if (fd != null) try { fd.close(); } catch (IOException ignored) { }
    }
    private void onContentOffer(int channel, long id, long pointer, String types) {
        Output output = nativeOutputs.get(pointer);
        try { owner.contentOffer(channel, id, output == null ? 0 : output.id, types); }
        catch (RemoteException error) { requestStop(); }
    }
    private void onContentRequest(int channel, long id, long request, String type) {
        try { owner.contentRequest(channel, id, request, type); }
        catch (RemoteException error) { requestStop(); }
    }
    private void onContentReply(long request, int fd) {
        try (ParcelFileDescriptor data = fd < 0 ? null : ParcelFileDescriptor.adoptFd(fd)) {
            owner.contentReply(request, data);
        } catch (IOException | RemoteException error) { requestStop(); }
    }
    private void onDragEvent(long pointer, long offer, boolean finished, boolean accepted) {
        Output output = nativeOutputs.get(pointer);
        if (output == null) return;
        try { owner.dragEvent(output.id, offer, finished, accepted); }
        catch (RemoteException error) { requestStop(); }
    }

    @Override public void setShellOutput(long id, int width, int height) {
        command(() -> {
            if (id <= 0 || width < 1 || height < 1 || width > 16384 || height > 16384) {
                shellOutput(id, 0, 0, "Invalid Wayland shell output dimensions");
                return;
            }
            if (shell.owner() != id && !shell.acquire(id)) {
                shellOutput(id, 0, 0, "Wayland shell output already owned or lease expired");
                return;
            }
            if (!nativeShellOutput(handle, width, height)) {
                releaseShellOutput(id);
                shellOutput(id, 0, 0, "Cannot configure Wayland shell output");
                return;
            }
            shellOutput(id, width, height, "");
        });
    }

    @Override public void releaseShell(long id) { command(() -> releaseShellOutput(id)); }

    @Override public void publishToplevel(long binding, long id, String title, String appId,
            boolean active, boolean maximized, boolean fullscreen, boolean removed) {
        command(() -> {
            if (binding <= 0 || shell.owner() != binding) return;
            if (title == null || appId == null || !nativeToplevel(handle, id,
                    title.getBytes(StandardCharsets.UTF_8), appId.getBytes(StandardCharsets.UTF_8),
                    active, maximized, fullscreen, removed)) {
                releaseShellOutput(binding);
                shellOutput(binding, 0, 0, "Invalid workspace toplevel publication");
            }
        });
    }

    private void onToplevelAction(long id, int action) {
        if (shell.owner() <= 0) return;
        try { owner.toplevelAction(shell.owner(), id, action); }
        catch (RemoteException error) { requestStop(); }
    }

    private void releaseShellOutput(long id) {
        if (id == 0 || shell.owner() != id) return;
        // Native destruction publishes removals under the old owner before it is revoked.
        nativeShellOutput(handle, 0, 0);
        shell.release(id);
    }

    @Override public void configureShell(long owner, long surface, long revision,
            int x, int y, int width, int height) {
        command(() -> {
            if (!shell.accepts(owner, surface, revision)) return;
            if (!nativeConfigureShell(handle, surface, x, y, width, height)) {
                releaseShellOutput(owner);
                shellOutput(owner, 0, 0, "Cannot configure Wayland shell surface");
            }
        });
    }

    private void shellOutput(long id, int width, int height, String error) {
        try { owner.shellOutput(id, width, height, error); }
        catch (RemoteException failure) { requestStop(); }
    }

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
            if (output == null) return;
            var acknowledgement = output.credit.acknowledge(serial);
            if (acknowledgement != FrameCredit.Acknowledgement.STALE)
                nativeFrameConsumed(output.handle, acknowledgement == FrameCredit.Acknowledgement.REFRESH);
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

    private void onFrame(long pointer, HardwareBuffer buffer, int descriptor, int fence, int width, int height) {
        try (buffer;
             ParcelFileDescriptor pixels = descriptor < 0 ? null : ParcelFileDescriptor.adoptFd(descriptor);
             ParcelFileDescriptor acquire = fence < 0 ? null : ParcelFileDescriptor.adoptFd(fence)) {
            Output output = nativeOutputs.get(pointer);
            HostedFrame frame = pixels == null && buffer == null ? null : new HostedFrame(buffer, pixels, acquire, width, height);
            if (output != null) owner.frame(output.id, frame == null ? 0 : output.credit.pending(), output.generation, frame);
        } catch (RemoteException | IOException error) { requestStop(); }
    }

    private void onWindow(long id, long parent, byte[] title, byte[] appId, boolean mapped,
            int width, int height, long requestSerial, boolean fullscreen, boolean removed) {
        if (removed) applicationViews.remove(id);
        else applicationViews.add(id);
        if (removed) releaseSurfaceOutputs(id);
        try {
            owner.window(id, parent, new String(title, StandardCharsets.UTF_8),
                    new String(appId, StandardCharsets.UTF_8), mapped, width, height, requestSerial, fullscreen, removed);
        } catch (RemoteException error) { requestStop(); }
    }

    private void onTextInput(long pointer, long editor, long revision, byte[] surrounding,
            int cursor, int anchor, int purpose, int hints) {
        Output output = nativeOutputs.get(pointer);
        if (output == null) return;
        try { owner.textInput(output.id, editor, revision, surrounding, cursor, anchor, purpose, hints); }
        catch (RemoteException error) { requestStop(); }
    }
    private void onCursor(long pointer, int[] pixels, int width, int height, int hotspotX, int hotspotY, boolean hidden) {
        Output output = nativeOutputs.get(pointer);
        if (output == null) return;
        try { owner.cursor(output.id, pixels, width, height, hotspotX, hotspotY, hidden); }
        catch (RemoteException error) { requestStop(); }
    }

    private void onShell(long id, byte[] name, boolean mapped, boolean configureNeeded, int layer, int keyboard,
            int anchors, long width, long height, int left, int top, int right, int bottom,
            int exclusiveZone, boolean removed) {
        if (removed) releaseSurfaceOutputs(id);
        WaylandShellSurface surface = removed ? null : new WaylandShellSurface(id, ++shellRevision,
                new String(name, StandardCharsets.UTF_8), mapped, configureNeeded, WaylandShellSurface.layer(layer),
                WaylandShellSurface.keyboard(keyboard), anchors, width, height,
                left, top, right, bottom, exclusiveZone);
        if (!shell.update(shell.owner(), id, surface)) return;
        try { owner.shellSurface(shell.owner(), id, surface); }
        catch (RemoteException error) { requestStop(); }
    }

    private void onGeometry(long id, long revision, boolean mapped, int left, int top, int right, int bottom,
            boolean complete, int[] input, boolean dependents) {
        boolean shellSurface = shell.contains(id);
        if (!shellSurface && !applicationViews.contains(id)) return;
        var geometry = WaylandViewGeometry.fromNative(id, revision, mapped, left, top, right, bottom, complete, input, dependents);
        try { owner.geometry(shellSurface ? shell.owner() : 0, geometry); }
        catch (RemoteException error) { requestStop(); }
    }

    private void releaseSurfaceOutputs(long id) {
        for (int index = outputs.size() - 1; index >= 0; --index) {
            Output output = outputs.valueAt(index);
            if (output.window != id) continue;
            outputs.removeAt(index);
            nativeOutputs.remove(output.handle);
            nativeReleaseOutput(output.handle);
        }
    }

    private void onError(String message) { failed(0, message); requestStop(); }
    private void failed(long id, String message) {
        failed(id, 0, message);
    }
    private void failed(long id, long generation, String message) {
        try { if (owner != null) owner.failed(id, generation, message); }
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
        contentFiles.close();
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
        if ("ready".equals(phase) && "1".equals(System.getenv("MAGICDESK_WAYLAND_GUEST_SOCKET"))) {
            String label = nativeMemoryLabel();
            if (label == null) throw new IllegalStateException("Cannot identify compositor shared-memory access");
            extras.putString("memoryLabel", label);
        }
        context.sendBroadcast(new Intent(ACTION).setPackage(hostPackage).putExtras(extras), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private native long nativeStart();
    private static native String nativeMemoryLabel();
    private static native int nativeEventFd(long server);
    private static native int nativeDispatch(long server);
    private static native int nativeConnect(long server);
    private static native String nativeSocket(long server);
    private static native void nativeStop(long server);
    private static native long nativeOpenOutput(long server, long window, int width, int height);
    private static native boolean nativeResize(long output, int width, int height);
    private static native boolean nativeViewport(long output, int x, int y, int width, int height);
    private static native boolean nativeSetVisible(long output, boolean visible);
    private static native void nativeReleaseOutput(long output);
    private static native void nativeFrameConsumed(long output, boolean refresh);
    private static native void nativeFocus(long output, boolean focused);
    private static native boolean nativeScale(long output, double scale);
    private static native long nativeBorrowDependents(long output);
    private static native void nativePointer(long output, double x, double y);
    private static native void nativeButton(long output, int button, boolean down);
    private static native void nativeScroll(long output, double horizontal, double vertical);
    private static native void nativeKey(long output, int androidKey, int scanCode, boolean down);
    private static native void nativeText(long output, long editor, byte[] utf8, boolean composing, int cursor);
    private static native void nativeDeleteText(long output, long editor, long revision, int before, int after, byte[] preedit, int cursor);
    private static native void nativeCloseWindow(long server, long window, boolean force);
    private static native void nativeConfirmFullscreen(long server, long window, long serial, boolean fullscreen);
    private static native void nativeContentEnable(long server, boolean active);
    private static native boolean nativeContentPublish(long server, int channel, long id, String types);
    private static native void nativeContentRead(long server, int channel, long id, long request, String type);
    private static native void nativeContentReply(long server, long request, int fd);
    private static native void nativeDrag(long server, long output, int action, long offer, double x, double y, boolean accepted);
    private static native boolean nativeShellOutput(long server, int width, int height);
    private static native boolean nativeToplevel(long server, long id, byte[] title, byte[] appId,
            boolean active, boolean maximized, boolean fullscreen, boolean removed);
    private static native boolean nativeConfigureShell(long server, long surface, int x, int y, int width, int height);
}
