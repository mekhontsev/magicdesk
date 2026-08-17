package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.system.Os;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class InternalAudioRecorder
        implements PlatformAudioCaptureDriver.Recorder {
    private static final String TAG = "MagicDeskRecording";
    // RedMagic's stock screen recorder and Game Highlights use this vendor source.
    private static final int SAMPLE_RATE_HZ = 48_000;
    private static final int BIT_RATE = 96_000;
    private static final int SHELL_UID = 2_000;
    private static final Object ACTIVITY_THREAD_LOCK = new Object();

    private final Context mContext;
    private final String mOutputPath;
    private MediaRecorder mRecorder;
    private boolean mStarted;

    InternalAudioRecorder(final Context context, final String outputPath) {
        mContext = context;
        mOutputPath = outputPath;
    }

    @SuppressLint({"MissingPermission", "WrongConstant"})
    public void start() throws IOException {
        if (mRecorder != null) {
            throw new IllegalStateException("internal audio recorder already started");
        }
        final MediaRecorder recorder = createRecorder(attributionContext());
        try {
            recorder.setAudioSource(InternalAudioSourceCapability.SOURCE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ);
            recorder.setAudioEncodingBitRate(BIT_RATE);
            recorder.setOutputFile(mOutputPath);
            recorder.prepare();
            recorder.start();
            mRecorder = recorder;
            mStarted = true;
        } catch (IOException | RuntimeException error) {
            recorder.release();
            throw error;
        }
    }

    public void stop() {
        final MediaRecorder recorder = mRecorder;
        if (recorder == null) {
            return;
        }
        mRecorder = null;
        try {
            if (mStarted) {
                recorder.stop();
            }
        } finally {
            mStarted = false;
            recorder.release();
        }
    }

    private Context attributionContext() throws IOException {
        if (mContext == null) {
            throw new IOException("recording context is unavailable");
        }
        if (Os.getuid() != SHELL_UID) {
            return mContext;
        }
        try {
            return mContext.createPackageContext(
                    "com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("Android shell package is unavailable", error);
        }
    }

    @SuppressWarnings("deprecation")
    private static MediaRecorder createRecorder(final Context context)
            throws IOException {
        synchronized (ACTIVITY_THREAD_LOCK) {
            Object activityThread = null;
            Field boundApplicationField = null;
            Object previousBoundApplication = null;
            boolean identityInstalled = false;
            try {
                final Class<?> activityThreadClass = Class.forName(
                        "android.app.ActivityThread");
                final Method currentActivityThread = activityThreadClass
                        .getDeclaredMethod("currentActivityThread");
                currentActivityThread.setAccessible(true);
                activityThread = currentActivityThread.invoke(null);
                if (activityThread == null) {
                    throw new IOException("Android activity thread is unavailable");
                }

                boundApplicationField = activityThreadClass.getDeclaredField(
                        "mBoundApplication");
                boundApplicationField.setAccessible(true);
                previousBoundApplication = boundApplicationField.get(activityThread);
                final Method currentPackageName = activityThreadClass
                        .getDeclaredMethod("currentPackageName");
                currentPackageName.setAccessible(true);
                if (currentPackageName.invoke(null) == null) {
                    // UserService has an Application but no AppBindData. Android 16's
                    // MediaRecorder JNI aborts instead of rejecting a null package.
                    final Class<?> bindDataClass = Class.forName(
                            "android.app.ActivityThread$AppBindData");
                    final Constructor<?> constructor =
                            bindDataClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    final Object bindData = constructor.newInstance();
                    final Field appInfoField = bindDataClass.getDeclaredField(
                            "appInfo");
                    appInfoField.setAccessible(true);
                    final ApplicationInfo appInfo = context.getApplicationInfo();
                    appInfoField.set(bindData, appInfo);
                    boundApplicationField.set(activityThread, bindData);
                    identityInstalled = true;
                }
                return new MediaRecorder(context);
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IOException(
                        "cannot initialize the vendor audio recorder", error);
            } finally {
                if (identityInstalled) {
                    try {
                        boundApplicationField.set(
                                activityThread, previousBoundApplication);
                    } catch (IllegalAccessException error) {
                        Log.w(TAG,
                                "cannot restore recording process identity",
                                error);
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}
