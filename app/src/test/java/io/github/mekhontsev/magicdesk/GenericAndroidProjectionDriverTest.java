package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;

public final class GenericAndroidProjectionDriverTest {
    @Test
    public void castSettingsAvailabilityAndLaunchUseTheSameAndroidAction() throws Exception {
        verify("""
                Driver driver = new Driver();
                check(!driver.hasWirelessConnectionUi(null), "null context available");
                check(!driver.openWirelessConnectionUi(null), "null activity launched");
                Activity activity = new Activity();
                check(!driver.hasWirelessConnectionUi(activity), "missing handler available");
                check(!driver.openWirelessConnectionUi(activity), "missing handler launched");
                check(activity.launched == null, "launch without handler");
                activity.manager.available = true;
                check(driver.hasWirelessConnectionUi(activity), "handler unavailable");
                check(activity.launched == null, "availability probe launched UI");
                check(driver.openWirelessConnectionUi(activity), "launch rejected");
                check(Settings.ACTION_CAST_SETTINGS.equals(activity.launched.action),
                        "wrong launch action");
                """);
    }

    @Test
    public void removedOrProtectedHandlerFailsWithoutLaunchingAFallback() throws Exception {
        verify("""
                Driver driver = new Driver();
                for (RuntimeException failure : List.of(
                        new ActivityNotFoundException(), new SecurityException())) {
                    Activity activity = new Activity();
                    activity.manager.available = true;
                    activity.failure = failure;
                    check(!driver.openWirelessConnectionUi(activity), "failed launch accepted");
                    check(activity.attempts == 1, "unexpected fallback launch");
                }
                check(CompatibilityDiagnostics.errors == 2, "missing diagnostic errors");
                """);
    }

    @Test
    public void manifestAllowsCastSettingsDiscovery() throws Exception {
        final var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(Path.of("src/main/AndroidManifest.xml").toFile());
        assertEquals("1", XPathFactory.newInstance().newXPath().evaluate(
                "count(/manifest/queries/intent/action["
                        + "@*[name()='android:name']='android.settings.CAST_SETTINGS'])",
                document));
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Settings {
                    static final String ACTION_CAST_SETTINGS = "android.settings.CAST_SETTINGS";
                }
                static class PackageManager { boolean available; }
                static class Context {
                    final PackageManager manager = new PackageManager();
                    PackageManager getPackageManager() { return manager; }
                }
                static class Activity extends Context {
                    Intent launched;
                    RuntimeException failure;
                    int attempts;
                    void startActivity(Intent intent) {
                        attempts++;
                        if (failure != null) throw failure;
                        launched = intent;
                    }
                }
                static class Intent {
                    final String action;
                    Intent(String action) { this.action = action; }
                    Object resolveActivity(PackageManager manager) {
                        check(Settings.ACTION_CAST_SETTINGS.equals(action), "wrong probe action");
                        return manager.available ? new Object() : null;
                    }
                }
                static class ActivityNotFoundException extends RuntimeException {}
                static class CompatibilityDiagnostics {
                    static int errors;
                    static void record(String code, String title, String detail, Throwable error) {
                        errors++;
                    }
                }
                static class Driver {
                """ + RuntimeSourceFixture.methods(
                        "platform/android/GenericAndroidProjectionDriver",
                        "hasWirelessConnectionUi", "openWirelessConnectionUi")
                + "}\npublic static void verify() {" + scenario + "}\n");
    }
}
