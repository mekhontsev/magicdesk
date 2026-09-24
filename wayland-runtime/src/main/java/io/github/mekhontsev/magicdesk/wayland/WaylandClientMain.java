package io.github.mekhontsev.magicdesk.wayland;

import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import io.github.mekhontsev.magicdesk.hosted.HostedProcessContext;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs under the selected executor identity, then replaces itself with the native client. */
public final class WaylandClientMain {
    private WaylandClientMain() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 6) throw new IllegalArgumentException("Missing Wayland client startup arguments");
        Looper.prepareMainLooper();
        var handler = new Handler(Looper.getMainLooper());
        var context = HostedProcessContext.create(arguments[0]);
        int hostUid = context.getPackageManager().getPackageUid(arguments[1], 0);
        var delivered = new AtomicBoolean();
        Runnable deadline = () -> System.exit(124);
        var client = new IWaylandClient.Stub() {
            @Override public void deliver(ParcelFileDescriptor connection, IWaylandClientReceipt receipt) {
                if (Binder.getCallingUid() != hostUid || !delivered.compareAndSet(false, true)) {
                    if (connection != null) try { connection.close(); } catch (IOException ignored) { }
                    throw new SecurityException("Unauthorized or repeated client FD delivery");
                }
                handler.post(() -> {
                    try (connection) {
                        if (connection == null || receipt == null) throw new IOException("Missing client connection");
                        String[] command = new String[arguments.length - 3];
                        command[0] = arguments[4];
                        command[1] = Integer.toString(connection.getFd());
                        System.arraycopy(arguments, 5, command, 2, arguments.length - 5);
                        Os.fcntlInt(connection.getFileDescriptor(), OsConstants.F_SETFD, 0);
                        receipt.accepted();
                        handler.removeCallbacks(deadline);
                        Os.execv(command[0], command);
                    } catch (Exception error) { error.printStackTrace(); System.exit(127); }
                });
            }
        };
        Bundle extras = new Bundle();
        extras.putBinder("client", client);
        extras.putString("token", arguments[3]);
        // EVENT_WAIT: host FD delivery; expiry exits without starting the client.
        handler.postDelayed(deadline, 10_000);
        context.sendBroadcast(new Intent(arguments[2]).setPackage(arguments[1]).putExtras(extras), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        Looper.loop();
    }
}
