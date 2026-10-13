package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Dispatcher-confined policy state; Android submission and task ownership live in the host. */
final class WindowLayoutSession {
    private static final WindowLayoutPolicy RELEASE_POLICY = new FloatingWindowLayoutPolicy();

    // Mutations are dispatcher-confined; input adapters may read the immutable policy snapshot.
    private volatile WindowLayoutPolicy mPolicy;
    private WindowLayoutPolicy.Workspace mLastObservation;

    WindowLayoutSession(WindowLayoutPolicy policy) {
        mPolicy = Objects.requireNonNull(policy);
    }

    WindowLayoutPolicy policy() { return mPolicy; }

    boolean observesWorkspace() { return mPolicy.observesWorkspace(); }

    boolean acceptObservation(WindowLayoutPolicy.Workspace workspace) {
        if (workspace.equals(mLastObservation)) return false;
        // Consume even a rejected observation; only new state should trigger planning again.
        mLastObservation = workspace;
        return true;
    }

    void clearObservation() { mLastObservation = null; }

    WindowLayoutPolicy.Plan plan(WindowLayoutPolicy.Workspace workspace,
            WindowLayoutPolicy.Request request) {
        WindowLayoutPolicy policy = request.event() == WindowLayoutPolicy.Event.RELEASE
                ? RELEASE_POLICY : mPolicy;
        WindowLayoutPolicy.Plan plan = policy.plan(workspace, request);
        plan.validate(workspace);
        return plan;
    }

    // The executor calls this only after checking operation validity and submission success.
    void acknowledge(WindowLayoutPolicy.Plan plan) {
        if (plan.continuation() != null) mPolicy = plan.continuation();
    }

    void replacePolicy(WindowLayoutPolicy policy) {
        mPolicy = Objects.requireNonNull(policy);
        clearObservation();
    }
}
