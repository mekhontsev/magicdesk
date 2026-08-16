package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class PlatformSourceIsolationTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final String IMPLEMENTATION_REFERENCE =
            "io.github.mekhontsev.magicdesk.platform.";
    private static final String PLATFORM_SELECTOR =
            "io/github/mekhontsev/magicdesk/PlatformDrivers.java";
    private static final String VENDOR_DIRECTORY =
            "/io/github/mekhontsev/magicdesk/platform/nubia/";
    private static final String PLATFORM_DIRECTORY =
            "/io/github/mekhontsev/magicdesk/platform/";
    private static final String[] VENDOR_RUNTIME_IDENTIFIERS = {
        "\"cn.nubia",
        "\"com.zte",
        "\"com.redmagic",
        "\"redmagic.app.manager",
        "\"nubia_screen_off_tp",
        "\"app_mirror_displayid",
        "\"NubiaAppMirrorDisplay",
        "\"setCmdToDisplay",
        "\"RedMagicAppManager",
        "\"ColorfulLightService",
        "\"/sys/kernel/lcd_enhance/"
    };

    @Test
    public void onlyCompositionRootImportsPlatformImplementations()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final Path source : productionSources()) {
            final String relative = relativePath(source);
            final String rooted = "/" + relative;
            if (!PLATFORM_SELECTOR.equals(relative)
                    && !rooted.contains(PLATFORM_DIRECTORY)
                    && read(source).contains(IMPLEMENTATION_REFERENCE)) {
                violations.add(relative);
            }
        }
        assertTrue(
                "Platform implementation imports outside PlatformDrivers: "
                        + violations,
                violations.isEmpty());
    }

    @Test
    public void vendorRuntimeIdentifiersStayInVendorAdapter()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final Path source : productionSources()) {
            final String relative = "/" + relativePath(source);
            if (relative.contains(VENDOR_DIRECTORY)) {
                continue;
            }
            final String contents = read(source);
            for (final String identifier : VENDOR_RUNTIME_IDENTIFIERS) {
                if (contents.contains(identifier)) {
                    violations.add(relative.substring(1) + ": " + identifier);
                }
            }
        }
        assertTrue(
                "Vendor runtime identifiers outside platform adapter: "
                        + violations,
                violations.isEmpty());
    }

    @Test
    public void platformAdaptersDoNotReachDesktopHostFacade()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final Path source : productionSources()) {
            final String relative = "/" + relativePath(source);
            if (relative.contains(PLATFORM_DIRECTORY)
                    && read(source).contains("DesktopRuntimeBridge")) {
                violations.add(relative.substring(1));
            }
        }
        assertTrue(
                "Platform adapters reaching desktop host facade: "
                        + violations,
                violations.isEmpty());
    }

    private static List<Path> productionSources() throws IOException {
        final List<Path> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(sources::add);
        }
        return sources;
    }

    private static String read(final Path source) throws IOException {
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static String relativePath(final Path source) {
        return MAIN_JAVA.relativize(source).toString().replace('\\', '/');
    }
}
