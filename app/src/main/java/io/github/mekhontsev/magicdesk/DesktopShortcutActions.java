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
                        DesktopWindowAction.CLOSE);
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
                        DesktopWindowAction.FULLSCREEN);
                break;
            case RESTORE:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.RESTORE);
                break;
            case SNAP_LEFT:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.SNAP_LEFT);
                break;
            case SNAP_RIGHT:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.SNAP_RIGHT);
                break;
            case SNAP_TOP_LEFT:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.SNAP_TOP_LEFT);
                break;
            case SNAP_TOP_RIGHT:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.SNAP_TOP_RIGHT);
                break;
            case SNAP_BOTTOM_LEFT:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.SNAP_BOTTOM_LEFT);
                break;
            case SNAP_BOTTOM_RIGHT:
                DesktopOperations.manageActiveWindow(
                        DesktopWindowAction.SNAP_BOTTOM_RIGHT);
                break;
            case SHOW_DESKTOP:
                DesktopOperations.toggleDesktopWorkspace();
                break;
            case DISPLAY_FORWARD:
            case DISPLAY_REVERSE:
                DisplaySwitchController.advanceForInput(action == KeyboardShortcutStateMachine.Action.DISPLAY_REVERSE);
                break;
            case DISPLAY_COMMIT:
                DisplaySwitchController.commit();
                break;
            case DISPLAY_CANCEL:
                DisplaySwitchController.cancel();
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
            case PAPER_TOGGLE:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_TOGGLE);
                break;
            case PAPER_PREVIOUS:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_PREVIOUS);
                break;
            case PAPER_NEXT:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_NEXT);
                break;
            case PAPER_MOVE_LEFT:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_MOVE_LEFT);
                break;
            case PAPER_MOVE_RIGHT:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_MOVE_RIGHT);
                break;
            case PAPER_WIDTH:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_WIDTH);
                break;
            case PAPER_FLOAT:
                DesktopOperations.manageActiveWindow(DesktopWindowAction.PAPER_FLOAT);
                break;
            case NONE:
            default:
                break;
        }
    }

}
