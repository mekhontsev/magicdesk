package io.github.mekhontsev.magicdesk.wayland;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot authenticated connection transfer; the caller owns command execution and cancellation. */
public final class WaylandClientLaunch implements AutoCloseable {
    private final Context context;
    private final ParcelFileDescriptor connection;
    private final ClientAdmission admission;
    private final int clientUid;
    private final String action;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean finished;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompletableFuture<Void> transferred = new CompletableFuture<>();
    private final Runnable deadline = () -> finish(new java.util.concurrent.TimeoutException("Wayland FD handoff deadline expired"));
    private final IWaylandClientReceipt receipt = new IWaylandClientReceipt.Stub() {
        @Override public void accepted() {
            if (Binder.getCallingUid() != clientUid) throw new SecurityException("Not the selected client UID");
            handler.post(() -> finish(null));
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context source, Intent intent) {
            if (closed.get()) return;
            String token = intent.getStringExtra("token");
            if (!admission.accept(getSentFromUid(), token == null ? null : token.getBytes(StandardCharsets.US_ASCII))) return;
            try {
                var extras = intent.getExtras();
                var client = extras == null ? null : IWaylandClient.Stub.asInterface(extras.getBinder("client"));
                if (client == null) throw new IOException("Missing Wayland client Binder");
                client.deliver(connection, receipt);
                connection.close();
            } catch (IOException | android.os.RemoteException | RuntimeException error) { finish(error); }
        }
    };

    public WaylandClientLaunch(Context context, ParcelFileDescriptor connection, int clientUid) throws IOException {
        this.context = context.getApplicationContext();
        this.connection = java.util.Objects.requireNonNull(connection);
        this.clientUid = clientUid;
        action = context.getPackageName() + ".WAYLAND_CLIENT_" + UUID.randomUUID();
        try {
            admission = new ClientAdmission(clientUid);
            this.context.registerReceiver(receiver, new IntentFilter(action), null, handler, Context.RECEIVER_EXPORTED);
        } catch (RuntimeException error) {
            connection.close();
            throw error;
        }
        // EVENT_WAIT: authenticated FD delivery receipt; expiry fails and releases the transfer.
        handler.postDelayed(deadline, 10_000);
    }

    public List<String> arguments(String executorPackage, String helper, String executable, String... arguments) {
        if (closed.get()) throw new IllegalStateException("Wayland launch channel is closed");
        if (executorPackage == null || executorPackage.isBlank() || helper == null || !helper.startsWith("/")
                || executable == null || !executable.startsWith("/"))
            throw new IllegalArgumentException("Client package and absolute executable paths are required");
        ArrayList<String> result = new ArrayList<>(List.of("/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                "--nice-name=MagicDeskWaylandClient", WaylandClientMain.class.getName(),
                executorPackage, context.getPackageName(), action, admission.token, helper, executable));
        result.addAll(List.of(arguments));
        return List.copyOf(result);
    }

    public CompletionStage<Void> transferred() { return transferred.minimalCompletionStage(); }

    @Override public void close() {
        closed.set(true);
        handler.post(() -> finish(new IOException("Wayland launch channel is closed")));
    }

    private void finish(Throwable error) {
        if (finished) return;
        finished = true;
        closed.set(true);
        handler.removeCallbacks(deadline);
        context.unregisterReceiver(receiver);
        try { connection.close(); } catch (IOException ignored) { }
        if (error == null) transferred.complete(null);
        else transferred.completeExceptionally(error);
    }
}
