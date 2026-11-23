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
    private final Method mTransactionExtension;
    private final Method mSetScale;
    private final Method mUseShellCalculateBounds;
    private volatile String mWriteProbe;
    private volatile String mReadError = "none";
    private volatile String mPeerProbe = "not used";
    private final FreeformMultiplicity mMultiplicity;

    private HonorWindowingDriver(Field extension, Field scale, String probe, ClassLoader loader,
            String transactionClass, String tokenClass) {
        mTaskExtension = extension;
        mScale = scale;
        mProbe = probe;
        Method accessor = null, setScale = null, setBounds = null;
        Class<?> wct = null, token = null;
        mWriteProbe = "not applicable";
        if (scale != null) {
            try {
                wct = Class.forName(transactionClass, false, loader);
                token = Class.forName(tokenClass, false, loader);
                accessor = wct.getMethod("getHnWindowContainerTransactionEx");
                Class<?> changes = accessor.getReturnType();
                setScale = changes.getMethod("setFreeformScale", wct, token, float.class);
                setBounds = changes.getMethod("setUseShellCalculateBounds", wct, token, boolean.class);
                mWriteProbe = "available; caller-calculated bounds";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                mWriteProbe = error.toString();
            }
        }
        mTransactionExtension = accessor;
        mSetScale = setScale;
        mUseShellCalculateBounds = setBounds;
        mMultiplicity = HonorFreeformMultiplicity.detect(extension, accessor, wct, token);
    }

    public static HonorWindowingDriver inspect(Class<?> taskClass, String transactionClass, String tokenClass) {
        Field extension = null, scale = null;
        String probe;
        try {
            extension = taskClass.getField("hnTaskInfoEx");
            Field field = extension.getType().getField("mFreeformScale");
            if (field.getType() == float.class) scale = field;
            probe = scale == null ? "incompatible scale type" : "TaskInfo freeform scale";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            probe = error.toString();
        }
        return new HonorWindowingDriver(extension, scale, probe, taskClass.getClassLoader(),
                transactionClass, tokenClass);
    }

    boolean canReadTaskScale() { return mScale != null; }
    @Override public boolean hasScaledWindows() { return mSetScale != null && mUseShellCalculateBounds != null; }

    @Override public FreeformMultiplicity freeformMultiplicity() { return mMultiplicity; }

    @Override public boolean hasNativeScaleCommit() { return hasScaledWindows(); }

    @Override public TaskScalePeer taskScalePeer(int taskId) throws ReflectiveOperationException {
        try {
            // The framework singleton owns Binder death/reconnection. Do not retain its proxy.
            Class<?> manager = Class.forName("android.app.HnFreeformManager");
            Object service = manager.getMethod("getService").invoke(null);
            if (service == null) throw new IllegalStateException("freeform service unavailable or denied");
            TaskScalePeer peer = scalePeer(service,
                    Class.forName("android.window.IHnFreeformEx"), taskId);
            mPeerProbe = "available";
            return peer;
        } catch (ReflectiveOperationException | RuntimeException error) {
            mPeerProbe = error.toString();
            throw error;
        }
    }

    static TaskScalePeer scalePeer(Object service, Class<?> api, int taskId)
            throws ReflectiveOperationException {
        Method read = api.getMethod("getFreeformStackScale", Integer.TYPE);
        Method request = api.getMethod("notifyFreeformScaleChange", Integer.TYPE, Float.TYPE);
        if (!api.isInstance(service) || read.getReturnType() != Float.TYPE
                || request.getReturnType() != Void.TYPE) {
            throw new IllegalStateException("incompatible freeform scale service");
        }
        return new TaskScalePeer() {
            @Override public float read() throws ReflectiveOperationException {
                float value = (Float) read.invoke(service, taskId);
                if (!TaskWindowGeometry.validScale(value)) {
                    throw new IllegalStateException("decoration scale unavailable");
                }
                return value;
            }
            @Override public void request(float value) throws ReflectiveOperationException {
                if (!TaskWindowGeometry.validScale(value)) throw new IllegalArgumentException("invalid scale");
                request.invoke(service, taskId, value);
            }
        };
    }

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

    @Override public void prepareTaskGeometry(Object transaction, Object token, float scale)
            throws ReflectiveOperationException {
        if (!TaskWindowGeometry.validScale(scale)) {
            throw new IllegalArgumentException("invalid task scale");
        }
        if (!hasScaledWindows()) throw new UnsupportedOperationException("firmware geometry unavailable");
        try {
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
        return "read=" + mProbe + ", write=" + mWriteProbe + ", readError=" + mReadError
                + ", scalePeer=" + mPeerProbe + ", freeformMultiplicity=" + (mMultiplicity != null);
    }

    @Override public String restrictionsPropertyKey() { return null; }
    @Override public String roundedCornersPropertyKey() { return null; }
    @Override public boolean configure(boolean restrictions, boolean corners) { return false; }
    @Override public void restoreDefaults() { }
}
