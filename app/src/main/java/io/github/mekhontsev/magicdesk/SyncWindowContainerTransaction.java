package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class SyncWindowContainerTransaction {
    private static final long CALLBACK_TIMEOUT_SECONDS = 5L;
    private static final String CALLBACK_DESCRIPTOR =
            "android.window.IWindowContainerTransactionCallback";

    private SyncWindowContainerTransaction() {
    }

    static void apply(final Object activityTaskManagerService,
            final Class<?> transactionClass, final Object transaction)
            throws ReflectiveOperationException {
        final Object controller = activityTaskManagerService.getClass()
                .getMethod("getWindowOrganizerController")
                .invoke(activityTaskManagerService);
        final Class<?> callbackClass = Class.forName(CALLBACK_DESCRIPTOR);
        final SyncCallbackBinder callbackBinder = new SyncCallbackBinder();
        final Object callback = Proxy.newProxyInstance(
                callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                new CallbackInvocationHandler(callbackBinder));
        final Method applySyncTransaction = controller.getClass().getMethod(
                "applySyncTransaction", transactionClass, callbackClass);
        final int syncId = ((Integer) applySyncTransaction.invoke(
                controller, transaction, callback)).intValue();
        callbackBinder.await(syncId);
    }

    static void applyAsync(final Object activityTaskManagerService,
            final Class<?> transactionClass, final Object transaction)
            throws ReflectiveOperationException {
        final Object controller = activityTaskManagerService.getClass()
                .getMethod("getWindowOrganizerController")
                .invoke(activityTaskManagerService);
        controller.getClass().getMethod(
                "applyTransaction", transactionClass)
                .invoke(controller, transaction);
    }

    private static final class CallbackInvocationHandler implements InvocationHandler {
        private final IBinder mBinder;

        CallbackInvocationHandler(final IBinder binder) {
            mBinder = binder;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            final String name = method.getName();
            if ("asBinder".equals(name)) {
                return mBinder;
            }
            if ("toString".equals(name)) {
                return "MagicDeskSyncTransactionCallback";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
            }
            return null;
        }
    }

    private static final class SyncCallbackBinder extends Binder {
        private final CountDownLatch mReady = new CountDownLatch(1);
        private volatile int mReceivedSyncId = -1;
        private volatile Throwable mFailure;

        @Override
        protected boolean onTransact(final int code, final Parcel data, final Parcel reply,
                final int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(CALLBACK_DESCRIPTOR);
                }
                return true;
            }
            if (code != IBinder.FIRST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(CALLBACK_DESCRIPTOR);
            mReceivedSyncId = data.readInt();
            Object surfaceTransaction = null;
            Class<?> surfaceTransactionClass = null;
            try {
                surfaceTransactionClass =
                        Class.forName("android.view.SurfaceControl$Transaction");
                final Parcelable.Creator<?> creator = (Parcelable.Creator<?>)
                        surfaceTransactionClass.getField("CREATOR").get(null);
                surfaceTransaction = Parcel.class
                        .getMethod("readTypedObject", Parcelable.Creator.class)
                        .invoke(data, creator);
                if (surfaceTransaction != null) {
                    surfaceTransactionClass.getMethod("apply").invoke(surfaceTransaction);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                mFailure = e;
            } finally {
                if (surfaceTransaction != null && surfaceTransactionClass != null) {
                    try {
                        surfaceTransactionClass.getMethod("close").invoke(surfaceTransaction);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        if (mFailure == null) {
                            mFailure = e;
                        }
                    }
                }
                mReady.countDown();
            }
            return true;
        }

        void await(final int expectedSyncId) {
            final boolean completed;
            try {
                completed = mReady.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("sync callback interrupted", e);
            }
            if (!completed) {
                throw new IllegalStateException("sync callback timed out: " + expectedSyncId);
            }
            if (mReceivedSyncId != expectedSyncId) {
                throw new IllegalStateException("unexpected sync id " + mReceivedSyncId
                        + ", expected " + expectedSyncId);
            }
            if (mFailure != null) {
                throw new IllegalStateException("cannot apply surface transaction", mFailure);
            }
        }
    }
}
