package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GraphicalDiagnosticsTest {
    @Test public void passiveReportRetainsBoundedStartupObservations() throws Exception {
        String renderer = Files.readString(Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedRenderer.java"))
                .replace("package io.github.mekhontsev.magicdesk.hosted;", "");
        RuntimeSourceFixture.verify(renderer + "static "
                + RuntimeSourceFixture.nestedClass("GraphicalDiagnostics", "GraphicalDiagnostics") + """
            static class BuildConfig { static String VERSION_NAME = "fixture"; static int VERSION_CODE = 1; }
            static class Build { static String FINGERPRINT = "firmware-one"; }
            static class Log { static void w(String tag, String message, Throwable failure) { } }
            static class BoundedText {
                static String prefix(String text, int limit) { return text.substring(0, Math.min(text.length(), limit)); }
            }
            static class FeatureInfo {
                String name; int version;
                FeatureInfo(String name, int version) { this.name = name; this.version = version; }
            }
            static class PackageManager {
                static final String FEATURE_VULKAN_HARDWARE_VERSION = "version";
                static final String FEATURE_VULKAN_HARDWARE_LEVEL = "level";
                static final String FEATURE_VULKAN_HARDWARE_COMPUTE = "compute";
                FeatureInfo[] features = {null, new FeatureInfo(null, 0), new FeatureInfo("unrelated", 1),
                    new FeatureInfo("version", (1 << 22) | (1 << 12)),
                    new FeatureInfo("version", (1 << 22) | (3 << 12) | 275), new FeatureInfo("level", 0)};
                boolean unavailable;
                FeatureInfo[] getSystemAvailableFeatures() {
                    if (unavailable) throw new IllegalStateException();
                    return features;
                }
            }
            static class Preferences {
                final java.util.Map<String, Object> values = new java.util.HashMap<>();
                int writes;
                java.util.Map<String, Object> getAll() { return java.util.Map.copyOf(values); }
                Preferences edit() { ++writes; return this; }
                Preferences putString(String key, String value) { values.put(key, value); return this; }
                Preferences putLong(String key, long value) { values.put(key, value); return this; }
                void apply() { }
            }
            static class Context {
                static final int MODE_PRIVATE = 0;
                final Preferences saved = new Preferences();
                final PackageManager packages = new PackageManager();
                boolean unavailable;
                Preferences getSharedPreferences(String name, int mode) {
                    check(name.equals("graphics_diagnostics") && mode == MODE_PRIVATE, "storage owner");
                    if (unavailable) throw new IllegalStateException();
                    return saved;
                }
                PackageManager getPackageManager() { return packages; }
            }
            static String report(Context context) {
                var result = new StringBuilder();
                int writes = context.saved.writes;
                GraphicalDiagnostics.appendReport(result, context);
                check(writes == context.saved.writes, "report changes state");
                return result.toString();
            }
            public static void verify() {
                var context = new Context();
                String first = report(context);
                check(first.contains("version=1.3.275, level=0, compute=not declared"), "declaration decoding");
                check(first.contains("X11 last startup: NOT_OBSERVED")
                    && first.contains("WAYLAND last startup: NOT_OBSERVED"), "absence is not unsupported");
                check(GraphicalDiagnostics.feature(null, "version", true).equals("unknown"), "unknown list");
                check(GraphicalDiagnostics.feature(new FeatureInfo[0], "level", false).equals("not declared"), "empty list");
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.X11, HostedRenderer.SOFTWARE, null);
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.X11, HostedRenderer.SOFTWARE, " ");
                check(context.saved.writes == 0, "empty observation changed storage");
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.X11, HostedRenderer.SOFTWARE,
                    "backend=software, selection=explicit");
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.WAYLAND, HostedRenderer.AUTOMATIC,
                    "backend=vulkan, device=fixture GPU, api=1.3.275, driverVersion=0x12345678");
                String observed = report(context);
                check(observed.contains("X11 last startup: backend=software, selection=explicit | requested=software"), "software");
                check(observed.contains("WAYLAND last startup: backend=vulkan, device=fixture GPU"), "independent protocol");
                check(observed.contains("currentFirmware=true") && observed.contains("recorded=")
                    && observed.contains("app=fixture (1)"), "observation context");
                Build.FINGERPRINT = "firmware-two";
                BuildConfig.VERSION_NAME = "next";
                check(report(context).contains("app=fixture (1) | currentFirmware=false"), "old observation silently promoted");
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.X11, HostedRenderer.AUTOMATIC,
                    "backend=software, selection=automatic-fallback");
                check(context.saved.values.size() == 10, "unbounded journal");
                check(report(context).contains("selection=automatic-fallback"), "fallback missing");
                String hostile = "  name\\n\\r\\0" + "x".repeat(3000);
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.WAYLAND, HostedRenderer.AUTOMATIC, hostile);
                String cleaned = (String) context.saved.values.get("WAYLAND.description");
                check(cleaned.length() == 1024 && cleaned.indexOf('\\n') < 0
                    && cleaned.indexOf('\\r') < 0 && cleaned.indexOf('\\0') < 0, "unbounded or multiline observation");
                context.packages.unavailable = true;
                check(report(context).contains("Android-declared Vulkan: unavailable (IllegalStateException)"), "feature failure");
                context.unavailable = true;
                GraphicalDiagnostics.record(context, GraphicalDiagnostics.Protocol.X11, HostedRenderer.AUTOMATIC, "ignored");
                check(report(context).contains("Renderer observations: unavailable (IllegalStateException)"), "storage failure");
            }
            """);
    }
}
