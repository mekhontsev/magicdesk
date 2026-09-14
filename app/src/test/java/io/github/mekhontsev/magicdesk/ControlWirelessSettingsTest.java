package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ControlWirelessSettingsTest {
    @Test public void connectedDisplayKeepsSettingsOpenForManagement() throws Exception {
        verify("""
                f.mDisplays = new DesktopDisplayInfo[] { new DesktopDisplayInfo("wireless") };
                f.openWirelessSettings();
                check(f.mProjection.opens == 1, "connected Miracast blocked its settings");
                check(!f.mReturnToPanelAfterWirelessConnection, "existing connection will reclaim the panel");
                check("ready".equals(f.mStatus), "connection management was reported as connecting");
                f.openWirelessSettings();
                check(f.mProjection.opens == 2, "settings cannot be reopened");
                """);
    }

    @Test public void newConnectionStillReturnsToThePanel() throws Exception {
        verify("""
                f.mDisplays = new DesktopDisplayInfo[] { new DesktopDisplayInfo("wired") };
                f.openWirelessSettings();
                check(f.mProjection.opens == 1, "wireless settings did not open");
                check(f.mReturnToPanelAfterWirelessConnection, "new connection lost its panel handoff");
                check("connecting".equals(f.mStatus), "missing connection status");
                """);
    }

    @Test public void failedLaunchCancelsThePanelHandoff() throws Exception {
        verify("""
                f.mProjection.available = false;
                f.openWirelessSettings();
                check(f.mProjection.opens == 1, "launch was not attempted");
                check(!f.mReturnToPanelAfterWirelessConnection, "failed launch retains a handoff");
                check("unavailable".equals(f.mStatus), "launch failure was hidden");
                """);
    }

    @Test public void unavailableUiAndInFlightOperationsRemainGuarded() throws Exception {
        verify("""
                f.mWirelessConnectionUiAvailable = false;
                f.openWirelessSettings();
                f.mWirelessConnectionUiAvailable = true;
                DesktopOperations.busy = true;
                f.openWirelessSettings();
                DesktopOperations.busy = false;
                f.mDisplayOperation = true;
                f.openWirelessSettings();
                check(f.mProjection.opens == 0, "settings bypassed capability or operation guard");
                check(f.refreshes == 3, "rejection did not refresh the panel");
                check(!f.mReturnToPanelAfterWirelessConnection, "rejected command retains a handoff");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                interface Actions { void openWirelessSettings(); }
                static class R { static class string {
                    static final int status_external_display_unavailable = 1;
                    static final int status_wireless_display_connecting = 2;
                } }
                static class DesktopDisplayInfo {
                    final String source;
                    DesktopDisplayInfo(String source) { this.source = source; }
                }
                static class DesktopOperations {
                    static boolean busy;
                    static boolean isSessionTransitionInProgress() { return busy; }
                }
                static class Projection {
                    boolean available = true;
                    int opens;
                    boolean openWirelessConnectionUi(Object activity) { opens++; return available; }
                }
                static class Control implements Actions {
                    boolean mWirelessConnectionUiAvailable = true;
                    boolean mDisplayOperation, mReturnToPanelAfterWirelessConnection;
                    DesktopDisplayInfo[] mDisplays = new DesktopDisplayInfo[0];
                    final Projection mProjection = new Projection();
                    String mStatus = "ready";
                    int refreshes;
                    void refresh() { refreshes++; }
                    String getString(int id) { return id == 1 ? "unavailable" : "connecting"; }
                """ + RuntimeSourceFixture.methods("ControlActivity", "openWirelessSettings", "wirelessConnected") + """
                }
                public static void verify() {
                    Control f = new Control();
                """ + scenario + "}\n");
    }
}
