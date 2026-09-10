package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared Android UI automation entry point. No HOME, Desktop or accessibility-service lifecycle. */
final class AndroidUiAutomation implements AutoCloseable {
    private final AutomationAwakeLease mAwake;
    private ShellUiAutomationHandle mHandle;

    AndroidUiAutomation(final Context context) { mAwake = new AutomationAwakeLease(context); }

    JSONObject execute(final String operation, final JSONObject args) throws IOException, JSONException {
        switch (operation) {
            case "device.keep_awake": return mAwake.acquire(args);
            case "device.release_awake": return mAwake.release(args.getString("leaseId"));
            case "ui.release": {
                final ShellUiAutomationHandle handle;
                synchronized (this) { handle = mHandle; }
                if (handle != null && handle.isAlive()) handle.close();
                return new JSONObject().put("released", true);
            }
            default: return handle().execute(operation, args);
        }
    }

    JSONObject awakeState() throws JSONException { return mAwake.state(); }

    private synchronized ShellUiAutomationHandle handle() throws IOException {
        if (mHandle == null || !mHandle.isAlive()) mHandle = ShellAccess.openUiAutomation();
        return mHandle;
    }

    @Override public synchronized void close() {
        mAwake.close();
        if (mHandle != null) {
            try { mHandle.close(); }
            catch (IOException | RuntimeException error) { Log.w("MagicDeskUiAutomation", "UI connection cleanup failed", error); }
            mHandle = null;
        }
    }
}
