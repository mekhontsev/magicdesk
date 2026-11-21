package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import java.io.IOException;

public final class GuestNetworkSettings extends NetworkSettingsActivity {
    static void show(Context context,GuestEnvironmentCatalog.Entry entry) {
        context.startActivity(new Intent(context,GuestNetworkSettings.class).putExtra(SCOPE,GuestLaunchSettings.scope(context,entry)));
    }
    private GuestLaunchSettings settings() throws IOException {
        return new GuestLaunchSettings(getApplicationContext(),getIntent().getStringExtra(SCOPE));
    }
    @Override protected GuestNetworkProfile readProfile() throws IOException { return settings().network(); }
    @Override protected void writeProfile(GuestNetworkProfile profile) throws IOException { settings().network(profile); }
}
