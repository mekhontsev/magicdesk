package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PhoneOverviewPresentationTest {
    @Test public void phoneOverviewOnlyRevealsTaskbarWithoutHomeLaunch() throws Exception {
        verify("""
                callback.presentHomeFromRecents();
                check(presentations == 1 && launches == 0, "phone Overview relaunched HOME");
                check(errors.isEmpty(), "successful reveal reported failure");
                """);
    }

    @Test public void absentPhoneWorkspaceStillLaunchesPhoneStartRecent() throws Exception {
        verify("""
                phoneTarget = null;
                callback.presentHomeFromRecents();
                check(presentations == 0 && launches == 1, "external session lost phone Start");
                check(launched.action.equals(Intent.ACTION_MAIN)
                        && launched.category.equals(Intent.CATEGORY_HOME)
                        && launched.pkg.equals("magicdesk") && launched.recent,
                        "phone Start intent changed");
                check(launched.flags == (Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) && launchDisplay == 0,
                        "phone Start placement changed");
                """);
    }

    @Test public void unavailablePhoneHostDoesNotFallBackToActivityLaunch() throws Exception {
        verify("""
                accepted = false;
                expectFailure(callback, "phone desktop is not ready");
                check(presentations == 1 && launches == 0, "rejected command launched HOME");
                """);
    }

    @Test public void repeatedNavigationDoesNotLaunchAnotherActivity() throws Exception {
        verify("""
                callback.presentHomeFromRecents();
                callback.presentHomeFromRecents();
                check(presentations == 2 && launches == 0, "repeated navigation relaunched HOME");
                """);
    }

    @Test public void endedLeaseAndStaleObserverCannotNavigate() throws Exception {
        verify("""
                observerActive = false;
                expectFailure(callback, "not active");
                observerActive = true;
                lease.phase = DesktopHomeRoleLease.Phase.RELEASING;
                expectFailure(callback, "not active");
                lease = null;
                expectFailure(callback, "not active");
                check(presentations == 0 && launches == 0, "inactive callback navigated");
                """);
    }

    @Test public void androidLaunchFailureUsesBinderSupportedException() throws Exception {
        verify("""
                phoneTarget = null;
                launchFailure = new IllegalStateException("Android rejected HOME");
                expectFailure(callback, "Android rejected HOME");
                check(launches == 1 && presentations == 0, "failed launch was retried");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class RemoteException extends Exception { }
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class DesktopDisplayTarget { }
                static DesktopDisplayTarget phoneTarget = new DesktopDisplayTarget();
                static DesktopHomeRoleLease.State lease = new DesktopHomeRoleLease.State();
                static boolean observerActive = true, accepted = true;
                static int presentations, launches, launchDisplay = -1;
                static Intent launched;
                static RuntimeException launchFailure;
                static final List<String> errors = new ArrayList<>();
                static class DesktopHomeRoleLease {
                    enum Phase { ACTIVE, RELEASING }
                    static class State {
                        Phase phase = Phase.ACTIVE;
                        DesktopDisplayTarget targetForDisplay(int id) {
                            check(id == 0, "Overview targeted another display");
                            return phoneTarget;
                        }
                    }
                    static State snapshot() { return lease; }
                }
                static class DesktopRuntimeBridge {
                    static boolean revealPhoneTaskbar() {
                        presentations++;
                        return accepted;
                    }
                }
                static class Listener { boolean isActive(int generation) { return observerActive; } }
                static class Owner {
                    final Listener mListener = new Listener();
                    void onObserverError(int generation, String message) { errors.add(message); }
                }
                final Owner mOwner = new Owner();
                final int mGeneration = 7;
                static class ShellAccess {
                    static String usefulMessage(Throwable error) { return error.getMessage(); }
                }
                static class Intent {
                    static final String ACTION_MAIN = "MAIN", CATEGORY_HOME = "HOME";
                    static final int FLAG_ACTIVITY_NEW_TASK = 1, FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 2;
                    String action, category, pkg; int flags; boolean recent;
                    Intent(String action) { this.action = action; }
                    Intent addCategory(String value) { category = value; return this; }
                    Intent setPackage(String value) { pkg = value; return this; }
                    Intent addFlags(int value) { flags |= value; return this; }
                    Intent putExtra(String key, boolean value) {
                        check(key.equals(PhoneHomeActivity.EXTRA_SHOW_RECENT), "wrong Start extra");
                        recent = value; return this;
                    }
                }
                static class PhoneHomeActivity { static final String EXTRA_SHOW_RECENT = "recent"; }
                static class ActivityOptions {
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int id) { launchDisplay = id; }
                    Object toBundle() { return this; }
                }
                static class Context {
                    String getPackageName() { return "magicdesk"; }
                    void startActivity(Intent intent, Object options) {
                        launches++; launched = intent;
                        if (launchFailure != null) throw launchFailure;
                    }
                }
                static class MagicDeskApplication {
                    static Context applicationContext() { return new Context(); }
                }
                static void expectFailure(Fixture callback, String message) throws Exception {
                    try { callback.presentHomeFromRecents(); throw new AssertionError("accepted failure"); }
                    catch (IllegalStateException expected) {
                        check(expected.getMessage().contains(message), expected.getMessage());
                    }
                }
                public static void verify() throws Exception {
                    Fixture callback = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                        "DesktopTaskWatcher", "presentHomeFromRecents"));
    }
}
