package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class ShellFrameworkInitializationTest {
    @Test public void unavailableLauncherDoesNotStartAnotherBackend() throws Exception {
        verify("""
                launcher.available = false;
                connection.connect();
                check(launcher.connections.isEmpty(), "unavailable launcher attempted a bind");
                launcher.available = true;
                connection.connect();
                check(launcher.connections.size() == 1, "available launcher was not used");
                """);
    }
    @Test public void settingAndIdentityAreVerifiedBeforePublishing() throws Exception {
        verify("""
                connection.connect();
                check(connection.connectedService() == null, "unverified service published");
                launcher.deliver(0, service);
                check(connection.connectedService() == service, "binding not published");
                check(service.toggle == 1 && service.error.isEmpty(), "wrong setting snapshot");
                check(reads == 1 && callbacks == 1, "repeated initialization");
                check(connection.snapshot(launcher.inspect()).uid == 2000, "wrong observed UID");
                """);
    }

    @Test public void unknownSettingDoesNotDisableIndependentServices() throws Exception {
        verify("""
                rejectSetting = true;
                connection.connect();
                launcher.deliver(0, service);
                check(connection.connectedService() == service, "Settings failure disabled service");
                check(service.error.contains("settings denied"), "missing unknown reason");
                """);
    }

    @Test public void initializationFailureIsTerminalUntilExplicitRetry() throws Exception {
        verify("""
                rejectService = true;
                connection.connect();
                launcher.deliver(0, service);
                connection.connect();
                check(connection.connectedService() == null, "published uninitialized binding");
                check(launcher.connections.size() == 1 && launcher.closed == 1, "automatic failure loop");
                check(callbacks == 1, "failure not announced");
                connection.clear();
                connection.connect();
                check(launcher.connections.size() == 2, "explicit retry prevented");
                """);
    }

    @Test public void cancelledCallbacksCannotReplaceCurrentBinding() throws Exception {
        verify("""
                connection.connect();
                connection.clear();
                connection.connect();
                launcher.deliver(0, service);
                check(connection.connectedService() == null && reads == 0, "stale callback initialized service");
                launcher.deliver(1, service);
                launcher.connections.get(0).onServiceDisconnected(new ComponentName());
                check(connection.connectedService() == service, "stale disconnect cleared current service");
                check(launcher.closed == 1, "cancel did not release old binding");
                """);
    }

    @Test public void forcedShellRejectsRootService() throws Exception {
        verify("""
                forceShell = true;
                service.identity = 0;
                connection.connect();
                launcher.deliver(0, service);
                check(connection.connectedService() == null, "root accepted under forced shell policy");
                check(reads == 0, "framework initialized before identity validation");
                check(connection.snapshot(launcher.inspect()).uid == -1, "unverified UID reported");
                """);
    }

    @Test public void staleApkBinderIsRejected() throws Exception {
        verify("""
                service.build = "old";
                connection.connect();
                launcher.deliver(0, service);
                check(connection.connectedService() == null && reads == 0, "old service accepted");
                """);
    }

    private static void verify(String scenario) throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "ShellServiceConnection.java"));
        RuntimeSourceFixture.verify("""
                static final Object appContext = new Object();
                static boolean rejectSetting, rejectService, forceShell;
                static int reads, callbacks;
                static class RemoteException extends Exception {}
                static class ComponentName {}
                interface IBinder { boolean pingBinder(); }
                interface ServiceConnection {
                    void onServiceConnected(ComponentName name, IBinder binder);
                    void onServiceDisconnected(ComponentName name);
                }
                static class Looper {
                    static final Looper main = new Looper();
                    static Looper myLooper() { return main; }
                    static Looper getMainLooper() { return main; }
                }
                static class SystemClock { static long uptimeMillis() { return 1; } }
                static class EventDrivenWaits {
                    enum Reason { SERVICE_BINDING }
                    static void await(Object lock, Reason reason, long timeout) throws InterruptedException {
                        throw new AssertionError("unexpected wait");
                    }
                }
                static class IShellCommandService implements IBinder {
                    String build = "build";
                    int identity = 2000, toggle;
                    String error;
                    String sourceId() { return build; }
                    int uid() { return identity; }
                    public boolean pingBinder() { return true; }
                    void initializeFramework(int value, String failure) throws RemoteException {
                        check(connection.connectedService() == null, "binding published before initialization");
                        if (rejectService) throw new RemoteException();
                        toggle = value; error = failure;
                    }
                    static class Stub {
                        static IShellCommandService asInterface(IBinder binder) {
                            return (IShellCommandService) binder;
                        }
                    }
                }
                static class MagicDeskApplication {
                    static Object applicationContext() { return appContext; }
                }
                static class FrameworkWindowingCompat {
                    static int readDesktopToggle(Object context) {
                        check(context == appContext, "Settings not read in app context");
                        reads++;
                        if (rejectSetting) throw new SecurityException("settings denied");
                        return 1;
                    }
                }
                static class ShellPrivilegePolicy {
                    static void verifyServiceUid(int uid) {
                        if ((uid != 0 && uid != 2000) || (forceShell && uid != 2000))
                            throw new SecurityException("unexpected UID");
                    }
                }
                static class ShellAccess {
                    static String usefulMessage(Throwable error) { return String.valueOf(error.getMessage()); }
                    static class Snapshot {
                        final int backend, uid, version;
                        final boolean installed, running, permissionGranted;
                        final String error;
                        Snapshot(int b, boolean i, boolean r, boolean p, int u, int v, String e) {
                            backend=b; installed=i; running=r; permissionGranted=p; uid=u; version=v; error=e;
                        }
                    }
                }
                static class ShellServiceLauncher {
                    enum Service { COMMAND }
                    interface Binding { void close(boolean terminate); }
                    final List<ServiceConnection> connections = new ArrayList<>();
                    int closed;
                    boolean available = true;
                    static ShellServiceLauncher current() { return launcher; }
                    boolean canBind() { return available; }
                    long bindTimeoutMillis() { return 10000; }
                    Binding bind(Service service, String tag, ServiceConnection callback) {
                        connections.add(callback);
                        return terminate -> { check(terminate, "command binding detached"); closed++; };
                    }
                    ShellAccess.Snapshot inspect() { return new ShellAccess.Snapshot(0,true,true,true,0,13,""); }
                    void deliver(int index, IBinder service) {
                        connections.get(index).onServiceConnected(new ComponentName(), service);
                    }
                }
                static class BuildConfig { static final String SOURCE_ID = "build"; }
                static class Log { static void w(String tag, String message, Throwable error) {} }
                static final ShellServiceLauncher launcher = new ShellServiceLauncher();
                static final ShellServiceConnection connection = new ShellServiceConnection(() -> callbacks++);
                static final IShellCommandService service = new IShellCommandService();
                """
                + source.substring(source.indexOf("final class ShellServiceConnection"))
                        .replaceFirst("final class ShellServiceConnection", "static final class ShellServiceConnection")
                + "public static void verify() throws Exception { " + scenario + " }");
    }
}
