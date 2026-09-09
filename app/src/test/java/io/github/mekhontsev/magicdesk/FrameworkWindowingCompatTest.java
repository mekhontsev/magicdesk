package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.IBinder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class FrameworkWindowingCompatTest {
    private static final int CAPTION_TYPE = 4;
    private static final int SOURCE_ID = 0x12340002;

    @Test
    public void generatedPublicationFlagWorksWithoutDesktopWrapper() {
        assertEquals("", FrameworkWindowingCompat.visibleTypesUnavailableReason(
                null, EnabledWindowFlags.class));
        assertEquals("framework client-insets publication disabled",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        null, DisabledWindowFlags.class));
    }

    @Test
    public void olderDesktopWrapperUsesGeneratedPublicationFlag() {
        assertEquals("", FrameworkWindowingCompat.visibleTypesUnavailableReason(
                Object.class, EnabledWindowFlags.class));
    }

    @Test
    public void desktopWrapperIncludesFrameworkOverrides() {
        assertEquals("", FrameworkWindowingCompat.visibleTypesUnavailableReason(
                EnabledDesktopFlags.class, DisabledWindowFlags.class));
        assertEquals("framework client-insets publication disabled",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        DisabledDesktopFlags.class, EnabledWindowFlags.class));
    }

    @Test
    public void failedDesktopFlagDoesNotFallBackToAnUnrelatedValue() {
        assertEquals("framework client-insets publication unknown: denied",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        RejectedDesktopFlags.class, EnabledWindowFlags.class));
    }

    @Test
    public void settingSnapshotPreservesDesktopToggleSemantics() {
        // raw flag, default desktop feature, developer toggle, effective flag
        final int[][] cases = {
            {0, 0, -1, 0}, {0, 0, 0, 0}, {0, 0, 1, 1},
            {1, 0, -1, 1}, {1, 0, 0, 1}, {1, 0, 1, 1},
            {0, 1, -1, 0}, {0, 1, 0, 0}, {0, 1, 1, 0},
            {1, 1, -1, 1}, {1, 1, 0, 0}, {1, 1, 1, 1},
            {1, 1, 42, 1}, {0, 0, 42, 0}
        };
        for (final int[] entry : cases) {
            SettingsWindowFlags.immersive = entry[0] == 1;
            SettingsWindowFlags.desktop = entry[1] == 1;
            final int[] reads = {0};
            assertEquals(entry[3] == 1 ? "" : "framework client-insets publication disabled",
                    FrameworkWindowingCompat.visibleTypesUnavailableReason(
                            SettingsRejectedDesktopFlags.class, SettingsWindowFlags.class,
                            () -> { reads[0]++; return entry[2]; }));
            assertEquals(1, reads[0]);
        }
    }

    @Test
    public void successfulNativeWrapperDoesNotNeedAnotherSettingsRead() {
        assertEquals("", FrameworkWindowingCompat.visibleTypesUnavailableReason(
                EnabledDesktopFlags.class, DisabledWindowFlags.class,
                () -> { throw new AssertionError("native flag already resolved"); }));
    }

    @Test
    public void unavailableSettingsRetainUnknownObservation() {
        assertEquals("framework client-insets publication unknown: settings denied",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        SettingsRejectedDesktopFlags.class, SettingsWindowFlags.class,
                        () -> { throw new SecurityException("settings denied"); }));
    }

    @Test
    public void propertyBasedOverrideCannotBeReplacedWithGlobalSetting() {
        assertEquals("framework client-insets publication unknown: package/uid mismatch",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        SettingsRejectedDesktopFlags.class, ExperienceWindowFlags.class,
                        () -> { throw new AssertionError("not a Settings-based override"); }));
    }

    @Test
    public void disabledDeveloperOptionCannotOverrideTheRawFlag() {
        assertEquals("framework client-insets publication unknown: package/uid mismatch",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        SettingsRejectedDesktopFlags.class, NoOverrideWindowFlags.class,
                        () -> { throw new AssertionError("developer override unavailable"); }));
    }

    @Test
    public void android15WithoutWrapperDoesNotReadDeveloperSettings() {
        assertEquals("framework client-insets publication disabled",
                FrameworkWindowingCompat.visibleTypesUnavailableReason(
                        Object.class, DisabledWindowFlags.class,
                        () -> { throw new AssertionError("framework has no wrapper"); }));
    }

    @Test
    public void desktopToggleUsesAppResolverAndFrameworkSettingsKey() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context {
                    final Object resolver = new Object();
                    Object getContentResolver() { return resolver; }
                }
                static final Context app = new Context();
                static class Settings {
                    static class Global {
                        static int getInt(Object resolver, String key, int fallback) {
                            check(resolver == app.resolver, "wrong attribution");
                            check(key.equals("override_desktop_mode_features"), "wrong Settings key");
                            check(fallback == -1, "unset must not disable publication");
                            return 1;
                        }
                    }
                }
                """ + RuntimeSourceFixture.methods("FrameworkWindowingCompat", "readDesktopToggle")
                + """
                public static void verify() {
                    check(readDesktopToggle(app) == 1, "toggle was not read");
                }
                """);
    }

    @Test
    public void shellProfileIsInitializedOnceBeforeOtherRuntimeConsumers() throws Exception {
        RuntimeSourceFixture.verify("""
                static int probes, resolutions;
                static class BuildConfig { static String FRAMEWORK_OVERRIDE = ""; }
                static class FrameworkWindowingCompat {
                    private static FrameworkWindowingCompat sCurrent;
                    final String reason;
                    FrameworkWindowingCompat(String reason) { this.reason = reason; }
                    static String visibleTypesUnavailableReason(java.util.function.IntSupplier read) {
                        probes++;
                        check(read != null, "setting missing before runtime detection");
                        return "toggle=" + read.getAsInt();
                    }
                    static FrameworkWindowingCompat detect(String override, String reason) {
                        resolutions++;
                        return new FrameworkWindowingCompat(reason);
                    }
                """ + RuntimeSourceFixture.methods("FrameworkWindowingCompat", "current", "initialize")
                + "}\n" + """
                public static void verify() {
                    FrameworkWindowingCompat.initialize(1, "");
                    FrameworkWindowingCompat profile = FrameworkWindowingCompat.current();
                    check(profile.reason.equals("toggle=1"), "wrong Settings reader");
                    FrameworkWindowingCompat.initialize(0, "");
                    check(FrameworkWindowingCompat.current() == profile, "replaced live profile");
                    check(probes == 1 && resolutions == 1, "repeated probe");
                }
                """);
    }

    public enum SettingsRejectedDesktopFlags {
        ENABLE_FULLY_IMMERSIVE_IN_DESKTOP;
        private final boolean mShouldOverrideByDevOption = true;
        public boolean isTrue() { throw new SecurityException("package/uid mismatch"); }
    }

    public static class SettingsWindowFlags {
        static boolean immersive;
        static boolean desktop;
        public static boolean enableFullyImmersiveInDesktop() { return immersive; }
        public static boolean enableDesktopWindowingMode() { return desktop; }
        public static boolean showDesktopWindowingDevOption() { return true; }
    }

    public static final class ExperienceWindowFlags extends SettingsWindowFlags {
        public static boolean showDesktopExperienceDevOption() { return true; }
    }

    public static final class NoOverrideWindowFlags extends SettingsWindowFlags {
        public static boolean showDesktopWindowingDevOption() { return false; }
    }

    @Test
    public void missingPublicationApiKeepsObservationUnknownAndCaptionUsable()
            throws Exception {
        final String reason = FrameworkWindowingCompat.visibleTypesUnavailableReason(null, null);
        assertEquals("framework client-insets publication unknown: flag API absent", reason);
        final FrameworkWindowingCompat compat = FrameworkWindowingCompat.inspect(
                ModernTaskInfo.class, ModernTransaction.class, Token.class,
                HierarchyOp.class, InsetsProvider.class, reason, "");
        assertNull(compat.readRequestedVisibleTypes(new ModernTaskInfo()));
        assertTrue(compat.addCaptionExclusion(
                new ModernTransaction(), new Token(), true, CAPTION_TYPE));
    }

    public static final class EnabledWindowFlags {
        public static boolean enableFullyImmersiveInDesktop() {
            return true;
        }
    }

    public static final class DisabledWindowFlags {
        public static boolean enableFullyImmersiveInDesktop() {
            return false;
        }
    }

    public enum EnabledDesktopFlags {
        ENABLE_FULLY_IMMERSIVE_IN_DESKTOP;

        public boolean isTrue() {
            return true;
        }
    }

    public enum DisabledDesktopFlags {
        ENABLE_FULLY_IMMERSIVE_IN_DESKTOP;

        public boolean isTrue() {
            return false;
        }
    }

    public enum RejectedDesktopFlags {
        ENABLE_FULLY_IMMERSIVE_IN_DESKTOP;

        public boolean isTrue() {
            throw new SecurityException("denied");
        }
    }

    @Test
    public void declaredFieldWithDisabledPublicationIsUnknown() throws Exception {
        final FrameworkWindowingCompat compat = FrameworkWindowingCompat.inspect(
                ModernTaskInfo.class, ModernTransaction.class, Token.class,
                HierarchyOp.class, InsetsProvider.class,
                "framework client-insets publication disabled", "");
        assertTrue(compat.capabilities().requestedVisibleTypesDetected);
        assertFalse(compat.capabilities().requestedVisibleTypesEnabled);
        assertNull(compat.readRequestedVisibleTypes(new ModernTaskInfo()));
        assertEquals("framework client-insets publication disabled",
                compat.requestedVisibleTypesDetail());
    }

    @Test
    public void missingFlexibleLaunchSizeDoesNotRejectAndroid15Launch()
            throws Exception {
        FrameworkWindowingCompat.FlexibleLaunchSize.inspect(Object.class, "")
                .apply(new Object());
    }

    @Test
    public void flexibleLaunchSizeUsesDetectedApi() throws Exception {
        final ModernLaunchOptions options = new ModernLaunchOptions();
        FrameworkWindowingCompat.FlexibleLaunchSize.inspect(
                ModernLaunchOptions.class, "").apply(options);
        assertTrue(options.flexible);
    }

    @Test
    public void android15ProfileDoesNotRequireFlexibleLaunchSize()
            throws Exception {
        final ModernLaunchOptions options = new ModernLaunchOptions();
        FrameworkWindowingCompat.FlexibleLaunchSize.inspect(
                ModernLaunchOptions.class,
                FrameworkWindowingCompat.ANDROID_15_OVERRIDE).apply(options);
        assertFalse(options.flexible);
    }

    @Test
    public void availableLaunchOptionFailureIsNotTreatedAsMissingApi() {
        final FrameworkWindowingCompat.FlexibleLaunchSize adapter =
                FrameworkWindowingCompat.FlexibleLaunchSize.inspect(
                        RejectedLaunchOptions.class, "");
        assertThrows(SecurityException.class,
                () -> adapter.apply(new RejectedLaunchOptions()));
    }

    public static final class ModernLaunchOptions {
        boolean flexible;

        public void setFlexibleLaunchSize(final boolean value) {
            flexible = value;
        }
    }

    public static final class RejectedLaunchOptions {
        public void setFlexibleLaunchSize(final boolean value) {
            throw new SecurityException("denied");
        }
    }

    @Test
    public void modernProfileUsesTaskInfoAndNativeCaptionOperation()
            throws Exception {
        final FrameworkWindowingCompat compat = inspect(
                ModernTaskInfo.class, ModernTransaction.class, "");
        final ModernTaskInfo task = new ModernTaskInfo();
        task.requestedVisibleTypes = 42;
        final ModernTransaction transaction = new ModernTransaction();

        assertEquals(Integer.valueOf(42),
                compat.readRequestedVisibleTypes(task));
        assertTrue(compat.addCaptionExclusion(
                transaction, new Token(), true, CAPTION_TYPE));
        assertEquals(CAPTION_TYPE,
                compat.lastExcludeInsetsTypes(transaction));
        assertEquals("native", compat.capabilities().captionStrategy());
        assertEquals("flags", compat.capabilities().insetsSourceApi);
    }

    @Test
    public void android15ProfileMasksModernApisAndKeepsSourcePolyfill()
            throws Exception {
        final FrameworkWindowingCompat compat = inspect(
                ModernTaskInfo.class,
                ModernTransaction.class,
                FrameworkWindowingCompat.ANDROID_15_OVERRIDE);

        assertNull(compat.readRequestedVisibleTypes(new ModernTaskInfo()));
        assertFalse(compat.addCaptionExclusion(
                new ModernTransaction(), new Token(), true, CAPTION_TYPE));
        assertTrue(compat.capabilities().requestedVisibleTypesDetected);
        assertFalse(compat.capabilities().requestedVisibleTypesEnabled);
        assertTrue(compat.capabilities().captionExclusionDetected);
        assertFalse(compat.capabilities().captionExclusionEnabled);
        assertEquals("source-polyfill",
                compat.capabilities().captionStrategy());
        assertEquals("without-flags-emulated",
                compat.capabilities().insetsSourceApi);
    }

    @Test
    public void android15ShapeUsesWithoutFlagsInsetsSourceSignature()
            throws Exception {
        final FrameworkWindowingCompat compat = inspect(
                Android15TaskInfo.class, Android15Transaction.class, "");
        final Android15Transaction transaction = new Android15Transaction();

        assertNull(compat.readRequestedVisibleTypes(new Android15TaskInfo()));
        assertFalse(compat.addCaptionExclusion(
                transaction, new Token(), true, CAPTION_TYPE));
        compat.addEmptyCaptionSource(
                transaction,
                new Token(),
                null,
                CAPTION_TYPE,
                null,
                SOURCE_ID);

        assertEquals("without-flags", compat.capabilities().insetsSourceApi);
        assertEquals("source-polyfill",
                compat.capabilities().captionStrategy());
        assertEquals(SOURCE_ID,
                transaction.operations.get(0).getInsetsFrameProvider().mId);
        assertEquals(1, transaction.withoutFlagsAddCalls);
    }

    @Test
    public void unavailableInsetsSourceNeverEnablesCaptionObservation() {
        for (final String sourceApi : new String[] {
                "unavailable", "unavailable:missing framework class", null}) {
            final FrameworkWindowingCompat.Capabilities capabilities =
                    new FrameworkWindowingCompat.Capabilities(
                            "automatic", false, false, false, false, false,
                            sourceApi);

            assertEquals("unavailable", capabilities.captionStrategy());
            assertEquals(
                    FrameworkWindowingCompat.ObservationProvenance.UNAVAILABLE,
                    capabilities.taskObservation.captionSource);
        }
    }

    @Test
    public void observationProfileCentralizesHybridFallbackCapabilities() {
        final FrameworkWindowingCompat modern = inspect(
                ModernTaskInfo.class, ModernTransaction.class, "");
        final FrameworkWindowingCompat android15 = inspect(
                ModernTaskInfo.class,
                ModernTransaction.class,
                FrameworkWindowingCompat.ANDROID_15_OVERRIDE);
        final FrameworkWindowingCompat.TaskObservationCapabilities
                observation = modern.capabilities().taskObservation;

        assertEquals("hybrid", observation.strategy);
        assertEquals(150L, observation.fallbackIntervalMillis);
        assertEquals(16, observation.taskLimit);
        assertEquals(FrameworkWindowingCompat.ObservationProvenance.HYBRID,
                observation.lifecycle);
        assertEquals(FrameworkWindowingCompat.ObservationProvenance.HYBRID,
                observation.stack);
        assertEquals(FrameworkWindowingCompat.ObservationProvenance.SAMPLED,
                observation.windowGeometry);
        assertEquals(FrameworkWindowingCompat.ObservationProvenance.SAMPLED,
                observation.immersiveRequest);
        assertEquals(FrameworkWindowingCompat.ObservationProvenance.SAMPLED,
                observation.captionSource);
        assertEquals(
                FrameworkWindowingCompat.ObservationProvenance.UNAVAILABLE,
                android15.capabilities().taskObservation.immersiveRequest);
    }

    private static FrameworkWindowingCompat inspect(
            final Class<?> taskInfo,
            final Class<?> transaction,
            final String override) {
        return FrameworkWindowingCompat.inspect(
                taskInfo,
                transaction,
                Token.class,
                HierarchyOp.class,
                InsetsProvider.class,
                "",
                override);
    }

    public static final class ModernTaskInfo {
        public int requestedVisibleTypes;
    }

    public static final class Android15TaskInfo {
    }

    public static final class Token {
    }

    public static final class InsetsProvider {
        private int mId;
    }

    public static final class HierarchyOp {
        private int mExcludeInsetsTypes;
        private final InsetsProvider mProvider;

        HierarchyOp(
                final int excludedTypes,
                final InsetsProvider provider) {
            mExcludeInsetsTypes = excludedTypes;
            mProvider = provider;
        }

        public int getExcludeInsetsTypes() {
            return mExcludeInsetsTypes;
        }

        public InsetsProvider getInsetsFrameProvider() {
            return mProvider;
        }
    }

    public static class BaseTransaction {
        final List<HierarchyOp> operations = new ArrayList<>();

        public List<HierarchyOp> getHierarchyOps() {
            return operations;
        }

        public BaseTransaction removeInsetsSource(
                final Token token,
                final IBinder owner,
                final int index,
                final int type) {
            operations.add(new HierarchyOp(0, new InsetsProvider()));
            return this;
        }
    }

    public static final class ModernTransaction extends BaseTransaction {
        public ModernTransaction setExcludeImeInsets(
                final Token token,
                final boolean exclude) {
            operations.add(new HierarchyOp(exclude ? 1 : 0, null));
            return this;
        }

        public ModernTransaction addInsetsSource(
                final Token token,
                final IBinder owner,
                final int index,
                final int type,
                final Rect frame,
                final Rect[] boundingRects,
                final int flags) {
            operations.add(new HierarchyOp(0, new InsetsProvider()));
            return this;
        }
    }

    public static final class Android15Transaction extends BaseTransaction {
        int withoutFlagsAddCalls;

        public Android15Transaction addInsetsSource(
                final Token token,
                final IBinder owner,
                final int index,
                final int type,
                final Rect frame,
                final Rect[] boundingRects) {
            withoutFlagsAddCalls++;
            operations.add(new HierarchyOp(0, new InsetsProvider()));
            return this;
        }
    }
}
