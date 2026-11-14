package io.github.mekhontsev.magicdesk.platform.honor;

import org.junit.Test;
import static org.junit.Assert.*;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformComponent;

public final class HonorWindowingDriverTest {
    public static class Task {
        public Extension hnTaskInfoEx = new Extension();
    }
    public static class Extension { public float mFreeformScale = .4114f; }
    public static class Token { }
    public static class Transaction {
        final Changes changes = new Changes();
        public Changes getHnWindowContainerTransactionEx() { return changes; }
    }
    public static class Changes {
        int calls;
        Object token;
        float scale;
        boolean calculateBounds = true;
        public void setUseShellCalculateBounds(Transaction transaction, Token target, boolean value) {
            assertSame(this, transaction.changes);
            assertEquals(0, calls);
            token = target;
            calculateBounds = value;
        }
        public void setFreeformScale(Transaction transaction, Token target, float value) {
            assertSame(this, transaction.changes);
            assertSame(token, target);
            assertFalse(calculateBounds);
            calls++; token = target; scale = value;
        }
    }
    public static class ScaleOnlyTransaction {
        public ScaleOnlyChanges getHnWindowContainerTransactionEx() { return new ScaleOnlyChanges(); }
    }
    public static class ScaleOnlyChanges {
        public void setFreeformScale(ScaleOnlyTransaction transaction, Token token, float scale) {
            fail("incomplete geometry contract must not be applied");
        }
    }

    @Test public void partialFirmwareContractFailsBeforeWritingAndDoesNotPoisonRetry() throws Exception {
        var driver = HonorWindowingDriver.inspect(Task.class);
        assertThrows(NoSuchMethodException.class,
                () -> driver.prepareTaskGeometry(new ScaleOnlyTransaction(), Token.class, new Token(), .5f));
        var transaction = new Transaction();
        driver.prepareTaskGeometry(transaction, Token.class, new Token(), .5f);
        assertEquals(1, transaction.changes.calls);
        assertFalse(transaction.changes.calculateBounds);
    }

    @Test public void capabilitySelectsExtensionWithoutModelChecks() {
        var driver = HonorWindowingDriver.inspect(Task.class);
        var extension = new HonorPlatformDriver(driver);
        assertTrue(extension.match(new PlatformDevice("", "", "unlisted", "", "", "", 37)).matched);
        assertEquals(java.util.Set.of(PlatformComponent.WINDOWING), extension.components());
        assertFalse(new HonorPlatformDriver(HonorWindowingDriver.inspect(Object.class))
                .match(new PlatformDevice("HONOR", "HONOR", "", "", "", "", 37)).matched);
        assertTrue(driver.supportsIndependentFreeform());
    }

    @Test public void liveScaleIsReadFromEachTaskWithoutBinder() {
        var driver = HonorWindowingDriver.inspect(Task.class);
        var task = new Task();
        assertEquals(.4114f, driver.taskScale(task), 0);
        task.hnTaskInfoEx.mFreeformScale = .79f;
        assertEquals(.79f, driver.taskScale(task), 0);
        task.hnTaskInfoEx.mFreeformScale = Float.NaN;
        assertTrue(Float.isNaN(driver.taskScale(task)));
        task.hnTaskInfoEx = null;
        assertTrue(Float.isNaN(driver.taskScale(task)));
        assertTrue(Float.isNaN(driver.taskScale(new Object())));
    }

    @Test public void scaleBelongsToTheSameTransactionAsBounds() throws Exception {
        var driver = HonorWindowingDriver.inspect(Task.class);
        var transaction = new Transaction();
        var token = new Token();
        driver.prepareTaskGeometry(transaction, Token.class, token, .4114f);
        assertSame(token, transaction.changes.token);
        assertEquals(.4114f, transaction.changes.scale, 0);
        assertEquals(1, transaction.changes.calls);
        var second = new Transaction();
        driver.prepareTaskGeometry(second, Token.class, token, .79f);
        assertEquals(.79f, second.changes.scale, 0);
        assertEquals(1, transaction.changes.calls);
        assertTrue(driver.geometryDiagnostic().contains("write=available"));
    }

    @Test public void rejectedWriteIsNotSilentlyTreatedAsIdentity() {
        var driver = HonorWindowingDriver.inspect(Task.class);
        assertThrows(ReflectiveOperationException.class,
                () -> driver.prepareTaskGeometry(new Object(), Token.class, new Token(), .5f));
        assertTrue(driver.geometryDiagnostic().contains("NoSuchMethodException"));
    }
}
