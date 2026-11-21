package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Optional presentation, never an environment identity or an Android package version. */
record GuestAppearance(String prettyName, Bitmap logo) {
    static final GuestAppearance EMPTY=new GuestAppearance("",null);
    static GuestAppearance read(CommandExecution execution,GuestEnvironmentCatalog.Entry entry) {
        if (execution.backend!=entry.backend()) throw new IllegalArgumentException("Environment executor mismatch");
        try (var session=execution.session("",false)) {
            var output=new ByteArrayOutputStream();
            var result=session.execute(GuestEnvironmentCatalog.storeCommand(entry,"os-info"),(bytes,offset,count)->{
                if (count>270336-output.size()) throw new IOException("Oversized distribution metadata");
                output.write(bytes,offset,count);
            });
            execution.requireCurrent();
            if (result.exitCode()!=0) return EMPTY;
            var data=new JSONObject(output.toString(StandardCharsets.UTF_8));
            String name=displayName(data.optString("prettyName"));
            String png=data.optString("png");
            Bitmap logo=null;
            if (!png.isEmpty() && png.length()<=262144) {
                try { logo=ApplicationIconBitmap.decode(java.util.HexFormat.of().parseHex(png),192); }
                catch (IllegalArgumentException ignored) { }
            }
            return new GuestAppearance(name,logo);
        } catch (IOException | RuntimeException | org.json.JSONException unavailable) { return EMPTY; }
    }

    static String displayName(String text) {
        if (text.length()>255 || text.codePoints().anyMatch(c->Character.isISOControl(c)
                || Character.getType(c)==Character.FORMAT || c==0x2028 || c==0x2029)) return "";
        return text.strip();
    }
}
