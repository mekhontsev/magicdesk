package io.github.mekhontsev.magicdesk;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Commits fullscreen-plane surfaces inside the standard task workspace. */
final class ShellDesktopSurfaceOrder {
    private static final long COMMIT_TIMEOUT_SECONDS = 2L;
    private boolean mHomeHost = true;
    private Object mService;
    private int mDisplayId = -1;
    private ShellDesktopTaskOwnership mOwnership;

    synchronized void configure(final Object service, final int displayId,
            final ShellDesktopTaskOwnership ownership, final boolean homeRole) {
        mService = service;
        mDisplayId = displayId;
        mOwnership = ownership;
        mHomeHost = homeRole;
    }

    synchronized void applyLayers(
            final Map<TaskDisplayAreaHandle, Integer> layers)
            throws ReflectiveOperationException {
        if (layers.isEmpty()) {
            return;
        }
        final int base = workspaceLayerBase(layers);
        final Class<?> surfaceClass = Class.forName("android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        try {
            for (final Map.Entry<TaskDisplayAreaHandle, Integer> entry
                    : layers.entrySet()) {
                transactionClass.getMethod("setLayer", surfaceClass, Integer.TYPE)
                        .invoke(transaction, entry.getKey().surfaceLeash(),
                                entry.getValue() > 0 ? base + entry.getValue()
                                        : entry.getValue());
            }
            applyCommitted(transactionClass, transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    private int workspaceLayerBase(final Map<TaskDisplayAreaHandle, Integer> layers)
            throws ReflectiveOperationException {
        // HOME is assigned layer zero regardless of hierarchy. A STANDARD
        // host shares native root ordering, including empty separators.
        if (mHomeHost || layers.values().stream().noneMatch(layer -> layer > 0)) return 0;
        final Set<Integer> areas = new LinkedHashSet<>();
        for (final TaskDisplayAreaHandle plane : layers.keySet()) areas.add(plane.featureId());
        return independentHostLayerBase(
                FrameworkTaskSnapshotSource.readRoots(mService, mDisplayId),
                areas, mOwnership.desktopHostTaskId());
    }

    static int independentHostLayerBase(final List<FrameworkTaskSnapshot> roots,
            final Set<Integer> planeAreas, final int hostTaskId) {
        final Set<Integer> siblings = new LinkedHashSet<>();
        for (final FrameworkTaskSnapshot root : roots) {
            if (planeAreas.contains(root.displayAreaFeatureId)) {
                siblings.add(-root.displayAreaFeatureId);
            } else if (root.displayAreaFeatureId
                    == TaskDisplayAreaHandle.Parent.DEFAULT_TASK_CONTAINER.featureId()) {
                siblings.add(root.taskId);
            }
        }
        final List<Integer> order = new ArrayList<>(siblings);
        final int host = order.indexOf(hostTaskId);
        if (host < 0) throw new IllegalStateException("desktop host is absent from root order");
        return order.size() - host - 1;
    }

    synchronized void setVisible(
            final Collection<TaskDisplayAreaHandle> planes,
            final boolean visible) throws ReflectiveOperationException {
        if (planes.isEmpty()) {
            return;
        }
        final Class<?> surfaceClass = Class.forName("android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        try {
            for (final TaskDisplayAreaHandle plane : planes) {
                transactionClass.getMethod(visible ? "show" : "hide", surfaceClass)
                        .invoke(transaction, plane.surfaceLeash());
            }
            applyCommitted(transactionClass, transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    private static void applyCommitted(
            final Class<?> transactionClass, final Object transaction)
            throws ReflectiveOperationException {
        ShellWindowTransitionExecutor.prepareSurfaceTransactions();
        final Class<?> listenerClass = Class.forName(
                "android.view.SurfaceControl$TransactionCommittedListener");
        final CountDownLatch committed = new CountDownLatch(1);
        final Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "onTransactionCommitted":
                            committed.countDown();
                            return null;
                        case "toString":
                            return "MagicDeskSurfaceCommitListener";
                        case "hashCode":
                            return Integer.valueOf(System.identityHashCode(proxy));
                        case "equals":
                            return Boolean.valueOf(arguments != null
                                    && arguments.length == 1 && proxy == arguments[0]);
                        default:
                            return null;
                    }
                });
        final Executor directExecutor = Runnable::run;
        transactionClass.getMethod("addTransactionCommittedListener",
                Executor.class, listenerClass).invoke(
                        transaction, directExecutor, listener);
        transactionClass.getMethod("apply").invoke(transaction);
        try {
            if (!committed.await(COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("desktop surface commit timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("desktop surface commit interrupted", error);
        }
    }
}
