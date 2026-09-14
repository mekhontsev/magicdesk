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
        final List<DesktopDisplayInfo> history = new ArrayList<>();
        final List<BuiltInWindowLauncher.Callback> completions = new ArrayList<>();
        volatile DesktopDisplayInfo source;
        Listener listener;
        volatile boolean fullscreen;
        volatile boolean closed;
        volatile boolean ready;
        boolean visible;
        DisplayInputRequests.Request inputRequest;
        volatile String error = "";
        long bindingGeneration;
        Change change;
        Session(DesktopDisplayInfo source, DesktopDisplayInfo output, boolean fullscreen) {
            this.source = source;
            this.output = output;
            this.fullscreen = fullscreen;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
    private DisplayPresentations() { }

    /** Present on an output without Viewer controls, including after parking. */
    static void showOn(Context context, int sourceId, int outputId,
            BuiltInWindowLauncher.Callback callback) {
        open(context, sourceId, outputId, true, callback);
    }

    static void open(Context context, int sourceId, int outputId, boolean fullscreen,
            BuiltInWindowLauncher.Callback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo source = requireSource(sourceId, null);
                final DesktopDisplayInfo output = DesktopDisplayCatalog.require(outputId, null);
                MAIN.post(() -> {
                    try {
                        final Session existing = forOutput(output.id);
                        if (existing != null) {
                            if (!existing.output.uniqueId.equals(output.uniqueId)) {
                                throw new IllegalStateException("viewer output identity changed");
                            }
                            setFullscreen(existing, fullscreen);
                            if (existing.listener == null) {
                                selectForOpen(existing, source, callback);
                            } else existing.listener.show(error -> {
                                if (error != null) callback.onComplete(error);
                                else selectForOpen(existing, source, callback);
                            });
                            return;
                        }
                        validate(source, output, null);
                        final Session session = new Session(source, output, fullscreen);
                        session.completions.add(callback);
                        synchronized (SESSIONS) { SESSIONS.put(session.id, session); }
                        ToolApplications.open(context, DisplayViewerActivity.createIntent(context, session.id),
                                ToolLaunchTarget.resolve("auto", output.id,
                                        DesktopRuntimeBridge.workspaceDisplayIds()), output.uniqueId, error -> {
                                    if (error != null) { failed(session, error); park(session); }
                                });
                    } catch (RuntimeException error) { callback.onComplete(error); }
                });
            } catch (Exception error) { MAIN.post(() -> callback.onComplete(error)); }
        });
    }

    static Session find(String id) { synchronized (SESSIONS) { return SESSIONS.get(id); } }

    private static void selectForOpen(Session session, DesktopDisplayInfo source,
            BuiltInWindowLauncher.Callback callback) {
        select(session, source.id, source.uniqueId, error -> {
            if (error != null || session.ready) callback.onComplete(error);
            else session.completions.add(callback);
        });
    }

    static org.json.JSONArray snapshot() throws org.json.JSONException {
        final org.json.JSONArray result = new org.json.JSONArray();
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                result.put(new org.json.JSONObject().put("id", session.id)
                        .put("sourceDisplayId", session.source.id).put("sourceUniqueId", session.source.uniqueId)
                        .put("outputDisplayId", session.output.id).put("outputUniqueId", session.output.uniqueId)
                        .put("mode", DisplayPresentationMode.forSource(session.source).id)
                        .put("ready", session.ready).put("fullscreen", session.fullscreen)
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

    private static void select(Session session, int sourceId, String uniqueId,
            BuiltInWindowLauncher.Callback completion) {
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo source = requireSource(sourceId, uniqueId);
                DesktopDisplayCatalog.require(session.output.id, session.output.uniqueId);
                MAIN.post(() -> {
                    if (session.closed) { completion.onComplete(new IllegalStateException("viewer was parked")); return; }
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
                        final Session other = forSource(source.id);
                        final Map<Session, DesktopDisplayInfo> next = new LinkedHashMap<>();
                        next.put(session, source);
                        if (other != null && other != session) next.put(other, session.source);
                        final Change change = new Change(next);
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
                if (!session.closed && session.output.id == outputId) return session;
            }
        }
        return null;
    }

    static Session forSource(int sourceId) {
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (!session.closed && session.source.id == sourceId) return session;
            }
        }
        return null;
    }

    static void previousForInput() {
        MAIN.post(() -> {
            final Session session = forSource(MagicDeskRuntime.inputDisplayId());
            if (session != null) previous(session);
        });
    }

    static void park(Session session) {
        park(session, error -> {
            if (error != null) CompatibilityDiagnostics.record("DISPLAY-VIEWER-002",
                    "Could not park viewer", ShellAccess.usefulMessage(error));
        });
    }

    static void park(Session session, BuiltInWindowLauncher.Callback completion) {
        if (session.closed) { completion.onComplete(null); return; }
        if (session.change != null) session.change.fail(new IllegalStateException("viewer was parked"));
        if (session.inputRequest != null) session.inputRequest.cancel();
        session.closed = true;
        session.ready = false;
        synchronized (SESSIONS) { SESSIONS.remove(session.id); }
        complete(session, new IllegalStateException("viewer was parked"));
        final int[] pending = {2};
        final Throwable[] failure = {null};
        final BuiltInWindowLauncher.Callback released = error -> {
            if (error != null) failure[0] = error;
            if (--pending[0] == 0) completion.onComplete(failure[0]);
        };
        MagicDeskRuntime.releaseSelectedInput(session.source.id, result -> {
            released.onComplete(result.success ? null : new IllegalStateException(result.message));
        });
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
        else { session.error = ""; notify(session); complete(session, null); }
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
        if (session.closed || !session.ready || session.change != null) {
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
                if (session.source.id == selected && selected != pair.getValue().id) {
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
        final Map<Integer, Integer> edges = new LinkedHashMap<>();
        final List<DesktopDisplayInfo> endpoints = new ArrayList<>();
        final java.util.Set<String> sources = new java.util.HashSet<>();
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (session.closed) continue;
                final DesktopDisplayInfo source = replacements.getOrDefault(session, reservedSource(session));
                if (!sources.add(source.uniqueId)) {
                    throw new IllegalStateException("source already has a viewer or pending binding");
                }
                edges.put(session.output.id, source.id);
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

    private static void validate(DesktopDisplayInfo source, DesktopDisplayInfo output, Session replacing) {
        if (source.id == output.id) throw new IllegalArgumentException("source and output must differ");
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (session == replacing || session.closed) continue;
                if (session.source.uniqueId.equals(source.uniqueId)
                        || reservedSource(session).uniqueId.equals(source.uniqueId)) {
                    throw new IllegalStateException("source already has a viewer; park it first");
                }
                if (session.output.uniqueId.equals(output.uniqueId)) {
                    throw new IllegalStateException("output already has a viewer; select its source there");
                }
            }
            final Map<Integer, Integer> edges = new LinkedHashMap<>();
            final List<DesktopDisplayInfo> endpoints = new ArrayList<>(List.of(source, output));
            for (Session session : SESSIONS.values()) {
                if (session != replacing && !session.closed) {
                    edges.put(session.output.id, reservedSource(session).id);
                    endpoints.add(session.output);
                    endpoints.add(reservedSource(session));
                }
            }
            edges.put(output.id, source.id);
            DisplayPresentationGraph.requireAcyclic(edges, endpoints.stream()
                    .filter(d -> "overlay".equals(d.source)).map(d -> d.id).toList());
        }
    }
}
