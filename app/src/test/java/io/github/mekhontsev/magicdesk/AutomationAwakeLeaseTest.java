package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class AutomationAwakeLeaseTest {
    @Test public void leaseIsBoundedRenewableAndRejectsStaleRelease() throws Exception {
        RuntimeSourceFixture.verify("""
                static class JSONException extends Exception { }
                static class JSONObject {
                    static final Object NULL = new Object();
                    Map<String,Object> values = new HashMap<>();
                    JSONObject put(String k, Object v) { values.put(k,v); return this; }
                    boolean has(String k) { return values.containsKey(k); }
                    String optString(String k) { return (String) values.get(k); }
                    JSONObject() { }
                    JSONObject(String state) { put("held", Boolean.valueOf(state)); }
                    boolean getBoolean(String k) { return (Boolean) values.get(k); }
                }
                static class android {
                    static class os {
                        interface IBinder { }
                        static class Binder implements IBinder { }
                        static class RemoteException extends Exception { }
                    }
                    static class util { static class Log { static void w(String t, String m, Throwable e) { } } }
                }
                interface IBackgroundWorkLease {
                    void renew(long duration) throws android.os.RemoteException;
                    String state() throws android.os.RemoteException;
                    void close() throws android.os.RemoteException;
                }
                static class Work implements IBackgroundWorkLease {
                    boolean held = true;
                    long duration;
                    public void renew(long ms) { duration = ms; }
                    public String state() { return Boolean.toString(held); }
                    public void close() { held = false; }
                }
                static class ShellAccess {
                    static Work last;
                    static IBackgroundWorkLease acquireBackgroundWork(int display, long duration,
                            boolean awake, android.os.IBinder owner) {
                        check(display == 7 && awake, "wrong work target");
                        last = new Work(); last.duration = duration; return last;
                    }
                }
                static class Context {
                    PowerManager power = new PowerManager();
                    PowerManager getSystemService(Class<?> type) { return power; }
                }
                static class PowerManager {
                    static final int SCREEN_BRIGHT_WAKE_LOCK = 10;
                    WakeLock newWakeLock(int flags, String tag) {
                        check(flags == SCREEN_BRIGHT_WAKE_LOCK, "must not wake or unlock phone");
                        return new WakeLock();
                    }
                    static class WakeLock {
                        boolean held; long timeout;
                        void setReferenceCounted(boolean v) { check(!v, "renewal must not accumulate references"); }
                        boolean isHeld() { return held; }
                        void acquire(long ms) { held=true; timeout=ms; }
                        void release() { held=false; }
                    }
                }
                static class SystemClock { static long elapsedRealtime() { return 100; } }
                static class AutomationDeviceState {
                    static String reason;
                    static AutomationDeviceState capture(Context c) { return new AutomationDeviceState(); }
                    String phoneUiUnavailableReason() { return reason; }
                }
                static class AndroidUiSelector {
                    static int integer(JSONObject a, String k, int d, int min, int max) {
                        int v = a.has(k) ? (Integer)a.values.get(k) : d;
                        if (v < min || v > max) throw new IllegalArgumentException();
                        return v;
                    }
                }
                final Context mContext = new Context();
                PowerManager.WakeLock mLock;
                String mId;
                long mExpiresAt;
                IBackgroundWorkLease mWork;
                int mDisplayId;
                final android.os.IBinder mOwner = new android.os.Binder();
                static void rejects(Runnable r) {
                    try { r.run(); throw new AssertionError("expected rejection"); }
                    catch (IllegalArgumentException expected) { }
                }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    f.acquire(new JSONObject().put("durationMillis",1000));
                    String first = f.mId;
                    check(f.mLock.held && f.mLock.timeout == 1000, "not bounded");
                    f.acquire(new JSONObject().put("leaseId",first).put("durationMillis",2000));
                    check(first.equals(f.mId) && f.mLock.timeout == 2000, "renewal changed owner");
                    boolean rejected = false;
                    try { f.release("stale"); } catch(IllegalArgumentException expected) { rejected=true; }
                    check(rejected && f.mLock.held, "stale release affected active lease");
                    f.release(first);
                    check(f.mLock == null, "explicit release retained lock");
                    f.acquire(new JSONObject());
                    check(!first.equals(f.mId), "new lease reused token");
                    f.mLock.held = false;
                    check(Boolean.FALSE.equals(f.state().values.get("held")), "expiry not observed");
                    f.acquire(new JSONObject());
                    f.close();
                    check(f.mLock == null && f.mId == null, "backend close leaked lease");
                    AutomationDeviceState.reason = "locked";
                    try { f.acquire(new JSONObject()); throw new AssertionError("locked phone accepted"); }
                    catch(IllegalStateException expected) { }
                    f.acquire(new JSONObject().put("displayId",7).put("durationMillis",1000));
                    check(f.mLock == null && f.mWork != null, "virtual work touched phone power");
                    String background = f.mId;
                    f.acquire(new JSONObject().put("leaseId",background).put("durationMillis",2000));
                    check(ShellAccess.last.duration == 2000 && f.mDisplayId == 7, "renewal lost display");
                    try { f.acquire(new JSONObject().put("leaseId",background).put("displayId",0));
                        throw new AssertionError("retargeted active lease"); }
                    catch(IllegalArgumentException expected) { }
                    try { f.release(first); throw new AssertionError("stale release accepted"); }
                    catch(IllegalArgumentException expected) { }
                    check(ShellAccess.last.held, "stale release closed work");
                    ShellAccess.last.held = false;
                    check(Boolean.FALSE.equals(f.state().values.get("held")), "service expiry not observed");
                    try { f.acquire(new JSONObject().put("leaseId",background));
                        throw new AssertionError("expired lease renewed"); }
                    catch(IllegalArgumentException expected) { }
                    f.acquire(new JSONObject().put("displayId",7));
                    check(!background.equals(f.mId), "new work reused expired id");
                    f.close();
                    check(!ShellAccess.last.held, "close leaked background work");
                }
                """ + RuntimeSourceFixture.methods("AutomationAwakeLease", "acquire", "release", "state", "close"));
    }
}
