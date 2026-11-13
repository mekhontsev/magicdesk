package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import org.junit.Test;

public final class IntegrationPackageSnapshotTest {
    @Test public void identifiesForksByInstalledMetadataNotByPackageOrApi() throws Exception {
        for (final String label : new String[]{"Shizuku", "Shizuku-Next", "Another manager"}) {
            final var snapshot = new IntegrationPackageSnapshot("shizukuManagerPackage",
                    "moe.shizuku.privileged.api", "com.hamondev.shevery;moe.shizuku.privileged.api",
                    "available", label, "14.2-fork", 4_294_967_300L, "");
            final StringBuilder report = new StringBuilder();
            snapshot.appendReport(report);
            assertTrue(report.toString().contains("label=" + label));
            assertTrue(report.toString().contains("versionName=14.2-fork, versionCode=4294967300"));
            assertFalse(report.toString().contains("launcherApi"));
            assertEquals(label, snapshot.toJson().getString("label"));
            assertEquals(4_294_967_300L, snapshot.toJson().getLong("versionCode"));
            assertEquals("moe.shizuku.privileged.api", snapshot.toJson().getString("selectedPackage"));
        }
    }

    @Test public void missingMetadataIsUnknownAndDoesNotInventAVersion() throws Exception {
        for (final String state : new String[]{"not-installed-or-not-visible", "unavailable"}) {
            final var snapshot = new IntegrationPackageSnapshot("termuxPackage", "example.terminal",
                    "example.terminal", state, null, null, null, "SecurityException");
            final StringBuilder report = new StringBuilder();
            snapshot.appendReport(report);
            assertTrue(report.toString().contains("installedMetadata=" + state));
            assertFalse(report.toString().contains("versionCode="));
            assertTrue(snapshot.toJson().isNull("versionCode"));
            assertEquals("", snapshot.toJson().getString("label"));
        }
    }

    @Test public void boundsUntrustedPackageTextAndPreservesMissingVersionName() throws Exception {
        final var snapshot = new IntegrationPackageSnapshot("termuxPackage", "com.termux", "com.termux",
                "available", " Termux\n\u0000Fork\r ", null, 10L, "x".repeat(1000));
        assertEquals("Termux Fork", snapshot.label());
        assertEquals(503, snapshot.error().length());
        final StringBuilder report = new StringBuilder();
        snapshot.appendReport(report);
        assertEquals(1, report.toString().chars().filter(value -> value == '\n').count());
        assertTrue(report.toString().contains("versionName=unavailable, versionCode=10"));
        assertEquals("", snapshot.toJson().getString("versionName"));
        final var longName = new IntegrationPackageSnapshot("termuxPackage", "com.termux", "com.termux",
                "available", "a".repeat(255) + "\uD83D\uDE00", "v".repeat(300), 10L, "");
        assertEquals("a".repeat(255) + "...", longName.label());
        assertEquals(259, longName.versionName().length());
    }

    @Test public void rootStartupDoesNotClaimSelectedShizukuPackageAsItsProvider() {
        assertEquals("launcher=su (no Shizuku manager)",
                CompatibilityDiagnostics.shellLauncherDetail(ShellBackend.ROOT, "com.hamondev.shevery"));
        assertEquals("selectedManager=com.hamondev.shevery",
                CompatibilityDiagnostics.shellLauncherDetail(ShellBackend.SHIZUKU, "com.hamondev.shevery"));
    }

    @Test public void readsOnlySelectedPackageInProvidedContextAndRetainsConfiguredChoice() throws Exception {
        verifyCapture("""
            Context context = new Context();
            var result = capture(context, new IntegrationPackage("termuxPackage", "selected.terminal", "next.terminal"));
            check(context.packages.reads.equals(List.of("selected.terminal")), "queried other packages");
            check(result.selectedPackage.equals("selected.terminal"), "selection changed");
            check(result.configuredPackages.equals("next.terminal"), "pending setting lost");
            check(result.label.equals("Installed fork"), "label not read");
            check(result.versionName.equals("2.0-custom") && result.versionCode == 4294967300L, "version truncated");
            check(result.metadataState.equals("available"), "metadata unavailable");
            context.packages.failure = new PackageManager.NameNotFoundException();
            result = capture(context, "shizukuManagerPackage", "missing.manager", "other.manager");
            check(result.metadataState.equals("not-installed-or-not-visible") && result.versionCode == null,
                "missing package fabricated metadata");
            check(context.packages.reads.equals(List.of("selected.terminal", "missing.manager")), "fallback lookup");
            context.packages.failure = new SecurityException();
            result = capture(context, "shizukuManagerPackage", "denied.manager", "denied.manager");
            check(result.metadataState.equals("unavailable") && result.error.equals("SecurityException"),
                "permission failure lost");
            """);
    }

    @Test public void labelFailureDoesNotDiscardInstalledVersion() throws Exception {
        verifyCapture("""
            Context context = new Context();
            context.packages.failLabel = true;
            var result = capture(context, "shizukuManagerPackage", "example.manager", "example.manager");
            check(result.metadataState.equals("available") && result.versionCode == 4294967300L, "version lost");
            check(result.label.isEmpty() && result.error.startsWith("label unavailable:"), "label failure hidden");
            context.packages.info.applicationInfo = null;
            result = capture(context, "shizukuManagerPackage", "example.manager", "example.manager");
            check(result.metadataState.equals("available") && result.versionCode == 4294967300L, "null app info");
            """);
    }

    private static void verifyCapture(final String checks) throws Exception {
        RuntimeSourceFixture.verify("""
            record IntegrationPackageSnapshot(String key, String selectedPackage, String configuredPackages,
                String metadataState, String label, String versionName, Long versionCode, String error) {}
            static class IntegrationPackage {
                String key, selected, configured;
                IntegrationPackage(String key, String selected, String configured) {
                    this.key=key; this.selected=selected; this.configured=configured;
                }
                String selected() { return selected; }
                String configured(Context context) { return configured; }
            }
            static class Context {
                final PackageManager packages = new PackageManager();
                PackageManager getPackageManager() { return packages; }
            }
            static class PackageInfo {
                Object applicationInfo = new Object();
                String versionName = "2.0-custom";
                long getLongVersionCode() { return 4294967300L; }
            }
            static class PackageManager {
                static class NameNotFoundException extends Exception {}
                record PackageInfoFlags(long flags) {
                    static PackageInfoFlags of(long flags) { return new PackageInfoFlags(flags); }
                }
                final List<String> reads = new ArrayList<>();
                final PackageInfo info = new PackageInfo();
                Exception failure; boolean failLabel;
                PackageInfo getPackageInfo(String name, PackageInfoFlags flags) throws NameNotFoundException {
                    reads.add(name); check(flags.flags == 0, "unexpected query flags");
                    if (failure instanceof NameNotFoundException missing) throw missing;
                    if (failure instanceof RuntimeException denied) throw denied;
                    return info;
                }
                CharSequence getApplicationLabel(Object info) {
                    if(failLabel) throw new IllegalStateException();
                    return "Installed fork";
                }
            }
            """ + RuntimeSourceFixture.methods("IntegrationPackageSnapshot", "capture")
                + "public static void verify() throws Exception {\n" + checks + "\n}");
    }
}
