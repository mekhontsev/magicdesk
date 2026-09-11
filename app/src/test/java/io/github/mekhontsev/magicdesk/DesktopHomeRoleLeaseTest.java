package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertSame;

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
    public void stateRetainsCompleteBindingAcrossPhaseChanges() throws Exception {
        final DesktopDisplayTarget original = DesktopDisplayTarget.simulated(7)
                .withProfile("display:simulated:workspace");
        final DesktopHomeRoleLease.AcquireResult result = acquire(original);
        assertSame(original, result.state.target());
        final DesktopHomeRoleLease.State releasing = result.state.withPhase(
                DesktopHomeRoleLease.Phase.RELEASING);
        assertSame(original, releasing.target());
        final DesktopDisplayTarget differentOutput = DesktopDisplayTarget.restore(
                DesktopDisplayOutput.Kind.SIMULATED, 7, 8,
                "display:simulated:workspace",
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);
        assertFalse(releasing.matches(differentOutput));
        assertTrue(releasing.matches(original.withProfile("display:simulated:updated")));
    }

    @Test
    public void preparationEnablesSurfacesWithoutTakingHome() throws Exception {
        final DesktopHomeRoleLease.AcquireResult preparation =
                DesktopHomeRoleLease.prepare(
                        DesktopDisplayTarget.simulated(7), DesktopSessionPolicy.USER,
                        DesktopCompatibilityPolicy.NONE);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(DesktopHomeSurfaceRouter.Surface.PHONE, mBackend.homeSurface);
        assertEquals(DesktopHomeRoleLease.Phase.PREPARED, mStorage.state.phase);
        assertFalse(DesktopHomeRoleLease.isPhoneOverviewRoutingActive());
        assertFalse(mBackend.primaryHomePresented);
        final int selections = mBackend.surfaceSelections;

        DesktopHomeRoleLease.activate(preparation);

        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(selections, mBackend.surfaceSelections);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
    }

    @Test
    public void compatibilityIsLatchedThroughReuseAndRelease() throws Exception {
        final DesktopDisplayTarget target = DesktopDisplayTarget.simulated(7);
        final DesktopCompatibilityPolicy selected = DesktopCompatibilityPolicy.NONE
                .with(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR, true)
                .with(DesktopCompatibilityPolicy.Option.PHONE_TASK_RECOVERY, true);
        final DesktopHomeRoleLease.AcquireResult preparation = DesktopHomeRoleLease.prepare(
                target, DesktopSessionPolicy.USER, selected);
        assertSame(selected, preparation.state.compatibility);
        DesktopHomeRoleLease.activate(preparation);
        assertSame(selected, mStorage.state.compatibility);
        final DesktopHomeRoleLease.AcquireResult reused = DesktopHomeRoleLease.prepare(
                target, DesktopSessionPolicy.USER, DesktopCompatibilityPolicy.NONE);
        assertSame(selected, reused.state.compatibility);
        DesktopHomeRoleLease.releaseForSessionClose(target);
        assertSame(selected, mStorage.state.compatibility);
        assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
    }

    @Test
    public void abandonedPreparationDisablesComponentsWithoutClaimingHome() throws Exception {
        final DesktopHomeRoleLease.AcquireResult preparation =
                DesktopHomeRoleLease.prepare(
                        DesktopDisplayTarget.simulated(7), DesktopSessionPolicy.USER,
                        DesktopCompatibilityPolicy.NONE);
        DesktopHomeRoleLease.releaseAfterFailedStart(preparation);
        assertEquals(0, mBackend.setCalls);
        assertNull(mBackend.homeSurface);
        assertNull(mStorage.state);
    }

    @Test
    public void acquirePersistsPreviousHomeBeforeClaimingRole()
            throws Exception {
        final DesktopHomeRoleLease.AcquireResult result =
                acquire(
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
        acquire(DesktopDisplayTarget.phone());

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
        acquire(
                target,
                DesktopSessionPolicy.ISOLATED_SELF_TEST);

        assertTrue(mBackend.primaryHomePresented);
        assertEquals(
                DesktopHomeSurfaceRouter.Surface.DESKTOP,
                mBackend.presentedSurface);

        assertTrue(DesktopHomeRoleLease.release(target));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(LAUNCHER, mBackend.presentedHomePackage);
        assertEquals(
                List.of(
                        "home:" + LAUNCHER,
                        "surface:disabled",
                        "present:" + LAUNCHER),
                mBackend.releaseCalls);
    }

    @Test
    public void acquireIsIdempotentForSameTarget() throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

        final DesktopHomeRoleLease.AcquireResult result =
                acquire(
                        DesktopDisplayTarget.simulated(7));

        assertFalse(result.created);
        assertEquals(1, mBackend.setCalls);
    }

    @Test
    public void acquirePersistsSessionPolicy() throws Exception {
        acquire(
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
                DesktopCompatibilityPolicy.NONE,
                DesktopHomeRoleLease.Phase.PREPARED);

        final DesktopHomeRoleLease.AcquireResult result =
                acquire(
                        DesktopDisplayTarget.simulated(7));

        assertTrue(result.created);
        DesktopHomeRoleLease.releaseAfterFailedStart(result);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mBackend.homeSurface);
        assertNull(mStorage.state);
    }

    @Test
    public void acquireRejectsSecondTarget() throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

        try {
            acquire(
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
            acquire(
                    DesktopDisplayTarget.simulated(7));
            fail("claim failure expected");
        } catch (IOException expected) {
            assertEquals(LAUNCHER, mBackend.homePackage);
            assertNull(mBackend.homeSurface);
            assertNull(mStorage.state);
        }
    }

    @Test
    public void releaseTransfersHomeAndClearsLease() throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7)));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(LAUNCHER, mBackend.presentedHomePackage);
        assertNull(mStorage.state);
        assertEquals(
                List.of(
                        "home:" + LAUNCHER,
                        "surface:disabled",
                        "present:" + LAUNCHER),
                mBackend.releaseCalls);
    }

    @Test
    public void sessionClosePresentsRestoredHomeOnlyAfterTeardown()
            throws Exception {
        acquire(DesktopDisplayTarget.phone());

        DesktopHomeRoleLease.releaseForSessionClose(DesktopDisplayTarget.phone());

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertEquals(MAGICDESK, mBackend.presentedHomePackage);
        assertEquals(List.of("home:" + LAUNCHER), mBackend.releaseCalls);
        assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
        assertEquals(DesktopHomeSurfaceRouter.Surface.DESKTOP, mBackend.homeSurface);
        assertFalse(DesktopHomeRoleLease.isPhoneOverviewRoutingActive());
        assertTrue(DesktopHomeRoleLease.isReleasingForSurface(
                DesktopHomeSurfaceRouter.Surface.DESKTOP));
        assertFalse(DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.DESKTOP));

        final DesktopHomeRoleLease.RestoredHomePresentation presentation =
                DesktopHomeRoleLease.finishSessionClose(DesktopDisplayTarget.phone());
        assertNull(mStorage.state);
        assertNull(mBackend.homeSurface);

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
                acquire(
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
                        "home:<none>",
                        "surface:disabled",
                        "present:<none>"),
                mBackend.releaseCalls);
        assertNull(mStorage.state);
    }

    @Test
    public void isolatedExternalClosePresentsHomeAfterTeardown()
            throws Exception {
        final DesktopDisplayTarget target =
                DesktopDisplayTarget.simulated(7);
        acquire(
                target,
                DesktopSessionPolicy.ISOLATED_SELF_TEST);

        DesktopHomeRoleLease.releaseForSessionClose(target);

        assertNull(mBackend.presentedHomePackage);
        assertTrue(DesktopHomeRoleLease.isReleasingForDisplay(7));
        assertFalse(DesktopHomeRoleLease.isReleasingForDisplay(0));
        assertFalse(DesktopHomeRoleLease.isActiveForDisplay(7));
        assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
        assertEquals(List.of("home:" + LAUNCHER), mBackend.releaseCalls);
        final DesktopHomeRoleLease.RestoredHomePresentation presentation =
                DesktopHomeRoleLease.finishSessionClose(target);
        DesktopHomeRoleLease.presentRestoredHome(presentation);
        assertEquals(LAUNCHER, mBackend.presentedHomePackage);
        assertFalse(DesktopHomeRoleLease.isReleasingForDisplay(7));
        assertEquals(List.of("home:" + LAUNCHER, "surface:disabled",
                "present:" + LAUNCHER), mBackend.releaseCalls);
    }

    @Test
    public void releaseRejectsDifferentTarget() throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

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
        acquire(DesktopDisplayTarget.simulated(7));

        DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mBackend.homeSurface);
        assertNull(mStorage.state);
    }

    @Test
    public void releaseRestoresHomeWhenSurfaceQuiesceFails()
            throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));
        mBackend.failHomeSurfaceDisable = true;

        try {
            DesktopHomeRoleLease.release(DesktopDisplayTarget.simulated(7));
            fail("disabling our HOME surfaces is required");
        } catch (IOException expected) {
            assertEquals(LAUNCHER, mBackend.homePackage);
            assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
        }
    }

    @Test
    public void interruptedReleaseRemainsRecoverableWithoutReclaimingHome()
            throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));
        mBackend.failHomeSurfaceDisable = true;

        try {
            DesktopHomeRoleLease.release(
                    DesktopDisplayTarget.simulated(7));
            fail("HOME surface disable failure expected");
        } catch (IOException expected) {
            assertEquals(LAUNCHER, mBackend.homePackage);
            assertEquals(
                    DesktopHomeRoleLease.Phase.RELEASING,
                    mStorage.state.phase);
        }

        mBackend.failHomeSurfaceDisable = false;
        assertTrue(DesktopHomeRoleLease.reconcile(false));
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void releasePreservesAUserSelectedThirdPartyHome()
            throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));
        mBackend.homePackage = "com.example.otherhome";

        assertTrue(DesktopHomeRoleLease.release(
                DesktopDisplayTarget.simulated(7)));

        assertEquals("com.example.otherhome", mBackend.homePackage);
        assertEquals("com.example.otherhome", mBackend.presentedHomePackage);
        assertNull(mBackend.homeSurface);
        assertNull(mStorage.state);
    }

    @Test
    public void sessionLossRestoresOwnedHomeLease() throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.releaseAfterSessionLoss(7));

        assertFalse(DesktopHomeRoleLease.isPhoneOverviewRoutingActive());
        assertEquals(
                DesktopHomeRoleLease.Phase.RELEASING,
                mStorage.lastWrittenPhase);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mBackend.homeSurface);
        assertNull(mStorage.state);
    }

    @Test
    public void startupReconciliationRetainsLiveActiveLease()
            throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

        assertFalse(DesktopHomeRoleLease.reconcile(true));

        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(DesktopHomeRoleLease.Phase.ACTIVE, mStorage.state.phase);
    }

    @Test
    public void startupReconciliationRestoresStaleActiveLease()
            throws Exception {
        acquire(DesktopDisplayTarget.simulated(7));

        assertTrue(DesktopHomeRoleLease.reconcile(false));

        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mBackend.homeSurface);
        assertNull(mStorage.state);
    }

    @Test
    public void startupRelinquishDiscardsLeaseWithoutChangingRole()
            throws Exception {
        acquire(DesktopDisplayTarget.phone());

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
    public void newSessionCanAcquireHomeAfterStartupRecovery() throws Exception {
        acquire(DesktopDisplayTarget.phone());
        DesktopHomeRoleLease.discardForStartupRelinquish();
        assertFalse(DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.DESKTOP));
        // Disabling our components returns HOME to the system outside the
        // shell-backed lease. A later explicit start owns a new lease.
        mBackend.homePackage = LAUNCHER;
        final DesktopHomeRoleLease.AcquireResult prepared = DesktopHomeRoleLease.prepare(
                DesktopDisplayTarget.phone(), DesktopSessionPolicy.USER,
                DesktopCompatibilityPolicy.NONE);
        assertFalse(DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.DESKTOP));
        DesktopHomeRoleLease.activate(prepared);
        assertTrue(DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.DESKTOP));
        assertEquals(LAUNCHER, DesktopHomeRoleLease.snapshot().previousHome.packageName);
    }

    @Test
    public void unavailableHomeMetadataDoesNotBlockLeaseAcquisition()
            throws Exception {
        mBackend.failHomeResolution = true;

        final DesktopHomeRoleLease.AcquireResult result =
                acquire(
                        DesktopDisplayTarget.simulated(7));

        assertTrue(result.created);
        assertEquals(MAGICDESK, mBackend.homePackage);
        assertEquals(LAUNCHER, result.state.previousHome.packageName);
        assertEquals("", result.state.previousHome.componentName);
        assertEquals(
                AndroidHomeSelection.Availability.UNRESOLVED,
                result.state.previousHome.availability);
    }

    @Test
    public void deferredCloseWithoutDefaultHomeCannotSelectMagicDeskAgain()
            throws Exception {
        mBackend.homePackage = "";
        final DesktopDisplayTarget target = DesktopDisplayTarget.simulated(7);
        acquire(target);
        DesktopHomeRoleLease.releaseForSessionClose(target);
        assertEquals("", mBackend.homePackage);
        assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);

        final DesktopHomeRoleLease.RestoredHomePresentation presentation =
                DesktopHomeRoleLease.finishSessionClose(target);
        assertNull(mStorage.state);
        assertNull(mBackend.homeSurface);
        DesktopHomeRoleLease.presentRestoredHome(presentation);
        assertEquals("", mBackend.resolvedHomePackage);
    }

    @Test
    public void splitCloseRemainsRecoverableAfterSurfaceFailure() throws Exception {
        final DesktopDisplayTarget target = DesktopDisplayTarget.simulated(7);
        acquire(target);
        DesktopHomeRoleLease.releaseForSessionClose(target);
        mBackend.failHomeSurfaceDisable = true;
        try {
            DesktopHomeRoleLease.finishSessionClose(target);
            fail("surface cleanup failure expected");
        } catch (IOException expected) {
            assertEquals(LAUNCHER, mBackend.homePackage);
            assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
        }
        mBackend.failHomeSurfaceDisable = false;
        assertTrue(DesktopHomeRoleLease.reconcile(false));
        assertNull(mStorage.state);
        assertNull(mBackend.homeSurface);
    }

    @Test
    public void finalizationDoesNotReplaceHomeSelectedDuringTeardown() throws Exception {
        final DesktopDisplayTarget target = DesktopDisplayTarget.simulated(7);
        acquire(target);
        DesktopHomeRoleLease.releaseForSessionClose(target);
        mBackend.homePackage = "com.example.otherhome";
        DesktopHomeRoleLease.presentRestoredHome(
                DesktopHomeRoleLease.finishSessionClose(target));
        assertEquals("com.example.otherhome", mBackend.homePackage);
        assertEquals("com.example.otherhome", mBackend.presentedHomePackage);
    }

    @Test
    public void failedRoleReturnStillDisablesSurfacesAtEndOfClose() throws Exception {
        final DesktopDisplayTarget target = DesktopDisplayTarget.simulated(7);
        acquire(target);
        mBackend.failHomeRestore = true;
        try {
            DesktopHomeRoleLease.releaseForSessionClose(target);
            fail("role return failure expected");
        } catch (IOException expected) {
            assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
            assertEquals(DesktopHomeSurfaceRouter.Surface.PHONE, mBackend.homeSurface);
        }
        try {
            DesktopHomeRoleLease.finishSessionClose(target);
            fail("role return still fails");
        } catch (IOException expected) {
            assertNull(mBackend.homeSurface);
            assertEquals(DesktopHomeRoleLease.Phase.RELEASING, mStorage.state.phase);
        }
        mBackend.failHomeRestore = false;
        DesktopHomeRoleLease.finishSessionClose(target);
        assertEquals(LAUNCHER, mBackend.homePackage);
        assertNull(mStorage.state);
    }

    @Test
    public void idleReconciliationDoesNotEnableHomeSurfaces() throws Exception {
        assertFalse(DesktopHomeRoleLease.reconcile(false));
        assertNull(mBackend.homeSurface);
        assertEquals(List.of("surface:disabled"), mBackend.releaseCalls);
    }

    private final class FakeBackend implements DesktopHomeRoleLease.Backend {
        String homePackage;
        int setCalls;
        int surfaceSelections;
        boolean failMagicDeskClaim;
        boolean failHomeRestore;
        boolean stateWasPreparedBeforeSet;
        boolean primaryHomePresented;
        boolean failHomeSurfaceDisable;
        boolean failHomeResolution;
        int presentedUserId = -1;
        String presentedHomePackage;
        String resolvedHomePackage;
        DesktopHomeSurfaceRouter.Surface homeSurface;
        DesktopHomeSurfaceRouter.Surface presentedSurface;
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
                final DesktopHomeSurfaceRouter.Selection selection) {
            surfaceSelections++;
            homeSurface = selection.primary;
        }

        @Override
        public void disableHomeSurfaces() throws IOException {
            if (failHomeSurfaceDisable) {
                throw new IOException("HOME surface disable rejected");
            }
            homeSurface = null;
            releaseCalls.add("surface:disabled");
        }

        @Override
        public void setHomePackage(
                final int userId,
                final String packageName) throws IOException {
            setCalls++;
            if (failHomeRestore && LAUNCHER.equals(packageName)) {
                throw new IOException("HOME restore rejected");
            }
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
            resolvedHomePackage = packageName == null || packageName.isEmpty()
                    ? homeSurface == null ? "" : MAGICDESK : packageName;
            presentedSurface = homeSurface;
            if (!MAGICDESK.equals(packageName)) {
                releaseCalls.add("present:"
                        + (packageName == null || packageName.isEmpty()
                                ? "<none>" : packageName));
            }
        }
    }

    private static DesktopHomeRoleLease.AcquireResult acquire(
            final DesktopDisplayTarget target) throws IOException {
        return acquire(target, DesktopSessionPolicy.USER);
    }

    private static DesktopHomeRoleLease.AcquireResult acquire(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) throws IOException {
        return DesktopHomeRoleLease.activate(DesktopHomeRoleLease.prepare(
                target, policy, DesktopCompatibilityPolicy.NONE));
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
