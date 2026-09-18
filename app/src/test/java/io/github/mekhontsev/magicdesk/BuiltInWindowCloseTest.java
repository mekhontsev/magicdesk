package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class BuiltInWindowCloseTest {
    @Test public void closeUsesMainThreadAndExactProfileTaskWithoutHoldingRegistryLock() throws Exception {
        RuntimeSourceFixture.verify("""
            interface CloseHandler { void requestClose(boolean force); }
            static class Activity {
                int task = 7;
                boolean finishing, destroyed;
                int getTaskId() { return task; }
                String getPackageName() { return "host"; }
                boolean isFinishing() { return finishing; }
                boolean isDestroyed() { return destroyed; }
            }
            static class Host extends Activity implements CloseHandler {
                int requests;
                boolean lastForce, fail;
                public void requestClose(boolean force) {
                    check(!Thread.holdsLock(WINDOWS), "handler called under registry lock");
                    if (fail) throw new IllegalStateException("disconnected");
                    requests++; lastForce = force;
                }
            }
            static class AppProfile {
                static AppProfile current(Activity host) { return new AppProfile(); }
                boolean owns(int user) { return user == 0; }
            }
            static class TaskRepository {
                static class TaskEntry { int taskId = 7, userId; String packageName = "host"; }
                record ActionResult(boolean success, String message) { }
                interface ActionCallback { void onComplete(ActionResult result); }
            }
            static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
            static class Handler {
                List<Runnable> pending = new ArrayList<>();
                void post(Runnable action) { pending.add(action); }
                void drain() { while (!pending.isEmpty()) pending.remove(0).run(); }
            }
            static final Handler MAIN = new Handler();
            static final List<java.lang.ref.WeakReference<Activity>> WINDOWS = new ArrayList<>();
            public static void verify() {
                var host = new Host();
                WINDOWS.add(new java.lang.ref.WeakReference<>(host));
                var task = new TaskRepository.TaskEntry();
                List<TaskRepository.ActionResult> results = new ArrayList<>();
                check(requestClose(task, false, results::add), "live handler not selected");
                check(host.requests == 0 && results.isEmpty(), "request ran outside main delivery");
                MAIN.drain();
                check(host.requests == 1 && !host.lastForce && results.get(0).success, "graceful request lost");
                requestClose(task, true, results::add); MAIN.drain();
                check(host.lastForce && host.requests == 2, "force became ordinary removal");
                task.userId = 10;
                check(!requestClose(task, true, results::add), "foreign profile matched");
                task.userId = 0; task.packageName = "other";
                check(!requestClose(task, true, results::add), "foreign package matched");
                task.packageName = "host"; task.taskId = 8;
                check(!requestClose(task, true, results::add), "another task matched");
                task.taskId = 7;
                host.fail = true; requestClose(task, true, results::add); MAIN.drain();
                check(!results.get(2).success && host.requests == 2, "failure swallowed");
                host.fail = false;
                requestClose(task, false, results::add);
                host.finishing = true;
                MAIN.drain();
                check(!results.get(3).success && host.requests == 2, "late request reached finishing host");
                check(!requestClose(task, true, results::add), "finishing host selected");
                host.finishing = false; host.destroyed = true;
                check(!requestClose(task, true, results::add), "destroyed host selected");
                check(!requestClose(null, false, results::add), "null task selected");
            }
            """ + RuntimeSourceFixture.methods("BuiltInWindowRegistry", "closeHost", "requestClose")
                    .replace("void requestClose(boolean force);", "")
                    .replace("WeakReference<Activity>", "java.lang.ref.WeakReference<Activity>"));
    }
}
