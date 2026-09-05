package io.github.mekhontsev.magicdesk;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Commits organizer surface order for one desktop, including its chrome. */
final class ShellDesktopSurfaceOrder {
    private static final long COMMIT_TIMEOUT_SECONDS = 2L;
    private TaskDisplayAreaHandle mChrome;

    synchronized void attachChrome(final TaskDisplayAreaHandle chrome) {
        if (mChrome != null && mChrome != chrome) {
            throw new IllegalStateException("desktop chrome is already attached");
        }
        mChrome = chrome;
    }

    synchronized void detachChrome(final TaskDisplayAreaHandle chrome) {
        if (mChrome == chrome) {
            mChrome = null;
        }
    }

    synchronized void applyLayers(
            final Map<TaskDisplayAreaHandle, Integer> layers)
            throws ReflectiveOperationException {
        final Map<TaskDisplayAreaHandle, Integer> composed =
                composeLayers(layers, mChrome);
        if (composed.isEmpty()) {
            return;
        }
        final Class<?> surfaceClass = Class.forName("android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        try {
            for (final Map.Entry<TaskDisplayAreaHandle, Integer> entry
                    : composed.entrySet()) {
                transactionClass.getMethod("setLayer", surfaceClass, Integer.TYPE)
                        .invoke(transaction, entry.getKey().surfaceLeash(),
                                entry.getValue());
            }
            applyCommitted(transactionClass, transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    synchronized void setVisible(
            final Collection<TaskDisplayAreaHandle> planes,
            final boolean visible) throws ReflectiveOperationException {
        final Class<?> surfaceClass = Class.forName("android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        try {
            for (final TaskDisplayAreaHandle plane : planes) {
                transactionClass.getMethod(visible ? "show" : "hide", surfaceClass)
                        .invoke(transaction, plane.surfaceLeash());
            }
            if (mChrome != null) {
                transactionClass.getMethod("setLayer", surfaceClass, Integer.TYPE)
                        .invoke(transaction, mChrome.surfaceLeash(), Integer.MAX_VALUE);
            }
            applyCommitted(transactionClass, transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    static <T> Map<T, Integer> composeLayers(
            final Map<T, Integer> applicationLayers, final T chrome) {
        final Map<T, Integer> composed = new LinkedHashMap<>(applicationLayers);
        if (chrome != null) {
            // Every application-plane commit includes the chrome invariant;
            // callers cannot accidentally put the reveal edge behind a task.
            composed.put(chrome, Integer.valueOf(Integer.MAX_VALUE));
        }
        return composed;
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
