package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;

public final class FrameworkChildProcessApiTest {
    @Test public void readsFrameworkPropertyAndDefaultWithoutContentProvider() throws Exception {
        verify("""
            var api = new FrameworkChildProcessApi();
            check(api.readDefault(), "framework default");
            android.util.FeatureFlagUtils.enabled=false;
            check(!api.readDefault(), "property override not reflected");
            """);
    }

    @Test public void unsupportedFlagIsAnError() throws Exception {
        verify("""
            android.util.FeatureFlagUtils.supported=false;
            try { new FrameworkChildProcessApi(); throw new AssertionError("unsupported accepted"); }
            catch(IOException expected) {}
            """);
    }

    @Test public void frameworkFailuresRemainFailures() throws Exception {
        verify("""
            var api = new FrameworkChildProcessApi();
            android.util.FeatureFlagUtils.fail=true;
            try { api.readDefault(); throw new AssertionError("denied read accepted"); }
            catch(ReflectiveOperationException expected) {}
            """);
    }

    private static void verify(String body) throws Exception {
        final String[] names = {"Context", "FeatureFlagUtils"};
        final String[] sources = {
                """
                package android.content;
                public class Context {}
                """,
                """
                package android.util;
                import java.util.Map;
                public class FeatureFlagUtils {
                    public static boolean supported=true, enabled=true, fail=false;
                    public static Map<String,String> getAllFeatureFlags() {
                        return supported ? Map.of("settings_enable_monitor_phantom_procs", "true") : Map.of();
                    }
                    public static boolean isEnabled(android.content.Context context, String key) {
                        if(fail) throw new SecurityException();
                        if(context!=null || !key.equals("settings_enable_monitor_phantom_procs")) throw new AssertionError();
                        return enabled;
                    }
                }
                """};
        final Path directory = Files.createTempDirectory("magicdesk-child-process-api-");
        try {
            final String[] paths = new String[names.length + 1];
            paths[0] = "FrameworkChildProcessApi";
            for (int index = 0; index < names.length; index++) {
                final Path file = directory.resolve(names[index] + ".java");
                Files.writeString(file, sources[index]);
                paths[index + 1] = file.toString();
            }
            RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                    "public static void verify() throws Exception {" + body + "}", paths);
        } finally {
            try (var files = Files.walk(directory)) {
                for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
