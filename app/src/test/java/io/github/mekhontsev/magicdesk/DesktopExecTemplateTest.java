package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class DesktopExecTemplateTest {
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
