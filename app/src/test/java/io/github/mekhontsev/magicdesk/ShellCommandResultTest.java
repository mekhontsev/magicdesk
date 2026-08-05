package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

public final class ShellCommandResultTest {
    @Test
    public void parsesSuccessfulOutput() throws Exception {
        final ShellAccess.CommandResult result =
                ShellAccess.parseCommandResult("0\nline one\nline two\n");

        assertEquals(0, result.exitCode);
        assertEquals("line one\nline two\n", result.output);
    }

    @Test
    public void preservesNonZeroExitCodeAndOutput() throws Exception {
        final ShellAccess.CommandResult result =
                ShellAccess.parseCommandResult("7\npermission denied");

        assertEquals(7, result.exitCode);
        assertEquals("permission denied", result.output);
    }

    @Test
    public void rejectsMalformedServiceResponse() throws Exception {
        try {
            ShellAccess.parseCommandResult("missing separator");
            fail("Expected IOException");
        } catch (IOException expected) {
            assertEquals(
                    "invalid response from Shizuku command service",
                    expected.getMessage());
        }
    }
}
