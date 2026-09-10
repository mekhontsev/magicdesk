package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class DesktopHomeStartupGuardTest {
    @Test
    public void secondaryHomeAllowsPerDisplayInstances() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        final NodeList activities = factory.newDocumentBuilder()
                .parse(Path.of("src/main/AndroidManifest.xml").toFile())
                .getElementsByTagName("activity");
        final String android = "http://schemas.android.com/apk/res/android";
        int found = 0;
        for (int i = 0; i < activities.getLength(); i++) {
            final Element activity = (Element) activities.item(i);
            final NodeList categories = activity.getElementsByTagName("category");
            for (int j = 0; j < categories.getLength(); j++) {
                final String category = ((Element) categories.item(j))
                        .getAttributeNS(android, "name");
                if ("android.intent.category.SECONDARY_HOME".equals(category)) {
                    // Android reroutes singleTask/singleInstance HOME to display 0.
                    assertEquals("secondary HOME must support a display-local instance",
                            "singleTop", activity.getAttributeNS(android, "launchMode"));
                    found++;
                }
            }
        }
        assertEquals("one external HOME component", 1, found);
    }

    @Test
    public void applicationEnablesItsRegisteredBackCallbacks() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        final Element application = (Element) factory.newDocumentBuilder()
                .parse(Path.of("src/main/AndroidManifest.xml").toFile())
                .getElementsByTagName("application").item(0);
        assertTrue("HOME must handle Back instead of finishing",
                "true".equals(application.getAttributeNS(
                        "http://schemas.android.com/apk/res/android",
                        "enableOnBackInvokedCallback")));
    }

    @Test
    public void homeSurfacesAreDisabledBeforeAnySession() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        final NodeList activities = factory.newDocumentBuilder()
                .parse(Path.of("src/main/AndroidManifest.xml").toFile())
                .getElementsByTagName("activity");
        int found = 0;
        final String android = "http://schemas.android.com/apk/res/android";
        for (int i = 0; i < activities.getLength(); i++) {
            final Element activity = (Element) activities.item(i);
            final String name = activity.getAttributeNS(android, "name");
            if (".PhoneDesktopHomeActivity".equals(name)) {
                assertTrue("primary HOME must reuse the standard HOME root",
                        "singleTop".equals(activity.getAttributeNS(android, "launchMode")));
            }
            final NodeList categories = activity.getElementsByTagName("category");
            boolean home = false;
            for (int j = 0; j < categories.getLength(); j++) {
                final String category = ((Element) categories.item(j))
                        .getAttributeNS(android, "name");
                home |= "android.intent.category.HOME".equals(category)
                        || "android.intent.category.SECONDARY_HOME".equals(category);
            }
            if (home) {
                assertTrue(name, "false".equals(activity.getAttributeNS(android, "enabled")));
                found++;
            }
        }
        assertTrue("all three HOME surfaces declared", found == 3);
    }

    @Test
    public void secondaryHomeSharesTheExternalSessionComponentBatch() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopHomeSurfaceRouter.java"));
        assertTrue(source.contains("context, DesktopActivity.class"));
        assertTrue(source.contains("getComponentEnabledSetting(secondary) == phoneState"));
        assertTrue(source.matches("(?s).*new PackageManager.ComponentEnabledSetting\\("
                + "\\s*secondary,\\s*phoneState,\\s*PackageManager.DONT_KILL_APP\\).*"));
    }

    @Test
    public void startupDoesNotTrustRoleOwnershipAlone() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopHomeStartupGuard.java"));
        assertTrue(source.contains("resolveActivity("));
        assertTrue(source.contains("!ownsRole && lease == null && !resolvesToUs"));
        assertTrue(source.indexOf("disableHomeSurfaces()")
                < source.indexOf("!ownsRole && lease == null && !resolvesToUs"));
    }

    @Test
    public void mainProcessOwnsStartupRecovery() {
        assertTrue(DesktopHomeStartupGuard.isPrimaryProcess(
                "io.github.mekhontsev.magicdesk",
                "io.github.mekhontsev.magicdesk"));
    }

    @Test
    public void recoveryDoesNotLatchAdmissionForTheProcessLifetime() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DesktopHomeStartupGuard.java"));
        assertFalse(source.contains("sRelinquishedOnProcessStart"));
        for (final String activity : new String[] {"PhoneHomeActivity", "DesktopShellActivity"}) {
            final String activitySource = Files.readString(Path.of(
                    "src/main/java/io/github/mekhontsev/magicdesk/" + activity + ".java"));
            assertFalse(activitySource.contains("shouldDiscardStaleHomeLaunch"));
            assertTrue(activitySource.contains("HomeLease()"));
        }
    }

    @Test
    public void auxiliaryProcessesCannotReleaseHome() {
        assertFalse(DesktopHomeStartupGuard.isPrimaryProcess(
                "io.github.mekhontsev.magicdesk:task_area_backstop",
                "io.github.mekhontsev.magicdesk"));
        assertFalse(DesktopHomeStartupGuard.isPrimaryProcess(
                "io.github.mekhontsev.magicdesk:selftest",
                "io.github.mekhontsev.magicdesk"));
    }
}
