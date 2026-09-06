package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class DesktopExecTemplateTest {
    @Test
    public void expandedCommandAcceptsExactLimitIncludingArgumentQuotes() {
        final String prefix = "'tool' ";
        final String name = "a".repeat(DesktopExecCommand.MAX_LENGTH - prefix.length() - 2);
        assertEquals(prefix + "'" + name + "'", DesktopExecTemplate.expand(
                "tool %c", DesktopLaunchArguments.empty(), name, "", ""));
        assertThrows(IllegalArgumentException.class, () -> DesktopExecTemplate.expand(
                "tool %c", DesktopLaunchArguments.empty(), name + "a", "", ""));
    }

    @Test
    public void shellEscapingCountsTowardExpansionLimit() {
        final String name = "'".repeat(818) + "aaaa";
        final String expanded = DesktopExecTemplate.expand(
                "%c", DesktopLaunchArguments.empty(), name, "", "");
        assertEquals(DesktopExecCommand.MAX_LENGTH, expanded.length());
        assertEquals(ShellCommandLine.quote(name), expanded);
        assertThrows(IllegalArgumentException.class, () -> DesktopExecTemplate.expand(
                "%c", DesktopLaunchArguments.empty(), name + "a", "", ""));
    }

    @Test
    public void repeatedSelectionFieldsCannotMaterializeAnOversizedCommand() {
        for (final var argument : List.of(DesktopLaunchArgument.file("/x"),
                DesktopLaunchArgument.file("/" + "a".repeat(8184)))) {
            final var selection = DesktopLaunchArguments.of(Collections.nCopies(128, argument));
            for (final String field : List.of("%F", "%U")) {
                assertThrows(IllegalArgumentException.class, () -> DesktopExecTemplate.expand(
                        (field + " ").repeat(1365), selection, "", "", ""));
            }
        }
    }

    @Test
    public void fieldInsideSingleTokenIsAlsoBounded() {
        assertThrows(IllegalArgumentException.class, () -> DesktopExecTemplate.expand(
                "%c".repeat(2000), DesktopLaunchArguments.empty(), "a".repeat(3000), "", ""));
    }

    @Test
    public void metadataArgumentsCannotBypassExpansionLimit() {
        final String oversized = "x".repeat(8192);
        assertThrows(IllegalArgumentException.class, () -> DesktopExecTemplate.expand(
                "tool %i", DesktopLaunchArguments.empty(), "", oversized, ""));
        assertThrows(IllegalArgumentException.class, () -> DesktopExecTemplate.expand(
                "tool %k", DesktopLaunchArguments.empty(), "", "", oversized));
    }

    @Test
    public void missingAndEmptyFieldsKeepExistingArgumentSemantics() {
        assertEquals("'tool' '' 'x'", DesktopExecTemplate.expand(
                "tool %c %f %k x%f %i", DesktopLaunchArguments.empty(), "", "", ""));
    }

    @Test
    public void rawShellCommandKeepsOperatorsAndDecodesPercent() {
        assertEquals(
                "echo one; printf '%%s' two | head -1",
                DesktopExecTemplate.expand(
                        "echo one; printf '%%%%s' two | head -1",
                        DesktopLaunchArguments.empty(),
                        "", "", ""));
    }

    @Test
    public void singleFileIsExpandedAsOneQuotedArgument() {
        assertEquals(
                "'viewer' '--input=/storage/emulated/0/My File'",
                DesktopExecTemplate.expand(
                        "viewer --input=%f",
                        DesktopLaunchArguments.files(List.of(
                                "/storage/emulated/0/My File")),
                        "Viewer", "viewer", "/tmp/viewer.desktop"));
    }

    @Test
    public void multipleFilesAndUrisExpandInOrder() {
        final DesktopLaunchArguments arguments =
                DesktopLaunchArguments.files(List.of(
                        "/storage/emulated/0/one.txt",
                        "/storage/emulated/0/two.txt"));

        assertEquals(
                "'open' '/storage/emulated/0/one.txt' "
                        + "'/storage/emulated/0/two.txt'",
                DesktopExecTemplate.expand(
                        "open %F", arguments, "", "", ""));
        assertEquals(
                "'open-uri' 'file:///storage/emulated/0/one.txt' "
                        + "'file:///storage/emulated/0/two.txt'",
                DesktopExecTemplate.expand(
                        "open-uri %U", arguments, "", "", ""));
    }

    @Test
    public void contentUriIsNotInventedAsALocalFile() {
        final DesktopLaunchArguments arguments = DesktopLaunchArguments.of(
                List.of(DesktopLaunchArgument.uri(
                        "content://example.documents/item/42")));

        assertEquals(
                "'open' 'content://example.documents/item/42'",
                DesktopExecTemplate.expand(
                        "open %u", arguments, "", "", ""));
        assertEquals(
                "'open'",
                DesktopExecTemplate.expand(
                        "open %f", arguments, "", "", ""));
    }

    @Test
    public void missingFileFieldIsRemoved() {
        assertEquals(
                "'viewer' '--quiet'",
                DesktopExecTemplate.expand(
                        "viewer %f --quiet",
                        DesktopLaunchArguments.empty(),
                        "", "", ""));
    }

    @Test
    public void metadataFieldsExpandWithoutShellInjection() {
        assertEquals(
                "'tool' '--name=My App' '--icon' 'app.icon' "
                        + "'/storage/emulated/0/Desktop/App.desktop'",
                DesktopExecTemplate.expand(
                        "tool --name=%c %i %k",
                        DesktopLaunchArguments.empty(),
                        "My App",
                        "app.icon",
                        "/storage/emulated/0/Desktop/App.desktop"));
    }

    @Test
    public void parserSupportsQuotedArgumentsContainingFields() {
        assertEquals(
                "'sh' '-c' 'echo /storage/emulated/0/a b'",
                DesktopExecTemplate.expand(
                        "sh -c 'echo %f'",
                        DesktopLaunchArguments.files(List.of(
                                "/storage/emulated/0/a b")),
                        "", "", ""));
    }

    @Test
    public void malformedTemplatesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopExecTemplate.expand(
                        "open prefix%F",
                        DesktopLaunchArguments.empty(),
                        "", "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopExecTemplate.expand(
                        "open %x",
                        DesktopLaunchArguments.empty(),
                        "", "", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopExecTemplate.expand(
                        "open \"%f",
                        DesktopLaunchArguments.empty(),
                        "", "", ""));
    }

    @Test
    public void argumentCapabilityOnlyReportsFileAndUriFields() {
        assertTrue(DesktopExecTemplate.acceptsArguments("open %F"));
        assertTrue(DesktopExecTemplate.acceptsArguments("open %u"));
        assertFalse(DesktopExecTemplate.acceptsArguments("open %c %%"));
    }
}
