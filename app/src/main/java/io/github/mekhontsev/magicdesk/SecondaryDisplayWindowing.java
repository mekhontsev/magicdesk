package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session lifecycle wiring and durable recovery for Android display defaults. */
final class SecondaryDisplayWindowing {
    private SecondaryDisplayWindowing() {
    }

    static void prepare(final int displayId) throws IOException {
        Holder.SESSION.prepare(displayId);
    }

    static void release(final int displayId) throws IOException {
        Holder.SESSION.release(displayId);
    }

    static void onDisplayRemoved(final int displayId) {
        TaskCommandQueue.execute(() -> {
            try {
                release(displayId);
            } catch (IOException error) {
                recordRecoveryFailure(error);
            }
        });
    }

    static void recoverPending() {
        if (!ShellAccess.isReady()) {
            return;
        }
        TaskCommandQueue.execute(() -> {
            try {
                Holder.SESSION.recover();
            } catch (IOException error) {
                recordRecoveryFailure(error);
            }
        });
    }

    static String diagnostics() {
        try {
            return Holder.SESSION.diagnostics();
        } catch (IOException error) {
            return "policy=freeform, restoration=unavailable: " + error.getMessage();
        }
    }

    private static void recordRecoveryFailure(final IOException error) {
        CompatibilityDiagnostics.record("DISPLAY-WINDOWING-001",
                "Could not restore secondary display default mode",
                error.getMessage(), error);
    }

    private static final class Holder {
        static final DisplayWindowingSession SESSION = new DisplayWindowingSession(
                new DisplayWindowingSession.Api() {
                    @Override
                    public int[] displayIds() throws IOException {
                        final DisplayManager manager = MagicDeskApplication.applicationContext()
                                .getSystemService(DisplayManager.class);
                        if (manager == null) {
                            throw new IOException("display manager is unavailable");
                        }
                        final Display[] displays = manager.getDisplays();
                        final int[] ids = new int[displays.length];
                        for (int i = 0; i < displays.length; i++) {
                            ids[i] = displays[i].getDisplayId();
                        }
                        return ids;
                    }

                    @Override
                    public DisplayWindowingSnapshot read(final int displayId) throws IOException {
                        return ShellAccess.readDisplayWindowing(displayId);
                    }

                    @Override
                    public void set(final int displayId, final String uniqueId, final int mode)
                            throws IOException {
                        ShellAccess.setDisplayWindowing(displayId, uniqueId, mode);
                    }
                }, new PreferencesStorage());
    }

    private static final class PreferencesStorage implements DisplayWindowingSession.Storage {
        private SharedPreferences preferences() {
            return MagicDeskApplication.applicationContext().getSharedPreferences(
                    "magicdesk_display_windowing", Context.MODE_PRIVATE);
        }

        @Override
        public Map<String, DisplayWindowingSnapshot> read() throws IOException {
            final Map<String, DisplayWindowingSnapshot> pending = new LinkedHashMap<>();
            try {
                final JSONArray entries = new JSONArray(preferences().getString("pending", "[]"));
                for (int i = 0; i < entries.length(); i++) {
                    final JSONObject entry = entries.getJSONObject(i);
                    final DisplayWindowingSnapshot previous = new DisplayWindowingSnapshot(
                            entry.getInt("displayId"), entry.getString("uniqueId"),
                            entry.getInt("mode"), entry.getBoolean("virtual"));
                    if (previous.displayId <= 0 || previous.uniqueId.isEmpty()
                            || previous.mode <= 0 || previous.mode == 5) {
                        throw new IOException("invalid saved display default mode");
                    }
                    pending.put(previous.uniqueId, previous);
                }
            } catch (JSONException | ClassCastException error) {
                throw new IOException("cannot read display mode restoration state", error);
            }
            return pending;
        }

        @Override
        @SuppressLint("ApplySharedPref")
        public void write(final Map<String, DisplayWindowingSnapshot> pending) throws IOException {
            final JSONArray entries = new JSONArray();
            try {
                for (DisplayWindowingSnapshot previous : pending.values()) {
                    entries.put(new JSONObject().put("displayId", previous.displayId)
                            .put("uniqueId", previous.uniqueId).put("mode", previous.mode)
                            .put("virtual", previous.virtual));
                }
            } catch (JSONException error) {
                throw new IOException("cannot encode display mode restoration state", error);
            }
            if (!preferences().edit().putString("pending", entries.toString()).commit()) {
                throw new IOException("cannot save display mode restoration state");
            }
        }
    }
}
