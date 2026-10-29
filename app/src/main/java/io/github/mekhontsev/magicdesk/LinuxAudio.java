package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A launch-owned audio capability. Binding does not load PipeWire or open Android output. */
final class LinuxAudio implements Closeable {
    private static final java.util.Set<LinuxAudio> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Context context;
    private final Binder lifetime = new Binder();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final OperationResources resources = new OperationResources();
    private final CompletableFuture<ILinuxAudioService> connected = new CompletableFuture<>();
    private final ServiceConnection binding = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            connected.complete(ILinuxAudioService.Stub.asInterface(binder));
        }
        @Override public void onServiceDisconnected(ComponentName name) { failed(new IOException("Audio service disconnected")); }
        @Override public void onNullBinding(ComponentName name) { failed(new IOException("Audio service rejected binding")); }
        @Override public void onBindingDied(ComponentName name) { failed(new IOException("Audio service binding died")); }
    };
    private ILinuxAudioLease lease;
    private ExecutorSocketEndpoint nativeEndpoint, pulseEndpoint;
    private String nativePath, pulsePath;

    private LinuxAudio(Context context) { this.context = context.getApplicationContext(); }

    static LinuxAudio prepare(Context context, CommandExecution commands) {
        try { return acquire(context, commands); }
        catch (IOException error) {
            CompatibilityDiagnostics.record("LINUX-AUDIO-001", "Linux audio is unavailable",
                    ShellAccess.usefulMessage(error), error);
            LinuxAudio unavailable = new LinuxAudio(context);
            unavailable.close();
            return unavailable;
        }
    }

    static LinuxAudio acquire(Context context, CommandExecution commands) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Audio admission requires a worker");
        LinuxAudio audio = new LinuxAudio(context);
        try {
            var binding = audio.resources.reserve();
            boolean bound = audio.context.bindService(new Intent(audio.context, LinuxAudioService.class),
                    audio.binding, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IOException("Cannot bind Linux audio service");
            binding.attach(() -> audio.context.unbindService(audio.binding));
            var lease = audio.resources.reserve();
            audio.lease = await(audio.connected, "audio service").acquire("audio-" + UUID.randomUUID(), audio.lifetime);
            lease.attach(() -> { try { audio.lease.close(); } catch (RemoteException ignored) { } });
            var brokerSlot = audio.resources.reserve();
            var broker = new ExecutorEndpointBroker(context, commands, audio::failed);
            brokerSlot.attach(broker);
            ExecutorSocketEndpoint.Factory factory = broker.factory;
            var nativeEndpoint = audio.resources.reserve();
            audio.nativeEndpoint = new ExecutorSocketEndpoint(commands.uid, 0, factory,
                    fd -> audio.lease.accept(0, fd), audio::failed);
            nativeEndpoint.attach(audio.nativeEndpoint);
            var pulseEndpoint = audio.resources.reserve();
            audio.pulseEndpoint = new ExecutorSocketEndpoint(commands.uid, 0, factory,
                    fd -> audio.lease.accept(1, fd), audio::failed);
            pulseEndpoint.attach(audio.pulseEndpoint);
            // Shroot supplies guest filesystem names for abstract endpoints; shell needs no sock_file permission.
            if (commands.uid != 2000) {
                audio.nativePath = audio.nativeEndpoint.filesystemPath();
                audio.pulsePath = audio.pulseEndpoint.filesystemPath();
            }
            LIVE.add(audio);
            if (audio.closed.get()) LIVE.remove(audio);
            if (audio.closed.get()) throw new IOException("Audio admission closed during startup");
            return audio;
        } catch (IOException | RuntimeException | RemoteException error) {
            audio.close();
            throw new IOException("Cannot prepare Linux audio", error);
        }
    }

    String exports() {
        return TerminalLaunchEnvironment.exports(environment());
    }
    java.util.Map<String, String> environment() {
        if (closed.get()) return java.util.Map.of();
        var result = new java.util.LinkedHashMap<String, String>();
        result.put("PIPEWIRE_REMOTE", nativePath == null ? "@" + nativeEndpoint.name : nativePath);
        result.put("MAGICDESK_AUDIO_NATIVE_ENDPOINT", nativeEndpoint.name);
        result.put("MAGICDESK_AUDIO_PULSE_ENDPOINT", pulseEndpoint.name);
        if (nativePath != null) {
            result.put("PULSE_SERVER", "unix:" + pulsePath);
            result.put("MAGICDESK_AUDIO_PIPEWIRE", nativePath);
            result.put("MAGICDESK_AUDIO_PULSE", pulsePath);
        }
        return result;
    }

    long[] snapshot() throws RemoteException { return lease.snapshot(); }

    static String diagnostics() {
        int count = LIVE.size();
        for (LinuxAudio audio : LIVE) {
            try {
                long[] s = audio.snapshot();
                return "leases=" + count + ", active=" + (s[0] != 0) + ", error=" + s[1]
                        + ", receivedFrames=" + s[2] + ", renderedFrames=" + s[3]
                        + ", silenceFrames=" + s[4] + ", overruns=" + s[5]
                        + ", callbacks=" + s[7] + ", queuedFrames=" + s[8] + ", androidXruns=" + s[9];
            } catch (RemoteException | RuntimeException error) {
                return "leases=" + count + ", observation=unavailable";
            }
        }
        return "leases=0, runtime=inactive";
    }

    private void failed(Throwable error) {
        connected.completeExceptionally(error);
        android.util.Log.e("MagicDeskAudio", "Audio launch unavailable", error);
        close();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        LIVE.remove(this);
        connected.completeExceptionally(new IOException("Audio launch closed"));
        resources.close();
    }

    private static <T> T await(CompletableFuture<T> value, String operation) throws IOException {
        try {
            // EVENT_WAIT: exact Binder service/admission announcement; expiry rejects this launch.
            return value.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException(operation + " interrupted", error);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IOException(operation + " unavailable", error);
        }
    }
}
