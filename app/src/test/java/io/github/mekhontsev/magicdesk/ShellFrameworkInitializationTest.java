package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellFrameworkInitializationTest {
    @Test
    public void publicSettingArrivesBeforeBindingIsPublished() throws Exception {
        verify("""
                new Fixture().onServiceConnected(new ComponentName(), service);
                check(mService == service && !mBinding, "binding not published");
                check(service.toggle == 1 && service.error.isEmpty(), "wrong setting snapshot");
                check(reads == 1 && callbacks == 1, "repeated initialization");
                """);
    }

    @Test
    public void settingReadFailureDoesNotDisableOtherShellCapabilities() throws Exception {
        verify("""
                rejectSetting = true;
                new Fixture().onServiceConnected(new ComponentName(), service);
                check(mService == service, "Settings failure disabled shell service");
                check(service.error.contains("settings denied"), "missing unknown reason");
                check(callbacks == 1, "connection was not announced");
                """);
    }

    @Test
    public void failedInitializationCannotPublishReadyBinding() throws Exception {
        verify("""
                rejectService = true;
                new Fixture().onServiceConnected(new ComponentName(), service);
                check(mService == null && !mBinding, "published uninitialized binding");
                check(callbacks == 1, "waiters were not notified");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final Object mLock = new Object(), appContext = new Object();
                static boolean mBinding = true, rejectSetting, rejectService;
                static int reads, callbacks;
                static IShizukuCommandService mService;
                static class RemoteException extends Exception {}
                static class ComponentName {}
                interface IBinder { boolean pingBinder(); }
                static class IShizukuCommandService implements IBinder {
                    int toggle;
                    String error;
                    public boolean pingBinder() { return true; }
                    void initializeFramework(int value, String failure) throws RemoteException {
                        check(mService == null, "binding published before initialization");
                        if (rejectService) throw new RemoteException();
                        toggle = value; error = failure;
                    }
                    static class Stub {
                        static IShizukuCommandService asInterface(IBinder binder) {
                            return (IShizukuCommandService) binder;
                        }
                    }
                }
                static final IShizukuCommandService service = new IShizukuCommandService();
                static final Runnable mConnectedCallback = () -> { callbacks++; };
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
                static class ShellAccess {
                    static String usefulMessage(Throwable error) { return error.getMessage(); }
                }
                static class Log { static void w(String tag, String message, Throwable error) {} }
                """ + RuntimeSourceFixture.methods("ShellServiceConnection",
                        "initializeFramework", "onServiceConnected")
                + "public static void verify() throws Exception { " + scenario + " }");
    }
}
