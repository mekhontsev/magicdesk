package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class X11RuntimeBoundaryTest {
    @Test public void runtimeOwnsJavaAndJniWithoutUpstreamTypes() throws Exception {
        Path root = Path.of("../x11-runtime");
        try (var files = Files.walk(root.resolve("src/main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file);
                assertFalse(file.toString(), source.contains("com.termux.x11"));
                assertFalse(file.toString(), source.contains("ICmdEntryInterface"));
            }
        }
        String build = Files.readString(root.resolve("build.gradle"));
        assertFalse(build.contains("compileOnly"));
        assertFalse(build.contains("x11-stubs"));
        String jni = Files.readString(root.resolve("src/main/cpp/x11_jni.cpp"));
        assertTrue(jni.contains("#include \"embedded.h\""));
        assertFalse(jni.contains("#include \"lorie.h\""));
    }

    @Test public void nativeEngineDoesNotLoadOrAuthorizeJava() throws Exception {
        Path root = Path.of("../vendor/magicdesk-x11/lorie/src/main/cpp/lorie");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!(name.endsWith(".c") || name.endsWith(".cpp") || name.endsWith(".h"))) continue;
                String source = Files.readString(file);
                for (String forbidden : new String[]{"jni.h", "JNIEnv", "JavaVM", "com.termux.x11",
                        "MAGICDESK_X11_SESSION", "MAGICDESK_X11_TOKEN", "MAGICDESK_X11_PACKAGE"})
                    assertFalse(file + ": " + forbidden, source.contains(forbidden));
            }
        }
    }

    @Test public void commandPackingAndBorrowedResourcesHaveOneOwner() throws Exception {
        String session = Files.readString(Path.of("../x11-runtime/src/main/java/io/github/mekhontsev/magicdesk/x11/X11Session.java"));
        assertFalse(session.contains("nativeCommand("));
        assertFalse(session.contains("* 10000"));
        String api = Files.readString(Path.of("../vendor/magicdesk-x11/lorie/src/main/cpp/lorie/embedded.h"));
        assertFalse(api.contains("lorieConnectionCommand("));
        assertFalse(api.contains("LORIE_OUTPUT_"));
        String activity = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "X11Activity.java"));
        for (String forbidden : new String[]{"openOutput(", "claimFullscreen(", "releaseDensity(", "new HostedContentExchange(", "new HostedFullscreen("})
            assertFalse(forbidden, activity.contains(forbidden));
    }
}
