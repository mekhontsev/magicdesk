package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cached primitive access to Android's hidden window-container API. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class FrameworkWindowingApi {
    private static final String TOKEN_CLASS =
            "android.window.WindowContainerToken";
    private static final String TRANSACTION_CLASS =
            "android.window.WindowContainerTransaction";

    private final Class<?> mTokenClass;
    private final Class<?> mTransactionClass;
    private final Map<String, Method> mMethods;
    private final String mError;

    private FrameworkWindowingApi(
            final Class<?> tokenClass,
            final Class<?> transactionClass,
            final Map<String, Method> methods,
            final String error) {
        mTokenClass = tokenClass;
        mTransactionClass = transactionClass;
        mMethods = methods;
        mError = error == null ? "" : error;
    }

    static FrameworkWindowingApi current() {
        return CurrentHolder.INSTANCE;
    }

    static FrameworkWindowingApi inspect(
            final Class<?> tokenClass,
            final Class<?> transactionClass) {
        try {
            final Map<String, Method> methods = new LinkedHashMap<>();
            methods.put("setWindowingMode", transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE));
            methods.put("setBounds", transactionClass.getMethod(
                    "setBounds", tokenClass, Rect.class));
            methods.put("reorder", transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE));
            methods.put("reorderParents", transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE));
            methods.put("reparent", transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE));
            methods.put("setFocusable", transactionClass.getMethod(
                    "setFocusable", tokenClass, Boolean.TYPE));
            methods.put("setAlwaysOnTop", transactionClass.getMethod(
                    "setAlwaysOnTop", tokenClass, Boolean.TYPE));
            methods.put("setForceTranslucent", transactionClass.getMethod(
                    "setForceTranslucent", tokenClass, Boolean.TYPE));
            methods.put("setHidden", transactionClass.getMethod(
                    "setHidden", tokenClass, Boolean.TYPE));
            methods.put("removeTask", transactionClass.getMethod(
                    "removeTask", tokenClass));
            methods.put("startTask", transactionClass.getMethod(
                    "startTask", Integer.TYPE, Bundle.class));
            addOptional(methods, "setDensityDpi", transactionClass,
                    tokenClass, Integer.TYPE);
            addOptional(methods, "setIgnoreOrientationRequest",
                    transactionClass, tokenClass, Boolean.TYPE);
            return new FrameworkWindowingApi(
                    tokenClass, transactionClass, methods, "");
        } catch (ReflectiveOperationException | RuntimeException error) {
            return unavailable(tokenClass, transactionClass, error);
        }
    }

    boolean available() {
        return mError.isEmpty();
    }

    boolean supportsDensityOverride() {
        return available() && mMethods.containsKey("setDensityDpi");
    }

    String error() {
        return mError;
    }

    String diagnosticDetail() {
        return available()
                ? "available, operations=" + String.join(",", mMethods.keySet())
                : "unavailable, error=" + mError;
    }

    Class<?> tokenClass() throws ReflectiveOperationException {
        requireAvailable();
        return mTokenClass;
    }

    Class<?> transactionClass() throws ReflectiveOperationException {
        requireAvailable();
        return mTransactionClass;
    }

    Object newTransaction() throws ReflectiveOperationException {
        requireAvailable();
        return mTransactionClass.getConstructor().newInstance();
    }

    void setWindowingMode(
            final Object transaction,
            final Object token,
            final int mode) throws ReflectiveOperationException {
        invoke("setWindowingMode", transaction, token, Integer.valueOf(mode));
    }

    void setBounds(
            final Object transaction,
            final Object token,
            final Rect bounds) throws ReflectiveOperationException {
        invoke("setBounds", transaction, token, bounds);
    }

    void reorder(
            final Object transaction,
            final Object token,
            final boolean onTop) throws ReflectiveOperationException {
        invoke("reorder", transaction, token, Boolean.valueOf(onTop));
    }

    void reorder(
            final Object transaction,
            final Object token,
            final boolean onTop,
            final boolean includingParents) throws ReflectiveOperationException {
        invoke("reorderParents", transaction, token,
                Boolean.valueOf(onTop), Boolean.valueOf(includingParents));
    }

    void reparent(
            final Object transaction,
            final Object token,
            final Object parent,
            final boolean onTop) throws ReflectiveOperationException {
        invoke("reparent", transaction,
                new Object[] {token, parent, Boolean.valueOf(onTop)});
    }

    void setFocusable(
            final Object transaction,
            final Object token,
            final boolean focusable) throws ReflectiveOperationException {
        invoke("setFocusable", transaction,
                token, Boolean.valueOf(focusable));
    }

    void setAlwaysOnTop(
            final Object transaction,
            final Object token,
            final boolean alwaysOnTop) throws ReflectiveOperationException {
        invoke("setAlwaysOnTop", transaction,
                token, Boolean.valueOf(alwaysOnTop));
    }

    void setForceTranslucent(
            final Object transaction,
            final Object token,
            final boolean translucent) throws ReflectiveOperationException {
        invoke("setForceTranslucent", transaction,
                token, Boolean.valueOf(translucent));
    }

    void setHidden(
            final Object transaction,
            final Object token,
            final boolean hidden) throws ReflectiveOperationException {
        invoke("setHidden", transaction, token, Boolean.valueOf(hidden));
    }

    void setDensityDpi(
            final Object transaction,
            final Object token,
            final int densityDpi) throws ReflectiveOperationException {
        invoke("setDensityDpi", transaction,
                token, Integer.valueOf(densityDpi));
    }

    void setIgnoreOrientationRequest(
            final Object transaction,
            final Object token,
            final boolean ignore) throws ReflectiveOperationException {
        invoke("setIgnoreOrientationRequest", transaction,
                token, Boolean.valueOf(ignore));
    }

    void removeTask(
            final Object transaction,
            final Object token) throws ReflectiveOperationException {
        invoke("removeTask", transaction, token);
    }

    void startTask(
            final Object transaction,
            final int taskId,
            final Bundle options) throws ReflectiveOperationException {
        invoke("startTask", transaction, Integer.valueOf(taskId), options);
    }

    private void invoke(
            final String operation,
            final Object target,
            final Object... arguments) throws ReflectiveOperationException {
        requireAvailable();
        final Method method = mMethods.get(operation);
        if (method == null) {
            throw new NoSuchMethodException(
                    "WindowContainerTransaction#" + operation);
        }
        method.invoke(target, arguments);
    }

    private void requireAvailable() throws ReflectiveOperationException {
        if (!available()) {
            throw new ReflectiveOperationException(
                    "window-container API unavailable: " + mError);
        }
    }

    private static void addOptional(
            final Map<String, Method> methods,
            final String name,
            final Class<?> owner,
            final Class<?>... parameterTypes) {
        try {
            methods.put(name, owner.getMethod(name, parameterTypes));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional operations report their absence only when requested.
        }
    }

    private static FrameworkWindowingApi detect() {
        try {
            return inspect(
                    Class.forName(TOKEN_CLASS),
                    Class.forName(TRANSACTION_CLASS));
        } catch (ReflectiveOperationException | LinkageError
                | RuntimeException error) {
            return unavailable(null, null, error);
        }
    }

    private static FrameworkWindowingApi unavailable(
            final Class<?> tokenClass,
            final Class<?> transactionClass,
            final Throwable error) {
        final String message = error.getMessage();
        return new FrameworkWindowingApi(
                tokenClass,
                transactionClass,
                new LinkedHashMap<>(),
                message == null || message.isBlank()
                        ? error.getClass().getSimpleName() : message);
    }

    private static final class CurrentHolder {
        static final FrameworkWindowingApi INSTANCE = detect();
    }
}
