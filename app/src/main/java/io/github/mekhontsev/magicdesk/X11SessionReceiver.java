package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;

/** Session-scoped Binder handoff, authorized by sender identity and an unpredictable startup nonce. */
public final class X11SessionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            Bundle binder = intent.getBundleExtra(null);
            if (binder == null) return;
            Bundle extras = new Bundle();
            extras.putString("session", intent.getStringExtra("session"));
            extras.putString("display", intent.getStringExtra("display"));
            extras.putBinder("server", binder.getBinder(null));
            X11Sessions.handoff(intent.getStringExtra("phase"), intent.getStringExtra("token"),
                    extras, getSentFromUid());
        } catch (RemoteException | RuntimeException error) {
            android.util.Log.w("MagicDeskX11", "Rejected X11 session handoff: " + error.getClass().getSimpleName());
        }
    }
}
