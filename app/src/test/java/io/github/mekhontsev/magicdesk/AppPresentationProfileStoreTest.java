package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class AppPresentationProfileStoreTest {
    private static final AppProfile PROFILE = new AppProfile(0, 0);
    private static final AppIdentity APP = PROFILE.application("example.application");

    @Before
    public void setUp() {
        DesktopStateStore.useStorageForTests(
                new DesktopStateStore.Storage() {
                    private String mEncoded = "";

                    @Override
                    public String read() {
                        return mEncoded;
                    }

                    @Override
                    public void write(final String encoded) {
                        mEncoded = encoded;
                    }
                });
    }

    @After
    public void tearDown() {
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void customScaleRoundTripsAndResets() {
        assertTrue(AppPresentationProfileStore.setScale(
                APP, 100));
        assertEquals(
                100,
                AppPresentationProfileStore.load(
                        APP).scalePercent);

        assertTrue(AppPresentationProfileStore.reset(
                APP));
        assertNull(AppPresentationProfileStore.load(
                APP));
    }

    @Test
    public void invalidAndInfrastructureProfilesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AppPresentationProfileStore.setScale(
                        APP, 49));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppPresentationProfileStore.setScale(
                        PROFILE.application(BuildConfig.APPLICATION_ID), 100));
    }
}
