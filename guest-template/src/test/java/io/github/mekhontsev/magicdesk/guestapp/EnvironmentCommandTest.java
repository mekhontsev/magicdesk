package io.github.mekhontsev.magicdesk.guestapp;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class EnvironmentCommandTest {
    @Test public void ptyChildUsesItsRealParentAndPreservesArguments() throws Exception {
        Path directory=Files.createTempDirectory("guest-command-");
        Path helper=directory.resolve("runtime's entry");
        String shell=Files.isExecutable(Path.of("/bin/sh")) ? "/bin/sh" : "/system/bin/sh";
        try {
            Files.writeString(helper,"#!"+shell+"\nprintf '%s\\n' \"$@\"\n");
            assertTrue(helper.toFile().setExecutable(true));
            String store="/private/Linux's data";
            var arguments=List.of("--env","VALUE=has spaces; $(false)","--socket-abstract","/tmp/audio","magicdesk-0123456789abcdef");
            String command=EnvironmentCommand.terminal(helper.toString(),store,arguments);
            Process child=new ProcessBuilder(shell,"-c",command).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(5,TimeUnit.SECONDS));
                var output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                assertEquals(0,child.exitValue());
                assertEquals("--parent-pid",output.get(0));
                assertEquals(Files.readSymbolicLink(Path.of("/proc/self")).toString(),output.get(1));
                assertEquals("login",output.get(2)); assertEquals(store,output.get(3));
                assertEquals(arguments,output.subList(4,output.size()));
            } finally { child.destroyForcibly(); }
        } finally { Files.deleteIfExists(helper); Files.deleteIfExists(directory); }
    }
}
