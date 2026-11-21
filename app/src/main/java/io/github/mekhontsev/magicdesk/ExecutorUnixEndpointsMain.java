package io.github.mekhontsev.magicdesk;

import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.mekhontsev.magicdesk.hosted.HostedProcessContext;

/** Endpoint admission for a captured non-shell executor; never copies protocol bytes. */
public final class ExecutorUnixEndpointsMain {
    static final String ACTION = "io.github.mekhontsev.magicdesk.UNIX_ENDPOINTS_READY";
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected host, executor and nonce");
        Looper.prepareMainLooper();
        var context = HostedProcessContext.create(args[1]);
        int ownerUid = context.getPackageManager().getPackageUid(args[0], 0);
        Handler handler = new Handler(Looper.getMainLooper());
        var endpoint = new UnixEndpointBroker(ownerUid,
                java.nio.file.Path.of(java.util.Objects.requireNonNull(System.getenv("TMPDIR"))),
                () -> handler.post(() -> System.exit(0)));
        // EVENT_WAIT: authenticated owner retention; expiry releases this unclaimed broker.
        handler.postDelayed(endpoint::expireUnretained, 30_000);
        Bundle extras = new Bundle();
        extras.putBinder("endpoints", endpoint);
        extras.putString("nonce", args[2]);
        context.sendBroadcast(new Intent(ACTION).setPackage(args[0]).putExtras(extras), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        Looper.loop();
    }
}
