package io.github.mekhontsev.magicdesk;

import android.app.Activity;

interface MagicDeskSessionHost {
    Activity sessionActivity();

    void showSessionStatus(String message);

    void showSessionError(String code, String message, Throwable error);

    void releaseSessionUi();
}
