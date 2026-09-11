package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class AndroidFilePublicationContractTest {
    @Test
    public void fileActionsPrepareCompleteRequestsBeforeRegisteringUris() throws Exception {
        final String gateway = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AndroidIntegrationGateway.java"));
        final String open = gateway.substring(gateway.indexOf("DesktopAutomationResult openFile("),
                gateway.indexOf("DesktopAutomationResult share("));
        final String share = gateway.substring(gateway.indexOf("DesktopAutomationResult share("),
                gateway.indexOf("DesktopAutomationResult openContent("));
        for (final String source : new String[] {open, share}) {
            assertTrue(source.contains("ShellFileGrantStore.Preparation"));
            assertFalse(source.contains("ShellFileGrantStore.create("));
            final int publish = source.indexOf("grants.publish();");
            assertTrue(publish > source.indexOf("AndroidIntegrationRequest.activity("));
            assertTrue(publish > source.indexOf("launchTarget(args)"));
            assertTrue(publish > source.indexOf("requireLaunchTarget(placement, request.presentation)"));
            assertTrue(publish < source.indexOf("return execute("));
            assertFalse(source.contains("discardUnpublished("));
        }
    }
}
