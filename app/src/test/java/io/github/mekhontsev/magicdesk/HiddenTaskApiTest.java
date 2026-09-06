package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class HiddenTaskApiTest {
    @Test
    public void startsRecentsWithNullOptionsAndPreservesEveryIntegerResult() throws Exception {
        for (final int result : new int[] {0, 2, -96}) {
            final FakeService service = new FakeService(Integer.valueOf(result));
            assertEquals(result, HiddenTaskApi.startActivityFromRecents(service, 42));
            assertEquals(42, service.taskId);
            assertNull(service.options);
        }
    }

    @Test
    public void rejectsMissingOrNonIntegerResults() {
        for (final Object result : new Object[] {null, "0", Long.valueOf(0), Boolean.TRUE}) {
            assertThrows(IllegalStateException.class, () ->
                    HiddenTaskApi.startActivityFromRecents(new FakeService(result), 42));
        }
    }

    @Test
    public void missingMethodRemainsAReflectiveFailure() {
        assertThrows(NoSuchMethodException.class, () ->
                HiddenTaskApi.startActivityFromRecents(new Object(), 42));
    }

    @Test
    public void invocationFailureRetainsTheServiceCause() {
        final FailingService service = new FailingService();
        final InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> HiddenTaskApi.startActivityFromRecents(service, 42));
        assertSame(service.failure, error.getCause());
    }

    @Test
    public void boundsReadersRejectUnavailableAndWrongTypeWithoutFallback() {
        for (final Object bounds : new Object[] {null, "bounds", Integer.valueOf(0)}) {
            final FakeTask task = new FakeTask(new FakeWindowConfiguration(bounds));
            assertThrows(IllegalStateException.class, () -> HiddenTaskApi.readBounds(task));
            assertThrows(IllegalStateException.class, () -> HiddenTaskApi.readMaxBounds(task));
        }
    }

    @Test
    public void boundsReadersPreserveMissingMemberFailures() {
        final FakeTask task = new FakeTask(new Object());
        assertThrows(NoSuchMethodException.class, () -> HiddenTaskApi.readBounds(task));
        assertThrows(NoSuchMethodException.class, () -> HiddenTaskApi.readMaxBounds(task));
    }

    @Test
    public void typedWindowValuesRetainFrameworkResults() throws Exception {
        RuntimeSourceFixture.verify("""
                public static final class Task {
                    public final Configuration configuration = new Configuration();
                }
                public static final class Configuration {
                    public final WindowConfiguration windowConfiguration = new WindowConfiguration();
                }
                public static final class WindowConfiguration {
                    public int getWindowingMode() { return 5; }
                    public int getActivityType() { return 2; }
                }
                public static void verify() throws Exception {
                    Task task = new Task();
                    check(getTaskWindowingMode(task) == 5, "windowing mode was changed");
                    check(getTaskActivityType(task) == 2, "activity type was changed");
                    check(Modifier.isPrivate(Fixture.class.getDeclaredMethod(
                            "getWindowConfigurationValue", Object.class, String.class).getModifiers()),
                            "string member boundary must remain private");
                }
                """ + RuntimeSourceFixture.methods("HiddenTaskApi",
                "getTaskWindowingMode", "getTaskActivityType", "getWindowConfigurationValue",
                "getWindowConfiguration", "getField"));
    }

    @Test
    public void boundsReadersShareADefensiveCopyBoundary() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/HiddenTaskApi.java"));
        assertTrue(source.contains("return readWindowConfigurationBounds(task, \"getBounds\")"));
        assertTrue(source.contains("return readWindowConfigurationBounds(task, \"getMaxBounds\")"));
        final String reader = source.substring(source.indexOf(
                "private static Rect readWindowConfigurationBounds("),
                source.indexOf("static Object getTaskToken("));
        assertTrue(reader.contains("bounds instanceof Rect"));
        assertTrue(reader.contains("return new Rect((Rect) bounds)"));
    }

    public static final class FakeTask {
        public final FakeConfiguration configuration;

        FakeTask(final Object windowConfiguration) {
            configuration = new FakeConfiguration(windowConfiguration);
        }
    }

    public static final class FakeConfiguration {
        public final Object windowConfiguration;

        FakeConfiguration(final Object windowConfiguration) {
            this.windowConfiguration = windowConfiguration;
        }
    }

    public static final class FakeWindowConfiguration {
        final Object bounds;

        FakeWindowConfiguration(final Object bounds) {
            this.bounds = bounds;
        }

        public Object getBounds() {
            return bounds;
        }

        public Object getMaxBounds() {
            return bounds;
        }

        public int getWindowingMode() {
            return 5;
        }

        public int getActivityType() {
            return 2;
        }
    }

    public static final class FakeService {
        final Object result;
        int taskId = -1;
        Bundle options;

        FakeService(final Object result) {
            this.result = result;
        }

        public Object startActivityFromRecents(final int taskId, final Bundle options) {
            this.taskId = taskId;
            this.options = options;
            return result;
        }
    }

    public static final class FailingService {
        final IllegalStateException failure = new IllegalStateException("service failed");

        public int startActivityFromRecents(final int taskId, final Bundle options) {
            throw failure;
        }
    }
}
