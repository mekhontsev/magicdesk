package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Per-consent web proxy. Only the captured guest UID can open its Unix endpoint. */
final class LinuxInternetProxy implements Closeable {
    private final ConnectivityManager networks;
    private final ExecutorSocketEndpoint endpoint;
    private final Set<Flow> flows=new HashSet<>();
    private boolean closed;

    LinuxInternetProxy(Context context,int uid,ExecutorSocketEndpoint.Factory factory,Runnable failed) throws IOException {
        networks=context.getSystemService(ConnectivityManager.class);
        endpoint=new ExecutorSocketEndpoint(uid,0,factory,this::accept,ignored->failed.run());
    }
    String name() { return endpoint.name; }
    private synchronized void accept(ParcelFileDescriptor offered) throws IOException {
        if (closed || flows.size()>=16) return;
        Flow flow=new Flow(offered);
        flows.add(flow);
        new Thread(flow::serve,"Linux web request").start();
    }
    @Override public void close() {
        Set<Flow> live;
        synchronized (this) { closed=true; live=Set.copyOf(flows); }
        endpoint.close();
        for (Flow flow:live) flow.close();
    }
    private final class Flow implements Closeable {
        final ParcelFileDescriptor guest;
        final ExecutorSocketChannel channel;
        private Socket remote;
        private boolean ended;
        Flow(ParcelFileDescriptor offered) throws IOException {
            guest=ParcelFileDescriptor.dup(offered.getFileDescriptor());
            try { channel=new ExecutorSocketChannel(guest); }
            catch (IOException error) { guest.close(); throw error; }
        }
        @Override public synchronized void close() {
            if (ended) return;
            ended=true;
            if (remote!=null) try { remote.close(); } catch (IOException ignored) { }
            try { Os.shutdown(guest.getFileDescriptor(),OsConstants.SHUT_RDWR); }
            catch (android.system.ErrnoException ignored) { }
            channel.close();
        }
        void serve() {
            try {
                var input=new BufferedInputStream(channel.input);
                var output=channel.output;
                Socket origin;
                PublicWebRequest request;
                try {
                    channel.readTimeout(15000);
                    request=PublicWebRequest.read(input);
                    channel.readTimeout(0);
                    origin=connect(request);
                } catch (IOException | SecurityException denied) {
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII)); return;
                }
                if (request.request()==null) {
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    new Thread(()->{
                        try { input.transferTo(origin.getOutputStream()); origin.shutdownOutput(); }
                        catch (IOException error) { close(); }
                    },"Linux HTTPS upload").start();
                } else origin.getOutputStream().write(request.request());
                origin.getInputStream().transferTo(output);
            } catch (IOException ignored) { /* Cancelled, timed out or peer disconnected. */ }
            finally { close(); synchronized (LinuxInternetProxy.this) { flows.remove(this); } }
        }
        private Socket connect(PublicWebRequest request) throws IOException {
            var network=networks.getActiveNetwork();
            if (network==null) throw new IOException("No active network");
            String host=request.host();
            String fqdn=(host.endsWith(".") ? host : host+".").toLowerCase(java.util.Locale.ROOT);
            if (fqdn.endsWith(".local.") || fqdn.endsWith(".localhost.") || fqdn.endsWith(".home.arpa."))
                throw new IOException("Local names are not web targets");
            // Resolve once on this network. Dial the vetted numeric address, never the hostname.
            for (var address:network.getAllByName(host.matches("[0-9.]+") ? host : fqdn)) {
                if (!PublicWebRequest.publicAddress(address)) continue;
                var links=networks.getLinkProperties(network);
                if (links==null || links.getRoutes().stream().anyMatch(r->!r.isDefaultRoute() && r.matches(address))
                        || onLocalInterface(address)) continue;
                Socket socket=network.getSocketFactory().createSocket();
                synchronized (this) {
                    if (ended) { socket.close(); throw new IOException("Web access revoked"); }
                    remote=socket;
                }
                try {
                    socket.connect(new InetSocketAddress(address,request.port()),10000);
                    socket.setSoTimeout(60000); return socket;
                } catch (IOException error) { socket.close(); }
            }
            throw new IOException("No reachable public IPv4 address");
        }
    }
    private static boolean onLocalInterface(InetAddress address) throws SocketException {
        var devices=NetworkInterface.getNetworkInterfaces();
        if (devices==null) return true;
        while (devices.hasMoreElements()) for (var link:devices.nextElement().getInterfaceAddresses()) {
            if (!(link.getAddress() instanceof Inet4Address)) continue;
            if (new android.net.IpPrefix(link.getAddress(),link.getNetworkPrefixLength()).contains(address)) return true;
        }
        return false;
    }
}
