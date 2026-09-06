package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class DesktopLaunchArgumentsTest {
    @Test
    public void countValidationCanRunAtTransportBoundary() {
        DesktopLaunchArguments.requireCount(0);
        DesktopLaunchArguments.requireCount(128);
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchArguments.requireCount(129));
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchArguments.requireCount(-1));
    }

    @Test
    public void oversizedPathListIsRejectedBeforeReadingItsEntries() {
        final List<String> paths = new AbstractList<>() {
            @Override
            public String get(final int index) {
                throw new AssertionError("oversized list must not be traversed");
            }

            @Override
            public int size() {
                return 129;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchArguments.files(paths));
    }

    @Test
    public void individualUriCannotBypassArgumentLengthLimit() {
        final String prefix = "content://example/";
        final String uri = prefix + "a".repeat(8192 - prefix.length());
        assertEquals(uri, DesktopLaunchArgument.uri(uri).uri);
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchArgument.uri(uri + "a"));
    }

    @Test
    public void encodedFileUriMustFitTheSameLimit() {
        final String path = "/" + "\u044f".repeat(1364);
        assertEquals(8192, DesktopLaunchArgument.file(path).uri.length());
        assertThrows(IllegalArgumentException.class, () -> DesktopLaunchArgument.file(path + "a"));
    }

    @Test
    public void excessiveRawPathIsRejectedEvenWhenNormalizationWouldShortenIt() {
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchArgument.file("/./".repeat(3000) + "file"));
    }

    @Test
    public void exactSelectionLimitAndEmptyFactoriesRemainUsable() {
        final var arguments = DesktopLaunchArguments.files(Collections.nCopies(128, "/tmp/file"));
        assertEquals(128, arguments.values.size());
        assertEquals(128, arguments.filePaths().size());
        assertSame(DesktopLaunchArguments.empty(), DesktopLaunchArguments.files(null));
        assertSame(DesktopLaunchArguments.empty(), DesktopLaunchArguments.files(List.of()));
        assertSame(DesktopLaunchArguments.empty(), DesktopLaunchArguments.of(null));
    }

    @Test
    public void argumentSnapshotPreservesOrderAndDoesNotFollowSourceMutations() {
        final var file = DesktopLaunchArgument.file("/tmp/./my file");
        final var uri = DesktopLaunchArgument.uri("content://example/document/1");
        final var source = new ArrayList<>(List.of(uri, file));
        final var snapshot = DesktopLaunchArguments.of(source);
        source.clear();
        assertEquals(List.of("/tmp/my file"), snapshot.filePaths());
        assertEquals(List.of(uri.uri, "file:///tmp/my%20file"), snapshot.uris());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values.clear());
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchArguments.of(Collections.singletonList(null)));
    }
}
