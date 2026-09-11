package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ConsoleFontResourcesTest {
    @Test public void familyPackagesFourRealFacesWithMatchingWeights() throws Exception {
        final Path directory = Path.of("src/main/res/font");
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var fonts = factory.newDocumentBuilder().parse(directory.resolve("console_mono.xml").toFile())
                .getElementsByTagName("font");
        assertEquals(4, fonts.getLength());
        final Set<String> faces = new HashSet<>(), resources = new HashSet<>();
        final String namespace = "http://schemas.android.com/apk/res/android";
        for (int i = 0; i < fonts.getLength(); i++) {
            final Element font = (Element) fonts.item(i);
            final String weight = font.getAttributeNS(namespace, "fontWeight");
            final String style = font.getAttributeNS(namespace, "fontStyle");
            faces.add(weight + ":" + style);
            final String resource = font.getAttributeNS(namespace, "font");
            assertTrue(resource.startsWith("@font/"));
            assertTrue(resources.add(resource));
            final ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(
                    directory.resolve(resource.substring(6) + ".ttf")));
            assertEquals("TrueType sfnt", 0x00010000, data.getInt(0));
            final int os2 = table(data, 0x4f532f32);
            assertEquals(Integer.parseInt(weight), Short.toUnsignedInt(data.getShort(os2 + 4)));
            final int selection = Short.toUnsignedInt(data.getShort(os2 + 62));
            assertEquals("italic face", style.equals("italic"), (selection & 1) != 0);
            final int post = table(data, 0x706f7374);
            assertTrue("Nerd Font must be Mono", data.getInt(post + 12) != 0);
        }
        assertEquals(Set.of("400:normal", "700:normal", "400:italic", "700:italic"), faces);
    }

    private static int table(final ByteBuffer data, final int tag) {
        final int count = Short.toUnsignedInt(data.getShort(4));
        for (int i = 0; i < count; i++) {
            final int entry = 12 + i * 16;
            if (data.getInt(entry) == tag) return data.getInt(entry + 8);
        }
        throw new AssertionError("Missing TrueType table " + Integer.toHexString(tag));
    }
}
