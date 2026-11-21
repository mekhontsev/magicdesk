package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Phone-panel power capability, independent of Desktop and firmware work hints. */
public interface PlatformPhoneUiDriver {
    boolean isAvailable();

    boolean isPhoneScreenOff(Context context);

    boolean isPhoneScreenControlActive();

    boolean restorePhoneScreenForDisplay(int displayId);

    void requestPhoneScreenRestore();
}
