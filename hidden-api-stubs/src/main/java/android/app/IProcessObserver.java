package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IProcessObserver extends IInterface {
    void onProcessStarted(
            int pid,
            int processUid,
            int packageUid,
            String packageName,
            String processName) throws RemoteException;

    void onForegroundActivitiesChanged(
            int pid, int uid, boolean foregroundActivities)
            throws RemoteException;

    void onForegroundServicesChanged(
            int pid, int uid, int serviceTypes) throws RemoteException;

    void onProcessDied(int pid, int uid) throws RemoteException;

    abstract class Stub extends Binder implements IProcessObserver {
        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
