package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Verifies the read-only Nubia task callback from an untrusted app process. */
public final class NubiaSceneCallbackProbeCommand {
    private static final String SERVICE_NAME = "scenedecision";
    private static final String SERVICE_DESCRIPTOR =
            "com.zte.performance.scene.IZteSceneDecisionManager";
    private static final String CALLBACK_DESCRIPTOR =
            "com.zte.performance.scene.ITaskCallback";
    private static final int TRANSACTION_REGISTER_CALLBACK = 44;
    private static final int TRANSACTION_UNREGISTER_CALLBACK = 46;
    private static final long TOP_ACTIVITY_FLAG = 1L;

    private NubiaSceneCallbackProbeCommand() {
    }

    public static void main(final String[] arguments) {
        final CountDownLatch callbackReceived = new CountDownLatch(1);
        final SceneCallback callback = new SceneCallback(callbackReceived);
        IBinder service = null;
        try {
            service = getService(SERVICE_NAME);
            if (service == null) {
                throw new IllegalStateException("scenedecision service is missing");
            }
            transactCallback(
                    service,
                    TRANSACTION_REGISTER_CALLBACK,
                    callback,
                    true);
            if (!callbackReceived.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("initial task callback timed out");
            }
        } catch (ReflectiveOperationException
                | RemoteException
                | InterruptedException
                | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("scene-callback failed: " + error);
            System.exit(1);
        } finally {
            if (service != null) {
                try {
                    transactCallback(
                            service,
                            TRANSACTION_UNREGISTER_CALLBACK,
                            callback,
                            false);
                } catch (RemoteException | RuntimeException error) {
                    System.err.println(
                            "scene-callback unregister failed: " + error);
                }
            }
        }
    }

    private static IBinder getService(final String name)
            throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final Method getter =
                serviceManager.getMethod("getService", String.class);
        return (IBinder) getter.invoke(null, name);
    }

    private static void transactCallback(
            final IBinder service,
            final int transaction,
            final IBinder callback,
            final boolean includeFlag) throws RemoteException {
        final Parcel data = Parcel.obtain(service);
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (includeFlag) {
                data.writeLong(TOP_ACTIVITY_FLAG);
            }
            if (!service.transact(transaction, data, reply, 0)) {
                throw new RemoteException(
                        "unsupported scenedecision transaction " + transaction);
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static final class SceneCallback extends Binder {
        private final CountDownLatch mReceived;

        SceneCallback(final CountDownLatch received) {
            mReceived = received;
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(
                final int code,
                final Parcel data,
                final Parcel reply,
                final int flags) throws RemoteException {
            if (code != 1) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            final int event = data.readInt();
            final Bundle state = data.readTypedObject(Bundle.CREATOR);
            System.out.println(
                    "scene-callback event=" + event
                            + " state=" + formatBundle(state));
            mReceived.countDown();
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private static String formatBundle(final Bundle bundle) {
        if (bundle == null) {
            return "null";
        }
        final List<String> keys = new ArrayList<>(bundle.keySet());
        Collections.sort(keys);
        final StringBuilder output = new StringBuilder("{");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                output.append(", ");
            }
            final String key = keys.get(index);
            output.append(key).append('=').append(bundle.get(key));
        }
        return output.append('}').toString();
    }
}
