package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordinary Android clipboard/drag peer, independent of the X11 adapter and Desktop. */
public final class DebugContentExchangeActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        boolean floating = getIntent().getBooleanExtra("floating", false);
        if (floating) setTheme(android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(16, 48, 16, 16);
        body.setBackgroundColor(Color.WHITE);
        status = new TextView(this);
        status.setTextColor(Color.BLACK);
        status.setText("Ready");
        status.setContentDescription("Transfer result");
        body.addView(status);
        for (String type : List.of("text", "html", "image", "file")) {
            Button source = new Button(this);
            source.setText("Copy / drag " + type);
            source.setEnabled(false);
            body.addView(source);
            io.execute(() -> {
                try {
                    AndroidContentPayload payload = payload(type);
                    runOnUiThread(() -> {
                        source.setEnabled(true);
                        source.setOnClickListener(view -> {
                            var result = AndroidClipboardGateway.get(this).writeContent(payload, "fixture");
                            status.setText(result.successful ? "Copied " + type : result.error);
                        });
                        source.setOnLongClickListener(view -> source.startDragAndDrop(payload.toClipData(),
                                new View.DragShadowBuilder(source), null,
                                View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ));
                    });
                } catch (Exception error) { result("ERROR " + error); }
            });
        }
        Button read = new Button(this);
        read.setText("Read clipboard");
        read.setOnClickListener(view -> {
            var content = AndroidClipboardGateway.get(this).readContent().content;
            if (content != null) io.execute(() -> inspect(content));
        });
        body.addView(read);
        TextView drop = new TextView(this);
        drop.setText("Drop here");
        drop.setTextColor(Color.BLACK);
        drop.setBackgroundColor(0xffc9e4dd);
        drop.setGravity(android.view.Gravity.CENTER);
        drop.setContentDescription("Transfer drop target");
        body.addView(drop, new LinearLayout.LayoutParams(-1, 0, 1));
        drop.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DROP) {
                var grant = requestDragAndDropPermissions(event);
                var content = AndroidContentPayload.fromClipData(event.getClipData(), AndroidContentPayload.Origin.DRAG);
                io.execute(() -> { try { inspect(content); } finally { if (grant != null) grant.release(); } });
            }
            return true;
        });
        setContentView(body);
        if (floating) {
            // An ordinary Android peer beside a fullscreen guest, without Desktop.
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
            getWindow().setLayout(400, 680);
        }
    }

    private AndroidContentPayload payload(String type) throws Exception {
        if (type.equals("text") || type.equals("html")) return AndroidContentPayload.create(
                AndroidContentPayload.Origin.APPLICATION, "Fixture", "", "Android transfer \u03b1\u03b2",
                type.equals("html") ? "<b>Android transfer</b> \u03b1\u03b2" : "", List.of(), List.of(), false);
        String name = type.equals("image") ? "fixture.png" : "fixture.txt";
        var uri = GeneratedContentProvider.publish(this, name, out -> {
            if (type.equals("image")) {
                Bitmap bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
                try { bitmap.eraseColor(0xff48ae79); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                finally { bitmap.recycle(); }
            } else out.write("Android file transfer\n".getBytes(StandardCharsets.UTF_8));
        });
        return AndroidContentPayload.uris("Fixture", List.of(new AndroidContentPayload.UriItem(uri,
                getContentResolver().getType(uri))), List.of(), AndroidContentPayload.Origin.APPLICATION);
    }

    private void inspect(AndroidContentPayload content) {
        try {
            StringBuilder report = new StringBuilder("RECEIVED text=").append(content.text.length())
                    .append(" html=").append(content.htmlText.length()).append(" files=").append(content.uriItems.size());
            for (var item : content.uriItems) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (var input = getContentResolver().openInputStream(item.uri)) {
                    byte[] bytes = new byte[65536];
                    for (int count; (count = input.read(bytes)) >= 0;) { digest.update(bytes, 0, count); size += count; }
                }
                report.append(" bytes=").append(size).append(" sha256=").append(java.util.HexFormat.of().formatHex(digest.digest()));
            }
            result(report.toString());
        } catch (Exception error) { result("ERROR " + error); }
    }

    private void result(String message) {
        android.util.Log.i("X11ContentFixture", message);
        runOnUiThread(() -> { if (!isDestroyed()) status.setText(message); });
    }

    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
