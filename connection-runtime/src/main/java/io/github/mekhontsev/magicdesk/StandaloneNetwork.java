package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import java.util.List;

/** Package permission is the ceiling, independent of the saved launch defaults. */
public final class StandaloneNetwork {
    public static boolean allowed(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    public static GuestNetworkProfile read(Context context) {
        if (!allowed(context)) return GuestNetworkProfile.forSettings(GuestNetworkProfile.Mode.NONE,List.of());
        return GuestNetworkProfile.fromSettings(context.getSharedPreferences("linux-network",Context.MODE_PRIVATE).getString("profile",null));
    }
    static void write(Context context,GuestNetworkProfile profile) throws IOException {
        if (!context.getSharedPreferences("linux-network",Context.MODE_PRIVATE).edit().putString("profile",profile.settings()).commit())
            throw new IOException("Cannot save network settings");
    }
    private StandaloneNetwork() {}
}
