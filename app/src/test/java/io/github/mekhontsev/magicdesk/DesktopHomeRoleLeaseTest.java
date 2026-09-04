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
import java.util.ArrayList;
import java.util.List;

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
        assertEquals(LAUNCHER, mStorage.state.previousHome.packageName);
        assertEquals(
                LAUNCHER + "/.Launcher",
                mStorage.state.previousHome.componentName);
        assertEquals(
                AndroidHomeSelection.Availability.DECLARED,
                mStorage.state.previousHome.availability);
        assertEquals(42, mStorage.state.previousHome.packageVersionCode);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
        assertTrue(DesktopHomeRoleLease.isPhoneOverviewRoutingActive());
        assertTrue(mBackend.stateWasPreparedBeforeSet);
        assertEquals(
                DesktopHomeSurfaceRouter.Surface.PHONE,
                mBackend.homeSurface);
        assertTrue(mBackend.primaryHomePresented);
        assertEquals(0, mBackend.presentedUserId);
    }

    @Test
    public void phoneTargetMakesDesktopThePrimaryHome() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.phone());

        assertEquals(
                DesktopHomeSurfaceRouter.Surface.DESKTOP,
                mBackend.homeSurface);
        assertTrue(DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.DESKTOP));
        assertFalse(DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.PHONE));
        assertEquals(
                DesktopHomeSurfaceRouter.Surface.DESKTOP,
                mBackend.presentedSurface);
    }

    @Test
    public void isolatedPhoneTargetStillPresentsDesktopHome()
            throws Exception {
        final DesktopDisplayTarget target = DesktopDisplayTarget.phone();
        DesktopHomeRoleLease.acquire(
                target,
                DesktopSessionPolicy.ISOLATED_SELF_TEST);

        assertTrue(mBackend.primaryHomePresented);
        assertEquals(
                DesktopHomeSurfaceRouter.Surface.DESKTOP,
                mBackend.presentedSurface);

        assertTrue(DesktopHomeRoleLease.release(target));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(MAGICDESK, mBackend.presentedHomePackage);
        assertEquals(
                List.of(
                        "surface:disabled",
                        "home:" + LAUNCHER,
                        "surface:default"),
                mBackend.releaseCalls);
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
                homeSelection(LAUNCHER),
                DesktopDisplayTarget.simulated(7),
                DesktopSessionPolicy.USER,
                DesktopHomeRoleLease.Phase.PREPARED);

        final DesktopHomeRoleLease.AcquireResult result =
                DesktopHomeRoleLease.acquire(
                        DesktopDisplayTarget.simulated(7));

        assertTrue(result.created);
        DesktopHomeRoleLease.releaseAfterFailedStart(result);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertTrue(mBackend.homeSurfaceRestored);
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
            assertTrue(mBackend.homeSurfaceRestored);
            assertNull(mStorage.state);
        }
    }

    @Test
    public void releaseTransfersHomeAndClearsLease() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7)));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(LAUNCHER, mBackend.presentedHomePackage);
        assertNull(mStorage.state);
        assertEquals(
                List.of(
                        "surface:disabled",
                        "home:" + LAUNCHER,
                        "present:" + LAUNCHER,
                        "surface:default"),
                mBackend.releaseCalls);
    }

    @Test
    public void sessionClosePresentsRestoredHomeOnlyAfterTeardown()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.phone());

        final DesktopHomeRoleLease.RestoredHomePresentation presentation =
                DesktopHomeRoleLease.releaseForSessionClose(
                        DesktopDisplayTarget.phone());

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(MAGICDESK, mBackend.presentedHomePackage);
        assertEquals(
                List.of(
                        "surface:disabled",
                        "home:" + LAUNCHER,
                        "surface:default"),
                mBackend.releaseCalls);

        DesktopHomeRoleLease.presentRestoredHome(presentation);

        assertEquals(LAUNCHER, mBackend.presentedHomePackage);
        assertEquals("present:" + LAUNCHER,
                mBackend.releaseCalls.get(mBackend.releaseCalls.size() - 1));
    }

    @Test
    public void acquireAndReleasePreserveMissingRoleHolder()
            throws Exception {
        mBackend.homePackage = "";

        final DesktopHomeRoleLease.AcquireResult result =
                DesktopHomeRoleLease.acquire(
                        DesktopDisplayTarget.phone());

        assertTrue(result.created);
        assertEquals("", result.state.previousHome.packageName);
        assertEquals(
                AndroidHomeSelection.Availability.NONE,
                result.state.previousHome.availability);
        assertEquals(MAGICDESK, mBackend.homePackage);

        assertTrue(DesktopHomeRoleLease.release(
                DesktopDisplayTarget.phone()));
        assertEquals("", mBackend.homePackage);
        assertEquals(List.of(
                        "surface:disabled",
                        "home:<none>",
                        "present:<none>",
                        "surface:default"),
                mBackend.releaseCalls);
        assertNull(mStorage.state);
    }

    @Test
    public void isolatedExternalReleaseDoesNotPresentPhoneHome()
            throws Exception {
        final DesktopDisplayTarget target =
                DesktopDisplayTarget.simulated(7);
        DesktopHomeRoleLease.acquire(
                target,
                DesktopSessionPolicy.ISOLATED_SELF_TEST);

        assertTrue(DesktopHomeRoleLease.release(target));

        assertNull(mBackend.presentedHomePackage);
        assertEquals(
                List.of(
                        "surface:disabled",
                        "home:" + LAUNCHER,
                        "surface:default"),
                mBackend.releaseCalls);
    }

    @Test
    public void releaseRejectsDifferentTarget() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        try {
            DesktopHomeRoleLease.release(
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

        DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertTrue(mBackend.homeSurfaceRestored);
        assertNull(mStorage.state);
    }

    @Test
    public void releaseRestoresHomeWhenSurfaceQuiesceFails()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));
        mBackend.failHomeSurfaceDisable = true;

        assertTrue(DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7)));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertTrue(mBackend.homeSurfaceRestored);
        assertNull(mStorage.state);
    }

    @Test
    public void interruptedReleaseRemainsRecoverableWithoutReclaimingHome()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));
        mBackend.failHomeSurfaceRestore = true;

        try {
            DesktopHomeRoleLease.release(
                    DesktopDisplayTarget.simulated(7));
            fail("HOME surface restore failure expected");
        } catch (IOException expected) {
            assertEquals(LAUNCHER, mBackend.homePackage);
            assertEquals(
                    DesktopHomeRoleLease.Phase.RELEASING,
                    mStorage.state.phase);
        }

        mBackend.failHomeSurfaceRestore = false;
        assertTrue(DesktopHomeRoleLease.reconcile(false));
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void releasePreservesAUserSelectedThirdPartyHome()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));
        mBackend.homePackage = "com.example.otherhome";

        assertTrue(DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7)));

        assertEquals("com.example.otherhome", mBackend.homePackage);
        assertEquals("com.example.otherhome", mBackend.presentedHomePackage);
        assertTrue(mBackend.homeSurfaceRestored);
        assertNull(mStorage.state);
    }

    @Test
    public void sessionLossRestoresOwnedHomeLease() throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.releaseAfterSessionLoss(7));

        assertFalse(DesktopHomeRoleLease.isPhoneOverviewRoutingActive());
        assertEquals(
                DesktopHomeRoleLease.Phase.RELEASING,
                mStorage.lastWrittenPhase);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertTrue(mBackend.homeSurfaceRestored);
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
        assertTrue(mBackend.homeSurfaceRestored);
        assertNull(mStorage.state);
    }

    @Test
    public void startupRelinquishDiscardsLeaseWithoutChangingRole()
            throws Exception {
        DesktopHomeRoleLease.acquire(DesktopDisplayTarget.phone());

        assertTrue(DesktopHomeRoleLease.discardForStartupRelinquish());

        assertEquals(MAGICDESK, mBackend.homePackage);
        assertNull(mStorage.state);
        assertTrue(mBackend.releaseCalls.isEmpty());
    }

    @Test
    public void startupRelinquishDoesNotRequireStoredLease()
            throws Exception {
        assertFalse(DesktopHomeRoleLease.discardForStartupRelinquish());
        assertNull(mStorage.state);
    }

    @Test
    public void unavailableHomeMetadataDoesNotBlockLeaseAcquisition()
            throws Exception {
        mBackend.failHomeResolution = true;

        final DesktopHomeRoleLease.AcquireResult result =
                DesktopHomeRoleLease.acquire(
                        DesktopDisplayTarget.simulated(7));

        assertTrue(result.created);
        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(LAUNCHER, result.state.previousHome.packageName);
        assertEquals("", result.state.previousHome.componentName);
        assertEquals(
                AndroidHomeSelection.Availability.UNRESOLVED,
                result.state.previousHome.availability);
    }

    private final class FakeBackend implements DesktopHomeRoleLease.Backend {
        String homePackage;
        int setCalls;
        boolean failMagicDeskClaim;
        boolean stateWasPreparedBeforeSet;
        boolean primaryHomePresented;
        boolean failHomeSurfaceDisable;
        boolean failHomeSurfaceRestore;
        boolean failHomeResolution;
        int presentedUserId = -1;
        String presentedHomePackage;
        DesktopHomeSurfaceRouter.Surface homeSurface;
        DesktopHomeSurfaceRouter.Surface presentedSurface;
        boolean homeSurfaceRestored;
        final List<String> releaseCalls = new ArrayList<>();

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
        public AndroidHomeSelection resolveHomeSelection(
                final int userId,
                final String packageName) throws IOException {
            if (failHomeResolution) {
                throw new IOException("HOME resolution unavailable");
            }
            return homeSelection(packageName);
        }

        @Override
        public void selectHomeSurface(
                final DesktopHomeSurfaceRouter.Surface surface) {
            homeSurface = surface;
            homeSurfaceRestored = false;
        }

        @Override
        public void disableHomeSurfaces() throws IOException {
            if (failHomeSurfaceDisable) {
                throw new IOException("HOME surface disable rejected");
            }
            homeSurface = null;
            homeSurfaceRestored = false;
            releaseCalls.add("surface:disabled");
        }

        @Override
        public void restoreHomeSurface() throws IOException {
            if (failHomeSurfaceRestore) {
                throw new IOException("surface restore rejected");
            }
            homeSurface = DesktopHomeSurfaceRouter.Surface.PHONE;
            homeSurfaceRestored = true;
            releaseCalls.add("surface:default");
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
            if (!MAGICDESK.equals(packageName)) {
                releaseCalls.add("home:" + packageName);
            }
        }

        @Override
        public void clearHomePackage(
                final int userId,
                final String packageName) {
            if (packageName.equals(homePackage)) {
                homePackage = "";
            }
            releaseCalls.add("home:<none>");
        }

        @Override
        public void presentHome(
                final int userId,
                final String packageName) {
            primaryHomePresented = true;
            presentedUserId = userId;
            presentedHomePackage = packageName;
            presentedSurface = homeSurface;
            if (!MAGICDESK.equals(packageName)) {
                releaseCalls.add("present:"
                        + (packageName == null || packageName.isEmpty()
                                ? "<none>" : packageName));
            }
        }
    }

    private static final class MemoryStorage
            implements DesktopHomeRoleLease.Storage {
        DesktopHomeRoleLease.State state;
        DesktopHomeRoleLease.Phase lastWrittenPhase;

        @Override
        public DesktopHomeRoleLease.State read() {
            return state;
        }

        @Override
        public void write(final DesktopHomeRoleLease.State value) {
            state = value;
            lastWrittenPhase = value.phase;
        }

        @Override
        public void clear() {
            state = null;
        }
    }

    private static AndroidHomeSelection homeSelection(
            final String packageName) {
        return AndroidHomeSelection.fromPersisted(
                packageName,
                packageName + "/.Launcher",
                42,
                AndroidHomeSelection.Availability.DECLARED.name());
    }
}
