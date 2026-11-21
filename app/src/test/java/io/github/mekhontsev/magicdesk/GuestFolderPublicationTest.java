package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GuestFolderPublicationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void perStoreArgumentRecordsAreDataAndIncompleteTablesFailClosed() throws Exception {
        Path home=temp.getRoot().toPath(),store=Files.createDirectories(home.resolve("a space/instances/id"));
        Path table=home.resolve(GuestLaunchSettings.DIR+store+".folders");
        String shell=Files.isExecutable(Path.of("/system/bin/sh")) ? "/system/bin/sh" : "/bin/sh";
        var cmd=new ProcessBuilder(shell,"-c","md_store=$1; shift; "+GuestLaunchSettings.arguments()+
                "printf '%s\\n' \"$@\"", "test", store.toString(),"--","/bin/test").redirectErrorStream(true);
        cmd.environment().put("HOME",home.toString());
        assertEquals(List.of("--","/bin/test"),output(cmd,0));
        Files.createDirectories(table.getParent());
        String path="/mnt/android/quote ' $(false); spaces";
        Files.writeString(table,"--bind-fuse-ro\n@peer\n"+path+"\nend\n");
        assertEquals(List.of("--bind-fuse-ro","@peer",path,"--","/bin/test"),output(cmd,0));
        Files.writeString(table,"--bind-fuse-ro\n@peer\n");
        assertEquals(List.of("Android folder mounts are unavailable"),output(cmd,1));
        Files.delete(table);
        Path network=home.resolve(GuestLaunchSettings.DIR+store+".network");
        Files.writeString(network,"bridge\n127.0.0.1:8000:80/tcp\nend\n");
        assertEquals(List.of("--magicdesk-default-publish","127.0.0.1:8000:80/tcp","--magicdesk-default-network","bridge","--","/bin/test"),output(cmd,0));
        Files.writeString(network,"none\n");
        assertEquals(List.of("Incomplete Linux network settings"),output(cmd,1));
    }
    private static List<String> output(ProcessBuilder cmd,int code) throws Exception {
        var p=cmd.start();
        try {
            assertTrue(p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));
            var out=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            assertEquals(out.toString(),code,p.exitValue()); return out;
        } finally { p.destroyForcibly(); }
    }
}
