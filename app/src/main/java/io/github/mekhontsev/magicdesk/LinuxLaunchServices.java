package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.Closeable;
import java.io.IOException;

/** Optional launch capabilities; neither belongs to a display protocol or Desktop. */
final class LinuxLaunchServices implements Closeable {
    private final LinuxAudio audio;
    final LinuxGamepads gamepads;
    private final LinuxAutomationLaunch automation;
    private final boolean nativeEnvironment;

    private LinuxLaunchServices(LinuxAudio audio, LinuxGamepads gamepads, LinuxAutomationLaunch automation, boolean nativeEnvironment) {
        this.audio = audio;
        this.gamepads = gamepads;
        this.automation = automation;
        this.nativeEnvironment = nativeEnvironment;
    }

    static LinuxLaunchServices prepare(Context context, CommandExecution commands, String graphicsId, String environment) {
        LinuxAudio audio = LinuxAudio.prepare(context, commands);
        LinuxGamepads gamepads = null;
        try { gamepads = LinuxGamepads.prepare(context, commands); }
        catch (IOException error) {
            CompatibilityDiagnostics.record("LINUX-GAMEPAD-001", "Linux gamepad input is unavailable",
                    ShellAccess.usefulMessage(error), error);
        } catch (RuntimeException error) { audio.close(); throw error; }
        LinuxAutomationLaunch automation = null;
        try { automation = new LinuxAutomationLaunch(context, commands, graphicsId, environment); }
        catch (IOException error) {
            CompatibilityDiagnostics.record("LINUX-AUTOMATION-001", "Linux automation is unavailable",
                    ShellAccess.usefulMessage(error), error);
        }
        return new LinuxLaunchServices(audio, gamepads, automation, environment.isEmpty());
    }

    String exports() { return audio.exports() + (gamepads == null ? "" : gamepads.exports())
            + (automation == null ? "" : automation.exports()); }

    String command(String command, String shell) {
        if (!nativeEnvironment || automation == null) return command;
        return LinuxAutomationEnvironment.command(command, shell);
    }

    @Override public void close() {
        if (automation != null) automation.close();
        if (gamepads != null) gamepads.close();
        audio.close();
    }
}
