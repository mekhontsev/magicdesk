package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.ServiceConnection;

/** App-only startup never constructs a privilege transport or asks its manager for access. */
final class DisabledShellServiceLauncher implements ShellServiceLauncher {
    static final String REASON = "Privileged access is disabled in Limits";
    @Override public void initialize(Runnable changed, Runnable disconnected) { }
    @Override public ShellAccess.Snapshot inspect() {
        return ShellAccess.Snapshot.unavailable(ShellBackend.active(), false, REASON);
    }
    @Override public boolean canBind() { return false; }
    @Override public Binding bind(Service service, String tag, ServiceConnection connection) {
        throw new IllegalStateException(REASON);
    }
    @Override public void requestPermission() { throw new IllegalStateException(REASON); }
    @Override public void openManager(Context context) { throw new IllegalStateException(REASON); }
}
