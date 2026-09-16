package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Presentation lifetime is independent of both the source display and Desktop. */
final class DisplayPresentations {
    interface Listener {
        void changed();
        void show(BuiltInWindowLauncher.Callback completion);
        void detach(BuiltInWindowLauncher.Callback completion);
    }

    static final class Session {
        final String id = UUID.randomUUID().toString();
        final DesktopDisplayInfo output;
        final boolean outputAttachment;
        final List<DesktopDisplayInfo> history = new ArrayList<>();
        final List<BuiltInWindowLauncher.Callback> completions = new ArrayList<>();
        volatile DesktopDisplayInfo source;
        volatile int taskId = -1;
        Listener listener;
        volatile boolean fullscreen;
        volatile boolean closed;
        volatile boolean ready;
        boolean visible;
        DisplayInputRequests.Request inputRequest;
        volatile String error = "";
        long bindingGeneration;
        Change change;
        Session(DesktopDisplayInfo source, DesktopDisplayInfo output, boolean fullscreen, boolean outputAttachment) {
            this.source = source;
            this.output = output;
            this.fullscreen = fullscreen;
            this.outputAttachment = outputAttachment;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
    private static final Map<String, Session> LAST_OUTPUTS = new LinkedHashMap<>();
    private DisplayPresentations() { }

    /** Launch options for the built-in Viewer; neither path acquires input or Desktop. */
    static void openViewer(Context context, ToolLaunchTarget target, String outputUniqueId,
            Integer sourceId, boolean outputAttachment, boolean fullscreen,
            BuiltInWindowLauncher.Callback callback) {
        if (sourceId != null && sourceId < 0) throw new IllegalArgumentException("invalid source display");
        if (sourceId == null && (outputAttachment || fullscreen)) {
            throw new IllegalArgumentException("output mode and immersive viewing require sourceDisplayId");
        }
        if (outputAttachment) {
            if (target.desktop) throw new IllegalArgumentException("output mode requires independent display placement");
            attach(context, sourceId, target.displayId, fullscreen, null, outputUniqueId, null, callback);
            return;
        }
        if (sourceId == null) {
            ToolApplications.open(context, DisplayViewerActivity.createIntent(context), target, outputUniqueId, callback);
            return;
        }
        TaskCommandQueue.execute(() -> {
            try {
                target.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
                final DesktopDisplayInfo source = requireSource(sourceId, null);
                final DesktopDisplayInfo output = DesktopDisplayCatalog.require(target.displayId, outputUniqueId);
                MAIN.post(() -> {
                    try {
                        final Session session = mirror(source, output);
                        session.fullscreen = fullscreen;
                        session.completions.add(callback);
                        launchViewer(context, session, target);
                    } catch (RuntimeException error) { callback.onComplete(error); }
                });
            } catch (Exception error) { MAIN.post(() -> callback.onComplete(error)); }
        });
    }

    private static void launchViewer(Context context, Session session, ToolLaunchTarget target) {
        try {
            ToolApplications.open(context, DisplayViewerActivity.createIntent(context, session.id),
                    target, session.output.uniqueId, error -> {
                        if (error != null) { failed(session, error); detach(session); }
                    });
        } catch (RuntimeException error) { failed(session, error); detach(session); }
    }

    /** Attach a fullscreen output to the source, whose lifetime remains independent. */
    static void attachOutput(Context context, DesktopDisplayInfo source, DesktopDisplayInfo output,
            BuiltInWindowLauncher.Callback callback) {
        attach(context, source.id, output.id, true, source.uniqueId, output.uniqueId, null, callback);
    }

    /** The encompassing switch operation owns input and its rollback, not the binding change. */
    static void attachForSwitch(Context context, DesktopDisplayInfo source, DesktopDisplayInfo output,
            BuiltInWindowLauncher.Callback callback) {
        final Session previous = forOutput(output.id);
        attach(context, source.id, output.id, true, source.uniqueId, output.uniqueId,
                new AttachmentExpectation(previous, previous == null ? source.uniqueId : previous.source.uniqueId),
                false, callback);
    }

    static void attachForDesktop(Context context, DesktopDisplayInfo source, DesktopDisplayInfo output,
            Session previous, BuiltInWindowLauncher.Callback callback) {
        attach(context, source.id, output.id, true, source.uniqueId, output.uniqueId,
                new AttachmentExpectation(previous, source.uniqueId), callback);
    }

    private record AttachmentExpectation(Session session, String sourceUniqueId) {
        void verify(Session current) {
            if (current != session || current != null && (current.change != null
                    || !current.source.uniqueId.equals(sourceUniqueId))) {
                throw new IllegalStateException("Output attachment changed during startup");
            }
        }
    }

    private static void attach(Context context, int sourceId, int outputId, boolean fullscreen,
            String sourceUniqueId, String outputUniqueId, AttachmentExpectation expected,
            BuiltInWindowLauncher.Callback callback) {
        attach(context, sourceId, outputId, fullscreen, sourceUniqueId, outputUniqueId, expected, true, callback);
    }

    private static void attach(Context context, int sourceId, int outputId, boolean fullscreen,
            String sourceUniqueId, String outputUniqueId, AttachmentExpectation expected, boolean followInput,
            BuiltInWindowLauncher.Callback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo source = requireSource(sourceId, sourceUniqueId);
                final DesktopDisplayInfo output = DesktopDisplayCatalog.require(outputId, outputUniqueId);
                source.requirePresentationOutput(output);
                MAIN.post(() -> {
                    try {
                        final Session existing = forOutput(output.id);
                        if (expected != null) expected.verify(existing);
                        if (existing != null) {
                            if (!existing.output.uniqueId.equals(output.uniqueId)) {
                                throw new IllegalStateException("viewer output identity changed");
                            }
                            setFullscreen(existing, fullscreen);
                            if (existing.listener == null) {
                                selectForAttachment(existing, source, followInput, callback);
                            } else existing.listener.show(error -> {
                                if (error != null) callback.onComplete(error);
                                else {
                                    try {
                                        if (expected != null) expected.verify(forOutput(output.id));
                                        selectForAttachment(existing, source, followInput, callback);
                                    } catch (RuntimeException failure) { callback.onComplete(failure); }
                                }
                            });
                            return;
                        }
                        validate(source, output, true);
                        final Session session = new Session(source, output, fullscreen, true);
                        session.completions.add(callback);
                        synchronized (SESSIONS) { SESSIONS.put(session.id, session); }
                        // Presentation belongs to Android, not the output's optional Desktop.
                        launchViewer(context, session,
                                ToolLaunchTarget.resolve("display", output.id, java.util.Set.of()));
                    } catch (RuntimeException error) { callback.onComplete(error); }
                });
            } catch (Exception error) { MAIN.post(() -> callback.onComplete(error)); }
        });
    }

    static Session find(String id) { synchronized (SESSIONS) { return SESSIONS.get(id); } }

    static Session forTask(int taskId) {
        if (taskId < 0) return null;
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (!session.closed && session.taskId == taskId) return session;
            }
        }
        return null;
    }

    /** Register an already placed application window; do not launch or redirect an output. */
    static Session mirror(DesktopDisplayInfo source, DesktopDisplayInfo output) {
        validate(source, output, false);
        final Session session = new Session(source, output, false, false);
        synchronized (SESSIONS) { SESSIONS.put(session.id, session); }
        return session;
    }

    static boolean canAttachOutput(DesktopDisplayInfo source, DesktopDisplayInfo output) {
        try { validate(source, output, true); return true; }
        catch (IllegalArgumentException | IllegalStateException unavailable) { return false; }
    }

    /** Return through the source's current or last output, never creating a Viewer. */
    static void showForSource(int sourceId, BuiltInWindowLauncher.Callback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo source = requireSource(sourceId, null);
                MAIN.post(() -> showSource(source, callback));
            } catch (Exception error) { MAIN.post(() -> callback.onComplete(error)); }
        });
    }

    private static Session returnOutput(DesktopDisplayInfo source) {
        synchronized (SESSIONS) {
            final Session active = forSource(source.id);
            return active != null && active.source.uniqueId.equals(source.uniqueId)
                    ? active : LAST_OUTPUTS.get(source.uniqueId);
        }
    }

    private static void rememberOutput(Session session) {
        if (!session.outputAttachment) return;
        synchronized (SESSIONS) { LAST_OUTPUTS.put(session.source.uniqueId, session); }
    }

    private static void showSource(DesktopDisplayInfo source, BuiltInWindowLauncher.Callback callback) {
        try {
            final Session session = returnOutput(source);
            if (session == null) { callback.onComplete(null); return; }
            if (session.closed || session.listener == null || session.change != null) {
                callback.onComplete(new IllegalStateException("viewer is not available for presentation"));
                return;
            }
            source.requirePresentationOutput(session.output);
            final long binding = session.bindingGeneration;
            final BuiltInWindowLauncher.Callback checked = error -> callback.onComplete(
                    error != null ? error : session.closed || !session.source.uniqueId.equals(source.uniqueId)
                            ? new IllegalStateException("viewer binding changed while showing it") : null);
            session.listener.show(error -> {
                if (error != null || session.closed || session.bindingGeneration != binding || session.change != null) {
                    callback.onComplete(error != null ? error
                            : new IllegalStateException("viewer binding changed while showing it"));
                    return;
                }
                // moveToFront is only a request. Resume/Surface attachment owns readiness.
                selectForAttachment(session, source, checked);
            });
        } catch (RuntimeException error) { callback.onComplete(error); }
    }

    private static void selectForAttachment(Session session, DesktopDisplayInfo source,
            BuiltInWindowLauncher.Callback callback) {
        selectForAttachment(session, source, true, callback);
    }

    private static void selectForAttachment(Session session, DesktopDisplayInfo source, boolean followInput,
            BuiltInWindowLauncher.Callback callback) {
        select(session, source.id, source.uniqueId, followInput, error -> {
            if (error != null || session.ready) callback.onComplete(error);
            else session.completions.add(callback);
        });
    }

    static org.json.JSONArray snapshot() throws org.json.JSONException {
        final org.json.JSONArray result = new org.json.JSONArray();
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                result.put(new org.json.JSONObject().put("id", session.id)
                        .put("taskId", session.taskId)
                        .put("sourceDisplayId", session.source.id).put("sourceUniqueId", session.source.uniqueId)
                        .put("outputDisplayId", session.output.id).put("outputUniqueId", session.output.uniqueId)
                        .put("mode", session.outputAttachment ? "output" : "mirror")
                        .put("transport", session.outputAttachment
                                ? DisplayPresentationMode.forSource(session.source).id : DisplayPresentationMode.MIRROR.id)
                        .put("protectedContent", session.source.protectedContent())
                        .put("ready", session.ready).put("immersive", session.fullscreen)
                        .put("error", session.error));
            }
        }
        return result;
    }

    static void select(Session session, int sourceId) {
        select(session, sourceId, error -> reportSelection(session, error));
    }

    static void select(Session session, int sourceId, BuiltInWindowLauncher.Callback completion) {
        select(session, sourceId, null, completion);
    }

    static void select(Session session, int sourceId, String uniqueId,
            BuiltInWindowLauncher.Callback completion) {
        select(session, sourceId, uniqueId, true, completion);
    }

    private static void select(Session session, int sourceId, String uniqueId, boolean followInput,
            BuiltInWindowLauncher.Callback completion) {
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo source = requireSource(sourceId, uniqueId);
                source.requirePresentationOutput(DesktopDisplayCatalog.require(session.output.id, session.output.uniqueId));
                MAIN.post(() -> {
                    if (session.closed) { completion.onComplete(new IllegalStateException("viewer was detached")); return; }
                    try {
                        if (session.change != null) {
                            if (!source.uniqueId.equals(session.change.next.get(session).uniqueId)) {
                                throw new IllegalStateException("viewer source change is in progress");
                            }
                            session.completions.add(completion);
                            return;
                        }
                        if (source.uniqueId.equals(session.source.uniqueId)) {
                            if (session.ready || (!session.visible && session.listener != null
                                    && session.error.isEmpty())) {
                                session.error = "";
                                notify(session);
                                completion.onComplete(null);
                                return;
                            }
                            if (session.error.isEmpty()) {
                                session.completions.add(completion);
                                return;
                            }
                        }
                        // Retrying a failed binding uses the same serialized
                        // detach/attach transaction as changing its source.
                        final Session other = session.outputAttachment ? forSource(source.id) : null;
                        final Map<Session, DesktopDisplayInfo> next = new LinkedHashMap<>();
                        next.put(session, source);
                        if (other != null && other != session) next.put(other, session.source);
                        final Change change = new Change(next, followInput);
                        session.completions.add(completion);
                        change.start();
                    } catch (RuntimeException error) { completion.onComplete(error); }
                });
            } catch (Exception error) { MAIN.post(() -> completion.onComplete(error)); }
        });
    }

    static void previous(Session session) {
        previous(session, error -> reportSelection(session, error));
    }

    static void previous(Session session, BuiltInWindowLauncher.Callback completion) {
        if (!session.history.isEmpty()) {
            final DesktopDisplayInfo previous = session.history.get(0);
            select(session, previous.id, previous.uniqueId, completion);
        } else completion.onComplete(new IllegalStateException("viewer has no previous source"));
    }

    private static void reportSelection(Session session, Throwable error) {
        if (error == null || session.closed) return;
        session.error = ShellAccess.usefulMessage(error);
        notify(session);
    }

    static Session forOutput(int outputId) {
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (!session.closed && session.outputAttachment && session.output.id == outputId) return session;
            }
        }
        return null;
    }

    static Session forSource(int sourceId) {
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (!session.closed && session.outputAttachment && session.source.id == sourceId) return session;
            }
        }
        return null;
    }

    static boolean canSwitchOutput(DesktopDisplayInfo source, DesktopDisplayInfo output) {
        final Session session = forOutput(output.id);
        if (session != null && (session.closed || session.change != null || session.listener == null)) return false;
        if (source.uniqueId.equals(output.uniqueId)) return true;
        try {
            if (session == null) validate(source, output, true);
            else {
                final Map<Session, DesktopDisplayInfo> next = new LinkedHashMap<>();
                next.put(session, source);
                final Session other = forSource(source.id);
                if (other != null && other != session) {
                    if (other.change != null || other.listener == null) return false;
                    next.put(other, session.source);
                }
                validateGraph(next);
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) { return false; }
    }

    static void detach(Session session) {
        detach(session, error -> {
            if (error != null) CompatibilityDiagnostics.record("DISPLAY-VIEWER-002",
                    "Could not detach viewer", ShellAccess.usefulMessage(error));
        });
    }

    static void detach(Session session, BuiltInWindowLauncher.Callback completion) {
        if (session.closed) { completion.onComplete(null); return; }
        if (session.change != null) session.change.fail(new IllegalStateException("viewer was detached"));
        if (session.inputRequest != null) session.inputRequest.cancel();
        session.closed = true;
        session.ready = false;
        synchronized (SESSIONS) {
            SESSIONS.remove(session.id);
            LAST_OUTPUTS.values().removeIf(output -> output == session);
        }
        complete(session, new IllegalStateException("viewer was detached"));
        final int[] pending = {2};
        final Throwable[] failure = {null};
        final BuiltInWindowLauncher.Callback released = error -> {
            if (error != null) failure[0] = error;
            if (--pending[0] == 0) completion.onComplete(failure[0]);
        };
        if (session.outputAttachment) MagicDeskRuntime.releaseSelectedInput(session.source.id, result -> {
            released.onComplete(result.success ? null : new IllegalStateException(result.message));
        });
        else released.onComplete(null);
        if (session.listener != null) session.listener.detach(released);
        else released.onComplete(null);
        notify(session);
    }

    static void failed(Session session, Throwable error) {
        if (session.closed) return;
        if (session.change != null) { session.change.fail(error); return; }
        session.ready = false;
        session.error = ShellAccess.usefulMessage(error);
        complete(session, error);
        notify(session);
    }

    static void notify(Session session) {
        DesktopAutomationEventJournal.record("display", "presentation_changed", session.error.isEmpty(),
                "viewer=" + session.id + " source=" + session.source.id + " output=" + session.output.id
                        + " ready=" + session.ready + " closed=" + session.closed);
        if (session.listener != null) session.listener.changed();
    }

    static void attached(Session session) {
        if (session.closed) return;
        session.ready = true;
        if (session.change != null) session.change.attached(session);
        else { session.error = ""; rememberOutput(session); notify(session); complete(session, null); }
    }

    static void setFullscreen(Session session, boolean fullscreen) {
        session.fullscreen = fullscreen;
        notify(session);
    }

    static void visibilityChanged(Session session, boolean visible) {
        if (session.closed) return;
        session.visible = visible;
        if (!visible && session.change != null) session.change.suspended(session);
    }

    static void detached(Session session) {
        if (session.closed) return;
        session.ready = false;
        notify(session);
    }

    static void controlInput(Session session, TaskRepository.ActionCallback callback) {
        if (!session.outputAttachment || session.closed || !session.ready || session.change != null) {
            callback.onComplete(new TaskRepository.ActionResult(false, "viewer is not ready"));
            return;
        }
        session.inputRequest = MagicDeskRuntime.selectInputDisplay(session.source.id, callback);
    }

    static void updateGeometry(Session session, DesktopDisplayInfo source) {
        if (session.closed || session.change != null
                || !session.source.uniqueId.equals(source.uniqueId)) return;
        if (session.source.width == source.width && session.source.height == source.height
                && session.source.densityDpi == source.densityDpi) return;
        new Change(Map.of(session, source)).start();
    }

    private static void complete(Session session, Throwable error) {
        final var completions = new ArrayList<>(session.completions);
        session.completions.clear();
        for (var completion : completions) completion.onComplete(error);
    }

    /** Cancels old viewer input before rebinding; direct input follows its output only when acquired. */
    private static final class Change {
        final Map<Session, DesktopDisplayInfo> next;
        final java.util.Set<Session> waiting;
        final int inputSource;
        final int previousInput = MagicDeskRuntime.inputDisplayId();
        final long initialInputVersion = MagicDeskRuntime.inputSelectionVersion();
        long releasedInputVersion = -1;
        DisplayInputRequests.Request inputRequest;
        Session inputViewer;
        int detached;
        boolean finished;
        boolean published;
        boolean inputPending;
        Change(Map<Session, DesktopDisplayInfo> next) {
            this(next, true);
        }
        Change(Map<Session, DesktopDisplayInfo> next, boolean followInput) {
            this.next = next;
            waiting = new java.util.HashSet<>();
            int input = -1;
            final int selected = MagicDeskRuntime.inputDisplayId();
            for (var pair : next.entrySet()) {
                final Session session = pair.getKey();
                if (session.change != null || session.listener == null || session.closed) {
                    throw new IllegalStateException("viewer is not ready for a source change");
                }
                if (session.output.id == pair.getValue().id) {
                    throw new IllegalArgumentException("source and output must differ");
                }
                pair.getValue().requirePresentationOutput(session.output);
                if (followInput && session.outputAttachment && session.source.id == selected && selected != pair.getValue().id) {
                    input = pair.getValue().id;
                    inputViewer = session;
                }
            }
            inputSource = input;
            validateGraph(next);
        }
        void start() {
            for (Session session : next.keySet()) {
                session.change = this;
                session.ready = false;
                session.error = "";
                DisplayPresentations.notify(session);
            }
            for (Session session : next.keySet()) {
                session.listener.detach(error -> {
                    if (finished) return;
                    if (error != null) { fail(error); return; }
                    if (++detached == next.size()) {
                        if (inputSource < 0 || MagicDeskRuntime.inputSelectionVersion() != initialInputVersion
                                || MagicDeskRuntime.inputDisplayId() != previousInput) publish();
                        else {
                            inputRequest = MagicDeskRuntime.selectInputDisplay(-1, initialInputVersion, result -> MAIN.post(() -> {
                                if (result.success) publish();
                                else fail(new IllegalStateException(result.message));
                            }));
                            releasedInputVersion = inputRequest == null ? -1 : inputRequest.version;
                        }
                    }
                });
            }
        }
        void publish() {
            if (finished) return;
            published = true;
            for (var pair : next.entrySet()) {
                final Session session = pair.getKey();
                if (session.visible) waiting.add(session);
                if (!session.source.uniqueId.equals(pair.getValue().uniqueId)) {
                    session.history.removeIf(d -> d.uniqueId.equals(pair.getValue().uniqueId)
                            || d.uniqueId.equals(session.source.uniqueId));
                    session.history.add(0, session.source);
                }
                session.source = pair.getValue();
                session.bindingGeneration++;
            }
            for (Session session : next.keySet()) DisplayPresentations.notify(session);
            finishBindings();
        }
        void attached(Session session) {
            waiting.remove(session);
            finishBindings();
        }
        void suspended(Session session) {
            waiting.remove(session);
            if (inputPending && session == inputViewer) {
                if (inputRequest != null) inputRequest.cancel();
                complete();
                return;
            }
            finishBindings();
        }
        void finishBindings() {
            if (finished || inputPending || !published || !waiting.isEmpty()) return;
            // Bindings commit even when a peer has no visible Surface. Only
            // attached outputs can reacquire input; resuming creates its lease.
            // A later explicit input choice wins, even if it selected the same
            // display again while these Surfaces were being replaced.
            if (inputViewer == null || !inputViewer.visible || !inputViewer.ready
                    || releasedInputVersion < 0 || MagicDeskRuntime.inputSelectionVersion() != releasedInputVersion
                    || MagicDeskRuntime.inputDisplayId() != -1) complete();
            else {
                inputPending = true;
                final long expected = releasedInputVersion;
                releasedInputVersion = -1;
                inputRequest = MagicDeskRuntime.selectInputDisplay(inputSource, expected, result -> MAIN.post(() -> {
                    if (result.success) complete(); else fail(new IllegalStateException(result.message));
                }));
            }
        }
        void complete() {
            if (finished) return;
            finished = true;
            for (Session session : next.keySet()) {
                session.change = null;
                session.error = "";
                rememberOutput(session);
                DisplayPresentations.notify(session);
                DisplayPresentations.complete(session, null);
            }
        }
        void fail(Throwable error) {
            if (finished) return;
            finished = true;
            if (inputRequest != null) inputRequest.cancel();
            for (Session session : next.keySet()) {
                session.change = null;
                session.ready = false;
                session.error = ShellAccess.usefulMessage(error);
                DisplayPresentations.notify(session);
                DisplayPresentations.complete(session, error);
            }
        }
    }

    private static void validateGraph(Map<Session, DesktopDisplayInfo> replacements) {
        final List<DisplayPresentationGraph.Edge> edges = new ArrayList<>();
        final List<DesktopDisplayInfo> endpoints = new ArrayList<>();
        final java.util.Set<String> sources = new java.util.HashSet<>();
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (session.closed) continue;
                final DesktopDisplayInfo source = replacements.getOrDefault(session, reservedSource(session));
                source.requirePresentationOutput(session.output);
                if (session.outputAttachment && !sources.add(source.uniqueId)) {
                    throw new IllegalStateException("source already has a viewer or pending binding");
                }
                edges.add(new DisplayPresentationGraph.Edge(session.output.id, source.id));
                endpoints.add(source);
                endpoints.add(session.output);
            }
        }
        DisplayPresentationGraph.requireAcyclic(edges, endpoints.stream()
                .filter(d -> "overlay".equals(d.source)).map(d -> d.id).toList());
    }

    private static DesktopDisplayInfo reservedSource(Session session) {
        return session.change == null ? session.source : session.change.next.get(session);
    }

    static DesktopDisplayInfo requireSource(int displayId, String uniqueId) throws java.io.IOException {
        return DesktopDisplayCatalog.require(displayId, uniqueId);
    }

    private static void validate(DesktopDisplayInfo source, DesktopDisplayInfo output, boolean outputAttachment) {
        if (source.id == output.id) throw new IllegalArgumentException("source and output must differ");
        source.requirePresentationOutput(output);
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (!outputAttachment || !session.outputAttachment || session.closed) continue;
                if (session.source.uniqueId.equals(source.uniqueId)
                        || reservedSource(session).uniqueId.equals(source.uniqueId)) {
                    throw new IllegalStateException("source already has a viewer; detach it first");
                }
                if (session.output.uniqueId.equals(output.uniqueId)) {
                    throw new IllegalStateException("output already has a viewer; select its source there");
                }
            }
            final List<DisplayPresentationGraph.Edge> edges = new ArrayList<>();
            final List<DesktopDisplayInfo> endpoints = new ArrayList<>(List.of(source, output));
            for (Session session : SESSIONS.values()) {
                if (!session.closed) {
                    edges.add(new DisplayPresentationGraph.Edge(session.output.id, reservedSource(session).id));
                    endpoints.add(session.output);
                    endpoints.add(reservedSource(session));
                }
            }
            edges.add(new DisplayPresentationGraph.Edge(output.id, source.id));
            DisplayPresentationGraph.requireAcyclic(edges, endpoints.stream()
                    .filter(d -> "overlay".equals(d.source)).map(d -> d.id).toList());
        }
    }
}
