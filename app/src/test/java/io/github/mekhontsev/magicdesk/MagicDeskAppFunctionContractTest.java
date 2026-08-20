package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

public final class MagicDeskAppFunctionContractTest {
    @Test
    public void schemasContainEveryCatalogFunctionExactlyOnce()
            throws Exception {
        final Set<String> expected = Set.of(
                MagicDeskAppFunctionCatalog.all());
        final Document legacy = parse(
                "src/main/assets/magicdesk_app_functions_v1.xml");
        final Document current = parse(
                "src/main/assets/magicdesk_app_functions.xml");

        assertEquals(expected, values(legacy, "function_id"));
        assertEquals(expected, values(current, "id"));
        assertEquals(expected, values(current, "functionId"));
        assertEquals(expected.size(),
                current.getElementsByTagName("appfunction").getLength());
    }

    @Test
    public void manifestRestrictsAndVersionGatesService() throws Exception {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        final String base = Files.readString(
                Path.of("src/main/res/values/bools.xml"),
                StandardCharsets.UTF_8);
        final String api36 = Files.readString(
                Path.of("src/main/res/values-v36/bools.xml"),
                StandardCharsets.UTF_8);

        assertTrue(manifest.contains(
                "android:name=\".MagicDeskAppFunctionService\""));
        assertTrue(manifest.contains(
                "android:permission=\"android.permission."
                        + "BIND_APP_FUNCTION_SERVICE\""));
        assertTrue(manifest.contains(
                "android:name=\"android.app.appfunctions\""));
        assertTrue(manifest.contains(
                "android:name=\"android.app.appfunctions.v2\""));
        assertTrue(base.contains(
                "name=\"app_functions_available\">false"));
        assertTrue(api36.contains(
                "name=\"app_functions_available\">true"));
    }

    @Test
    public void publicFunctionsDoNotExposeDeveloperAutomation()
            throws Exception {
        final String current = Files.readString(
                Path.of("src/main/assets/magicdesk_app_functions.xml"),
                StandardCharsets.UTF_8);

        assertFalse(current.contains("selfTest"));
        assertFalse(current.contains("sendKey"));
        assertFalse(current.contains("movePointer"));
        assertFalse(current.contains("forceStop"));
    }

    private static Document parse(final String path) throws Exception {
        final DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);
        return factory.newDocumentBuilder().parse(Path.of(path).toFile());
    }

    private static Set<String> values(
            final Document document, final String tag) {
        final Set<String> values = new HashSet<>();
        final NodeList nodes = document.getElementsByTagName(tag);
        for (int index = 0; index < nodes.getLength(); index++) {
            if ("appfunction".equals(
                    nodes.item(index).getParentNode().getNodeName())) {
                values.add(nodes.item(index).getTextContent().trim());
            }
        }
        return values;
    }
}
