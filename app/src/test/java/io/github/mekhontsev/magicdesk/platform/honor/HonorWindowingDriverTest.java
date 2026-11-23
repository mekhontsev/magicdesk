package io.github.mekhontsev.magicdesk.platform.honor;

import org.junit.Test;
import static org.junit.Assert.*;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformComponent;

public final class HonorWindowingDriverTest {
    private static HonorWindowingDriver inspect(Class<?> task, Class<?> transaction) {
        return HonorWindowingDriver.inspect(task, transaction.getName(), Token.class.getName());
    }
    public interface ScaleApi {
        float getFreeformStackScale(int taskId);
        void notifyFreeformScaleChange(int taskId, float scale);
    }
    public static final class ScaleService implements ScaleApi {
        float scale = .5f;
        boolean denied;
        @Override public float getFreeformStackScale(int taskId) {
            assertEquals(42, taskId);
            if (denied) throw new SecurityException("denied");
            return scale;
        }
        @Override public void notifyFreeformScaleChange(int taskId, float value) {
            assertEquals(42, taskId); scale = value;
        }
    }

    @Test public void scalePeerUsesNamedInterfaceAndRejectsUnknownState() throws Exception {
        var service = new ScaleService();
        var peer = HonorWindowingDriver.scalePeer(service, ScaleApi.class, 42);
        assertEquals(.5f, peer.read(), 0);
        peer.request(.75f); assertEquals(.75f, peer.read(), 0);
        assertThrows(IllegalArgumentException.class, () -> peer.request(Float.NaN));
        service.scale = 0;
        assertThrows(IllegalStateException.class, peer::read);
        service.denied = true;
        assertThrows(java.lang.reflect.InvocationTargetException.class, peer::read);
        assertThrows(IllegalStateException.class,
                () -> HonorWindowingDriver.scalePeer(new Object(), ScaleApi.class, 42));
    }
    public static class Task {
        public Extension hnTaskInfoEx = new Extension();
    }
    public static class Extension {
        public float mFreeformScale = .4114f;
        public boolean mIsCustomizedFreeformWindow, mIsTakeoverWindow;
        public Prop mCustomizedWindowProp;
    }
    public static class Prop {
        boolean active, limited = true, recents;
        public boolean isLimitedByQuantity() { return limited; }
        public void setActiveState(boolean value) { active = value; }
        public void setIsLimitedByQuantity(boolean value) { limited = value; }
        public void setFocusableInRecent(boolean value) { recents = value; }
    }
    public static class Token { }
    public static class Transaction {
        final Changes changes = new Changes();
        public Changes getHnWindowContainerTransactionEx() { return changes; }
    }
    public static class Changes {
        Prop profile;
        public void setCustomizedWindow(Transaction tx, Token task, Prop prop) {
            token = task;
            profile = prop;
        }
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

    @Test public void quantityExemptionIsTaskLocalReversibleAndLeavesForeignOwnersAlone() throws Exception {
        var policy = new HonorFreeformMultiplicity(Task.class.getField("hnTaskInfoEx"), Prop.class,
                Transaction.class.getMethod("getHnWindowContainerTransactionEx"), Transaction.class, Token.class);
        var task = new Task();
        assertTrue(policy.canClaim(task));
        assertEquals("ordinary", policy.describe(task));
        task.hnTaskInfoEx.mIsTakeoverWindow = true;
        assertFalse(policy.canClaim(task));
        task.hnTaskInfoEx.mIsTakeoverWindow = false;
        task.hnTaskInfoEx.mIsCustomizedFreeformWindow = true;
        assertFalse(policy.canClaim(task));
        var tx = new Transaction();
        var token = new Token();
        policy.setExempt(tx, token, true);
        assertSame(token, tx.changes.token);
        assertTrue(tx.changes.profile.active && !tx.changes.profile.limited && tx.changes.profile.recents);
        task.hnTaskInfoEx.mCustomizedWindowProp = tx.changes.profile;
        assertEquals("custom-exempt", policy.describe(task));
        policy.setExempt(tx, token, false);
        assertFalse(tx.changes.profile.active);
        assertTrue(tx.changes.profile.limited);
    }
    public static class ScaleOnlyTransaction {
        public ScaleOnlyChanges getHnWindowContainerTransactionEx() { return new ScaleOnlyChanges(); }
    }
    public static class ScaleOnlyChanges {
        public void setFreeformScale(ScaleOnlyTransaction transaction, Token token, float scale) {
            fail("incomplete geometry contract must not be applied");
        }
    }

    @Test public void missingAccessorOrBoundsSetterLeavesScaleReadOnly() {
        for (Class<?> tx : new Class<?>[] {Object.class, ScaleOnlyTransaction.class}) {
            var driver = inspect(Task.class, tx);
            assertEquals(.4114f, driver.taskScale(new Task()), 0);
            assertFalse(driver.hasScaledWindows());
            assertFalse(driver.hasNativeScaleCommit());
            assertNull(driver.freeformMultiplicity());
            assertTrue(driver.geometryDiagnostic().contains("NoSuchMethodException"));
        }
    }

    @Test public void exemptionRequiresItsOwnTransactionSetter() throws Exception {
        assertThrows(NoSuchMethodException.class, () -> new HonorFreeformMultiplicity(
                Task.class.getField("hnTaskInfoEx"), Prop.class,
                ScaleOnlyTransaction.class.getMethod("getHnWindowContainerTransactionEx"),
                ScaleOnlyTransaction.class, Token.class));
    }

    @Test public void capabilitySelectsExtensionWithoutModelChecks() {
        var driver = inspect(Task.class, Transaction.class);
        var extension = new HonorPlatformDriver(driver);
        assertTrue(extension.match(new PlatformDevice("", "", "unlisted", "", "", "", 37)).matched);
        assertEquals(java.util.Set.of(PlatformComponent.WINDOWING), extension.components());
        assertFalse(new HonorPlatformDriver(inspect(Object.class, Transaction.class))
                .match(new PlatformDevice("HONOR", "HONOR", "", "", "", "", 37)).matched);
        assertTrue(driver.supportsIndependentFreeform());
    }

    @Test public void liveScaleIsReadFromEachTaskWithoutBinder() {
        var driver = inspect(Task.class, Transaction.class);
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
        var driver = inspect(Task.class, Transaction.class);
        assertTrue(driver.hasScaledWindows());
        var transaction = new Transaction();
        var token = new Token();
        driver.prepareTaskGeometry(transaction, token, .4114f);
        assertSame(token, transaction.changes.token);
        assertEquals(.4114f, transaction.changes.scale, 0);
        assertEquals(1, transaction.changes.calls);
        var second = new Transaction();
        driver.prepareTaskGeometry(second, token, .79f);
        assertEquals(.79f, second.changes.scale, 0);
        assertEquals(1, transaction.changes.calls);
        assertTrue(driver.geometryDiagnostic().contains("write=available"));
    }

    public static final class Denied extends Transaction {
        @Override public Changes getHnWindowContainerTransactionEx() { throw new SecurityException("denied"); }
    }
    @Test public void rejectedWriteIsNotSilentlyTreatedAsIdentity() {
        var driver = inspect(Task.class, Transaction.class);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> driver.prepareTaskGeometry(new Denied(), new Token(), .5f));
        assertTrue(driver.hasScaledWindows());
        assertTrue(driver.geometryDiagnostic().contains("InvocationTargetException"));
    }
}
