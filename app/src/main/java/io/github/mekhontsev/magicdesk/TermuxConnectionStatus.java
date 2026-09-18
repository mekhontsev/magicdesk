package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeoutException;

/** Process-local command observation, confined to the main thread; never a tool prerequisite. */
final class TermuxConnectionStatus {
    enum State { UNCHECKED, CHECKING, READY, FAILED, TIMED_OUT }
    record Snapshot(State state, String error) { }

    interface Probe {
        Runnable start(Context context, TermuxIntegration.Endpoint endpoint,
                TermuxIntegration.ResultCallback callback);
    }

    private record EndpointKey(String packageName, String service, int uid, String home, String error) {
        static EndpointKey of(TermuxIntegration.Endpoint endpoint) {
            return new EndpointKey(endpoint.packageName,
                    endpoint.service == null ? "" : endpoint.service.flattenToString(),
                    endpoint.uid, endpoint.homeDirectory, endpoint.error);
        }
    }

    private static final Snapshot UNCHECKED = new Snapshot(State.UNCHECKED, "");
    private static final TermuxConnectionStatus INSTANCE = new TermuxConnectionStatus((context, endpoint, callback) -> {
        final var registration = TermuxIntegration.checkConnection(context, endpoint, callback);
        return () -> TermuxCommandResultReceiver.cancel(registration);
    });

    private final Probe probe;
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private EndpointKey endpointKey;
    private Snapshot snapshot = UNCHECKED;
    private Runnable cancel;
    private long generation;

    TermuxConnectionStatus(Probe probe) { this.probe = probe; }

    static TermuxConnectionStatus get() { return INSTANCE; }

    static void initialize(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPostResumed(Activity activity) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    INSTANCE.check(app, TermuxIntegration.inspect(app), false);
                }
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    Snapshot current(TermuxIntegration.Endpoint endpoint) {
        return Objects.equals(endpointKey, EndpointKey.of(endpoint)) ? snapshot : UNCHECKED;
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
        listener.run();
    }

    void removeListener(Runnable listener) { listeners.remove(listener); }

    void check(Context context, TermuxIntegration.Endpoint endpoint, boolean retry) {
        final EndpointKey key = EndpointKey.of(endpoint);
        if (!Objects.equals(endpointKey, key)) {
            ++generation;
            if (cancel != null) cancel.run();
            cancel = null;
            endpointKey = key;
            snapshot = UNCHECKED;
        }
        if (!endpoint.available()) {
            notifyListeners();
            return;
        }
        if (snapshot.state() == State.CHECKING || (!retry && snapshot.state() != State.UNCHECKED)) return;
        final long request = ++generation;
        snapshot = new Snapshot(State.CHECKING, "");
        notifyListeners();
        try {
            final Runnable cancellation = probe.start(context, endpoint, (reply, error) -> complete(request, reply, error));
            if (generation == request && snapshot.state() == State.CHECKING) cancel = cancellation;
            else cancellation.run();
        } catch (RuntimeException error) {
            complete(request, null, error);
        }
    }

    private void complete(long request, TermuxIntegration.CommandResult reply, Throwable error) {
        if (request != generation || snapshot.state() != State.CHECKING) return;
        cancel = null;
        if (error instanceof TimeoutException) snapshot = new Snapshot(State.TIMED_OUT, "");
        else if (error != null) snapshot = new Snapshot(State.FAILED, ShellAccess.usefulMessage(error));
        else if (TermuxIntegration.connectionVerified(reply)) snapshot = new Snapshot(State.READY, "");
        else snapshot = new Snapshot(State.FAILED, reply != null && !reply.success() ? reply.usefulMessage() : "");
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }
}
