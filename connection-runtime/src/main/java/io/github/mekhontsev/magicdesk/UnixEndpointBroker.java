package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import java.nio.file.Path;
import java.util.ArrayList;

/** Executor-local admission delegated to one explicit host UID and lifetime. */
public final class UnixEndpointBroker extends IExecutorUnixEndpoints.Stub {
    private final int ownerUid;
    private final Path temporary;
    private final Runnable ended;
    private final ArrayList<UnixSocketEndpoint> endpoints = new ArrayList<>();
    private final IBinder.DeathRecipient died = this::release;
    private IBinder owner;
    private boolean closed;

    public UnixEndpointBroker(int ownerUid, Path temporary, Runnable ended) {
        this.ownerUid=ownerUid;
        this.temporary=java.util.Objects.requireNonNull(temporary);
        this.ended=java.util.Objects.requireNonNull(ended);
    }

    private void checkOwner() {
        if (Binder.getCallingUid()!=ownerUid) throw new SecurityException("Wrong endpoint broker owner");
    }

    @Override public synchronized void retain(IBinder next) throws RemoteException {
        checkOwner();
        if (owner!=null || closed) throw new IllegalStateException("Endpoint broker is unavailable");
        java.util.Objects.requireNonNull(next).linkToDeath(died,0);
        owner=next;
    }

    @Override public synchronized IUnixEndpoint open(String name,int uid,int timeout,IUnixConnectionReceiver receiver) {
        checkOwner();
        if (owner==null || closed) throw new IllegalStateException("Endpoint broker is not retained");
        if (endpoints.size()>=64) throw new IllegalStateException("Too many connection endpoints");
        try {
            var endpoint=new UnixSocketEndpoint(name,uid,timeout,receiver,temporary);
            endpoints.add(endpoint);
            return endpoint;
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot open endpoint",error); }
    }

    @Override public void close() { checkOwner(); release(); }
    public void expireUnretained() { release(true); }

    public void release() { release(false); }

    private void release(boolean onlyUnretained) {
        java.util.List<UnixSocketEndpoint> owned;
        IBinder lifetime;
        synchronized (this) {
            if (closed || (onlyUnretained && owner!=null)) return;
            closed=true; lifetime=owner; owner=null;
            owned=java.util.List.copyOf(endpoints); endpoints.clear();
        }
        if (lifetime!=null) lifetime.unlinkToDeath(died,0);
        for (var endpoint:owned) endpoint.release();
        ended.run();
    }
}
