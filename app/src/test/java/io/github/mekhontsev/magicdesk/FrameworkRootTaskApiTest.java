package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkRootTaskApiTest {
    @Test
    public void deletesOnlyTheRetainedEmptyRoot() throws Exception {
        verify("""
                check(root.close(service), "empty root deletion failed");
                check(organizer.deleted == task.token, "wrong token deleted");
                organizer.deleted = null;
                service.tasks.clear();
                check(root.close(service), "already removed root must be complete");
                check(organizer.deleted == null, "deleted an absent root");
                """);
    }

    @Test
    public void refusesApplicationOrEvenEmptyChildTasks() throws Exception {
        verify("""
                task.empty = false;
                try { root.close(service); throw new AssertionError("application task deleted"); }
                catch (IllegalStateException expected) { }
                task.empty = true;
                organizer.children = List.of(new Object());
                try { root.close(service); throw new AssertionError("child task deleted"); }
                catch (IllegalStateException expected) { }
                check(organizer.deleted == null, "unsafe delete reached organizer");
                """);
    }

    @Test
    public void ownershipRequiresCookieAndTaskIdentity() throws Exception {
        verify("""
                task.cookie = new IBinder();
                check(root.close(service), "unowned replacement must not be touched");
                check(organizer.deleted == null, "deleted another owner's task");
                task.cookie = cookie;
                task.id++;
                try { root.close(service); throw new AssertionError("task identity changed"); }
                catch (IllegalStateException expected) { }
                check(organizer.deleted == null, "deleted wrong identity");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class IBinder { }
                static class Task {
                    int id = 42;
                    Object token = new Object();
                    IBinder cookie;
                    boolean empty = true;
                }
                static class Service { List<Task> tasks = new ArrayList<>(); }
                public static class Organizer {
                    Object deleted;
                    List<?> children = List.of();
                    public List<?> getChildTasks(Object token, int[] types) { return children; }
                    public boolean deleteRootTask(Object token) { deleted = token; return true; }
                }
                static class HiddenTaskApi {
                    static int getTaskId(Object task) { return ((Task)task).id; }
                    static Object getTaskToken(Object task) { return ((Task)task).token; }
                    static boolean isEmptyTask(Object task) { return ((Task)task).empty; }
                    static boolean hasTaskLaunchCookie(Object task, IBinder cookie) {
                        return ((Task)task).cookie == cookie;
                    }
                    static List<?> getRootTaskInfos(Object service, int display) {
                        return ((Service)service).tasks;
                    }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkRuntime windowing() { return this; }
                    Class<?> tokenClass() { return Object.class; }
                }
                """ + RuntimeSourceFixture.nestedClass("FrameworkRootTaskApi", "Root")
                + RuntimeSourceFixture.methods("FrameworkRootTaskApi", "find") + """
                public static void verify() throws Exception {
                    Service service = new Service();
                    Organizer organizer = new Organizer();
                    IBinder cookie = new IBinder();
                    Task task = new Task();
                    task.cookie = cookie;
                    service.tasks.add(task);
                    Root root = new Root(organizer, cookie, task);
                """ + scenario + "}\n");
    }
}
