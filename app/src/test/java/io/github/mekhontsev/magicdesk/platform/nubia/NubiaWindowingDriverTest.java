package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NubiaWindowingDriverTest {
    @Test
    public void deniedPropertiesDoNotThrowOrRequireReboot() {
        final List<NubiaDesktopPropertyManager.Property> warnings = new ArrayList<>();
        final NubiaWindowingDriver windowing = new NubiaWindowingDriver(
                (property, value) -> {
                    assertEquals("false", value);
                    throw new IOException("expected=false observed=<empty>");
                }, (property, error) -> {
                    warnings.add(property);
                    assertEquals("expected=false observed=<empty>", error.getMessage());
                });

        assertFalse(windowing.configure(false, false));
        assertEquals(List.of(NubiaDesktopPropertyManager.Property.values()), warnings);
    }

    @Test
    public void eachPropertyIsIndependentAndVerifiedChangesRequireReboot() {
        for (final NubiaDesktopPropertyManager.Property denied
                : NubiaDesktopPropertyManager.Property.values()) {
            final List<NubiaDesktopPropertyManager.Property> attempts = new ArrayList<>();
            final List<NubiaDesktopPropertyManager.Property> warnings = new ArrayList<>();
            final NubiaWindowingDriver windowing = new NubiaWindowingDriver(
                    (property, value) -> {
                        attempts.add(property);
                        if (property == denied) {
                            throw new IOException("permission denied");
                        }
                        return true;
                    }, (property, error) -> warnings.add(property));

            assertTrue(windowing.configure(false, false));
            assertEquals(List.of(NubiaDesktopPropertyManager.Property.values()), attempts);
            assertEquals(List.of(denied), warnings);
        }
    }

    @Test
    public void writesOnlyMissingProperties() {
        for (int mask = 0; mask < 4; mask++) {
            final List<NubiaDesktopPropertyManager.Property> attempts = new ArrayList<>();
            final NubiaWindowingDriver windowing = new NubiaWindowingDriver(
                    (property, value) -> {
                        attempts.add(property);
                        assertEquals("false", value);
                        return true;
                    }, (property, error) -> { throw new AssertionError(error); });
            final boolean restrictionsDisabled = (mask & 1) != 0;
            final boolean cornersDisabled = (mask & 2) != 0;

            assertEquals(mask != 3, windowing.configure(restrictionsDisabled, cornersDisabled));
            assertEquals(!restrictionsDisabled, attempts.contains(
                    NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS));
            assertEquals(!cornersDisabled, attempts.contains(
                    NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS));
        }
    }

    @Test
    public void alreadyAppliedWritesDoNotRequireReboot() {
        final NubiaWindowingDriver windowing = new NubiaWindowingDriver(
                (property, value) -> false,
                (property, error) -> { throw new AssertionError(error); });
        assertFalse(windowing.configure(false, false));
    }

    @Test
    public void processSecurityDenialsRemainOptional() {
        final List<NubiaDesktopPropertyManager.Property> warnings = new ArrayList<>();
        final NubiaWindowingDriver windowing = new NubiaWindowingDriver(
                (property, value) -> { throw new SecurityException("not permitted"); },
                (property, error) -> warnings.add(property));
        assertFalse(windowing.configure(false, false));
        assertEquals(List.of(NubiaDesktopPropertyManager.Property.values()), warnings);
    }

    @Test
    public void restoreContinuesAfterAPropertyDenial() {
        final List<NubiaDesktopPropertyManager.Property> attempts = new ArrayList<>();
        final List<NubiaDesktopPropertyManager.Property> warnings = new ArrayList<>();
        final NubiaWindowingDriver windowing = new NubiaWindowingDriver(
                (property, value) -> {
                    attempts.add(property);
                    assertEquals("", value);
                    throw new IOException("permission denied");
                }, (property, error) -> warnings.add(property));
        windowing.restoreDefaults();
        assertEquals(List.of(NubiaDesktopPropertyManager.Property.values()), attempts);
        assertEquals(attempts, warnings);
    }
}
