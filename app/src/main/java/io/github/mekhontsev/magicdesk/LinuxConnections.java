package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import java.io.IOException;

/** Explicit foreign-UID capabilities. No command, storage or privileged-service delegation. */
final class LinuxConnections {
    private static final java.util.Set<Connection> LIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();

    static Connection acquire(Context context,int uid,IExecutorUnixEndpoints endpoints,GraphicalSessions.Session graphics,
            OperationResources operation) throws IOException {
        var result=new Connection(uid);
        operation.reserve().attach(result::release);
        try {
            result.requireOpen();
            endpoints.asBinder().linkToDeath(result.died,0);
            result.resources.reserve().attach(() -> endpoints.asBinder().unlinkToDeath(result.died,0));
            endpoints.retain(result.lifetime);
            result.resources.reserve().attach(() -> { try { endpoints.close(); } catch (RemoteException ignored) { } });
            var owner=result.resources.reserve();
            result.audio=LinuxAudio.acquire(context,uid,endpoints::open);
            owner.attach(result.audio);
            if (graphics!=null) {
                var graphicsOwner=result.resources.reserve();
                result.graphics=graphics.connect(uid,endpoints::open,result::release);
                graphicsOwner.attach(result.graphics);
            }
            result.requireOpen();
            LIVE.add(result);
            if (result.closed.get()) { LIVE.remove(result); throw new IOException("Connection cancelled"); }
            return result;
        } catch (RemoteException | IOException | RuntimeException error) {
            result.release(); throw new IOException("Cannot connect Linux services",error);
        }
    }

    static final class Connection extends ILinuxConnection.Stub {
        final int uid;
        final Binder lifetime=new Binder();
        final OperationResources resources=new OperationResources();
        final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        final IBinder.DeathRecipient died=this::release;
        private LinuxAudio audio;
        private GraphicalConnection graphics;
        Connection(int uid) { this.uid=uid; }
        void requireOpen() throws IOException { if (closed.get()) throw new IOException("Linux connection is closed"); }
        private void checkOwner() { if (Binder.getCallingUid()!=uid) throw new SecurityException("Wrong Linux connection owner"); }
        @Override public Bundle describe() {
            checkOwner();
            if (closed.get()) throw new IllegalStateException("Linux connection is closed");
            var environment=audio.environment();
            var result=new Bundle(); result.putInt("format",LinuxConnectionContract.FORMAT);
            result.putString("pipewire",environment.get("MAGICDESK_AUDIO_NATIVE_ENDPOINT"));
            result.putString("pulse",environment.get("MAGICDESK_AUDIO_PULSE_ENDPOINT"));
            if (graphics!=null) result.putAll(graphics.describe());
            return result;
        }
        @Override public void close() { checkOwner(); release(); }
        void release() {
            if (!closed.compareAndSet(false,true)) return;
            LIVE.remove(this); resources.close();
        }
    }
    private LinuxConnections() { }
}
