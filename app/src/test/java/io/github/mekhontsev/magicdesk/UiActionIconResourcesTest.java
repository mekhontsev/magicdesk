package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UiActionIconResourcesTest {
    @Test public void consoleActionsShareOneVectorSizeAndStroke() throws Exception {
        final String layout = RuntimeSourceFixture.methods("CommandConsoleActivity", "createContentView");
        assertFalse(layout.contains("android.R.drawable"));
        final var icons = Pattern.compile("R\\.drawable\\.(\\w+)").matcher(layout);
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final String namespace = "http://schemas.android.com/apk/res/android";
        int count = 0;
        while (icons.find()) {
            final String name = icons.group(1);
            final var document = factory.newDocumentBuilder().parse(
                    Path.of("src/main/res/drawable", name + ".xml").toFile());
            final Element vector = document.getDocumentElement();
            assertEquals(name, "vector", vector.getTagName());
            for (final String size : new String[]{"width", "height"}) {
                assertEquals(name, "24dp", vector.getAttributeNS(namespace, size));
            }
            for (final String size : new String[]{"viewportWidth", "viewportHeight"}) {
                assertEquals(name, "24", vector.getAttributeNS(namespace, size));
            }
            final var paths = vector.getElementsByTagName("path");
            assertTrue(name, paths.getLength() > 0);
            for (int i = 0; i < paths.getLength(); i++) {
                final Element path = (Element) paths.item(i);
                assertEquals(name, "#00000000", path.getAttributeNS(namespace, "fillColor"));
                assertEquals(name, "2", path.getAttributeNS(namespace, "strokeWidth"));
                assertEquals(name, "round", path.getAttributeNS(namespace, "strokeLineCap"));
            }
            count++;
        }
        assertTrue("toolbar has no actions", count > 0);
    }

    @Test public void actionIconsDoNotComeFromFirmware() throws Exception {
        final Pattern frameworkIcon = Pattern.compile("android\\.R\\.drawable\\.(\\w+)");
        try (var files = Files.walk(Path.of(RuntimeSourceFixture.MAIN))) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                final var icons = frameworkIcon.matcher(Files.readString(file));
                while (icons.find()) {
                    // Unknown third-party app artwork remains Android's own fallback.
                    assertEquals(file.toString(), "sym_def_app_icon", icons.group(1));
                }
            }
        }
    }
}
