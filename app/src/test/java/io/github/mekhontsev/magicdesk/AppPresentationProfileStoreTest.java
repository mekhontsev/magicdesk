package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class AppPresentationProfileStoreTest {
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
                "example.application", 100));
        assertEquals(
                100,
                AppPresentationProfileStore.load(
                        "example.application").scalePercent);

        assertTrue(AppPresentationProfileStore.reset(
                "example.application"));
        assertNull(AppPresentationProfileStore.load(
                "example.application"));
    }

    @Test
    public void invalidAndInfrastructureProfilesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AppPresentationProfileStore.setScale(
                        "example.application", 49));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppPresentationProfileStore.setScale(
                        BuildConfig.APPLICATION_ID, 100));
    }
}
