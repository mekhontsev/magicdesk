package io.github.mekhontsev.magicdesk.commandtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public final class ResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { save(context, intent.getAction(), intent); }
    static void save(Context context, String id, Intent intent) {
        try {
            JSONObject result = new JSONObject();
            var extras = intent.getExtras();
            if (extras != null) for (String key : extras.keySet()) result.put(key, extras.get(key));
            java.nio.file.Files.write(new java.io.File(context.getFilesDir(), id + ".json").toPath(),
                    result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.util.Log.i("RunCommandFixture", id + ": " + result);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
