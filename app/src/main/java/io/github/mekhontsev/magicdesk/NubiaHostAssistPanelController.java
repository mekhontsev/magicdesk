package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hides Nubia's external-display handle while MagicDesk owns the desktop. */
final class NubiaHostAssistPanelController {
    private static final String TAG = "MagicDeskHostAssist";
    private static final AtomicBoolean HIDE_IN_PROGRESS = new AtomicBoolean();

    /*
     * HostAssistMgr intentionally leaves a small handle after closePanel(). It
     * fully removes the window only when it temporarily sees USB projection.
     * Re-publishing the unchanged display id makes its observer evaluate that
     * state. The real projection type is restored before this command exits.
     */
    private static final String HIDE_COMMAND =
            "panel=$(/system/bin/dumpsys SurfaceFlinger --list"
                    + " | /system/bin/grep -m 1 HostAssistPanel); "
                    + "[ -n \"$panel\" ] || { echo host-panel=absent; exit 0; }; "
                    + "old_type=$(/system/bin/settings get global"
                    + " tp_type_for_games); "
                    + "display_id=$(/system/bin/settings get global"
                    + " app_mirror_displayid); "
                    + "status=$(/system/bin/settings get global"
                    + " nubia_systemui_wifidisplay_status); "
                    + "case \"$display_id\" in ''|null|*[!0-9]*)"
                    + " echo invalid-display:$display_id; exit 2;; esac; "
                    + "[ \"$display_id\" -gt 0 ]"
                    + " || { echo inactive-display; exit 2; }; "
                    + "[ \"$status\" = 1 ]"
                    + " || { echo invalid-projection-status:$status; exit 2; }; "
                    + "restore_type() { "
                    + "if [ -z \"$old_type\" ] || [ \"$old_type\" = null ]; then "
                    + "/system/bin/settings delete global tp_type_for_games"
                    + " >/dev/null; else /system/bin/settings put global"
                    + " tp_type_for_games \"$old_type\" >/dev/null; fi; }; "
                    + "trap restore_type EXIT; "
                    + "/system/bin/settings put global tp_type_for_games 3; "
                    + "/system/bin/settings put global app_mirror_displayid"
                    + " \"$display_id\"; "
                    + "i=0; while /system/bin/dumpsys SurfaceFlinger --list"
                    + " | /system/bin/grep -q HostAssistPanel; do "
                    + "i=$((i+1)); [ \"$i\" -lt 25 ]"
                    + " || { echo host-panel=timeout; exit 3; }; "
                    + "/system/bin/sleep 0.04; done; "
                    + "echo host-panel=hidden";

    private NubiaHostAssistPanelController() {
    }

    static void hideIfPresent() {
        if (!ShellAccess.isReady()
                || !HIDE_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                final String output = ShellAccess.run(HIDE_COMMAND).trim();
                Log.i(TAG, output);
            } catch (IOException error) {
                Log.w(TAG, "Could not hide HostAssistPanel", error);
                CompatibilityDiagnostics.record(
                        "NUBIA-HOST-PANEL-001",
                        "Could not hide the Nubia external desktop panel",
                        error.getMessage(),
                        error);
            } finally {
                HIDE_IN_PROGRESS.set(false);
            }
        });
    }

}
