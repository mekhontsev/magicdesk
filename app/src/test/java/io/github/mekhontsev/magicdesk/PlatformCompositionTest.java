package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.graphics.Point;

import io.github.mekhontsev.magicdesk.platform.android.GenericAndroidPlatformDriver;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public final class PlatformCompositionTest {
    @Test
    public void partialExtensionOverridesOnlyDeclaredComponent() {
        final PlatformDriver baseline = new GenericAndroidPlatformDriver();
        final PlatformPointerDriver pointer = new TestPointerDriver();
        final PlatformExtension extension = extension(
                Set.of(PlatformComponent.POINTER), pointer);

        final PlatformDriver composed = ComposedPlatformDriver.compose(
                baseline, extension, PlatformMatch.matched("fixture marker"));

        assertSame(pointer, composed.pointer());
        assertSame(baseline.projection(), composed.projection());
        assertEquals("fixture", composed.selection()
                .provider(PlatformComponent.POINTER).id);
        assertEquals("android", composed.selection()
                .provider(PlatformComponent.PROJECTION).id);
    }

    @Test
    public void undeclaredImplementationDoesNotLeakIntoComposition() {
        final PlatformDriver baseline = new GenericAndroidPlatformDriver();
        final PlatformDriver composed = ComposedPlatformDriver.compose(
                baseline,
                extension(Collections.emptySet(), new TestPointerDriver()),
                PlatformMatch.matched("fixture marker"));

        assertSame(baseline.pointer(), composed.pointer());
    }

    @Test
    public void declaredComponentRequiresImplementation() {
        final PlatformDriver composed = ComposedPlatformDriver.compose(
                new GenericAndroidPlatformDriver(),
                extension(Set.of(PlatformComponent.POINTER), null),
                PlatformMatch.matched("fixture marker"));

        assertThrows(IllegalStateException.class, composed::pointer);
    }

    @Test
    public void capabilityProbeIsolatesBrokenOptionalComponent() {
        final PlatformPointerDriver brokenPointer =
                new TestPointerDriver() {
                    @Override
                    public boolean isAvailable() {
                        throw new IllegalStateException("fixture failure");
                    }
                };
        final PlatformDriver composed = ComposedPlatformDriver.compose(
                new GenericAndroidPlatformDriver(),
                extension(Set.of(PlatformComponent.POINTER), brokenPointer),
                PlatformMatch.matched("fixture marker"));

        final PlatformCapabilitySnapshot.Entry entry =
                PlatformCapabilitySnapshot.capture(composed).entry(
                        PlatformCapabilityId.ABSOLUTE_POINTER);

        assertEquals(PlatformCapabilityState.BROKEN, entry.state);
        assertEquals("fixture", entry.providerId);
        assertEquals("fixture failure", entry.detail);
    }

    private static PlatformExtension extension(
            final Set<PlatformComponent> components,
            final PlatformPointerDriver pointer) {
        return new PlatformExtension() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public String name() {
                return "Fixture firmware";
            }

            @Override
            public PlatformMatch match(final PlatformDevice device) {
                return PlatformMatch.matched("fixture marker");
            }

            @Override
            public Set<PlatformComponent> components() {
                return components;
            }

            @Override
            public PlatformFeatures extendFeatures(
                    final PlatformFeatures baseline) {
                return baseline;
            }

            @Override
            public PlatformPointerDriver pointer() {
                return pointer;
            }
        };
    }

    private static class TestPointerDriver
            implements PlatformPointerDriver {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean capturePosition() {
            return true;
        }

        @Override
        public Point restorePositionIfDisplaced() {
            return null;
        }

        @Override
        public int[] getPosition(final int displayId) {
            return null;
        }

        @Override
        public boolean injectClick(final int displayId, final int button) {
            return false;
        }

        @Override
        public boolean updatePosition(
                final int displayId,
                final int x,
                final int y,
                final int action,
                final long downTime) {
            return false;
        }

        @Override
        public void refreshViewport() {
        }

        @Override
        public void close() {
        }
    }
}
