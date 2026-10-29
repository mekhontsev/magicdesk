package io.github.mekhontsev.magicdesk;

import android.content.*;
import android.os.*;
import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Executor-owned Unix admission, shared by optional Linux services, independent of privileged startup. */
final class ExecutorEndpointBroker implements Closeable {
    private final OperationResources resources = new OperationResources();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Binder lifetime = new Binder();
    final ExecutorSocketEndpoint.Factory factory;

    ExecutorEndpointBroker(Context context, CommandExecution commands, Consumer<Throwable> failed) throws IOException {
        if (commands.termux == null) { factory = ShellAccess::openUnixEndpoint; return; }
        String nonce = UUID.randomUUID().toString();
        CompletableFuture<IExecutorUnixEndpoints> ready = new CompletableFuture<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (getSentFromUid() != commands.uid || !nonce.equals(intent.getStringExtra("nonce"))) return;
                var extras = intent.getExtras();
                IBinder binder = extras == null ? null : extras.getBinder("endpoints");
                if (binder != null) ready.complete(IExecutorUnixEndpoints.Stub.asInterface(binder));
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ExecutorUnixEndpointsMain.ACTION), Context.RECEIVER_EXPORTED);
        try {
            String command = "exec env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + q(context.getApplicationInfo().sourceDir)
                    + " /system/bin/app_process -Xnoimage-dex2oat / --nice-name=magicdesk-unix-admission "
                    + ExecutorUnixEndpointsMain.class.getName() + " " + q(context.getPackageName())
                    + " " + q(commands.termux.packageName) + " " + q(nonce);
            var slot = resources.reserve();
            slot.attach(commands.start(command, "", "Linux service admission", null, (code, output, error) -> {
                IOException failure = new IOException("Executor admission exited: " + code, error);
                ready.completeExceptionally(failure);
                if (!closed.get()) failed.accept(failure);
                close();
            }));
            IExecutorUnixEndpoints broker;
            try {
                // EVENT_WAIT: exact executor Binder announcement; deadline rejects startup.
                broker = ready.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Admission interrupted", error); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                throw new IOException("Executor admission unavailable", error);
            }
            resources.reserve().attach(() -> { try { broker.close(); } catch (RemoteException ignored) { } });
            broker.retain(lifetime);
            factory = broker::open;
            if (closed.get()) throw new IOException("Executor admission closed during startup");
        } catch (IOException | RemoteException | RuntimeException error) {
            close(); throw new IOException("Cannot prepare executor admission", error);
        } finally { context.unregisterReceiver(receiver); }
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) resources.close(); }
    private static String q(String s) { return ShellCommandLine.quote(s); }
}
