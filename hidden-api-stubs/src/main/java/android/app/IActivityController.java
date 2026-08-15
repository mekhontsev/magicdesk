package android.app;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IActivityController extends IInterface {
    boolean activityStarting(Intent intent, String packageName)
            throws RemoteException;

    boolean activityResuming(String packageName) throws RemoteException;

    boolean appCrashed(
            String processName,
            int pid,
            String shortMessage,
            String longMessage,
            long timeMillis,
            String stackTrace) throws RemoteException;

    int appEarlyNotResponding(
            String processName,
            int pid,
            String annotation) throws RemoteException;

    int appNotResponding(
            String processName,
            int pid,
            String processStats) throws RemoteException;

    int systemNotResponding(String message) throws RemoteException;

    abstract class Stub extends Binder implements IActivityController {
        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
