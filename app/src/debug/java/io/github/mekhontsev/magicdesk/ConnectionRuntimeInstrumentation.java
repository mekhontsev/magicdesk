package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real FD custody, cancellation and admission; it does not create a Desktop or display. */
public final class ConnectionRuntimeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        new Thread(() -> {
            var result=new Bundle();
            HandlerThread events=new HandlerThread("UnixScopeVerification"); events.start();
            try {
                verifyScope(events);
                verifyAdmission();
                verifyRoutedAudio();
                result.putString("result","PASS direct traffic, scoped revocation, peer cleanup, exact handoff, broker rejection and path-independent audio");
                finish(Activity.RESULT_OK,result);
            } catch (Throwable error) {
                result.putString("error",android.util.Log.getStackTraceString(error));
                finish(Activity.RESULT_CANCELED,result);
            } finally { events.quitSafely(); }
        },"ConnectionVerification").start();
    }

    private void verifyRoutedAudio() throws Exception {
        var unused=getTargetContext().getCacheDir().toPath().resolve("unused-"+"x".repeat(150));
        var broker=new UnixEndpointBroker(Os.getuid(),unused,() -> { });
        broker.retain(new Binder());
        try (var audio=LinuxAudio.acquireRouted(getTargetContext(),Os.getuid(),broker::open)) {
            var environment=audio.environment();
            check(environment.get("PIPEWIRE_REMOTE").startsWith("@magicdesk-"),"Routed audio requires a filesystem socket");
            check(environment.containsKey("MAGICDESK_AUDIO_PULSE_ENDPOINT"),"Pulse route missing");
            check(!java.nio.file.Files.exists(unused),"Routed admission created unused filesystem endpoints");
        } finally { broker.release(); }
    }

    private static void verifyScope(HandlerThread events) throws Exception {
        try (var scope=new UnixConnectionScope(events.getLooper().getQueue());
             var other=new UnixConnectionScope(events.getLooper().getQueue());
             var first=new Pair(); var second=new Pair()) {
            scope.retain(first.server); other.retain(second.server);
            first.send(41); first.receive(41); second.send(42); second.receive(42);
            scope.close();
            check(Os.read(first.peer.getFileDescriptor(),new byte[1],0,1)==0,"Revoked peer did not receive EOF");
            second.send(43); second.receive(43);
            check(other.size()==1,"Revocation affected another scope");
            second.peer.close();
            check(BoundedStateAwaiter.awaitUnchecked(BoundedStateAwaiter.Reason.RUNTIME_READY,3000,10,
                    other::size,count -> count==0)==0,"Closed peer retained its descriptor");
            try { scope.retain(second.server); throw new AssertionError("Closed scope admitted a connection"); }
            catch (IOException expected) { }
        }
    }

    private void verifyAdmission() throws Exception {
        int uid=Os.getuid();
        var temporary=getTargetContext().getCacheDir().toPath();
        var ended=new java.util.concurrent.atomic.AtomicInteger();
        var broker=new UnixEndpointBroker(uid,temporary,ended::incrementAndGet);
        var accepted=new CompletableFuture<Offer>();
        var receiver=new IUnixConnectionReceiver.Stub() {
            @Override public void accepted(long serial,ParcelFileDescriptor fd) {
                try { accepted.complete(new Offer(serial,ParcelFileDescriptor.dup(fd.getFileDescriptor()))); }
                catch (IOException error) { accepted.completeExceptionally(error); }
            }
            @Override public void failed(String message) { accepted.completeExceptionally(new IOException(message)); }
        };
        String name="magicdesk-"+java.util.UUID.randomUUID();
        try {
            try { broker.open(name,uid,1000,receiver); throw new AssertionError("Unretained broker admitted a connection"); }
            catch (IllegalStateException expected) { }
            broker.retain(new Binder());
            broker.expireUnretained();
            check(ended.get()==0,"Startup deadline revoked a retained broker");
            IUnixEndpoint endpoint=broker.open(name,uid,1000,receiver);
            try (var socket=new LocalSocket()) {
                socket.connect(new LocalSocketAddress(name));
                socket.setSoTimeout(3000);
                // EVENT_WAIT: exact accepted descriptor; expiry fails admission verification.
                Offer offer=accepted.get(3,TimeUnit.SECONDS);
                try (var fd=offer.fd) {
                    endpoint.acknowledge(offer.serial);
                    socket.getOutputStream().write(73);
                    byte[] byteValue=new byte[1];
                    check(Os.read(fd.getFileDescriptor(),byteValue,0,1)==1 && byteValue[0]==73,"Descriptor traffic changed");
                }
            }
            String path=endpoint.filesystemPath();
            check((Os.stat(java.nio.file.Path.of(path).getParent().toString()).st_mode&0777)==0700,"Endpoint directory is not private");
            endpoint.close(); broker.close(); broker.release();
            check(ended.get()==1,"Broker cleanup was not idempotent");
            check(!java.nio.file.Files.exists(java.nio.file.Path.of(path)),"Endpoint file survived release");
            var rejected=new UnixEndpointBroker(uid+1,temporary,() -> { });
            try {
                try { rejected.retain(new Binder()); throw new AssertionError("Wrong UID retained the broker"); }
                catch (SecurityException expected) { }
            } finally { rejected.release(); }
            var expired=new UnixEndpointBroker(uid,temporary,() -> { });
            expired.expireUnretained();
            try { expired.retain(new Binder()); throw new AssertionError("Expired broker accepted an owner"); }
            catch (IllegalStateException expected) { }
        } finally { broker.release(); }
    }

    private record Offer(long serial,ParcelFileDescriptor fd) { }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static final class Pair implements AutoCloseable {
        final ParcelFileDescriptor server,peer;
        Pair() throws Exception {
            FileDescriptor one=new FileDescriptor(),two=new FileDescriptor();
            Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_STREAM,0,one,two);
            ParcelFileDescriptor first=null,second=null;
            try {
                first=ParcelFileDescriptor.dup(one); second=ParcelFileDescriptor.dup(two);
                Os.setsockoptTimeval(first.getFileDescriptor(),OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,
                        android.system.StructTimeval.fromMillis(3000));
                Os.setsockoptTimeval(second.getFileDescriptor(),OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,
                        android.system.StructTimeval.fromMillis(3000));
            } catch (Exception error) {
                if (first!=null) first.close();
                if (second!=null) second.close();
                throw error;
            }
            finally { Os.close(one); Os.close(two); }
            server=first; peer=second;
        }
        void send(int value) throws Exception { check(Os.write(peer.getFileDescriptor(),new byte[]{(byte)value},0,1)==1,"Short socket write"); }
        void receive(int value) throws Exception {
            byte[] data=new byte[1];
            check(Os.read(server.getFileDescriptor(),data,0,1)==1 && data[0]==value,"Scope consumed protocol data");
        }
        @Override public void close() throws IOException { try { server.close(); } finally { peer.close(); } }
    }
}
