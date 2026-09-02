package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public final class DesktopHomeRoleLeaseTest {
    private static final String MAGICDESK =
            "io.github.mekhontsev.magicdesk";
    private static final String LAUNCHER = "com.example.launcher";

    private MemoryStorage mStorage;
    private FakeBackend mBackend;

    @Before
    public void setUp() {
        mStorage = new MemoryStorage();
        mBackend = new FakeBackend(LAUNCHER);
        DesktopHomeRoleLease.useForTests(mStorage, mBackend);
    }

    @After
    public void tearDown() {
        DesktopHomeRoleLease.useForTests(null, null);
    }

    @Test
    public void acquirePersistsPreviousHomeBeforeClaimingRole()
            throws Exception {
        final DesktopHomeRoleLease.AcquireResult result =
                DesktopHomeRoleLease.acquire(
                        DesktopDisplayTarget.simulated(7));

        assertTrue(result.created);
        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(LAUNCHER, mStorage.state.previousPackage);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
        assertTrue(mBackend.stateWasPreparedBeforeSet);
        assertTrue(mBackend.primaryHomePresented);
        assertEquals(0, mBackend.presentedUserId);
    }

    @Test
    public void acquireIsIdempotentForSameTarget() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        final DesktopHomeRoleLease.AcquireResult result =
                DesktopHomeRoleLease.acquire(
                        DesktopDisplayTarget.simulated(7));

        assertFalse(result.created);
        assertEquals(1, mBackend.setCalls);
    }

    @Test
    public void acquirePersistsSessionPolicy() throws Exception {
        DesktopHomeRoleLease.acquire(
                DesktopDisplayTarget.simulated(7),
                DesktopSessionPolicy.ISOLATED_SELF_TEST);

        assertTrue(DesktopHomeRoleLease.isActiveForDisplay(7));
        assertFalse(DesktopHomeRoleLease.isActiveForDisplay(8));
        assertEquals(
                DesktopSessionPolicy.ISOLATED_SELF_TEST,
                mStorage.state.policy);
        assertEquals(
                DesktopSessionPolicy.ISOLATED_SELF_TEST,
                mStorage.state.withPhase(
                        DesktopHomeRoleLease.Phase.RELEASING).policy);
        assertFalse(mBackend.primaryHomePresented);
    }

    @Test
    public void resumedPreparedClaimIsOwnedByCurrentAcquisition()
            throws Exception {
        mStorage.state = new DesktopHomeRoleLease.State(
                0,
                LAUNCHER,
                DesktopDisplayTarget.simulated(7),
                DesktopSessionPolicy.USER,
                DesktopHomeRoleLease.Phase.PREPARED);

        final DesktopHomeRoleLease.AcquireResult result =
                DesktopHomeRoleLease.acquire(
                        DesktopDisplayTarget.simulated(7));

        assertTrue(result.created);
        DesktopHomeRoleLease.releaseAfterFailedStart(result);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void acquireRejectsSecondTarget() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        try {
            DesktopHomeRoleLease.acquire(
                    DesktopDisplayTarget.wired(8));
            fail("second target must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already leased"));
        }
    }

    @Test
    public void failedClaimRestoresPreviousHomeAndClearsLease() {
        mBackend.failMagicDeskClaim = true;

        try {
            DesktopHomeRoleLease.acquire(
                    DesktopDisplayTarget.simulated(7));
            fail("claim failure expected");
        } catch (IOException expected) {
            assertEquals(LAUNCHER, mBackend.homePackage);
            assertNull(mStorage.state);
        }
    }

    @Test
    public void releaseKeepsStateUntilCompletion() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.prepareRelease(
                DesktopDisplayTarget.simulated(7)));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(
                DesktopHomeRoleLease.Phase.RELEASING,
                mStorage.state.phase);

        DesktopHomeRoleLease.completeRelease();
        assertNull(mStorage.state);
    }

    @Test
    public void failedSessionCloseCanRollbackRole() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));
        DesktopHomeRoleLease.prepareRelease(
                DesktopDisplayTarget.simulated(7));

        DesktopHomeRoleLease.rollbackRelease();

        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
    }

    @Test
    public void releaseRejectsDifferentTarget() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        try {
            DesktopHomeRoleLease.prepareRelease(
                    DesktopDisplayTarget.wired(8));
            fail("mismatched release must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("target mismatch"));
        }

        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
    }

    @Test
    public void releaseRestoresPreviousRoleHolder() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        DesktopHomeRoleLease.prepareRelease(
                DesktopDisplayTarget.simulated(7));
        DesktopHomeRoleLease.completeRelease();

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void releasePreservesAUserSelectedThirdPartyHome()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));
        mBackend.homePackage = "com.example.otherhome";

        assertFalse(DesktopHomeRoleLease.prepareRelease(
                DesktopDisplayTarget.simulated(7)));

        assertEquals("com.example.otherhome", mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void sessionLossRestoresOwnedHomeLease() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.releaseAfterSessionLoss(7));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void startupReconciliationRetainsLiveActiveLease()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        assertFalse(DesktopHomeRoleLease.reconcile(true));

        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
    }

    @Test
    public void startupReconciliationRestoresStaleActiveLease()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.reconcile(false));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void emergencyReleasePreventsSessionRecoveryWhenShellReturns()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.phone());

        final DesktopHomeRoleLease.State state =
                DesktopHomeRoleLease.prepareEmergencyRelease();

        assertEquals(DesktopHomeRoleLease.Phase.RELEASING, state.phase);
        assertEquals(
                DesktopHomeRoleLease.Phase.RELEASING,
                mStorage.state.phase);
        assertTrue(DesktopHomeRoleLease.reconcile(false));
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void emergencyReleaseDoesNotRequireStoredLease()
            throws Exception {
        assertNull(DesktopHomeRoleLease.prepareEmergencyRelease());
        assertNull(mStorage.state);
    }

    private final class FakeBackend implements DesktopHomeRoleLease.Backend {
        String homePackage;
        int setCalls;
        boolean failMagicDeskClaim;
        boolean stateWasPreparedBeforeSet;
        boolean primaryHomePresented;
        int presentedUserId = -1;

        FakeBackend(final String homePackage) {
            this.homePackage = homePackage;
        }

        @Override
        public int currentUserId() {
            return 0;
        }

        @Override
        public String getHomePackage(final int userId) {
            return homePackage;
        }

        @Override
        public void enableMagicDeskHome() {
        }

        @Override
        public void setHomePackage(
                final int userId,
                final String packageName) throws IOException {
            setCalls++;
            if (MAGICDESK.equals(packageName)) {
                stateWasPreparedBeforeSet = mStorage.state != null
                        && mStorage.state.phase
                                == DesktopHomeRoleLease.Phase.PREPARED;
                if (failMagicDeskClaim) {
                    throw new IOException("claim rejected");
                }
            }
            homePackage = packageName;
        }

        @Override
        public void presentMagicDeskHome(final int userId) {
            primaryHomePresented = true;
            presentedUserId = userId;
        }
    }

    private static final class MemoryStorage
            implements DesktopHomeRoleLease.Storage {
        DesktopHomeRoleLease.State state;

        @Override
        public DesktopHomeRoleLease.State read() {
            return state;
        }

        @Override
        public void write(final DesktopHomeRoleLease.State value) {
            state = value;
        }

        @Override
        public void clear() {
            state = null;
        }
    }
}
