package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        assertEquals("legacy-emulated",
                compat.capabilities().insetsSourceApi);
    }

    @Test
    public void android15ShapeUsesLegacyInsetsSourceSignature()
            throws Exception {
        final FrameworkWindowingCompat compat = inspect(
                LegacyTaskInfo.class, LegacyTransaction.class, "");
        final LegacyTransaction transaction = new LegacyTransaction();

        assertNull(compat.readRequestedVisibleTypes(new LegacyTaskInfo()));
        assertFalse(compat.addCaptionExclusion(
                transaction, new Token(), true, CAPTION_TYPE));
        compat.addEmptyCaptionSource(
                transaction,
                new Token(),
                null,
                CAPTION_TYPE,
                null,
                SOURCE_ID);

        assertEquals("legacy", compat.capabilities().insetsSourceApi);
        assertEquals("source-polyfill",
                compat.capabilities().captionStrategy());
        assertEquals(SOURCE_ID,
                transaction.operations.get(0).getInsetsFrameProvider().mId);
        assertEquals(1, transaction.legacyAddCalls);
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
                override);
    }

    public static final class ModernTaskInfo {
        public int requestedVisibleTypes;
    }

    public static final class LegacyTaskInfo {
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

    public static final class LegacyTransaction extends BaseTransaction {
        int legacyAddCalls;

        public LegacyTransaction addInsetsSource(
                final Token token,
                final IBinder owner,
                final int index,
                final int type,
                final Rect frame,
                final Rect[] boundingRects) {
            legacyAddCalls++;
            operations.add(new HierarchyOp(0, new InsetsProvider()));
            return this;
        }
    }
}
