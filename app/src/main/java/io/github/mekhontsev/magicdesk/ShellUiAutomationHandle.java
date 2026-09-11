package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** App lifetime token: shell also releases the connection if the APK process disappears. */
final class ShellUiAutomationHandle implements AutoCloseable {
    private final IShellCommandService mService;
    private final IBinder mOwner = new Binder();

    ShellUiAutomationHandle(final IShellCommandService service) { mService = service; }

    boolean isAlive() { return mService.asBinder().isBinderAlive(); }

    JSONObject execute(final String operation, final JSONObject args) throws IOException, JSONException {
        try {
            return new JSONObject(mService.executeUiAutomation(mOwner, operation, args.toString()));
        } catch (RemoteException error) {
            throw new IOException("UI automation service disconnected; do not repeat an action without inspecting UI", error);
        }
    }

    @Override public void close() throws IOException {
        try { mService.releaseUiAutomation(mOwner); }
        catch (RemoteException error) { throw new IOException("cannot release UI automation connection", error); }
    }
}
