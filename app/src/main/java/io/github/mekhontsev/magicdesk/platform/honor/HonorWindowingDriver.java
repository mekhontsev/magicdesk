package io.github.mekhontsev.magicdesk.platform.honor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;
import io.github.mekhontsev.magicdesk.TaskWindowGeometry;

/** Passive TaskInfo geometry and transaction-local scale, without system settings. */
public final class HonorWindowingDriver implements PlatformWindowingDriver {
    private final Field mTaskExtension;
    private final Field mScale;
    private final String mProbe;
    private Method mTransactionExtension;
    private Method mSetScale;
    private Method mUseShellCalculateBounds;
    private String mWriteProbe = "not used";
    private volatile String mReadError = "none";

    private HonorWindowingDriver(Field extension, Field scale, String probe) {
        mTaskExtension = extension;
        mScale = scale;
        mProbe = probe;
    }

    public static HonorWindowingDriver detect() {
        return inspect(android.app.ActivityManager.RunningTaskInfo.class);
    }

    public static HonorWindowingDriver inspect(Class<?> taskClass) {
        try {
            Field extension = taskClass.getField("hnTaskInfoEx");
            Field scale = extension.getType().getField("mFreeformScale");
            if (scale.getType() != Float.TYPE) {
                return new HonorWindowingDriver(null, null, "incompatible scale type");
            }
            return new HonorWindowingDriver(extension, scale, "TaskInfo freeform scale");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return new HonorWindowingDriver(null, null, error.toString());
        }
    }

    @Override public boolean hasScaledWindows() { return mScale != null; }

    @Override public float taskScale(Object task) {
        if (mScale == null || task == null) return Float.NaN;
        try {
            Object extension = mTaskExtension.get(task);
            float scale = extension == null ? Float.NaN : mScale.getFloat(extension);
            if (!TaskWindowGeometry.validScale(scale)) {
                mReadError = "missing or invalid task scale";
                return Float.NaN;
            }
            return scale;
        } catch (ReflectiveOperationException | RuntimeException error) {
            mReadError = error.toString();
            return Float.NaN;
        }
    }

    @Override public synchronized void prepareTaskGeometry(Object transaction,
            Class<?> tokenClass, Object token, float scale)
            throws ReflectiveOperationException {
        if (!TaskWindowGeometry.validScale(scale)) {
            throw new IllegalArgumentException("invalid task scale");
        }
        try {
            if (mTransactionExtension == null) {
                Method extension = transaction.getClass().getMethod(
                        "getHnWindowContainerTransactionEx");
                Method setScale = extension.getReturnType().getMethod(
                        "setFreeformScale", transaction.getClass(), tokenClass, Float.TYPE);
                Method calculatedBounds = extension.getReturnType().getMethod(
                        "setUseShellCalculateBounds", transaction.getClass(), tokenClass, Boolean.TYPE);
                mTransactionExtension = extension;
                mSetScale = setScale;
                mUseShellCalculateBounds = calculatedBounds;
            }
            Object extension = mTransactionExtension.invoke(transaction);
            // The caller owns these bounds, as in the firmware's own desktop placement.
            mUseShellCalculateBounds.invoke(extension, transaction, token, false);
            mSetScale.invoke(extension, transaction, token, scale);
            mWriteProbe = "available; caller-calculated bounds";
        } catch (ReflectiveOperationException | RuntimeException error) {
            mWriteProbe = error.toString();
            throw error;
        }
    }

    @Override public synchronized String geometryDiagnostic() {
        return "read=" + mProbe + ", write=" + mWriteProbe + ", readError=" + mReadError;
    }

    @Override public String restrictionsPropertyKey() { return null; }
    @Override public String roundedCornersPropertyKey() { return null; }
    @Override public boolean configure(boolean restrictions, boolean corners) { return false; }
    @Override public void restoreDefaults() { }
}
