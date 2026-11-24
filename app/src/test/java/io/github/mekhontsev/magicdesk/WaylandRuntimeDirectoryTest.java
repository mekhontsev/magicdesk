package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WaylandRuntimeDirectoryTest {
    @Test public void compactDirectoryFitsTheLongestBrokerEndpoint() throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.topLevelMethods("WaylandExecution", "runtimeDirectory") + """
            public static void verify() {
                String parent = "/data/user/10/io.github.mekhontsev.magicdesk.examplebuild/cache/w";
                byte[] id = new byte[16];
                Arrays.fill(id, (byte) 0xff);
                String path = runtimeDirectory(parent, id, true);
                check(path.substring(parent.length() + 1).matches("[A-Za-z0-9_-]{22}"), "compact path component");
                check((path + "/wayland-2147483647").getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 107,
                    "broker endpoint fits before the terminating byte");
                try {
                    runtimeDirectory(parent + "é", id, true);
                    throw new AssertionError("overlong UTF-8 path accepted");
                } catch (IllegalArgumentException expected) {
                    check(runtimeDirectory(parent + "é", id, false).startsWith(parent),
                        "short compositor endpoint fits the same directory");
                }
            }
            """);
    }
}
