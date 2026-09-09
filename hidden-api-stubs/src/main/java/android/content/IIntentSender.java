package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/** Compile-only Android 15+ Binder signature; Android supplies the actual Stub. */
public interface IIntentSender extends IInterface {
    void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
            IIntentReceiver finishedReceiver, String requiredPermission, Bundle options)
            throws RemoteException;

    abstract class Stub extends Binder implements IIntentSender {
        @Override public IBinder asBinder() { return this; }
    }
}
