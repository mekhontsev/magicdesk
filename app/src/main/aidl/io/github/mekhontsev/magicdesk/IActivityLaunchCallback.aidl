package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.os.Bundle;

interface IActivityLaunchCallback {
    void sendPendingIntent(
        in PendingIntent pendingIntent,
        in Bundle options) = 1;

    void presentPhoneOverview() = 2;

    boolean isPhoneOverviewRoutingActive() = 3;
}
