package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class BuiltInWindowPresentationTest {
    @Test public void presentationRequiresLiveTaskAndProfileOwnership() throws Exception {
        RuntimeSourceFixture.verify("""
                record Presentation(String title, Object icon) {}
                interface PresentationSource { Presentation taskPresentation(); }
                static class TaskRepository {
                    static class TaskEntry { int taskId, userId; String packageName = "host"; }
                }
                static class Activity {
                    int id = 17;
                    boolean destroyed, finishing;
                    boolean isDestroyed() { return destroyed; }
                    boolean isFinishing() { return finishing; }
                    int getTaskId() { return id; }
                    String getPackageName() { return "host"; }
                }
                static class WindowActivity extends Activity implements PresentationSource {
                    Presentation value = new Presentation("GIMP", new Object());
                    public Presentation taskPresentation() { return value; }
                }
                static class AppProfile {
                    static AppProfile current(Activity ignored) { return new AppProfile(); }
                    boolean owns(int user) { return user == 2; }
                }
                static List<java.lang.ref.WeakReference<Activity>> WINDOWS = new ArrayList<>();
                """ + RuntimeSourceFixture.methods("BuiltInWindowRegistry", "presentation")
                        .replace("WeakReference<", "java.lang.ref.WeakReference<") + """
                public static void verify() {
                    WindowActivity activity = new WindowActivity();
                    WINDOWS.add(new java.lang.ref.WeakReference<>(null));
                    WINDOWS.add(new java.lang.ref.WeakReference<>(activity));
                    TaskRepository.TaskEntry task = new TaskRepository.TaskEntry();
                    task.taskId = 17; task.userId = 2;
                    check(presentation(task) == activity.value, "lost live presentation");
                    task.userId = 3;
                    check(presentation(task) == null, "cross-profile presentation");
                    task.userId = 2; task.packageName = "another";
                    check(presentation(task) == null, "cross-package presentation");
                    task.packageName = "host"; task.taskId = 18;
                    check(presentation(task) == null, "cross-task presentation");
                    task.taskId = 17; activity.finishing = true;
                    check(presentation(task) == null, "finishing presentation");
                    activity.finishing = false; activity.destroyed = true;
                    check(presentation(task) == null, "destroyed presentation");
                    check(presentation(null) == null, "null task presentation");
                }
                """);
    }
}
