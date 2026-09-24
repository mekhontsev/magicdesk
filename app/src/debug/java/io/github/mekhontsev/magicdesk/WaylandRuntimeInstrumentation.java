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
import io.github.mekhontsev.magicdesk.wayland.WaylandViewport;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-UID production runtime test, independent of Desktop and privileged service startup. */
public final class WaylandRuntimeInstrumentation extends Instrumentation {
    private String clientPath;
    private boolean shellTest;
    private boolean dmabuf;
    private int chromeDisplay = -1;
    private int workspaceDisplay = -1;
    private boolean homeLayers;
    private int policyTask = -1;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        clientPath = arguments == null ? null : arguments.getString("client");
        shellTest = arguments != null && Boolean.parseBoolean(arguments.getString("shell"));
        dmabuf = arguments != null && Boolean.parseBoolean(arguments.getString("dmabuf"));
        if (arguments != null) chromeDisplay = Integer.parseInt(arguments.getString("chrome_display", "-1"));
        if (arguments != null) workspaceDisplay = Integer.parseInt(arguments.getString("workspace_display", "-1"));
        homeLayers = arguments != null && Boolean.parseBoolean(arguments.getString("home_layers", "false"));
        if (arguments != null) policyTask = Integer.parseInt(arguments.getString("policy_task", "-1"));
        start();
    }

    @Override public void onStart() {
        new Thread(() -> {
            Bundle result = new Bundle();
            try {
                runRuntime();
                result.putString("wayland_runtime", workspaceDisplay >= 0
                        ? "passed: workspace catalog hosting, automatic placement, input holes, unmap/remap, role rejection, reservation cleanup, retained application"
                                + (policyTask >= 0 ? "; fullscreen conceal/reveal with stable reservation and task plane" : "")
                        : chromeDisplay >= 0
                        ? "passed: Wayland chrome pixels, exact input holes, replacement receipts, borrowed output cleanup; shell runtime checks"
                        : shellTest
                        ? "passed: cross-UID shell binding, family geometry, viewport pixels/input, Surface replacement, input isolation, remap, scope release"
                        : "passed: cross-UID compositor, client FD, family geometry, Android pixels, input, close"
                                + (dmabuf ? "; GPU-produced DMA-BUF client" : ""));
                finish(Activity.RESULT_OK, result);
            } catch (Exception error) {
                result.putString("wayland_runtime", "failed: " + error);
                result.putString("trace", android.util.Log.getStackTraceString(error));
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
        CompletableFuture<Void> geometry = new CompletableFuture<>();
        CompletableFuture<Void> destroyed = new CompletableFuture<>(), serverExit = new CompletableFuture<>(), clientExit = new CompletableFuture<>();
        CompletableFuture<Long> mapped = new CompletableFuture<>();
        AtomicReference<String> failure = new AtomicReference<>();
        WaylandSession.Listener listener = new WaylandSession.Listener() {
            @Override public void geometryChanged(long window) {
                var value = session.get().geometry(window);
                if (value != null && value.mapped() && value.inputComplete() && value.acceptsInput(20, 20))
                    geometry.complete(null);
            }
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
                geometry.completeExceptionally(error);
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
            try (var shell = shellTest && workspaceDisplay < 0 ? new ShellFixture(session.get(), main) : null;
                    var workspace = workspaceDisplay >= 0 ? new WorkspaceFixture(session.get()) : null;
                    var launch = new WaylandClientLaunch(context, session.get().connect().get(10, TimeUnit.SECONDS), execution.uid)) {
                if (shell != null) shell.binding.get().ready().get(10, TimeUnit.SECONDS);
                // EVENT_WAIT: compositor admission; a missing acknowledgement aborts this fixture.
                if (workspace != null) workspace.binding.ready().get(10, TimeUnit.SECONDS);
                String invocation = "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + q(context.getApplicationInfo().sourceDir)
                        + " " + String.join(" ", launch.arguments(execution.termux.packageName,
                                library + "/libmagicdesk_wayland_client.so", clientPath,
                                workspace != null ? homeLayers ? "--home-client" : "--workspace-client"
                                        : dmabuf ? "--dma-client" : "--client")
                                .stream().map(WaylandRuntimeInstrumentation::q).toList());
                try (var client = execution.start(invocation, "", id + "-client", null,
                        (code, output, error) -> complete(clientExit, code, output, error))) {
                    launch.transferred().toCompletableFuture().get(15, TimeUnit.SECONDS);
                    long window = mapped.get(15, TimeUnit.SECONDS);
                    geometry.get(15, TimeUnit.SECONDS);
                    try (var output = session.get().openOutput(window, 80, 60)) {
                        output.setSurface(images.getSurface(), 80, 60);
                        if (workspace != null) {
                            workspace.exercise(output);
                            if (session.get().windows().stream().noneMatch(item -> item.id() == window))
                                throw new IOException("Shell revocation destroyed the application");
                            session.get().closeWindow(window);
                        } else if (shell != null) shell.exercise(output);
                        else {
                            pixels.get(15, TimeUnit.SECONDS);
                            // A replacement presentation also needs a receipt when the client is idle.
                            output.setSurface(images.getSurface(), new WaylandViewport(0, 0, 80, 60)).get(15, TimeUnit.SECONDS);
                            output.focus(true);
                            output.pointer(.25, .25);
                            output.button(0, true);
                            output.key(30, true);
                            output.focus(false);
                            session.get().closeWindow(window);
                        }
                        clientExit.get(15, TimeUnit.SECONDS);
                        destroyed.get(15, TimeUnit.SECONDS);
                        if (session.get().geometry(window) != null) throw new IOException("Destroyed view retained geometry");
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

    private final class WorkspaceFixture implements AutoCloseable, WaylandShellBinding.Listener {
        WaylandShellBinding binding;
        HostedShellWindows windows;
        ShellLayoutScope scope;
        ShellBounds initialWorkArea;
        DesktopShellActivity activity;
        final java.util.List<CompletableFuture<Void>> frames = java.util.List.of(
                new CompletableFuture<>(), new CompletableFuture<>(), new CompletableFuture<>());
        final CompletableFuture<String> revoked = new CompletableFuture<>();
        int density, mappings;
        boolean wasMapped;
        long surface;
        android.widget.FrameLayout homeRoot;
        android.view.View nativeControl;
        int nativeClicks;
        int expectedNativeClicks;
        CompletableFuture<Void> nativeInput;

        WorkspaceFixture(WaylandSession session) throws Exception {
            var created = new CompletableFuture<Void>();
            runOnMainSync(() -> {
                try {
                    var runtime = DesktopRuntimeBridge.getWorkspaceRuntime(workspaceDisplay);
                    activity = runtime == null ? null : runtime.host();
                    if (activity == null) throw new IOException("Explicit active Desktop required");
                    var panels = activity.panels();
                    if (homeLayers) homeRoot = (android.widget.FrameLayout) ((android.view.ViewGroup)
                            activity.findViewById(android.R.id.content)).getChildAt(0);
                    var display = getTargetContext().getSystemService(android.hardware.display.DisplayManager.class)
                            .getDisplay(workspaceDisplay);
                    var context = getTargetContext().createDisplayContext(display);
                    density = context.getResources().getDisplayMetrics().densityDpi;
                    scope = panels.shellScope();
                    initialWorkArea = scope.snapshot().workArea();
                    binding = new WaylandShellBinding(session, scope, density, this);
                    windows = binding.host(activity.shellSurfaceHost(state -> new WaylandSurfaceOutput(
                            binding.openOutput(state.id(), state.bounds().width(), state.bounds().height()))),
                            activity.shellPresentation());
                    created.complete(null);
                } catch (Exception error) { created.completeExceptionally(error); }
            });
            created.get();
        }

        @Override public void changed() {
            if (binding == null) return;
            for (var state : binding.surfaces()) {
                surface = state.id();
                if (state.mapped() && !wasMapped) mappings++;
                wasMapped = state.mapped();
                observe();
            }
        }

        @Override public void geometryChanged(long id) { observe(); }

        private void observe() {
            if (windows == null || !wasMapped) return;
            var state = binding.surfaces().stream().filter(item -> item.id() == surface).findFirst().orElse(null);
            var frame = WaylandShellBinding.frame(binding.geometry(surface));
            if (state == null || frame == null) return;
            int phase = mappings > 1 ? 2 : state.marginTop() == 13 ? 1 : 0;
            windows.presented(surface).whenComplete((ignored, error) -> {
                if (error == null) frames.get(phase).complete(null);
            });
        }

        @Override public void closed(String reason) {
            revoked.complete(reason);
            for (var frame : frames) if (!frame.isDone()) frame.completeExceptionally(new IOException(reason));
        }

        void exercise(WaylandSession.Output app) throws Exception {
            app.focus(true);
            app.key(30, true);
            app.key(30, false);
            for (var frame : frames) {
                // EVENT_WAIT: exact presentation/input receipt; clicks are never delayed by a settling timer.
                try { frame.get(15, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException error) {
                    var detail = new AtomicReference<String>();
                    runOnMainSync(() -> detail.set("stage=" + frames.indexOf(frame) + " mappings=" + mappings
                            + " catalog=" + binding.surfaces() + " geometry=" + binding.geometry(surface)));
                    throw new IOException("Workspace presentation timed out: " + detail.get(), error);
                }
                if (frame == frames.get(0) && policyTask >= 0) exercisePolicy();
                var placement = new AtomicReference<ShellBounds>();
                runOnMainSync(() -> {
                    var bounds = binding.surface(surface).content();
                    if (scope.snapshot().workArea().top() != bounds.bottom())
                        throw new IllegalStateException("Workspace reservation differs from panel placement");
                    placement.set(bounds);
                });
                var bounds = placement.get();
                int x = bounds.left(), y = bounds.top() + Math.round(10 * density / 160f);
                if (homeLayers) prepareHomeProbe(bounds, frames.indexOf(frame) < 2);
                if (!ShellAccess.injectPointerClickAt(workspaceDisplay, x + Math.round(30 * density / 160f), y, 1)
                        || homeLayers && frames.indexOf(frame) < 2 && !ShellAccess.injectPointerClickAt(
                                workspaceDisplay, x + Math.round(58 * density / 160f), y, 1))
                    throw new IOException("Workspace pointer injection failed");
                // EVENT_WAIT: native View receives the input hole, and overlaps a BACKGROUND surface.
                if (homeLayers) nativeInput.get(15, TimeUnit.SECONDS);
                if (homeLayers && !ShellAccess.injectPointerClickAt(workspaceDisplay,
                        x + Math.round(10 * density / 160f), y, 2))
                    throw new IOException("HOME shell secondary click injection failed");
                if (!ShellAccess.injectPointerClickAt(workspaceDisplay, x + Math.round(10 * density / 160f), y, 1))
                    throw new IOException("Workspace pointer injection failed");
            }
            // EVENT_WAIT: unsupported keyboard role revokes this contribution, not its graphical session.
            String reason = revoked.get(15, TimeUnit.SECONDS);
            if (!reason.contains("keyboard")) throw new IOException("Unexpected workspace revocation: " + reason);
            runOnMainSync(() -> {
                if (!scope.snapshot().workArea().equals(initialWorkArea))
                    throw new IllegalStateException("Workspace retained a revoked reservation");
            });
        }

        private void exercisePolicy() throws Exception {
            if (homeLayers) throw new IllegalArgumentException("Fullscreen policy fixture requires a TOP panel");
            var initial = policyTask();
            if (!initial.isFreeform()) throw new IOException("Policy fixture requires an existing freeform task");
            var reserved = new AtomicReference<ShellBounds>();
            runOnMainSync(() -> reserved.set(scope.snapshot().workArea()));
            boolean restore = false;
            try {
                var transition = new CompletableFuture<TaskRepository.ActionResult>();
                restore = MagicDeskRuntime.makeTaskFullscreen(initial, transition::complete);
                if (!restore) throw new IOException("Fullscreen gateway declined the fixture task");
                // EVENT_WAIT: production fullscreen completion; timeout fails, without replaying the transition.
                var result = transition.get(15, TimeUnit.SECONDS);
                if (!result.success) throw new IOException(result.message);
                awaitTop(false, () -> {});
                var fullscreen = policyTask();
                if (!fullscreen.isFullscreen()) throw new IOException("Fixture task did not enter fullscreen");
                runOnMainSync(() -> {
                    if (!windows.presented(surface).isCompletedExceptionally())
                        throw new IllegalStateException("Concealed surface retained a presentation receipt");
                });
                awaitTop(true, () -> activity.showStartSection(StartMenuController.MENU_APPS, false));
                awaitPresented();
                awaitTop(false, () -> activity.panels().hideAll());
                var after = policyTask();
                if (!after.isFullscreen() || after.rootTaskId != fullscreen.rootTaskId
                        || !after.bounds.equals(fullscreen.bounds) || after.displayId != fullscreen.displayId)
                    throw new IOException("Shell reveal changed the fullscreen task topology");
                runOnMainSync(() -> {
                    if (!reserved.get().equals(scope.snapshot().workArea()) || mappings != 1 || !wasMapped)
                        throw new IllegalStateException("Presentation policy changed the panel's protocol/layout state");
                });
            } finally {
                runOnMainSync(() -> activity.panels().hideAll());
                if (restore) awaitTop(true, () -> {
                    if (!MagicDeskRuntime.arrangeTask(workspaceDisplay, policyTask, DesktopTaskController.SHORTCUT_RESTORE))
                        throw new IllegalStateException("Restore gateway declined the fixture task");
                });
            }
            awaitPresented();
        }

        private TaskRepository.TaskEntry policyTask() throws IOException {
            var snapshot = MagicDeskRuntime.observedTaskSnapshot(workspaceDisplay);
            if (snapshot == null || !snapshot.available) throw new IOException("Workspace observation unavailable");
            return snapshot.tasks.stream().filter(task -> task.taskId == policyTask).findFirst()
                    .orElseThrow(() -> new IOException("Fixture task left the workspace"));
        }

        private void awaitTop(boolean visible, Runnable action) throws Exception {
            var state = new CompletableFuture<Void>();
            var presentation = new AtomicReference<ShellPresentationScope>();
            Runnable changed = () -> {
                if (presentation.get().isClosed()) state.completeExceptionally(new IOException("Workspace closed"));
                else if (presentation.get().visible(ShellSurface.Layer.TOP) == visible) state.complete(null);
            };
            runOnMainSync(() -> {
                try {
                    presentation.set(activity.shellPresentation());
                    presentation.get().listen(changed);
                    action.run();
                    changed.run();
                } catch (Exception error) { state.completeExceptionally(error); }
            });
            try {
                // EVENT_WAIT: workspace presentation policy publication; timeout fails the fixture, not the command.
                state.get(15, TimeUnit.SECONDS);
            } finally { runOnMainSync(() -> { if (presentation.get() != null) presentation.get().unlisten(changed); }); }
        }

        private void awaitPresented() throws Exception {
            var receipt = new AtomicReference<CompletableFuture<Void>>();
            runOnMainSync(() -> receipt.set(windows.presented(surface)));
            // EVENT_WAIT: replacement pixels and input region must be admitted before exercising the panel.
            receipt.get().get(15, TimeUnit.SECONDS);
        }

        @android.annotation.SuppressLint({"ClickableViewAccessibility", "RtlHardcoded"}) // Raw input assertions in display coordinates.
        private void prepareHomeProbe(ShellBounds bounds, boolean background) throws Exception {
            var placed = new CompletableFuture<Void>();
            runOnMainSync(() -> {
                try {
                    var texture = findTexture(homeRoot);
                    if (texture == null) throw new IOException("HOME has no shell texture");
                    var bitmap = texture.getBitmap(80, 40);
                    if (bitmap == null) throw new IOException("HOME shell pixels are unavailable");
                    try {
                        if (Math.abs(android.graphics.Color.alpha(bitmap.getPixel(12, 10)) - 128) > 1)
                            throw new IOException("HOME shell alpha was lost");
                    } finally { bitmap.recycle(); }
                    var layer = (android.view.View) texture.getParent();
                    if (nativeControl == null) {
                        nativeControl = new android.view.View(homeRoot.getContext());
                        nativeControl.setBackgroundColor(android.graphics.Color.RED);
                        nativeControl.setOnTouchListener((view, event) -> {
                            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                                    && ++nativeClicks == expectedNativeClicks) nativeInput.complete(null);
                            return true;
                        });
                        homeRoot.addView(nativeControl, homeRoot.indexOfChild(layer) + 1);
                    }
                    int layerIndex = homeRoot.indexOfChild(layer), nativeIndex = homeRoot.indexOfChild(nativeControl);
                    if (background ? layerIndex >= nativeIndex : layerIndex <= nativeIndex)
                        throw new IOException("HOME layer order differs from shell policy");
                    int[] origin = new int[2];
                    homeRoot.getLocationOnScreen(origin);
                    var params = new android.widget.FrameLayout.LayoutParams(Math.round(44 * density / 160f),
                            bounds.height(), android.view.Gravity.TOP | android.view.Gravity.LEFT);
                    params.leftMargin = bounds.left() - origin[0] + Math.round(20 * density / 160f);
                    params.topMargin = bounds.top() - origin[1];
                    nativeControl.setLayoutParams(params);
                    expectedNativeClicks = nativeClicks + (background ? 2 : 1);
                    nativeInput = new CompletableFuture<>();
                    homeRoot.getViewTreeObserver().registerFrameCommitCallback(() -> placed.complete(null));
                    homeRoot.invalidate();
                } catch (Exception error) { placed.completeExceptionally(error); }
            });
            // EVENT_WAIT: native test control layout is committed before pointer injection.
            placed.get(15, TimeUnit.SECONDS);
        }

        private android.view.TextureView findTexture(android.view.View view) {
            if (view instanceof android.view.TextureView texture) return texture;
            if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
                var result = findTexture(group.getChildAt(i));
                if (result != null) return result;
            }
            return null;
        }

        @Override public void close() { runOnMainSync(() -> {
            if (binding != null) binding.close();
            if (nativeControl != null) homeRoot.removeView(nativeControl);
        }); }
    }

    private final class ShellFixture implements AutoCloseable {
        final AtomicReference<WaylandShellBinding> binding = new AtomicReference<>();
        final ShellLayoutScope scope = new ShellLayoutScope();
        final CompletableFuture<Long> mapped = new CompletableFuture<>();
        final CompletableFuture<Void> alpha = new CompletableFuture<>(), interactive = new CompletableFuture<>();
        final CompletableFuture<Void> remapped = new CompletableFuture<>();
        final CompletableFuture<Void> geometry = new CompletableFuture<>();
        final ImageReader images = ImageReader.newInstance(80, 40, PixelFormat.RGBA_8888, 2);
        boolean remapping;

        ShellFixture(WaylandSession session, Handler main) {
            runOnMainSync(() -> {
                scope.resize(new ShellBounds(0, 0, 64, 100), new ShellBounds(0, 0, 64, 100));
                binding.set(new WaylandShellBinding(session, scope, 160, new WaylandShellBinding.Listener() {
                    @Override public void geometryChanged(long id) {
                        var value = binding.get().geometry(id);
                        if (value == null || !value.mapped()) return;
                        if (!value.inputComplete() || value.paint().left() != 0 || value.paint().top() != 0
                                || value.paint().right() != 64 || value.paint().bottom() != 24
                                || !value.acceptsInput(10, 10) || value.acceptsInput(30, 10) || value.acceptsInput(70, 35))
                            fail(new IOException("Incorrect shell family geometry"));
                        else geometry.complete(null);
                    }
                    @Override public void changed() {
                        var current = binding.get();
                        if (current == null) return;
                        for (var surface : current.surfaces()) {
                            if (surface.configureNeeded() && mapped.isDone()) remapping = true;
                            if (surface.mapped()) {
                                mapped.complete(surface.id());
                                if (remapping) remapped.complete(null);
                                if (scope.snapshot().workArea().top() != 27)
                                    fail(new IOException("Shell reservation not committed"));
                            }
                            if (surface.keyboard() == io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface.Keyboard.ON_DEMAND)
                                interactive.complete(null);
                        }
                    }
                    @Override public void closed(String reason) {
                        if (!reason.isEmpty() && !reason.equals("Shell layout scope was released")) fail(new IOException(reason));
                    }
                    private void fail(Exception error) {
                        mapped.completeExceptionally(error); alpha.completeExceptionally(error);
                        interactive.completeExceptionally(error); remapped.completeExceptionally(error);
                        geometry.completeExceptionally(error);
                    }
                }));
            });
            images.setOnImageAvailableListener(reader -> {
                try (var image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    var plane = image.getPlanes()[0];
                    var pixels = plane.getBuffer();
                    int padding = 35 * plane.getRowStride() + 70 * plane.getPixelStride();
                    int color = 10 * plane.getRowStride() + 12 * plane.getPixelStride();
                    if (pixels.get(3) == 0 && (pixels.get(color) & 255) == 0x40 && (pixels.get(color + 1) & 255) == 0x20
                            && (pixels.get(color + 2) & 255) == 0x10 && (pixels.get(color + 3) & 255) == 0x80
                            && pixels.get(padding + 3) == 0) alpha.complete(null);
                } catch (RuntimeException error) { alpha.completeExceptionally(error); }
            }, main);
        }

        void exercise(WaylandSession.Output app) throws Exception {
            long surface = mapped.get(15, TimeUnit.SECONDS);
            geometry.get(15, TimeUnit.SECONDS);
            AtomicReference<WaylandSession.Output> panel = new AtomicReference<>();
            runOnMainSync(() -> panel.set(binding.get().openOutput(surface, 80, 40)));
            try (var output = panel.get();
                    var replacement = ImageReader.newInstance(80, 40, PixelFormat.RGBA_8888, 2)) {
                output.setSurface(images.getSurface(), new WaylandViewport(-8, -6, 80, 40)).get(15, TimeUnit.SECONDS);
                alpha.get(15, TimeUnit.SECONDS);
                app.focus(true);
                app.key(30, true);
                output.focus(true);
                output.key(48, true);
                app.key(30, false);
                if (chromeDisplay >= 0) exerciseChrome(surface);
                else {
                    output.pointer(.2, .25);
                    output.button(0, true);
                    output.button(0, false);
                    interactive.get(15, TimeUnit.SECONDS);
                }
                output.focus(true);
                output.key(48, true);
                remapped.get(15, TimeUnit.SECONDS);
                var replacedPixels = new CompletableFuture<Void>();
                replacement.setOnImageAvailableListener(reader -> {
                    try (var image = reader.acquireLatestImage()) {
                        if (image == null) return;
                        var plane = image.getPlanes()[0];
                        var data = plane.getBuffer();
                        if ((data.get(0) & 255) == 0x40 && (data.get(1) & 255) == 0x20
                                && (data.get(2) & 255) == 0x10 && (data.get(3) & 255) == 0x80)
                            replacedPixels.complete(null);
                    } catch (RuntimeException error) { replacedPixels.completeExceptionally(error); }
                }, new Handler(Looper.getMainLooper()));
                output.setSurface(replacement.getSurface(), new WaylandViewport(4, 2, 80, 40)).get(15, TimeUnit.SECONDS);
                replacedPixels.get(15, TimeUnit.SECONDS);
                runOnMainSync(scope::clear);
                if (!scope.snapshot().exclusions().isEmpty()) throw new IOException("Shell reservations survived revocation");
                if (binding.get().geometry(surface) != null) throw new IOException("Shell geometry survived revocation");
            }
        }

        @SuppressWarnings("unchecked")
        @android.annotation.SuppressLint("ClickableViewAccessibility") // Counts without consuming; HostedSurfaceView owns clicks.
        private void exerciseChrome(long surface) throws Exception {
            var panels = new AtomicReference<DesktopPanelWindowController>();
            var view = new AtomicReference<HostedShellSurfaceView>();
            var lease = new AtomicReference<DesktopPanelWindowController.ShellWindow>();
            var bounds = new ShellBounds(340, 218, 940, 518);
            var shown = new CompletableFuture<Void>();
            var downs = new java.util.concurrent.atomic.AtomicInteger();
            try {
                runOnMainSync(() -> {
                    try {
                        var field = DesktopPanelWindowController.class.getDeclaredField("CONTROLLERS");
                        field.setAccessible(true);
                        panels.set(((java.util.Map<Integer, DesktopPanelWindowController>) field.get(null)).get(chromeDisplay));
                        if (panels.get() == null) throw new IOException("Explicit Desktop host required");
                        var display = getTargetContext().getSystemService(android.hardware.display.DisplayManager.class)
                                .getDisplay(chromeDisplay);
                        var output = binding.get().openOutput(surface, 80, 40);
                        view.set(new HostedShellSurfaceView(getTargetContext().createDisplayContext(display),
                                new WaylandSurfaceOutput(output)));
                        view.get().getChildAt(0).setOnTouchListener((target, event) -> {
                            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) downs.incrementAndGet();
                            return false;
                        });
                        var actual = WaylandShellBinding.frame(binding.get().geometry(surface));
                        var viewport = new ShellBounds(-8, -6, 72, 34);
                        lease.set(panels.get().borrowShellSurface(view.get(), bounds, "MagicDesk Wayland shell fixture"));
                        lease.get().ready().thenCompose(ignored -> {
                            var stale = lease.get().present(bounds, new HostedShellFrame(viewport, false, java.util.List.of()));
                            var current = lease.get().present(bounds, new HostedShellFrame(viewport, actual.inputComplete(), actual.input()));
                            if (!stale.isCompletedExceptionally()) throw new IllegalStateException("Replaced receipt remained pending");
                            return current;
                        }).whenComplete((ignored, error) -> {
                            if (error == null) shown.complete(null); else shown.completeExceptionally(error);
                        });
                    } catch (Exception error) { shown.completeExceptionally(error); }
                });
                // EVENT_WAIT: frame/input admission; deadline fails the fixture, never delays its clicks.
                try { shown.get(15, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException error) {
                    var detail = new AtomicReference<String>();
                    runOnMainSync(() -> {
                        try {
                            var field = HostedShellSurfaceView.class.getDeclaredField("admission");
                            field.setAccessible(true);
                            var state = (ShellFrameAdmission) field.get(view.get());
                            detail.set("phase=" + state.phase() + " generation=" + state.generation()
                                    + " size=" + view.get().getWidth() + "x" + view.get().getHeight()
                                    + " attached=" + view.get().isAttachedToWindow());
                        } catch (Exception reflection) { detail.set(reflection.toString()); }
                    });
                    throw new IOException("Chrome presentation receipt timed out: " + detail.get(), error);
                }
                var movedBounds = new ShellBounds(340, 218, 1060, 578);
                for (var placement : java.util.List.of(new ShellBounds(380, 218, 980, 518), movedBounds)) {
                    var moved = new CompletableFuture<Void>();
                    runOnMainSync(() -> {
                        var actual = WaylandShellBinding.frame(binding.get().geometry(surface));
                        lease.get().present(placement, new HostedShellFrame(new ShellBounds(-8, -6, 72, 34),
                                actual.inputComplete(), actual.input())).whenComplete((ignored, error) -> {
                            if (error == null) moved.complete(null); else moved.completeExceptionally(error);
                        });
                    });
                    // EVENT_WAIT: moved/resized window admission, not an animation settling pause.
                    moved.get(15, TimeUnit.SECONDS);
                }
                var copied = new CompletableFuture<Void>();
                runOnMainSync(() -> {
                    var bitmap = android.graphics.Bitmap.createBitmap(80, 40, android.graphics.Bitmap.Config.ARGB_8888);
                    var content = (android.view.SurfaceView) view.get().getChildAt(0);
                    android.view.PixelCopy.request(content, bitmap, status -> {
                        try {
                            if (status != android.view.PixelCopy.SUCCESS || !view.get().inputReady()
                                    || android.graphics.Color.alpha(bitmap.getPixel(0, 0)) != 0
                                    || Math.abs(android.graphics.Color.alpha(bitmap.getPixel(12, 10)) - 128) > 1)
                                throw new IOException("Chrome frame pixels/admission mismatch: " + status);
                            copied.complete(null);
                        } catch (Exception error) { copied.completeExceptionally(error); }
                        finally { bitmap.recycle(); }
                    }, new Handler(Looper.getMainLooper()));
                });
                // EVENT_WAIT: PixelCopy completion; missing pixels fail the fixture.
                copied.get(15, TimeUnit.SECONDS);
                int y = movedBounds.top() + movedBounds.height() * 2 / 5;
                if (!ShellAccess.injectPointerClickAt(chromeDisplay, movedBounds.left() + movedBounds.width() * 19 / 40, y, 1)
                        || !ShellAccess.injectPointerClickAt(chromeDisplay, movedBounds.left() + movedBounds.width() / 5, y, 1))
                    throw new IOException("Android pointer injection failed");
                // EVENT_WAIT: client reacts to its first button pair; no retry or synthetic settling delay.
                interactive.get(15, TimeUnit.SECONDS);
                if (downs.get() != 1) throw new IOException("Input hole captured Android touch: " + downs.get());
            } finally {
                runOnMainSync(() -> {
                    if (lease.get() != null) lease.get().close();
                    else if (view.get() != null) view.get().close();
                });
            }
        }

        @Override public void close() {
            runOnMainSync(() -> { if (binding.get() != null) binding.get().close(); });
            images.close();
        }
    }

    private static void complete(CompletableFuture<Void> future, int code, String output, Throwable error) {
        if (error != null) future.completeExceptionally(error);
        else if (code != 0) future.completeExceptionally(new IOException("Exit " + code + ": " + output));
        else future.complete(null);
    }

    private static String q(String value) { return ShellCommandLine.quote(value); }
}
