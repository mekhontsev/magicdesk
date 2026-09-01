package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.WindowInsets;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Runtime adapter for hidden windowing APIs that differ between Android releases. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class FrameworkWindowingCompat {
    static final String ANDROID_15_OVERRIDE = "android15";
    static final long TASK_OBSERVATION_INTERVAL_MILLIS = 150L;
    static final int TASK_OBSERVATION_LIMIT = 16;

    private static final String TASK_INFO_CLASS = "android.app.TaskInfo";
    private static final String TOKEN_CLASS =
            "android.window.WindowContainerToken";
    private static final String TRANSACTION_CLASS =
            "android.window.WindowContainerTransaction";
    private static final String HIERARCHY_OP_CLASS =
            TRANSACTION_CLASS + "$HierarchyOp";
    private static final String INSETS_PROVIDER_CLASS =
            "android.view.InsetsFrameProvider";

    private final RequestedVisibleTypesReader mRequestedVisibleTypes;
    private final CaptionExclusionWriter mCaptionExclusion;
    private final InsetsSourceWriter mInsetsSources;
    private final Capabilities mCapabilities;

    private FrameworkWindowingCompat(
            final RequestedVisibleTypesReader requestedVisibleTypes,
            final CaptionExclusionWriter captionExclusion,
            final InsetsSourceWriter insetsSources,
            final Capabilities capabilities) {
        mRequestedVisibleTypes = requestedVisibleTypes;
        mCaptionExclusion = captionExclusion;
        mInsetsSources = insetsSources;
        mCapabilities = capabilities;
    }

    static FrameworkWindowingCompat current() {
        return CurrentHolder.INSTANCE;
    }

    static String overrideDetail() {
        return BuildConfig.FRAMEWORK_OVERRIDE.isEmpty()
                ? "automatic" : BuildConfig.FRAMEWORK_OVERRIDE + " emulation";
    }

    Capabilities capabilities() {
        return mCapabilities;
    }

    int captionBarType() {
        return WindowInsets.Type.captionBar();
    }

    Integer readRequestedVisibleTypes(final Object task)
            throws ReflectiveOperationException {
        return mRequestedVisibleTypes.read(task);
    }

    boolean addCaptionExclusion(
            final Object transaction,
            final Object taskToken,
            final boolean exclude,
            final int captionType) throws ReflectiveOperationException {
        return mCaptionExclusion.add(
                transaction, taskToken, exclude, captionType);
    }

    int lastExcludeInsetsTypes(final Object transaction)
            throws ReflectiveOperationException {
        return mCaptionExclusion.lastExcludeInsetsTypes(transaction);
    }

    void addEmptyCaptionSource(
            final Object transaction,
            final Object taskToken,
            final IBinder owner,
            final int captionType,
            final Rect frame,
            final int sourceId) throws ReflectiveOperationException {
        mInsetsSources.addEmpty(
                transaction, taskToken, owner, captionType, frame, sourceId);
    }

    void removeCaptionSource(
            final Object transaction,
            final Object taskToken,
            final IBinder owner,
            final int captionType,
            final int sourceId) throws ReflectiveOperationException {
        mInsetsSources.remove(
                transaction, taskToken, owner, captionType, sourceId);
    }

    static FrameworkWindowingCompat inspect(
            final Class<?> taskInfoClass,
            final Class<?> transactionClass,
            final Class<?> tokenClass,
            final Class<?> hierarchyOpClass,
            final Class<?> insetsProviderClass,
            final String override) {
        final boolean emulateAndroid15 = ANDROID_15_OVERRIDE.equals(override);

        final Field requestedVisibleTypes = findPublicField(
                taskInfoClass, "requestedVisibleTypes");
        final RequestedVisibleTypesReader requestedReader = !emulateAndroid15
                && requestedVisibleTypes != null
                        ? new FieldRequestedVisibleTypesReader(
                                requestedVisibleTypes)
                        : UnavailableRequestedVisibleTypesReader.INSTANCE;

        final Method hierarchyOps = findPublicMethod(
                transactionClass, "getHierarchyOps");
        final Method setExcludeImeInsets = findPublicMethod(
                transactionClass,
                "setExcludeImeInsets",
                tokenClass,
                Boolean.TYPE);
        final Field excludeInsetsTypes = findDeclaredField(
                hierarchyOpClass, "mExcludeInsetsTypes");
        final Method getExcludeInsetsTypes = findPublicMethod(
                hierarchyOpClass, "getExcludeInsetsTypes");
        final boolean nativeCaptionExclusionDetected =
                hierarchyOps != null
                && setExcludeImeInsets != null
                && excludeInsetsTypes != null
                && getExcludeInsetsTypes != null;
        final CaptionExclusionWriter captionExclusion =
                nativeCaptionExclusionDetected && !emulateAndroid15
                        ? new NativeCaptionExclusionWriter(
                                setExcludeImeInsets,
                                hierarchyOps,
                                excludeInsetsTypes,
                                getExcludeInsetsTypes)
                        : UnavailableCaptionExclusionWriter.INSTANCE;

        final Method addInsetsSourceWithFlags = findPublicMethod(
                transactionClass,
                "addInsetsSource",
                tokenClass,
                IBinder.class,
                Integer.TYPE,
                Integer.TYPE,
                Rect.class,
                Rect[].class,
                Integer.TYPE);
        final Method addInsetsSourceWithoutFlags = findPublicMethod(
                transactionClass,
                "addInsetsSource",
                tokenClass,
                IBinder.class,
                Integer.TYPE,
                Integer.TYPE,
                Rect.class,
                Rect[].class);
        final Method removeInsetsSource = findPublicMethod(
                transactionClass,
                "removeInsetsSource",
                tokenClass,
                IBinder.class,
                Integer.TYPE,
                Integer.TYPE);
        final Method getInsetsFrameProvider = findPublicMethod(
                hierarchyOpClass, "getInsetsFrameProvider");
        final Field providerId = findDeclaredField(
                insetsProviderClass, "mId");
        final InsetsSourceWriter insetsSources;
        final String insetsSourceApi;
        if (hierarchyOps == null
                || removeInsetsSource == null
                || getInsetsFrameProvider == null
                || providerId == null) {
            insetsSources = UnavailableInsetsSourceWriter.INSTANCE;
            insetsSourceApi = "unavailable";
        } else if (emulateAndroid15
                && addInsetsSourceWithoutFlags != null) {
            insetsSources = new ReflectiveInsetsSourceWriter(
                    addInsetsSourceWithoutFlags,
                    false,
                    removeInsetsSource,
                    hierarchyOps,
                    getInsetsFrameProvider,
                    providerId);
            insetsSourceApi = "without-flags";
        } else if (emulateAndroid15 && addInsetsSourceWithFlags != null) {
            // Android 16 may omit the without-flags overload. Supplying zero
            // flags preserves Android 15 behavior for the debug profile.
            insetsSources = new ReflectiveInsetsSourceWriter(
                    addInsetsSourceWithFlags,
                    true,
                    removeInsetsSource,
                    hierarchyOps,
                    getInsetsFrameProvider,
                    providerId);
            insetsSourceApi = "without-flags-emulated";
        } else if (addInsetsSourceWithFlags != null) {
            insetsSources = new ReflectiveInsetsSourceWriter(
                    addInsetsSourceWithFlags,
                    true,
                    removeInsetsSource,
                    hierarchyOps,
                    getInsetsFrameProvider,
                    providerId);
            insetsSourceApi = "flags";
        } else if (addInsetsSourceWithoutFlags != null) {
            insetsSources = new ReflectiveInsetsSourceWriter(
                    addInsetsSourceWithoutFlags,
                    false,
                    removeInsetsSource,
                    hierarchyOps,
                    getInsetsFrameProvider,
                    providerId);
            insetsSourceApi = "without-flags";
        } else {
            insetsSources = UnavailableInsetsSourceWriter.INSTANCE;
            insetsSourceApi = "unavailable";
        }

        final boolean captionPolyfill = !captionExclusion.available()
                && insetsSources.available();
        return new FrameworkWindowingCompat(
                requestedReader,
                captionExclusion,
                insetsSources,
                new Capabilities(
                        override == null || override.isEmpty()
                                ? "automatic" : override,
                        requestedVisibleTypes != null,
                        requestedReader.available(),
                        nativeCaptionExclusionDetected,
                        captionExclusion.available(),
                        captionPolyfill,
                        insetsSourceApi));
    }

    private static FrameworkWindowingCompat detect(final String override) {
        try {
            return inspect(
                    Class.forName(TASK_INFO_CLASS),
                    Class.forName(TRANSACTION_CLASS),
                    Class.forName(TOKEN_CLASS),
                    Class.forName(HIERARCHY_OP_CLASS),
                    Class.forName(INSETS_PROVIDER_CLASS),
                    override);
        } catch (ReflectiveOperationException
                | LinkageError
                | RuntimeException error) {
            return unavailable(override, error);
        }
    }

    private static FrameworkWindowingCompat unavailable(
            final String override,
            final Throwable error) {
        final String detail = usefulMessage(error);
        return new FrameworkWindowingCompat(
                UnavailableRequestedVisibleTypesReader.INSTANCE,
                UnavailableCaptionExclusionWriter.INSTANCE,
                UnavailableInsetsSourceWriter.INSTANCE,
                new Capabilities(
                        override == null || override.isEmpty()
                                ? "automatic" : override,
                        false,
                        false,
                        false,
                        false,
                        false,
                        "unavailable:" + detail));
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static Field findPublicField(
            final Class<?> owner, final String name) {
        try {
            return owner.getField(name);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Field findDeclaredField(
            final Class<?> owner, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Method findPublicMethod(
            final Class<?> owner,
            final String name,
            final Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Object lastHierarchyOperation(
            final Method hierarchyOps,
            final Object transaction) throws ReflectiveOperationException {
        final List<?> operations = (List<?>) invoke(
                hierarchyOps, transaction);
        if (operations == null || operations.isEmpty()) {
            throw new IllegalStateException("window transaction has no hierarchy operation");
        }
        return operations.get(operations.size() - 1);
    }

    private static Object invoke(
            final Method method,
            final Object receiver,
            final Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof ReflectiveOperationException) {
                throw (ReflectiveOperationException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw error;
        }
    }

    static final class Capabilities {
        final String profile;
        final boolean requestedVisibleTypesDetected;
        final boolean requestedVisibleTypesEnabled;
        final boolean captionExclusionDetected;
        final boolean captionExclusionEnabled;
        final boolean captionSourcePolyfill;
        final String insetsSourceApi;
        final TaskObservationCapabilities taskObservation;

        Capabilities(
                final String selectedProfile,
                final boolean visibleTypesDetected,
                final boolean visibleTypesEnabled,
                final boolean exclusionDetected,
                final boolean exclusionEnabled,
                final boolean sourcePolyfill,
                final String sourceApi) {
            profile = selectedProfile;
            requestedVisibleTypesDetected = visibleTypesDetected;
            requestedVisibleTypesEnabled = visibleTypesEnabled;
            captionExclusionDetected = exclusionDetected;
            captionExclusionEnabled = exclusionEnabled;
            captionSourcePolyfill = sourcePolyfill;
            insetsSourceApi = sourceApi;
            taskObservation = new TaskObservationCapabilities(
                    visibleTypesEnabled,
                    !"unavailable".equals(sourceApi));
        }

        String captionStrategy() {
            if (captionExclusionEnabled) {
                return "native";
            }
            return captionSourcePolyfill ? "source-polyfill" : "unavailable";
        }
    }

    enum ObservationProvenance {
        EVENT("event"),
        SAMPLED("sampled"),
        HYBRID("event+sampled"),
        UNAVAILABLE("unavailable");

        final String label;

        ObservationProvenance(final String value) {
            label = value;
        }
    }

    static final class TaskObservationCapabilities {
        final String strategy = "hybrid";
        final long fallbackIntervalMillis =
                TASK_OBSERVATION_INTERVAL_MILLIS;
        final int taskLimit = TASK_OBSERVATION_LIMIT;
        final ObservationProvenance lifecycle =
                ObservationProvenance.HYBRID;
        final ObservationProvenance stack =
                ObservationProvenance.HYBRID;
        final ObservationProvenance windowGeometry =
                ObservationProvenance.SAMPLED;
        final ObservationProvenance immersiveRequest;
        final ObservationProvenance captionSource;

        TaskObservationCapabilities(
                final boolean immersiveAvailable,
                final boolean captionSourceAvailable) {
            immersiveRequest = immersiveAvailable
                    ? ObservationProvenance.SAMPLED
                    : ObservationProvenance.UNAVAILABLE;
            captionSource = captionSourceAvailable
                    ? ObservationProvenance.SAMPLED
                    : ObservationProvenance.UNAVAILABLE;
        }
    }

    private interface RequestedVisibleTypesReader {
        Integer read(Object task) throws ReflectiveOperationException;

        boolean available();
    }

    private static final class FieldRequestedVisibleTypesReader
            implements RequestedVisibleTypesReader {
        private final Field mField;

        FieldRequestedVisibleTypesReader(final Field field) {
            mField = field;
        }

        @Override
        public Integer read(final Object task)
                throws ReflectiveOperationException {
            return Integer.valueOf(mField.getInt(task));
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private enum UnavailableRequestedVisibleTypesReader
            implements RequestedVisibleTypesReader {
        INSTANCE;

        @Override
        public Integer read(final Object task) {
            return null;
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private interface CaptionExclusionWriter {
        boolean add(
                Object transaction,
                Object taskToken,
                boolean exclude,
                int captionType)
                throws ReflectiveOperationException;

        int lastExcludeInsetsTypes(Object transaction)
                throws ReflectiveOperationException;

        boolean available();
    }

    private static final class NativeCaptionExclusionWriter
            implements CaptionExclusionWriter {
        private final Method mSetExcludeImeInsets;
        private final Method mHierarchyOps;
        private final Field mExcludeInsetsTypes;
        private final Method mGetExcludeInsetsTypes;

        NativeCaptionExclusionWriter(
                final Method setExcludeImeInsets,
                final Method hierarchyOps,
                final Field excludeInsetsTypes,
                final Method getExcludeInsetsTypes) {
            mSetExcludeImeInsets = setExcludeImeInsets;
            mHierarchyOps = hierarchyOps;
            mExcludeInsetsTypes = excludeInsetsTypes;
            mGetExcludeInsetsTypes = getExcludeInsetsTypes;
        }

        @Override
        public boolean add(
                final Object transaction,
                final Object taskToken,
                final boolean exclude,
                final int captionType) throws ReflectiveOperationException {
            invoke(mSetExcludeImeInsets, transaction,
                    taskToken, Boolean.valueOf(exclude));
            if (exclude) {
                final Object operation = lastHierarchyOperation(
                        mHierarchyOps, transaction);
                mExcludeInsetsTypes.setInt(operation, captionType);
            }
            return true;
        }

        @Override
        public int lastExcludeInsetsTypes(final Object transaction)
                throws ReflectiveOperationException {
            return ((Integer) invoke(
                    mGetExcludeInsetsTypes,
                    lastHierarchyOperation(mHierarchyOps, transaction)))
                    .intValue();
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private enum UnavailableCaptionExclusionWriter
            implements CaptionExclusionWriter {
        INSTANCE;

        @Override
        public boolean add(
                final Object transaction,
                final Object taskToken,
                final boolean exclude,
                final int captionType) {
            return false;
        }

        @Override
        public int lastExcludeInsetsTypes(final Object transaction) {
            return 0;
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private interface InsetsSourceWriter {
        void addEmpty(
                Object transaction,
                Object taskToken,
                IBinder owner,
                int captionType,
                Rect frame,
                int sourceId) throws ReflectiveOperationException;

        void remove(
                Object transaction,
                Object taskToken,
                IBinder owner,
                int captionType,
                int sourceId) throws ReflectiveOperationException;

        boolean available();
    }

    private static final class ReflectiveInsetsSourceWriter
            implements InsetsSourceWriter {
        private final Method mAddInsetsSource;
        private final boolean mHasFlags;
        private final Method mRemoveInsetsSource;
        private final Method mHierarchyOps;
        private final Method mGetInsetsFrameProvider;
        private final Field mProviderId;

        ReflectiveInsetsSourceWriter(
                final Method addInsetsSource,
                final boolean hasFlags,
                final Method removeInsetsSource,
                final Method hierarchyOps,
                final Method getInsetsFrameProvider,
                final Field providerId) {
            mAddInsetsSource = addInsetsSource;
            mHasFlags = hasFlags;
            mRemoveInsetsSource = removeInsetsSource;
            mHierarchyOps = hierarchyOps;
            mGetInsetsFrameProvider = getInsetsFrameProvider;
            mProviderId = providerId;
        }

        @Override
        public void addEmpty(
                final Object transaction,
                final Object taskToken,
                final IBinder owner,
                final int captionType,
                final Rect frame,
                final int sourceId) throws ReflectiveOperationException {
            if (mHasFlags) {
                invoke(mAddInsetsSource, transaction,
                        taskToken,
                        owner,
                        Integer.valueOf(0),
                        Integer.valueOf(captionType),
                        frame,
                        null,
                        Integer.valueOf(0));
            } else {
                invoke(mAddInsetsSource, transaction,
                        taskToken,
                        owner,
                        Integer.valueOf(0),
                        Integer.valueOf(captionType),
                        frame,
                        null);
            }
            setLastProviderId(transaction, sourceId);
        }

        @Override
        public void remove(
                final Object transaction,
                final Object taskToken,
                final IBinder owner,
                final int captionType,
                final int sourceId) throws ReflectiveOperationException {
            invoke(mRemoveInsetsSource, transaction,
                    taskToken,
                    owner,
                    Integer.valueOf(0),
                    Integer.valueOf(captionType));
            setLastProviderId(transaction, sourceId);
        }

        private void setLastProviderId(
                final Object transaction,
                final int sourceId) throws ReflectiveOperationException {
            final Object operation = lastHierarchyOperation(
                    mHierarchyOps, transaction);
            final Object provider = invoke(
                    mGetInsetsFrameProvider, operation);
            if (provider == null) {
                throw new IllegalStateException(
                        "window transaction has no insets provider");
            }
            mProviderId.setInt(provider, sourceId);
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private enum UnavailableInsetsSourceWriter implements InsetsSourceWriter {
        INSTANCE;

        @Override
        public void addEmpty(
                final Object transaction,
                final Object taskToken,
                final IBinder owner,
                final int captionType,
                final Rect frame,
                final int sourceId) throws ReflectiveOperationException {
            throw new NoSuchMethodException(
                    "caption insets source operations are unavailable");
        }

        @Override
        public void remove(
                final Object transaction,
                final Object taskToken,
                final IBinder owner,
                final int captionType,
                final int sourceId) throws ReflectiveOperationException {
            throw new NoSuchMethodException(
                    "caption insets source operations are unavailable");
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private static final class CurrentHolder {
        private static final FrameworkWindowingCompat INSTANCE = detect(
                BuildConfig.FRAMEWORK_OVERRIDE);
    }
}
