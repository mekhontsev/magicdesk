package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class DesktopManagedTaskPolicyTest {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";

    @Test
    public void managesExternalApplicationTasks() {
        assertTrue(DesktopManagedTaskPolicy.isManagedApplicationTask(
                task("com.example", "com.example/.MainActivity", false)));
    }

    @Test
    public void managesBuiltInFilesWithShortOrFullComponentName() {
        assertTrue(DesktopManagedTaskPolicy.isManagedApplicationTask(task(
                PACKAGE_NAME,
                PACKAGE_NAME + "/.FileManagerActivity",
                false)));
        assertTrue(DesktopManagedTaskPolicy.isManagedApplicationTask(task(
                PACKAGE_NAME,
                PACKAGE_NAME + "/" + PACKAGE_NAME
                        + ".FileManagerActivity",
                false)));
    }

    @Test
    public void excludesMagicDeskInfrastructureAndHomeTasks() {
        assertFalse(DesktopManagedTaskPolicy.isManagedApplicationTask(task(
                PACKAGE_NAME,
                PACKAGE_NAME + "/.DesktopActivity",
                false)));
        assertFalse(DesktopManagedTaskPolicy.isManagedApplicationTask(task(
                PACKAGE_NAME,
                PACKAGE_NAME + "/.ControlActivity",
                false)));
        assertFalse(DesktopManagedTaskPolicy.isManagedApplicationTask(task(
                "com.example",
                "com.example/.HomeActivity",
                true)));
    }

    private static TaskRepository.TaskEntry task(
            final String packageName,
            final String componentName,
            final boolean home) {
        return new TaskRepository.TaskEntry(
                1,
                2,
                3,
                packageName,
                componentName,
                componentName,
                "freeform",
                new Rect(10, 20, 300, 400),
                home,
                true,
                true);
    }
}
