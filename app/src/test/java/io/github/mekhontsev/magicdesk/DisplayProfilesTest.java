package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import org.junit.Test;

public final class DisplayProfilesTest {
    private static final VirtualDisplaySpec FALLBACK = new VirtualDisplaySpec(1920, 1080, 160);

    @Test public void profileKeysMatchDesktopPreparationButDoNotRequireDesktopEligibility() {
        assertEquals("display:local:panel", DisplayProfiles.key(display(0, "panel", "phone", 520)));
        assertEquals("display:local:panel2", DisplayProfiles.key(display(2, "panel2", "internal", 400)));
        assertEquals("display:wired:monitor", DisplayProfiles.key(display(8, "monitor", "wired", 160)));
        assertEquals("display:wireless:tv", DisplayProfiles.key(display(9, "tv", "wireless", 160)));
        assertEquals("display:simulated:virtual", DisplayProfiles.key(display(10, "virtual", "virtual", 160)));
        assertEquals("display:simulated:preview", DisplayProfiles.key(display(11, "preview", "overlay", 160)));
        assertEquals(DisplayProfiles.key(display(8, "monitor", "wired", 160)),
                DisplayProfiles.key(display(28, "monitor", "wired", 240)));
    }

    @Test public void productLabelDoesNotChangeStableOrFallbackProfileIdentity() {
        for (String uniqueId : new String[] {"monitor", "", null}) {
            final DesktopDisplayInfo original = new DesktopDisplayInfo(42, uniqueId, "HDMI Screen", "HDMI Screen",
                    "wired", 1920, 1200, 213, true, false, false, true);
            final DesktopDisplayInfo named = new DesktopDisplayInfo(42, uniqueId, "HDMI Screen", "VITURE Beast",
                    "wired", 1920, 1200, 213, true, false, false, true);
            assertEquals(DisplayProfiles.key(original), DisplayProfiles.key(named));
            assertEquals(DisplayProfiles.key(DesktopDisplayOutput.Kind.WIRED, uniqueId,
                    "HDMI Screen", 1920, 1200), DisplayProfiles.key(named));
            assertEquals(original.uniqueId, named.uniqueId);
            assertEquals(original.id, named.id);
            assertEquals("VITURE Beast", named.name);
            assertEquals("HDMI Screen", named.systemName);
        }
    }

    @Test public void inheritanceFlattensOriginButSnapshotsTheImmediateReferencesSettings() {
        final DesktopDisplayInfo monitor = display(1, "monitor", "wired", 160);
        final DisplayProfileStore.Profile root = new DisplayProfileStore.Profile(DisplayProfiles.key(monitor));
        root.dpi = 224;
        root.dpiExplicit = true;
        root.outputTiming = "2560x1440@120";
        root.resetOutputModePending = true;
        final DisplayProfiles.CreationDefaults first = DisplayProfiles.snapshot(monitor, root, FALLBACK);
        assertEquals(2560, first.width);
        assertEquals(1440, first.height);
        assertEquals(224, first.densityDpi);
        final DesktopDisplayInfo v1 = display(8, "v1", "virtual", 224);
        final DisplayProfileStore.Profile child = DisplayProfiles.createdProfile(v1,
                first.spec(first.width, first.height, first.densityDpi, false));
        assertEquals(root.key, child.originProfileKey);
        assertNotEquals(root.key, child.key);
        assertNull(child.outputTiming);
        assertFalse(child.resetOutputModePending);
        assertEquals(2560, child.width);

        child.dpi = 288;
        final DisplayProfiles.CreationDefaults second = DisplayProfiles.snapshot(v1, child, FALLBACK);
        assertEquals(288, second.densityDpi);
        final DisplayProfileStore.Profile grandchild = DisplayProfiles.createdProfile(display(9, "v2", "virtual", 288),
                second.spec(1280, 720, second.densityDpi, false));
        assertEquals(root.key, grandchild.originProfileKey);
        assertEquals(1280, grandchild.width);
        assertEquals(720, grandchild.height);
        grandchild.dpi = 320;
        assertEquals(288, child.dpi);
        assertEquals(224, root.dpi);
        assertEquals("2560x1440@120", root.outputTiming);
    }

    @Test public void descendantRetainsOriginWithoutItsParentOrItsCurrentViewerOutput() throws Exception {
        final DisplayProfileStore.Profile saved = new DisplayProfileStore.Profile("display:simulated:v2");
        saved.originProfileKey = "display:wired:disconnected-monitor";
        saved.dpi = 240;
        saved.dpiExplicit = true;
        saved.width = 1280;
        saved.height = 720;
        final DesktopStateStore.State state = new DesktopStateStore.State();
        state.displayProfiles.put(saved.key, saved);
        final DisplayProfileStore.Profile reloaded = DesktopStateStore.decode(DesktopStateStore.encode(state))
                .displayProfiles.get(saved.key);
        final DisplayProfiles.CreationDefaults next = DisplayProfiles.snapshot(
                display(42, "v2", "virtual", 240), reloaded, FALLBACK);
        assertEquals(saved.originProfileKey, next.originProfileKey);
        assertEquals(240, next.densityDpi);
        final DisplayProfileStore.Profile copy = DisplayProfileStore.copy(reloaded);
        copy.dpi = 320;
        assertEquals(saved.originProfileKey, copy.originProfileKey);
        assertEquals(1280, copy.width);
        assertEquals(720, copy.height);
        assertEquals(240, reloaded.dpi);
    }

    @Test public void unconfiguredReferenceUsesLiveDensityNotAnImplicitDesktopRecommendation() {
        final DesktopDisplayInfo phone = display(0, "phone", "phone", 520);
        final DisplayProfileStore.Profile profile = new DisplayProfileStore.Profile(DisplayProfiles.key(phone));
        profile.dpi = 160;
        assertEquals(520, DisplayProfiles.snapshot(phone, profile, FALLBACK).densityDpi);
        assertEquals(profile.key, DisplayProfiles.snapshot(phone, profile, FALLBACK).originProfileKey);
    }

    @Test public void independentCreationDoesNotInheritTheLastUsedOriginOrProtectedPolicy() {
        final VirtualDisplaySpec previous = new VirtualDisplaySpec(1280, 720, 200, true).withOrigin("old-monitor");
        final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.snapshot(null, null, previous);
        assertEquals("", defaults.originProfileKey);
        assertEquals(1280, defaults.width);
        assertEquals(200, defaults.densityDpi);
        final VirtualDisplaySpec spec = defaults.spec(defaults.width, defaults.height, defaults.densityDpi, false);
        assertFalse(spec.protectedContent);
        final DisplayProfileStore.Profile profile = DisplayProfiles.createdProfile(display(8, "v1", "virtual", 200), spec);
        assertEquals(profile.key, profile.originProfileKey);
    }

    @Test public void portableDesktopUsesTheSameResolutionPolicyAsDirectDesktop() {
        for (final int[] dimensions : new int[][]{{1280, 720, 108}, {1920, 1080, 160}, {3840, 2160, 320}}) {
            final DesktopDisplayInfo output = new DesktopDisplayInfo(5, "cast", "Cast", "Cast", "virtual",
                    dimensions[0], dimensions[1], 320, false, false, false, false);
            final DisplayProfileStore.Profile profile = new DisplayProfileStore.Profile(DisplayProfiles.key(output));
            profile.dpi = 320;
            final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.desktopSnapshot(output, profile, 420);
            assertEquals(dimensions[0], defaults.width);
            assertEquals(dimensions[1], defaults.height);
            assertEquals(dimensions[2], defaults.densityDpi);
            assertEquals(profile.key, defaults.originProfileKey);
            assertEquals(320, DisplayProfiles.snapshot(output, profile, FALLBACK).densityDpi);
            assertFalse(profile.dpiExplicit);
            assertEquals(320, profile.dpi);
        }
    }

    @Test public void portableDesktopPreservesExplicitScaleAndFlattenedOrigin() {
        final DesktopDisplayInfo output = display(5, "child", "virtual", 320);
        final DisplayProfileStore.Profile profile = new DisplayProfileStore.Profile(DisplayProfiles.key(output));
        profile.originProfileKey = "display:wireless:tv";
        profile.dpiExplicit = true;
        profile.dpi = 213;
        final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.desktopSnapshot(output, profile, 160);
        assertEquals(213, defaults.densityDpi);
        assertEquals(profile.originProfileKey, defaults.originProfileKey);
        final DisplayProfileStore.Profile child = DisplayProfiles.createdProfile(display(6, "next", "virtual", 213),
                defaults.spec(defaults.width, defaults.height, defaults.densityDpi, false));
        assertTrue(child.dpiExplicit);
        assertEquals(213, child.dpi);
        assertEquals(profile.originProfileKey, child.originProfileKey);
    }

    @Test public void explicitSystemScaleResolvesToLiveDensityForBothCreationPaths() {
        final DesktopDisplayInfo output = display(5, "cast", "virtual", 320);
        final DisplayProfileStore.Profile profile = new DisplayProfileStore.Profile(DisplayProfiles.key(output));
        profile.dpiExplicit = true;
        profile.dpi = 0;
        assertEquals(320, DisplayProfiles.desktopSnapshot(output, profile, 420).densityDpi);
        assertEquals(320, DisplayProfiles.snapshot(output, profile, FALLBACK).densityDpi);
    }

    @Test public void referenceSnapshotDoesNotValidateVirtualLimitsBeforeTheUserCanEditThem() {
        final DesktopDisplayInfo tiny = new DesktopDisplayInfo(1, "tiny", "Tiny", "Tiny", "virtual",
                240, 240, 70, false, false, false, false);
        final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.snapshot(tiny,
                new DisplayProfileStore.Profile(DisplayProfiles.key(tiny)), FALLBACK);
        assertEquals(240, defaults.width);
        assertEquals(70, defaults.densityDpi);
        assertThrows(IllegalArgumentException.class, () -> defaults.spec(240, 240, 70, false));
        assertEquals(defaults.originProfileKey, defaults.spec(640, 480, 160, false).originProfileKey);
    }

    @Test public void unchangedRoundedScaleKeepsTheExactInheritedDpi() {
        assertEquals(213, DisplayCreationDialog.densityForScale(133, 213));
        assertEquals(240, DisplayCreationDialog.densityForScale(150, 213));
        assertEquals(520, DisplayCreationDialog.densityForScale(325, 520));
    }

    private static DesktopDisplayInfo display(int id, String uniqueId, String source, int dpi) {
        return new DesktopDisplayInfo(id, uniqueId, "Display", "Display", source, 2560, 1440, dpi, false, false,
                "virtual".equals(source), false);
    }
}
