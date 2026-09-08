package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticsActivityManifestTest {
    @Test
    public void externalDisplayConfigurationDoesNotRestartSelfTest() throws IOException {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        final int activity = manifest.indexOf("android:name=\".DiagnosticsActivity\"");
        assertTrue("DiagnosticsActivity is declared", activity >= 0);
        final int end = manifest.indexOf("/>", activity);
        assertTrue("DiagnosticsActivity declaration is complete", end > activity);
        final var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final var document = factory.newDocumentBuilder().parse(
                    Path.of("src/main/AndroidManifest.xml").toFile());
            final var activities = document.getElementsByTagName("activity");
            for (int i = 0; i < activities.getLength(); i++) {
                final var element = (org.w3c.dom.Element) activities.item(i);
                final String ns = "http://schemas.android.com/apk/res/android";
                if (!".DiagnosticsActivity".equals(element.getAttributeNS(ns, "name"))) {
                    continue;
                }
                final var handled = java.util.Set.of(
                        element.getAttributeNS(ns, "configChanges").split("\\|"));
                assertTrue(handled.containsAll(java.util.Set.of(
                        "orientation", "screenSize", "smallestScreenSize",
                        "keyboard", "keyboardHidden", "navigation", "screenLayout")));
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException error) {
            throw new IOException(error);
        }
    }
}
