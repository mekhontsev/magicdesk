package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;

public final class DesktopSelfTestManifestTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void windowFixtureRetainsOrdinaryTaskLifetime() throws Exception {
        verifyFixture(".DesktopSelfTestActivity");
    }

    @Test
    public void browserFixtureRetainsOrdinaryTaskLifetime() throws Exception {
        verifyFixture(".DesktopSelfTestBrowserActivity");
    }

    private static void verifyFixture(final String name) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var activities = factory.newDocumentBuilder()
                .parse(Path.of("src/main/AndroidManifest.xml").toFile())
                .getElementsByTagName("activity");
        for (int index = 0; index < activities.getLength(); index++) {
            final Element activity = (Element) activities.item(index);
            if (!name.equals(activity.getAttributeNS(ANDROID, "name"))) {
                continue;
            }
            // Android can trim an excluded task launched behind HOME before reveal.
            assertFalse(name + " must survive ordinary background preparation",
                    Boolean.parseBoolean(activity.getAttributeNS(
                            ANDROID, "excludeFromRecents")));
            assertFalse(Boolean.parseBoolean(activity.getAttributeNS(ANDROID, "noHistory")));
            assertEquals("android.permission.MANAGE_ACTIVITY_TASKS",
                    activity.getAttributeNS(ANDROID, "permission"));
            assertEquals("true", activity.getAttributeNS(ANDROID, "resizeableActivity"));
            return;
        }
        fail("Missing fixture " + name);
    }
}
