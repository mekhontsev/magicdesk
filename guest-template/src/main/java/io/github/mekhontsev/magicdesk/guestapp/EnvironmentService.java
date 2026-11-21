package io.github.mekhontsev.magicdesk.guestapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;
import io.github.mekhontsev.magicdesk.ConsoleTerminalInputConnection;
import io.github.mekhontsev.magicdesk.NativePtyProcess;
import io.github.mekhontsev.magicdesk.OperationResources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/** Owns this APK's processes and data. Activity and MagicDesk lifetimes are irrelevant. */
public final class EnvironmentService extends Service implements ConsoleTerminalInputConnection.Session {
    final class Connection extends Binder { EnvironmentService service() { return EnvironmentService.this; } }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService work = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService input = Executors.newSingleThreadExecutor();
    private volatile NativePtyProcess terminal;
    private final OperationResources lifetime=new OperationResources();
    private OperationResources operation;
    private volatile boolean closed;
    private TerminalEmulator emulator;
    private boolean opened;
    private HostConnection host;
    private HostConnection pendingTerminal;
    private OperationResources.Slot hostOwner;
    private long hostGeneration;
    Runnable changed;
    String status = "";

    @Override public void onCreate() {
        super.onCreate();
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("linux", "Linux session", NotificationManager.IMPORTANCE_LOW));
        var show = PendingIntent.getActivity(this,0,new Intent(this,EnvironmentActivity.class),PendingIntent.FLAG_IMMUTABLE);
        var stop = PendingIntent.getService(this,1,new Intent(this,EnvironmentService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(1,new Notification.Builder(this,"linux").setSmallIcon(R.drawable.launcher_icon)
                .setContentTitle(getApplicationInfo().loadLabel(getPackageManager())).setContentText("Linux session")
                .setContentIntent(show).setOngoing(true).addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop",stop).build()).build());
        emulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] bytes,int offset,int count) { EnvironmentService.this.write(java.util.Arrays.copyOfRange(bytes,offset,offset+count)); }
            @Override public void titleChanged(String old,String value) { notifyChanged(); }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { notifyChanged(); }
        },80,24,8,16,2000,new TerminalSessionClient() {
            @Override public void onTerminalCursorStateChange(boolean state) { }
            @Override public Integer getTerminalCursorStyle() { return 0; }
            @Override public void logError(String tag,String text) { android.util.Log.e(tag,text); }
            @Override public void logWarn(String tag,String text) { android.util.Log.w(tag,text); }
            @Override public void logInfo(String tag,String text) { android.util.Log.i(tag,text); }
            @Override public void logDebug(String tag,String text) { android.util.Log.d(tag,text); }
            @Override public void logVerbose(String tag,String text) { android.util.Log.v(tag,text); }
            @Override public void logStackTraceWithMessage(String tag,String text,Exception error) { android.util.Log.e(tag,text,error); }
            @Override public void logStackTrace(String tag,Exception error) { android.util.Log.e(tag,"Terminal",error); }
        });
    }

    @Override public IBinder onBind(Intent intent) { return new Connection(); }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (intent != null && "stop".equals(intent.getAction())) { shutdown(); stopSelf(); }
        return START_NOT_STICKY;
    }
    Path store() { return getDataDir().toPath().resolve("linux"); }
    boolean installed() { return Files.isDirectory(store()); }
    boolean active() { return operation != null; }
    boolean closed() { return closed; }
    boolean connected() { return host != null && host.ready(); }
    boolean canConnectHost() { return !closed && installed() && host==null && (!active() || terminal!=null); }
    boolean awaitingTerminalChoice() { return pendingTerminal!=null && !replaceTerminal; }
    private boolean replaceTerminal;
    void confirmTerminalReplacement() {
        if (!awaitingTerminalChoice()) return;
        replaceTerminal=true;
        endTerminal();
    }
    @Override public TerminalEmulator emulator() { return emulator; }

    private ProcessBuilder command(String... arguments) throws IOException {
        String nativeDir=getApplicationInfo().nativeLibraryDir;
        var words=new java.util.ArrayList<String>();
        words.add(nativeDir+"/libshroot_cli.so");
        words.addAll(java.util.List.of("--parent-pid",Integer.toString(android.os.Process.myPid())));
        words.addAll(java.util.List.of(arguments));
        return environment(new ProcessBuilder(words));
    }
    private ProcessBuilder environment(ProcessBuilder builder) throws IOException {
        Path temp=Files.createDirectories(getCacheDir().toPath().resolve("tmp"));
        builder.directory(getDataDir());
        builder.environment().remove("LD_PRELOAD"); builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().put("HOME",getDataDir().toString());
        builder.environment().put("TMPDIR",temp.toString());
        builder.environment().put("PATH","/system/bin");
        builder.environment().put("TERM","xterm-256color");
        builder.environment().put("COLORTERM","truecolor");
        return builder;
    }

    void importArchive(Uri uri) { archive(uri,true); }
    void exportArchive(Uri uri) { archive(uri,false); }
    private void archive(Uri uri,boolean restore) {
        if (active() || closed || restore==installed()) return;
        var resources=new OperationResources(); operation=resources;
        var owner=lifetime.reserve(); owner.attach(resources);
        status=restore ? "Restoring Linux" : "Saving Linux backup"; notifyChanged();
        work.execute(() -> {
            Exception failure=null;
            try {
                if (restore) EnvironmentArchives.restore(getCacheDir().toPath(),store(),
                        () -> getContentResolver().openInputStream(uri),this::command,resources);
                else EnvironmentArchives.backup(getCacheDir().toPath(),store(),
                        () -> getContentResolver().openOutputStream(uri,"wt"),this::command,resources);
            } catch (Exception error) {
                failure=error;
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            Exception error=failure;
            main.post(() -> {
                // Complete on the same thread that accepts cancellation, before releasing its token.
                boolean cancelled=resources.isClosed();
                owner.close(); operation=null;
                status=cancelled ? "Archive operation cancelled" : error!=null ? error.toString() : restore ? "" : "Backup saved";
                notifyChanged();
                if (restore && !cancelled && error==null && !closed) startTerminal();
            });
        });
    }

    void startTerminal() {
        if (active() || !installed() || closed) return;
        opened=true;
        int rows=emulator.mRows, columns=emulator.mColumns;
        var connections=host==null ? java.util.List.<String>of() : host.arguments();
        var scoped=host!=null && host.sessionScoped() ? host : null;
        boolean temporaryInternet=scoped!=null && scoped.internet();
        var resources=new OperationResources(); operation=resources;
        if (scoped!=null) resources.reserve().attach(scoped);
        var owner=lifetime.reserve();
        owner.attach(resources);
        status="Starting Linux"; notifyChanged();
        work.execute(() -> {
            NativePtyProcess pty=null;
            Exception failure=null;
            try {
                String nativeDir=getApplicationInfo().nativeLibraryDir;
                var folders=io.github.mekhontsev.magicdesk.documents.FolderMountLease.open(this,
                        io.github.mekhontsev.magicdesk.documents.FolderMounts.INDEPENDENT, android.os.Process.myUid(),
                        (name,uid,timeout,receiver) -> new io.github.mekhontsev.magicdesk.UnixSocketEndpoint(
                                name,uid,timeout,receiver,getCacheDir().toPath()));
                resources.reserve().attach(folders);
                var bindings=new java.util.ArrayList<>(connections);
                bindings.addAll(folders.arguments());
                var profile=io.github.mekhontsev.magicdesk.StandaloneNetwork.read(this);
                if (temporaryInternet && profile.mode()!=io.github.mekhontsev.magicdesk.GuestNetworkProfile.Mode.NONE)
                    throw new IOException("Temporary web access requires the private network profile. Reconnect using the new settings.");
                bindings.addAll(profile.arguments());
                if (scoped!=null) {
                    var helper=io.github.mekhontsev.magicdesk.LinuxConnectionContract.SESSION_HELPER;
                    bindings.addAll(java.util.List.of("--bind-ro-create",nativeDir,
                            io.github.mekhontsev.magicdesk.LinuxConnectionContract.HELPERS,"--","/bin/sh","-c",
                            "exec "+helper+" --connected-login \"$SHELL\" -l"));
                }
                String startup=EnvironmentCommand.terminal(nativeDir+"/libshroot_cli.so",store().toString(),bindings);
                pty=new NativePtyProcess(environment(new ProcessBuilder(nativeDir+"/libmagicdesk_pty_bridge.so",
                        Integer.toString(rows),Integer.toString(columns),getDataDir().toString(),
                        nativeDir+"/libmagicdesk_mksh.so",startup)),resources);
                if (resources.isClosed()) { pty.close(); return; }
                terminal=pty;
                main.post(() -> {
                    if (operation==resources && !resources.isClosed() && !closed) {
                        status=""; resizeTerminal(); notifyChanged();
                    }
                });
                byte[] buffer=new byte[32768]; int n;
                while ((n=pty.inputStream().read(buffer))>=0) {
                    byte[] bytes=java.util.Arrays.copyOf(buffer,n);
                    var delivered=new java.util.concurrent.CountDownLatch(1);
                    main.post(() -> { try { emulator.append(bytes,bytes.length); notifyChanged(); } finally { delivered.countDown(); } });
                    // EVENT_WAIT: owning UI thread consumes one bounded output chunk; service shutdown interrupts the reader.
                    delivered.await();
                }
            } catch (Exception error) {
                failure=error;
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally {
                Exception error=failure;
                boolean cancelled=resources.isClosed();
                owner.close();
                main.post(() -> {
                    terminal=null; operation=null;
                    if (!closed && pendingTerminal!=null && pendingTerminal==host) {
                        pendingTerminal=null; replaceTerminal=false; startTerminal(); return;
                    }
                    status=cancelled || error==null ? "Session ended" : error.toString();
                    notifyChanged();
                });
            }
        });
    }
    void open() { if (!opened) startTerminal(); }
    @Override public void write(byte[] data) {
        NativePtyProcess pty=terminal;
        if (pty==null || closed) return;
        byte[] captured=data.clone();
        input.execute(() -> { try { pty.write(captured); } catch (IOException error) {
            main.post(() -> {
                if (terminal==pty && operation!=null && !operation.isClosed() && !closed) {
                    status=error.toString(); notifyChanged();
                }
            });
        } });
    }
    void resize(int columns,int rows,int cellWidth,int cellHeight) {
        if (emulator.mColumns==columns && emulator.mRows==rows
                && emulator.getCellWidthPixels()==cellWidth && emulator.getCellHeightPixels()==cellHeight) return;
        emulator.resize(columns,rows,cellWidth,cellHeight);
        resizeTerminal();
        notifyChanged();
    }
    private void resizeTerminal() {
        NativePtyProcess pty=terminal;
        int rows=emulator.mRows, columns=emulator.mColumns;
        if (pty!=null && !closed) input.execute(() -> { try { pty.resize(rows,columns); } catch (IOException ignored) { } });
    }
    void endTerminal() {
        if (operation!=null) operation.close();
    }
    Intent connectHost() throws Exception {
        if (!canConnectHost()) throw new IOException("Linux is not ready for a connection");
        long generation=hostGeneration;
        host=new HostConnection(this,() -> main.post(() -> {
            if (generation==hostGeneration) { disconnectHost(); notifyChanged(); }
        }));
        hostOwner=lifetime.reserve(); hostOwner.attach(host);
        return host.request(io.github.mekhontsev.magicdesk.StandaloneNetwork.read(this).mode()
                ==io.github.mekhontsev.magicdesk.GuestNetworkProfile.Mode.NONE);
    }
    void acceptHost(Intent reply) {
        HostConnection pending=host;
        if (pending==null || closed) return;
        new Thread(() -> {
            try {
                pending.accept(reply);
                main.post(() -> {
                    if (pending!=host || closed) return;
                    if (pending.sessionScoped() && !active() && installed()) { startTerminal(); return; }
                    if (pending.sessionScoped() && active()) pendingTerminal=pending;
                    status=!active() ? "MagicDesk connected" :
                            "MagicDesk connected. Save your work, then choose End session and New terminal to use it.";
                    notifyChanged();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (pending==host) { disconnectHost(); status=error.toString(); notifyChanged(); }
                });
            }
        },"LinuxHostConnection").start();
    }
    void disconnectHost() {
        hostGeneration++; host=null; pendingTerminal=null; replaceTerminal=false;
        if (hostOwner!=null) { hostOwner.close(); hostOwner=null; }
    }
    private void notifyChanged() { if (changed!=null) changed.run(); }
    private void shutdown() {
        if (closed) return;
        closed=true; lifetime.close();
        work.shutdownNow(); input.shutdownNow();
        notifyChanged(); changed=null;
    }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
