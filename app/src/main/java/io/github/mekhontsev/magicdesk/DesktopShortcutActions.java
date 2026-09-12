package io.github.mekhontsev.magicdesk;

final class DesktopShortcutActions {
    private DesktopShortcutActions() {}

    static void dispatch(
            final KeyboardShortcutStateMachine.Action action) {
        switch (action) {
            case ALT_TAB_FORWARD:
                DesktopOperations.advanceAltTab(false);
                break;
            case ALT_TAB_REVERSE:
                DesktopOperations.advanceAltTab(true);
                break;
            case ALT_TAB_COMMIT:
                DesktopOperations.finishAltTab();
                break;
            case TOGGLE_LAYOUT:
                DesktopOperations.toggleHardwareKeyboardLayout();
                break;
            case DISMISS:
                MagicDeskRuntime.dismissTransientActivity();
                break;
            case CLOSE:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_CLOSE);
                break;
            case BACK:
                DesktopOperations.sendSystemBack();
                break;
            case LOCK:
                DesktopOperations.lockDevice();
                break;
            case NOTIFICATIONS:
                DesktopOperations.toggleNotificationCenter();
                break;
            case SYSTEM:
                DesktopOperations.toggleSystemPanel();
                break;
            case SETTINGS:
                DesktopOperations.openSettings();
                break;
            case FULLSCREEN:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_FULLSCREEN);
                break;
            case RESTORE:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_RESTORE);
                break;
            case SNAP_LEFT:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_LEFT);
                break;
            case SNAP_RIGHT:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_RIGHT);
                break;
            case SHOW_DESKTOP:
                DesktopOperations.toggleDesktopWorkspace();
                break;
            case SCREENSHOT:
                DesktopOperations.captureScreenshot(MagicDeskRuntime.inputDisplayId());
                break;
            case SCREEN_RECORDING:
                DisplayRecordingController.get().toggle(MagicDeskRuntime.inputDisplayId());
                break;
            case SHORTCUT_HELP:
                DesktopOperations.showShortcutHelp();
                break;
            case NONE:
            default:
                break;
        }
    }

}
