package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

/** Startup policy, independent of actual capabilities, transport selection and client grants. */
final class RuntimeLimits {
    enum Access {
        ROOT(R.string.limit_access_root), SHELL(R.string.limit_access_shell), APP_ONLY(R.string.limit_access_app);
        final int label;
        Access(int label) { this.label = label; }
        String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    static final Values DEFAULT = new Values(Access.SHELL, true, true);
    record Values(Access access, boolean termux, boolean desktop) {
        Values { java.util.Objects.requireNonNull(access); }
        boolean privilegedAllowed() { return access != Access.APP_ONLY; }
        boolean desktopAllowed() { return desktop && privilegedAllowed(); }
        int targetUid(int grantedUid) {
            if (!privilegedAllowed() || (grantedUid != 0 && grantedUid != 2000))
                throw new SecurityException("Privileged service is not allowed for UID " + grantedUid);
            return access == Access.SHELL ? 2000 : grantedUid;
        }
        void verifyServiceUid(int uid) {
            if (targetUid(uid) != uid)
                throw new SecurityException("Service UID exceeds Maximum access: " + uid);
        }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("maximumAccess", access.wireName())
                    .put("termux", termux).put("desktop", desktop);
        }
    }

    static Values active() { return Active.VALUE; }
    static Values configured(Context context) {
        var preferences = context.getSharedPreferences("runtime_limits", Context.MODE_PRIVATE);
        return new Values(Access.valueOf(preferences.getString("maximum_access", DEFAULT.access.name())),
                preferences.getBoolean("termux", DEFAULT.termux), preferences.getBoolean("desktop", DEFAULT.desktop));
    }
    static boolean save(Context context, Values limits) {
        return context.getSharedPreferences("runtime_limits", Context.MODE_PRIVATE).edit()
                .putString("maximum_access", limits.access.name()).putBoolean("termux", limits.termux)
                .putBoolean("desktop", limits.desktop).commit();
    }
    static boolean restartRequired(Context context) {
        return !active().equals(configured(context)) || ShellBackend.active() != ShellBackend.configured(context);
    }
    static JSONObject toJson(Context context) throws JSONException {
        return new JSONObject().put("active", active().toJson()).put("configured", configured(context).toJson())
                .put("restartRequired", restartRequired(context));
    }
    private static final class Active {
        static final Values VALUE = configured(MagicDeskApplication.applicationContext());
    }
    private RuntimeLimits() { }
}
