package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.widget.LinearLayout;

/** Optional system-panel controls supplied by the selected platform. */
public interface PlatformSystemControls {
    PlatformSystemControls NONE = new PlatformSystemControls() {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void setPanelVisible(final boolean visible) {
        }

        @Override
        public void populate(final LinearLayout parent, final int spacing) {
        }

        @Override
        public void onBatteryChanged(final Intent battery) {
        }
    };

    void start();

    void stop();

    void setPanelVisible(boolean visible);

    void populate(LinearLayout parent, int spacing);

    void onBatteryChanged(Intent battery);
}
